package com.teledrive.core.domain

import com.teledrive.core.model.AuthorizationState
import com.teledrive.core.model.FilesPage
import com.teledrive.core.model.HistoryCursor
import com.teledrive.core.model.RemoteMessageId
import com.teledrive.core.model.SavedMessagesChat
import com.teledrive.core.model.StoredFile
import com.teledrive.core.model.TelegramUser
import com.teledrive.core.model.TransferDirection
import com.teledrive.core.model.TransferId
import com.teledrive.core.model.TransferProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The only boundary the rest of the app uses for Telegram. Implementations may be real TDLib or a test fake.
 */
interface TelegramStorageGateway {
    val authorizationState: StateFlow<AuthorizationState>
    val transferUpdates: Flow<TransferProgress>

    suspend fun start()
    suspend fun submitPhoneNumber(phoneNumber: String)
    suspend fun submitCode(code: String)
    suspend fun submitPassword(password: String)
    suspend fun submitEmailAddress(emailAddress: String)
    suspend fun submitEmailCode(code: String)
    suspend fun cancelAuthorization()
    suspend fun logOut()

    suspend fun getCurrentUser(): TelegramUser
    suspend fun getSavedMessagesChat(): SavedMessagesChat
    suspend fun getFilesPage(cursor: HistoryCursor, limit: Int = 100): FilesPage

    suspend fun uploadDocument(request: UploadRequest): UploadResult
    suspend fun downloadFile(request: DownloadRequest): DownloadResult
    suspend fun cancelTransfer(transferId: TransferId, direction: TransferDirection, tdlibFileId: Int? = null)
    suspend fun deleteRemoteMessage(remote: RemoteMessageId, revoke: Boolean = false)
    suspend fun sendMetadataRecord(text: String): RemoteMessageId
    suspend fun close()
}

data class UploadRequest(
    val transferId: TransferId,
    val localPath: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
)

data class UploadResult(
    val transferId: TransferId,
    val file: StoredFile,
)

data class DownloadRequest(
    val transferId: TransferId,
    val remote: RemoteMessageId,
    val tdlibFileId: Int,
    val expectedSizeBytes: Long?,
)

data class DownloadResult(
    val transferId: TransferId,
    val remote: RemoteMessageId,
    val localPath: String,
    val sizeBytes: Long?,
)

class StorageGatewayException(
    val code: String?,
    override val message: String,
    val retryable: Boolean,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
