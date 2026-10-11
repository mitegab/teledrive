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
) {
    suspend fun upload(uri: Uri): StoredFile = withContext(Dispatchers.IO) {
        val staged = stage(uri)
        val transferId = TransferId(UUID.randomUUID().toString())
        val queued = Transfer(
            id = transferId,
            direction = TransferDirection.UPLOAD,
            filename = staged.displayName,
            totalBytes = staged.sizeBytes,
            state = TransferState.QUEUED,
        )
        repository.upsertTransfer(queued)
        try {
            repository.updateTransfer(TransferStateMachine.reduce(queued, TransferEvent.Start))
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
                ).copy(remote = result.file.remote, tdlibFileId = result.file.tdlibFileId),
            )
            result.file
        } catch (error: Throwable) {
            val running = TransferStateMachine.reduce(queued, TransferEvent.Start)
            repository.updateTransfer(
                TransferStateMachine.reduce(running, TransferEvent.Fail(error.message ?: "Upload failed", retryable = true)),
            )
            throw error
        } finally {
            staged.file.delete()
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
