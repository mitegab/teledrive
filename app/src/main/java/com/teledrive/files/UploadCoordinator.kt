package com.teledrive.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.teledrive.core.domain.FileIndexRepository
import com.teledrive.core.domain.TelegramStorageGateway
import com.teledrive.core.domain.UploadRequest
import com.teledrive.core.model.FilenameRules
import com.teledrive.core.model.StoredFile
import com.teledrive.core.model.Transfer
import com.teledrive.core.model.TransferDirection
import com.teledrive.core.model.TransferEvent
import com.teledrive.core.model.TransferId
import com.teledrive.core.model.TransferState
import com.teledrive.core.model.TransferStateMachine
import com.teledrive.transfers.ActiveTransferRegistry
import com.teledrive.transfers.TransferWorkScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class UploadCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gateway: TelegramStorageGateway,
    private val repository: FileIndexRepository,
    private val localStorage: LocalFileStorage,
    private val workScheduler: TransferWorkScheduler,
    private val activeTransfers: ActiveTransferRegistry,
) {
    private val activeUploads = ConcurrentHashMap<String, Job>()
    private val explicitCancellations = ConcurrentHashMap.newKeySet<String>()

    suspend fun cancel(transferId: TransferId) {
        val job = activeUploads[transferId.value] ?: return
        if (job.isCompleted) return
        explicitCancellations += transferId.value
        try {
            job.cancel(CancellationException("Transfer cancelled by user"))
            runCatching { job.join() }
        } finally {
            explicitCancellations.remove(transferId.value)
        }
    }

    suspend fun upload(uri: Uri): StoredFile = withContext(Dispatchers.IO) {
        val staged = stage(uri)
        val transferId = TransferId(UUID.randomUUID().toString())
        val queued = Transfer(
            id = transferId,
            direction = TransferDirection.UPLOAD,
            filename = staged.displayName,
            sourcePath = staged.file.absolutePath,
            totalBytes = staged.sizeBytes,
            state = TransferState.QUEUED,
        )
        repository.upsertTransfer(queued)
        val running = TransferStateMachine.reduce(queued, TransferEvent.Start)
        val currentJob = kotlin.coroutines.coroutineContext[Job]
        currentJob?.let { activeUploads[transferId.value] = it }
        var completed = false
        try {
            repository.updateTransfer(running)
            activeTransfers.markActive(transferId.value)
            workScheduler.enqueue()
            val result = gateway.uploadDocument(
                UploadRequest(
                    transferId = transferId,
                    localPath = staged.file.absolutePath,
                    displayName = staged.displayName,
                    mimeType = staged.mimeType,
                    sizeBytes = staged.sizeBytes,
                ),
            )
            val storedFile = result.file.withManagedLocalCopy(staged.file)
            repository.upsertFiles(listOf(storedFile))
            repository.updateTransfer(
                TransferStateMachine.reduce(
                    TransferStateMachine.reduce(queued, TransferEvent.Start),
                    TransferEvent.Complete,
                ).copy(remote = storedFile.remote, tdlibFileId = storedFile.tdlibFileId, sourcePath = null),
            )
            completed = true
            storedFile
        } catch (cancelled: CancellationException) {
            if (explicitCancellations.remove(transferId.value)) {
                withContext(NonCancellable) { runCatching { gateway.cancelTransfer(transferId, TransferDirection.UPLOAD) } }
            }
            // A worker/lifecycle stop leaves the persisted RUNNING row recoverable. The explicit
            // UI cancellation path settles it after this job has joined.
            throw cancelled
        } catch (error: Throwable) {
            val failure = error.toTransferFailure(running.attempt)
            repository.updateTransfer(TransferStateMachine.reduce(running, failure))
            if (failure.retryable) workScheduler.enqueue()
            throw error
        } finally {
            activeUploads.remove(transferId.value, currentJob)
            activeTransfers.clear(transferId.value)
            if (completed) localStorage.removeStagedSource(staged.file.absolutePath)
        }
    }

    suspend fun discardStagedSource(transfer: Transfer) = withContext(Dispatchers.IO) {
        localStorage.removeStagedSource(transfer.sourcePath)
        repository.updateTransfer(transfer.copy(sourcePath = null))
    }

    suspend fun retry(transfer: Transfer): StoredFile = withContext(Dispatchers.IO) {
        val sourcePath = transfer.sourcePath?.takeIf { localStorage.isStagedSource(it) }
            ?: throw IOException("The staged upload file is no longer available; choose the file again")
        val retryableTransfer = if (transfer.state == TransferState.RUNNING) transfer.copy(state = TransferState.FAILED) else transfer
        val queued = TransferStateMachine.reduce(retryableTransfer, TransferEvent.Retry)
        val running = TransferStateMachine.reduce(queued, TransferEvent.Start)
        repository.updateTransfer(running)
        val currentJob = kotlin.coroutines.coroutineContext[Job]
        currentJob?.let { activeUploads[transfer.id.value] = it }
        activeTransfers.markActive(transfer.id.value)
        try {
            val result = gateway.uploadDocument(
                UploadRequest(
                    transferId = transfer.id,
                    localPath = sourcePath,
                    displayName = transfer.filename,
                    mimeType = null,
                    sizeBytes = transfer.totalBytes,
                ),
            )
            val storedFile = result.file.withManagedLocalCopy(File(sourcePath))
            repository.upsertFiles(listOf(storedFile))
            repository.updateTransfer(
                TransferStateMachine.reduce(running, TransferEvent.Complete)
                    .copy(remote = storedFile.remote, tdlibFileId = storedFile.tdlibFileId, sourcePath = null),
            )
            localStorage.removeStagedSource(sourcePath)
            storedFile
        } catch (cancelled: CancellationException) {
            if (explicitCancellations.remove(transfer.id.value)) {
                withContext(NonCancellable) { runCatching { gateway.cancelTransfer(transfer.id, TransferDirection.UPLOAD) } }
            }
            throw cancelled
        } catch (error: Throwable) {
            val failure = error.toTransferFailure(running.attempt)
            repository.updateTransfer(TransferStateMachine.reduce(running, failure))
            if (failure.retryable) workScheduler.enqueue()
            throw error
        } finally {
            activeUploads.remove(transfer.id.value, currentJob)
            activeTransfers.clear(transfer.id.value)
        }
    }

    private suspend fun StoredFile.withManagedLocalCopy(source: File): StoredFile {
        val managedPath = try {
            localStorage.importCompleted(
                sourcePath = source.absolutePath,
                stableId = stableId,
                displayName = originalFilename,
                expectedSizeBytes = sizeBytes ?: source.length(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        return if (managedPath != null) {
            copy(localAvailability = com.teledrive.core.model.LocalAvailability.AVAILABLE, localPath = managedPath)
        } else {
            copy(localAvailability = com.teledrive.core.model.LocalAvailability.NOT_DOWNLOADED, localPath = null)
        }
    }

    private fun stage(uri: Uri): StagedUpload {
        val resolver = context.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex).takeIf { it >= 0L } else null
                name to size
            } else null
        } ?: (null to null)
        val safeName = FilenameRules.displayName(displayName.first, resolver.getType(uri))
        val directory = context.filesDir.resolve("staging").apply { mkdirs() }
        val output = File.createTempFile("upload-", ".part", directory)
        try {
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(output).use { out ->
                    input.copyTo(out, DEFAULT_BUFFER_SIZE)
                    out.fd.sync()
                }
            } ?: throw IOException("The selected file could not be opened")
            val copiedSize = output.length()
            if (displayName.second != null && displayName.second != copiedSize) {
                throw IOException("The selected content changed while it was being staged")
            }
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
        return StagedUpload(
            file = output,
            displayName = safeName,
            mimeType = resolver.getType(uri),
            sizeBytes = output.length(),
        )
    }

    private data class StagedUpload(
        val file: File,
        val displayName: String,
        val mimeType: String?,
        val sizeBytes: Long,
    )
}
