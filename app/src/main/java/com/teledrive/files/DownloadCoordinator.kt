package com.teledrive.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
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
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class DownloadCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gateway: TelegramStorageGateway,
    private val repository: FileIndexRepository,
    private val workScheduler: TransferWorkScheduler,
    private val activeTransfers: ActiveTransferRegistry,
) {
    suspend fun download(file: StoredFile): StoredFile = withContext(Dispatchers.IO) {
        if (file.localAvailability == LocalAvailability.AVAILABLE && !file.localPath.isNullOrBlank()) return@withContext file
        val fileId = file.tdlibFileId ?: throw IOException("Telegram no longer exposes this file")
        val transferId = TransferId(UUID.randomUUID().toString())
        val queued = Transfer(
            id = transferId,
            direction = TransferDirection.DOWNLOAD,
            filename = file.originalFilename,
            remote = file.remote,
            tdlibFileId = fileId,
            totalBytes = file.sizeBytes,
        )
        repository.upsertTransfer(queued)
        try {
            repository.updateTransfer(TransferStateMachine.reduce(queued, TransferEvent.Start))
            activeTransfers.markActive(transferId.value)
            workScheduler.enqueue()
            val result = gateway.downloadFile(
                DownloadRequest(
                    transferId = transferId,
                    remote = file.remote,
                    tdlibFileId = fileId,
                    expectedSizeBytes = file.sizeBytes,
                ),
            )
            val updated = file.copy(localAvailability = LocalAvailability.AVAILABLE, localPath = result.localPath)
            repository.updateFile(updated)
            repository.updateTransfer(
                TransferStateMachine.reduce(
                    TransferStateMachine.reduce(queued, TransferEvent.Start),
                    TransferEvent.Complete,
                ).copy(bytesTransferred = result.sizeBytes ?: file.sizeBytes ?: 0L),
            )
            updated
        } catch (error: Throwable) {
            val running = TransferStateMachine.reduce(queued, TransferEvent.Start)
            repository.updateTransfer(TransferStateMachine.reduce(running, error.toTransferFailure(running.attempt)))
            workScheduler.enqueue()
            throw error
        } finally {
            activeTransfers.clear(transferId.value)
        }
    }

    fun launchLocalFile(file: StoredFile, share: Boolean) {
        val path = file.localPath?.takeIf { File(it).isFile }
            ?: throw IOException("The local cached file is no longer available")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
        val mimeType = file.mimeType ?: "*/*"
        val intent = if (share) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri)
        } else {
            Intent(Intent.ACTION_VIEW, uri)
        }.apply {
            type = mimeType
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri(file.originalFilename, uri)
        }
        context.startActivity(Intent.createChooser(intent, if (share) "Share ${file.originalFilename}" else "Open ${file.originalFilename}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    suspend fun retry(transfer: Transfer): StoredFile = withContext(Dispatchers.IO) {
        val remote = transfer.remote ?: throw IOException("The remote file reference is no longer available")
        val file = repository.observeFile(remote.value).first()
            ?: throw IOException("The file is no longer in the local index")
        val fileId = transfer.tdlibFileId ?: file.tdlibFileId ?: throw IOException("Telegram no longer exposes this file")
        val queued = TransferStateMachine.reduce(transfer, TransferEvent.Retry)
        val running = TransferStateMachine.reduce(queued, TransferEvent.Start)
        repository.updateTransfer(running)
        activeTransfers.markActive(transfer.id.value)
        try {
            val result = gateway.downloadFile(DownloadRequest(transfer.id, file.remote, fileId, file.sizeBytes))
            val updated = file.copy(localAvailability = LocalAvailability.AVAILABLE, localPath = result.localPath)
            repository.updateFile(updated)
            repository.updateTransfer(TransferStateMachine.reduce(running, TransferEvent.Complete).copy(bytesTransferred = result.sizeBytes ?: file.sizeBytes ?: 0L))
            updated
        } catch (error: Throwable) {
            repository.updateTransfer(TransferStateMachine.reduce(running, error.toTransferFailure(running.attempt)))
            workScheduler.enqueue()
            throw error
        } finally {
            activeTransfers.clear(transfer.id.value)
        }
    }

    suspend fun removeLocalCopy(file: StoredFile) = withContext(Dispatchers.IO) {
        file.localPath?.let { path ->
            val local = File(path)
            if (local.isFile) local.delete()
        }
        repository.deleteLocalFile(file.stableId)
    }

    suspend fun export(localPath: String, destination: Uri) = withContext(Dispatchers.IO) {
        val source = File(localPath)
        if (!source.isFile) throw IOException("The local cached file is no longer available")
        val resolver = context.contentResolver
        resolver.openOutputStream(destination, "wt")?.use { output ->
            source.inputStream().use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
        } ?: throw IOException("The selected destination could not be opened")
    }
}
