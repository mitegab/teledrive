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
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UploadCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gateway: TelegramStorageGateway,
    private val repository: FileIndexRepository,
    private val workScheduler: TransferWorkScheduler,
    private val activeTransfers: ActiveTransferRegistry,
) {
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
        var completed = false
        try {
            repository.updateTransfer(TransferStateMachine.reduce(queued, TransferEvent.Start))
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
            repository.upsertFiles(listOf(result.file))
            repository.updateTransfer(
                TransferStateMachine.reduce(
                    TransferStateMachine.reduce(queued, TransferEvent.Start),
                    TransferEvent.Complete,
                ).copy(remote = result.file.remote, tdlibFileId = result.file.tdlibFileId, sourcePath = null),
            )
            completed = true
            result.file
        } catch (error: Throwable) {
            val running = TransferStateMachine.reduce(queued, TransferEvent.Start)
            repository.updateTransfer(
                TransferStateMachine.reduce(running, error.toTransferFailure(running.attempt)),
            )
            workScheduler.enqueue()
            throw error
        } finally {
            activeTransfers.clear(transferId.value)
            if (completed) staged.file.delete()
        }
    }

    suspend fun discardStagedSource(transfer: Transfer) = withContext(Dispatchers.IO) {
        transfer.sourcePath?.let { File(it).delete() }
        repository.updateTransfer(transfer.copy(sourcePath = null))
    }

    suspend fun retry(transfer: Transfer): StoredFile = withContext(Dispatchers.IO) {
        val sourcePath = transfer.sourcePath?.takeIf { File(it).isFile }
            ?: throw IOException("The staged upload file is no longer available; choose the file again")
        val queued = TransferStateMachine.reduce(transfer, TransferEvent.Retry)
        val running = TransferStateMachine.reduce(queued, TransferEvent.Start)
        repository.updateTransfer(running)
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
            repository.upsertFiles(listOf(result.file))
            repository.updateTransfer(
                TransferStateMachine.reduce(running, TransferEvent.Complete)
                    .copy(remote = result.file.remote, tdlibFileId = result.file.tdlibFileId, sourcePath = null),
            )
            File(sourcePath).delete()
            result.file
        } catch (error: Throwable) {
            repository.updateTransfer(TransferStateMachine.reduce(running, error.toTransferFailure(running.attempt)))
            workScheduler.enqueue()
            throw error
        } finally {
            activeTransfers.clear(transfer.id.value)
        }
    }

    private fun stage(uri: Uri): StagedUpload {
        val resolver = context.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null
                name to size
            } else null
        } ?: (null to null)
        val safeName = FilenameRules.displayName(displayName.first, resolver.getType(uri))
        val directory = context.filesDir.resolve("staging").apply { mkdirs() }
        val output = File.createTempFile("upload-", ".part", directory)
        try {
            resolver.openInputStream(uri)?.use { input ->
                output.outputStream().use { out -> input.copyTo(out, DEFAULT_BUFFER_SIZE) }
            } ?: throw IOException("The selected file could not be opened")
        } catch (error: Throwable) {
            output.delete()
            throw error
        }
        return StagedUpload(
            file = output,
            displayName = safeName,
            mimeType = resolver.getType(uri),
            sizeBytes = displayName.second ?: output.length(),
        )
    }

    private data class StagedUpload(
        val file: File,
        val displayName: String,
        val mimeType: String?,
        val sizeBytes: Long,
    )
}
