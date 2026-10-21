package com.teledrive.files

import android.content.Context
import android.net.Uri
import androidx.annotation.VisibleForTesting
import com.teledrive.core.domain.DownloadRequest
import com.teledrive.core.domain.FileIndexRepository
import com.teledrive.core.domain.StorageGatewayException
import com.teledrive.core.domain.TelegramStorageGateway
import com.teledrive.core.domain.toAppError
import com.teledrive.core.model.AppError
import com.teledrive.core.model.AppErrorMapper
import com.teledrive.core.model.DownloadBatchRules
import com.teledrive.core.model.DownloadLimits
import com.teledrive.core.model.DownloadSnapshot
import com.teledrive.core.model.ErrorCategory
import com.teledrive.core.model.ErrorOperation
import com.teledrive.core.model.LocalAvailability
import com.teledrive.core.model.RemoteAvailability
import com.teledrive.core.model.StoredFile
import com.teledrive.core.model.TeleDriveLog
import com.teledrive.core.model.Transfer
import com.teledrive.core.model.TransferDirection
import com.teledrive.core.model.TransferEvent
import com.teledrive.core.model.TransferId
import com.teledrive.core.model.TransferProgressPolicy
import com.teledrive.core.model.TransferRetryPolicy
import com.teledrive.core.model.TransferSpeedEstimator
import com.teledrive.core.model.TransferState
import com.teledrive.core.model.TransferStateMachine
import com.teledrive.transfers.ActiveTransferRegistry
import com.teledrive.transfers.TransferWorkScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.minutes

/** Typed failure raised to callers; the UI reads [error] instead of parsing text. */
class DownloadException(val error: AppError) : RuntimeException(error.userMessage)

data class BatchDownloadReport(
    val requested: Int = 0,
    /** Files downloaded and verified by this batch. */
    val downloaded: Int = 0,
    /** Files that already had verified bytes before the batch started. */
    val alreadyOffline: Int = 0,
    val failed: Int = 0,
    val canceled: Int = 0,
    /** Selected files that cannot be downloaded (no Telegram file id or deleted remotely). */
    val skipped: Int = 0,
) {
    val completed: Int get() = downloaded + alreadyOffline

    val message: String
        get() {
            if (requested == 0) return "No downloadable files were selected"
            val notes = buildList {
                if (failed > 0) add("$failed failed")
                if (canceled > 0) add("$canceled canceled")
                if (skipped > 0) add("$skipped skipped")
                if (alreadyOffline > 0) add("$alreadyOffline already offline")
            }
            val saved = "$downloaded file${if (downloaded == 1) "" else "s"}"
            return when {
                downloaded == 0 && notes.isEmpty() -> "Selected files are already available offline"
                notes.isEmpty() -> "Saved $saved for offline use"
                downloaded == 0 -> "No files could be saved offline (${notes.joinToString(", ")})"
                else -> "Saved $saved of $requested offline; ${notes.joinToString(", ")}"
            }
        }
}

/**
 * The single offline-download subsystem.
 *
 *  - one bounded worker pool (never one coroutine per queued file),
 *  - one active request per stable id (repeat taps and viewers reuse it),
 *  - storage preflight plus post-download verification before a file is offline-ready,
 *  - persisted transfer rows so progress survives navigation and retries stay durable,
 *  - cancellation that reaches TDLib and never touches the remote message.
 */
@Singleton
class DownloadCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gateway: TelegramStorageGateway,
    private val repository: FileIndexRepository,
    private val localStorage: LocalFileStorage,
    private val fileOpenRouter: FileOpenRouter,
    private val workScheduler: TransferWorkScheduler,
    private val activeTransfers: ActiveTransferRegistry,
    private val offline: OfflineFileRepository,
    private val statusStore: DownloadStatusStore,
) {
    private data class QueuedDownload(
        val file: StoredFile,
        val transferId: TransferId?,
        val deferred: CompletableDeferred<StoredFile>,
    )

    private val limits = DownloadLimits.Default
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueMutex = Mutex()
    private val queue = ArrayDeque<QueuedDownload>()
    private val requests = ConcurrentHashMap<String, CompletableDeferred<StoredFile>>()
    private val runningJobs = ConcurrentHashMap<String, Job>()
    private val explicitCancellations = ConcurrentHashMap.newKeySet<String>()
    private var workerCount = 0

    init {
        scope.launch {
            repository.observeTransfers().collect { transfers -> statusStore.mergePersisted(transfers) }
        }
        scope.launch {
            repository.observeFiles().collect { files -> files.forEach(statusStore::seedFromFile) }
        }
    }

    val statuses: kotlinx.coroutines.flow.StateFlow<Map<String, DownloadSnapshot>> = statusStore.states

    fun snapshot(stableId: String): DownloadSnapshot? = statusStore.snapshot(stableId)

    /** Suspend until the file is verified offline, or throws [DownloadException]. */
    suspend fun download(file: StoredFile): StoredFile = requestDownload(file, transferId = null).await()

    /** Starts (or reuses) a download without waiting; used by viewers and dialogs. */
    fun beginDownload(file: StoredFile): Deferred<StoredFile> = requestDownload(file, transferId = null)

    /**
     * Queues several files for offline use. Transfers are bounded by
     * [DownloadLimits.maxConcurrentTransfers]; one failure never aborts the batch.
     */
    suspend fun downloadAll(files: List<StoredFile>): BatchDownloadReport {
        val prepared = files.distinctBy { it.stableId }
        if (prepared.isEmpty()) return BatchDownloadReport()
        val alreadyOffline = DownloadBatchRules.alreadyOffline(prepared)
        val skipped = DownloadBatchRules.skipped(prepared)
        val candidates = DownloadBatchRules.candidates(prepared)
        val deferred = candidates.map { file -> file to requestDownload(file, transferId = null) }
        var completed = 0
        var failed = 0
        var canceled = 0
        deferred.forEach { (_, request) ->
            try {
                request.await()
                completed++
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                canceled++
            } catch (error: DownloadException) {
                if (error.error.isCancellation) canceled++ else failed++
            }
        }
        return BatchDownloadReport(
            requested = prepared.size,
            downloaded = completed,
            alreadyOffline = alreadyOffline.size,
            failed = failed,
            canceled = canceled,
            skipped = skipped.size,
        )
    }

    fun cancelAll() {
        val active = runningJobs.keys.toList()
        scope.launch {
            val queued = queueMutex.withLock { queue.map { it.file.stableId }.toList() }
            (active + queued).distinct().forEach(::cancelByStableId)
        }
    }

    fun cancel(remoteId: String?) {
        remoteId?.let(::cancelByStableId)
    }

    private fun cancelByStableId(stableId: String) {
        scope.launch { cancelNow(stableId) }
    }

    /**
     * Cancels and, when the transfer is running in-process, waits for it to settle.
     * Callers that are about to delete local bytes need the wait to avoid a race where
     * a finishing download publishes a copy after the removal.
     */
    private suspend fun cancelNow(stableId: String) {
        explicitCancellations += stableId
        val queued = queueMutex.withLock {
            queue.firstOrNull { it.file.stableId == stableId }?.also { queue.remove(it) }
        }
        if (queued != null) {
            settleQueuedCancellation(queued)
            return
        }
        val job = runningJobs[stableId]
        if (job != null && job.isActive) {
            job.cancel(CancellationException("Transfer cancelled by user"))
            job.join()
            return
        }
        settlePersistedCancellation(stableId)
        statusStore.update(stableId) { current ->
            (current ?: DownloadSnapshot(stableId = stableId, state = null)).asCanceled()
        }
    }

    /** Opens a file through the system viewer using only verified local bytes. */
    fun launchLocalFile(file: StoredFile, share: Boolean) = fileOpenRouter.open(file, share)

    /**
     * UI/worker entry point to resume a failed transfer with the same persisted row.
     * Starting is separated from awaiting so background work can enqueue several
     * downloads without holding a worker coroutine open for their whole duration.
     */
    suspend fun beginRetry(transfer: Transfer): Deferred<StoredFile> {
        val remote = transfer.remote
            ?: throw DownloadException(AppErrorMapper.fromCode("REMOTE_FILE_MISSING", null, ErrorOperation.DOWNLOAD))
        val file = repository.observeFile(remote.value).first()
            ?: throw DownloadException(
                AppError(
                    category = ErrorCategory.LOCAL_FILE_MISSING,
                    operation = ErrorOperation.DOWNLOAD,
                    userMessage = "This file is no longer in your library.",
                    retryable = false,
                ),
            )
        return requestDownload(file, transfer.id)
    }

    /** Resumes and waits for completion. */
    suspend fun retry(transfer: Transfer): StoredFile = beginRetry(transfer).await()

    /**
     * Retries by stable id, reusing the persisted FAILED/CANCELED row when one exists.
     * This is what the file browser and details dialog call, so the transfer history
     * never shows two disconnected attempts for the same file.
     */
    suspend fun retryDownload(stableId: String): StoredFile {
        val failed = repository.observeTransfers().first().firstOrNull {
            it.direction == TransferDirection.DOWNLOAD &&
                it.remote?.value == stableId &&
                (it.state == TransferState.FAILED || it.state == TransferState.CANCELED)
        }
        return if (failed != null) retry(failed) else download(
            repository.observeFile(stableId).first()
                ?: throw DownloadException(
                    AppError(
                        category = ErrorCategory.LOCAL_FILE_MISSING,
                        operation = ErrorOperation.DOWNLOAD,
                        userMessage = "This file is no longer in your library.",
                        retryable = false,
                    ),
                ),
        )
    }

    /**
     * Removes ONLY the offline copy. Remote Telegram data and the index row are kept,
     * so the file can be downloaded again at any time.
     */
    suspend fun removeLocalCopy(file: StoredFile) {
        if (statusStore.snapshot(file.stableId)?.isActive == true) cancelNow(file.stableId)
        offline.removeOfflineCopy(file)
        statusStore.update(file.stableId) { current ->
            current?.copy(state = null, isOfflineAvailable = false, bytesTransferred = 0L)
        }
    }

    suspend fun export(localPath: String, destination: Uri) = localStorage.export(localPath, destination, context)

    fun refreshStatus(file: StoredFile) {
        scope.launch {
            val current = offline.current(file)
            if (current.localAvailability == LocalAvailability.AVAILABLE && offline.isUsableOffline(current)) {
                statusStore.seedFromFile(current)
            }
        }
    }

    // ---- queueing -------------------------------------------------------------

    private fun requestDownload(file: StoredFile, transferId: TransferId?): Deferred<StoredFile> {
        while (true) {
            val existing = requests[file.stableId]
            if (existing != null && !existing.isCompleted) return existing
            val candidate = CompletableDeferred<StoredFile>()
            val installed = if (existing == null) {
                requests.putIfAbsent(file.stableId, candidate) == null
            } else {
                requests.replace(file.stableId, existing, candidate)
            }
            if (!installed) continue
            candidate.invokeOnCompletion { requests.remove(file.stableId, candidate) }
            enqueue(QueuedDownload(file, transferId, candidate))
            return candidate
        }
    }

    private fun enqueue(item: QueuedDownload) {
        scope.launch {
            queueMutex.withLock { queue.addLast(item) }
            ensureWorkers()
        }
    }

    private suspend fun ensureWorkers() {
        queueMutex.withLock {
            while (workerCount < limits.maxConcurrentTransfers && queue.isNotEmpty()) {
                workerCount++
                scope.launch { workerLoop() }
            }
        }
    }

    private suspend fun workerLoop() {
        try {
            while (true) {
                val next = queueMutex.withLock { queue.removeFirstOrNull() } ?: break
                runQueued(next)
            }
        } finally {
            withContext(NonCancellable) { queueMutex.withLock { workerCount-- } }
            scope.launch { ensureWorkers() }
        }
    }

    private suspend fun runQueued(item: QueuedDownload) {
        val stableId = item.file.stableId
        val transferId = item.transferId ?: TransferId(UUID.randomUUID().toString())
        val job = currentCoroutineContext()[Job]
        job?.let { runningJobs[stableId] = it }
        try {
            val result = performDownload(item, transferId)
            item.deferred.complete(result)
        } catch (cancelled: CancellationException) {
            settleCancellation(item, transferId)
            item.deferred.completeExceptionally(cancelled)
            throw cancelled
        } catch (error: Throwable) {
            val appError = error.toDownloadError()
            settleFailure(item, transferId, appError)
            item.deferred.completeExceptionally(DownloadException(appError))
        } finally {
            job?.let { runningJobs.remove(stableId, it) }
            explicitCancellations.remove(stableId)
            activeTransfers.clear(transferId.value)
            withContext(NonCancellable) { localStorage.discardPartialFiles(stableId) }
        }
    }

    // ---- the actual transfer ---------------------------------------------------

    private suspend fun performDownload(item: QueuedDownload, transferId: TransferId): StoredFile {
        val stableId = item.file.stableId
        var file = offline.current(item.file)

        if (offline.isUsableOffline(file)) {
            statusStore.seedFromFile(file)
            TeleDriveLog.d("download.reuse", "stable_id" to stableIdTail(stableId))
            return file
        }

        val remoteGone = file.remoteAvailability == RemoteAvailability.DELETED
        val fileId = file.tdlibFileId
        if (remoteGone || fileId == null) {
            throw DownloadException(AppErrorMapper.fromCode("REMOTE_FILE_MISSING", null, ErrorOperation.DOWNLOAD))
        }

        val freeBytes = localStorage.availableBytes()
        if (!limits.hasEnoughSpace(freeBytes, file.sizeBytes)) {
            throw DownloadException(
                AppErrorMapper.storageFull(ErrorOperation.DOWNLOAD, limits.requiredFreeBytes(file.sizeBytes), freeBytes),
            )
        }

        val previousRow = repository.getTransfer(transferId.value)
        var row = when {
            previousRow == null -> Transfer(
                id = transferId,
                direction = TransferDirection.DOWNLOAD,
                filename = file.originalFilename,
                remote = file.remote,
                tdlibFileId = fileId,
                totalBytes = file.sizeBytes,
            )
            previousRow.state == TransferState.FAILED || previousRow.state == TransferState.CANCELED ->
                TransferStateMachine.reduce(previousRow, TransferEvent.Retry)
            else -> previousRow
        }
        row = TransferStateMachine.reduce(
            row.copy(
                state = if (row.state.isActive) row.state else TransferState.QUEUED,
                errorMessage = null,
                retryAtEpochSeconds = null,
                remote = file.remote,
                tdlibFileId = fileId,
                filename = file.originalFilename,
                totalBytes = row.totalBytes ?: file.sizeBytes,
            ),
            TransferEvent.Start,
        )
        repository.upsertTransfer(row)
        activeTransfers.markActive(transferId.value)
        file = file.copy(localAvailability = LocalAvailability.DOWNLOADING)
        repository.updateFile(file)
        statusStore.put(snapshotOf(row, file))

        val rowRef = AtomicReference(row)
        val estimator = TransferSpeedEstimator(limits.speedEstimateWindowMillis)
        var lastPersistAt: Long? = null
        var lastPersistBytes = 0L
        var lastUiAt: Long? = null
        var observedBytes = 0L

        return coroutineScope {
            val progressJob = launch {
                gateway.transferUpdates.filter { it.transferId == transferId }.collect { update ->
                    val now = System.currentTimeMillis()
                    observedBytes = max(observedBytes, update.bytesTransferred)
                    val speed = estimator.sample(observedBytes, now)
                    val current = rowRef.get()
                    if (!current.state.isActive) return@collect
                    val next = current.copy(
                        state = if (current.state == TransferState.PREPARING && update.state.isActive) {
                            TransferState.RUNNING
                        } else {
                            current.state
                        },
                        bytesTransferred = observedBytes,
                        totalBytes = update.totalBytes ?: current.totalBytes,
                    )
                    rowRef.set(next)
                    if (TransferProgressPolicy.shouldPersistProgress(
                            lastPersistAt,
                            lastPersistBytes,
                            observedBytes,
                            next.state,
                            now,
                            limits,
                        )
                    ) {
                        repository.updateTransfer(next)
                        lastPersistAt = now
                        lastPersistBytes = observedBytes
                    }
                    if (TransferProgressPolicy.shouldRefreshUi(lastUiAt, now)) {
                        statusStore.put(snapshotOf(next, file).withProgress(observedBytes, next.totalBytes, speed))
                        lastUiAt = now
                    }
                }
            }
            try {
                val result = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MILLIS) {
                    gateway.downloadFile(
                        DownloadRequest(
                            transferId = transferId,
                            remote = file.remote,
                            tdlibFileId = fileId,
                            expectedSizeBytes = file.sizeBytes,
                        ),
                    )
                } ?: run {
                    withContext(NonCancellable) {
                        runCatching { gateway.cancelTransfer(transferId, TransferDirection.DOWNLOAD, fileId) }
                            .onFailure { error -> TeleDriveLog.d("download.cancel_request_failed", "error" to error.message) }
                    }
                    throw DownloadException(AppErrorMapper.fromCode("TIMEOUT", null, ErrorOperation.DOWNLOAD))
                }
                observedBytes = max(observedBytes, result.sizeBytes ?: 0L)

                val verifyingSource = repository.getTransfer(transferId.value) ?: rowRef.get()
                val verifying = if (verifyingSource.state.isActive) {
                    TransferStateMachine.reduce(verifyingSource, TransferEvent.Verify)
                } else {
                    verifyingSource.copy(state = TransferState.VERIFYING)
                }
                rowRef.set(verifying)
                repository.updateTransfer(verifying)
                statusStore.put(snapshotOf(verifying, file))

                val managedPath = localStorage.importCompleted(
                    sourcePath = result.localPath,
                    stableId = stableId,
                    displayName = file.originalFilename,
                    expectedSizeBytes = result.sizeBytes ?: file.sizeBytes,
                )
                val finalSize = result.sizeBytes ?: file.sizeBytes
                offline.markAvailable(file, managedPath, finalSize, System.currentTimeMillis() / 1000)

                val completed = verifying.copy(
                    state = TransferState.COMPLETED,
                    bytesTransferred = finalSize ?: observedBytes,
                    totalBytes = finalSize ?: verifying.totalBytes,
                    errorMessage = null,
                    retryAtEpochSeconds = null,
                )
                repository.updateTransfer(completed)
                val updated = offline.current(file)
                statusStore.put(snapshotOf(completed, updated).asCompleted(finalSize))
                TeleDriveLog.d(
                    "download.done",
                    "stable_id" to stableIdTail(stableId),
                    "bytes" to (finalSize ?: observedBytes),
                    "attempt" to completed.attempt,
                )
                updated
            } finally {
                progressJob.cancel()
            }
        }
    }

    // ---- settling -------------------------------------------------------------

    private suspend fun settleFailure(item: QueuedDownload, transferId: TransferId, error: AppError) {
        val stableId = item.file.stableId
        val row = repository.getTransfer(transferId.value) ?: Transfer(
            id = transferId,
            direction = TransferDirection.DOWNLOAD,
            filename = item.file.originalFilename,
            remote = item.file.remote,
            tdlibFileId = item.file.tdlibFileId,
            state = TransferState.FAILED,
        )
        val retryable = error.retryable && TransferRetryPolicy.shouldAutoRetry(row.attempt, retryable = true)
        // Telegram's flood-wait wins over our own backoff; never retry earlier than asked.
        val delaySeconds = error.retryAfterSeconds?.takeIf { it > 0L }
            ?: TransferRetryPolicy.nextDelaySeconds(row.attempt, Random.nextDouble())
        val retryAt = if (retryable) System.currentTimeMillis() / 1000 + delaySeconds else null
        val failed = row.copy(
            state = TransferState.FAILED,
            errorMessage = error.userMessage,
            retryAtEpochSeconds = retryAt,
        )
        withContext(NonCancellable) {
            runCatching { repository.updateTransfer(failed) }
                .onFailure { error -> TeleDriveLog.d("download.persist_failure_failed", "error" to error.message) }
            runCatching { restoreAvailability(item, keepValidCopy = true) }
                .onFailure { error -> TeleDriveLog.d("download.restore_state_failed", "error" to error.message) }
            localStorage.discardPartialFiles(stableId)
        }
        statusStore.update(stableId) { current ->
            (current ?: DownloadSnapshot(stableId = stableId, state = null)).asFailure(
                if (retryable) error else error.copy(retryable = false),
            )
        }
        TeleDriveLog.d("download.failed", "retryable" to retryable, "attempt" to row.attempt)
        TeleDriveLog.d(error.logLine())
        if (retryable) {
            val delaySeconds = (retryAt ?: 0L) - System.currentTimeMillis() / 1000
            workScheduler.enqueue(delaySeconds.coerceAtLeast(0L))
        }
    }

    private suspend fun settleCancellation(item: QueuedDownload, transferId: TransferId) {
        val stableId = item.file.stableId
        val explicit = explicitCancellations.contains(stableId)
        withContext(NonCancellable) {
            if (explicit) {
                runCatching { gateway.cancelTransfer(transferId, TransferDirection.DOWNLOAD, item.file.tdlibFileId) }
                    .onFailure { error -> TeleDriveLog.d("download.cancel_request_failed", "error" to error.message) }
            }
            settlePersistedCancellation(stableId, explicit = explicit)
            runCatching { restoreAvailability(item, keepValidCopy = true) }
                .onFailure { error -> TeleDriveLog.d("download.restore_state_failed", "error" to error.message) }
            localStorage.discardPartialFiles(stableId)
        }
        statusStore.update(stableId) { current ->
            (current ?: DownloadSnapshot(stableId = stableId, state = null)).asCanceled()
        }
        TeleDriveLog.d("download.canceled", "explicit" to explicit)
    }

    private suspend fun settleQueuedCancellation(item: QueuedDownload) {
        settlePersistedCancellation(item.file.stableId, explicit = true)
        statusStore.update(item.file.stableId) { current ->
            (current ?: DownloadSnapshot(stableId = item.file.stableId, state = null)).asCanceled()
        }
        item.deferred.cancel(CancellationException("Transfer cancelled by user"))
    }

    private suspend fun settlePersistedCancellation(stableId: String, explicit: Boolean = true) {
        val row = repository.observeTransfers().first()
            .firstOrNull { it.remote?.value == stableId && it.direction == TransferDirection.DOWNLOAD && it.state.isActive }
            ?: return
        if (explicit) {
            row.tdlibFileId?.let { fileId ->
                runCatching { gateway.cancelTransfer(row.id, TransferDirection.DOWNLOAD, fileId) }
                    .onFailure { error -> TeleDriveLog.d("download.cancel_request_failed", "error" to error.message) }
            }
        }
        repository.updateTransfer(
            row.copy(state = TransferState.CANCELED, errorMessage = null, retryAtEpochSeconds = null),
        )
    }

    private suspend fun restoreAvailability(item: QueuedDownload, keepValidCopy: Boolean) {
        val current = offline.current(item.file)
        if (keepValidCopy && offline.isUsableOffline(current)) {
            repository.updateFile(current.copy(localAvailability = LocalAvailability.AVAILABLE))
        } else {
            // A plain updateFile would be merged with the previous row (remote refreshes keep
            // local state), so clearing must go through the dedicated SQL update.
            repository.deleteLocalFile(current.stableId)
        }
    }

    private fun snapshotOf(row: Transfer, file: StoredFile): DownloadSnapshot = DownloadSnapshot(
        stableId = file.stableId,
        state = row.state,
        bytesTransferred = row.bytesTransferred,
        totalBytes = row.totalBytes,
        attempt = row.attempt,
        errorMessage = row.errorMessage,
        // A FAILED row is always manually retryable; automatic retries are a separate policy.
        retryable = row.state == TransferState.FAILED,
        isOfflineAvailable = file.localAvailability == LocalAvailability.AVAILABLE,
    )

    private fun stableIdTail(stableId: String): String = stableId.substringAfter(':', stableId).takeLast(6)

    private fun Throwable.toDownloadError(): AppError = when (this) {
        is DownloadException -> error
        is LocalStorageException -> this.toAppError(ErrorOperation.DOWNLOAD)
        is StorageGatewayException -> this.toAppError(ErrorOperation.DOWNLOAD)
        is CancellationException -> AppErrorMapper.cancellation(ErrorOperation.DOWNLOAD)
        else -> AppErrorMapper.fromThrowable(this, ErrorOperation.DOWNLOAD)
    }

    @VisibleForTesting
    internal suspend fun queuedCount(): Int = queueMutex.withLock { queue.size }

    @VisibleForTesting
    internal suspend fun activeWorkerCount(): Int = queueMutex.withLock { workerCount }

    private companion object {
        /** TDLib's own download waiter times out at 30 minutes; allow the copy step to finish. */
        val DOWNLOAD_TIMEOUT_MILLIS = 35.minutes.inWholeMilliseconds
    }
}
