package com.teledrive.files

import android.content.Context
import android.net.Uri
import com.teledrive.core.domain.DownloadRequest
import com.teledrive.core.domain.FileIndexRepository
import com.teledrive.core.domain.TelegramStorageGateway
import com.teledrive.core.model.LocalAvailability
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
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

class DownloadCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gateway: TelegramStorageGateway,
    private val repository: FileIndexRepository,
    private val localStorage: LocalFileStorage,
    private val fileOpenRouter: FileOpenRouter,
    private val workScheduler: TransferWorkScheduler,
    private val activeTransfers: ActiveTransferRegistry,
) {
    private val transferScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeDownloads = ConcurrentHashMap<String, Deferred<StoredFile>>()

    suspend fun download(file: StoredFile): StoredFile {
        val deferred = activeDownloads.computeIfAbsent(file.stableId) {
            val candidate = transferScope.async { executeDownload(file) }
            candidate.invokeOnCompletion { activeDownloads.remove(file.stableId, candidate) }
            candidate
        }
        return deferred.await()
    }

    private suspend fun executeDownload(file: StoredFile): StoredFile = withContext(Dispatchers.IO) {
        val currentFile = repository.observeFile(file.stableId).firstOrNull() ?: file
        if (currentFile.localAvailability == LocalAvailability.AVAILABLE && localStorage.isAvailable(currentFile.localPath, currentFile.sizeBytes)) return@withContext currentFile
        if (currentFile.localAvailability == LocalAvailability.AVAILABLE) repository.deleteLocalFile(currentFile.stableId)
        val fileId = currentFile.tdlibFileId ?: throw IOException("Telegram no longer exposes this file")
        val transferId = TransferId(UUID.randomUUID().toString())
        val queued = Transfer(
            id = transferId,
            direction = TransferDirection.DOWNLOAD,
            filename = currentFile.originalFilename,
            remote = currentFile.remote,
            tdlibFileId = fileId,
            totalBytes = currentFile.sizeBytes,
        )
        repository.upsertTransfer(queued)
        val running = TransferStateMachine.reduce(queued, TransferEvent.Start)
        try {
            repository.updateTransfer(running)
            activeTransfers.markActive(transferId.value)
            workScheduler.enqueue()
            val result = gateway.downloadFile(
                DownloadRequest(
                    transferId = transferId,
                    remote = currentFile.remote,
                    tdlibFileId = fileId,
                    expectedSizeBytes = currentFile.sizeBytes,
                ),
            )
            val managedPath = localStorage.importCompleted(result.localPath, currentFile.stableId, currentFile.originalFilename, result.sizeBytes ?: currentFile.sizeBytes)
            val updated = currentFile.copy(localAvailability = LocalAvailability.AVAILABLE, localPath = managedPath)
            repository.updateFile(updated)
            repository.updateTransfer(
                TransferStateMachine.reduce(
                    TransferStateMachine.reduce(queued, TransferEvent.Start),
                    TransferEvent.Complete,
                ).copy(bytesTransferred = result.sizeBytes ?: currentFile.sizeBytes ?: 0L),
            )
            updated
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runCatching { gateway.cancelTransfer(transferId, TransferDirection.DOWNLOAD, fileId) }
                repository.updateTransfer(TransferStateMachine.reduce(running, TransferEvent.Cancel))
            }
            throw cancelled
        } catch (error: Throwable) {
            val failure = error.toTransferFailure(running.attempt)
            repository.updateTransfer(TransferStateMachine.reduce(running, failure))
            if (failure.retryable) workScheduler.enqueue()
            throw error
        } finally {
            activeTransfers.clear(transferId.value)
        }
    }

    fun cancel(remoteId: String?) {
        remoteId?.let { activeDownloads[it]?.cancel(CancellationException("Transfer cancelled by user")) }
    }

    fun launchLocalFile(file: StoredFile, share: Boolean) = fileOpenRouter.open(file, share)

    suspend fun retry(transfer: Transfer): StoredFile = withContext(Dispatchers.IO) {
        val remote = transfer.remote ?: throw IOException("The remote file reference is no longer available")
        val file = repository.observeFile(remote.value).first()
            ?: throw IOException("The file is no longer in the local index")
        val fileId = transfer.tdlibFileId ?: file.tdlibFileId ?: throw IOException("Telegram no longer exposes this file")
        val retryableTransfer = if (transfer.state == TransferState.RUNNING) transfer.copy(state = TransferState.FAILED) else transfer
        val queued = TransferStateMachine.reduce(retryableTransfer, TransferEvent.Retry)
        val running = TransferStateMachine.reduce(queued, TransferEvent.Start)
        repository.updateTransfer(running)
        activeTransfers.markActive(transfer.id.value)
        try {
            val result = gateway.downloadFile(DownloadRequest(transfer.id, file.remote, fileId, file.sizeBytes))
            val managedPath = localStorage.importCompleted(result.localPath, file.stableId, file.originalFilename, result.sizeBytes ?: file.sizeBytes)
            val updated = file.copy(localAvailability = LocalAvailability.AVAILABLE, localPath = managedPath)
            repository.updateFile(updated)
            repository.updateTransfer(TransferStateMachine.reduce(running, TransferEvent.Complete).copy(bytesTransferred = result.sizeBytes ?: file.sizeBytes ?: 0L))
            updated
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                runCatching { gateway.cancelTransfer(transfer.id, TransferDirection.DOWNLOAD, fileId) }
                repository.updateTransfer(TransferStateMachine.reduce(running, TransferEvent.Cancel))
            }
            throw cancelled
        } catch (error: Throwable) {
            val failure = error.toTransferFailure(running.attempt)
            repository.updateTransfer(TransferStateMachine.reduce(running, failure))
            if (failure.retryable) workScheduler.enqueue()
            throw error
        } finally {
            activeTransfers.clear(transfer.id.value)
        }
    }

    suspend fun removeLocalCopy(file: StoredFile) = withContext(Dispatchers.IO) {
        localStorage.remove(file.localPath)
        repository.deleteLocalFile(file.stableId)
    }

    suspend fun export(localPath: String, destination: Uri) = localStorage.export(localPath, destination, context)
}
