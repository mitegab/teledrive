package com.teledrive.core.testing

import com.teledrive.core.domain.DownloadRequest
import com.teledrive.core.domain.DownloadResult
import com.teledrive.core.domain.StorageGatewayException
import com.teledrive.core.domain.TelegramStorageGateway
import com.teledrive.core.domain.UploadRequest
import com.teledrive.core.domain.UploadResult
import com.teledrive.core.model.AuthorizationState
import com.teledrive.core.model.FileKindDetector
import com.teledrive.core.model.FilesPage
import com.teledrive.core.model.HistoryCursor
import com.teledrive.core.model.RemoteAvailability
import com.teledrive.core.model.RemoteMessageId
import com.teledrive.core.model.SavedMessagesChat
import com.teledrive.core.model.StoredFile
import com.teledrive.core.model.TelegramUser
import com.teledrive.core.model.TransferDirection
import com.teledrive.core.model.TransferId
import com.teledrive.core.model.TransferProgress
import com.teledrive.core.model.TransferState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicLong

/** Deterministic fake for repository, ViewModel, and failure-state tests; it never pretends to contact Telegram. */
class FakeTelegramStorageGateway(
    initialAuthorization: AuthorizationState = AuthorizationState.Ready,
) : TelegramStorageGateway {
    private val nextMessageId = AtomicLong(100)
    private val _authorizationState = MutableStateFlow(initialAuthorization)
    private val _transferUpdates = MutableSharedFlow<TransferProgress>(extraBufferCapacity = 64)
    private val files = linkedMapOf<String, StoredFile>()
    private val metadata = mutableListOf<String>()

    var currentUser = TelegramUser(42, "Test", "User")
    var savedChat = SavedMessagesChat(42, "Saved Messages")
    var nextError: StorageGatewayException? = null
    var lastUpload: UploadRequest? = null
    var lastDownload: DownloadRequest? = null
    val deletedRemoteMessages = mutableListOf<RemoteMessageId>()
    val cancelledTransfers = mutableListOf<TransferId>()
    val metadataMessages: List<String> get() = metadata.toList()

    override val authorizationState: MutableStateFlow<AuthorizationState> = _authorizationState
    override val transferUpdates: Flow<TransferProgress> = _transferUpdates.asSharedFlow()

    fun seed(vararg values: StoredFile) = values.forEach { files[it.stableId] = it }

    fun setAuthorization(state: AuthorizationState) {
        _authorizationState.value = state
    }

    override suspend fun start() = Unit
    override suspend fun submitPhoneNumber(phoneNumber: String) = Unit
    override suspend fun submitCode(code: String) = Unit
    override suspend fun submitPassword(password: String) = Unit
    override suspend fun submitEmailAddress(emailAddress: String) = Unit
    override suspend fun submitEmailCode(code: String) = Unit
    override suspend fun cancelAuthorization() { _authorizationState.value = AuthorizationState.Closed }
    override suspend fun logOut() { _authorizationState.value = AuthorizationState.Closed }
    override suspend fun getCurrentUser(): TelegramUser = currentUser
    override suspend fun getSavedMessagesChat(): SavedMessagesChat = savedChat

    override suspend fun getFilesPage(cursor: HistoryCursor, limit: Int): FilesPage {
        nextError?.let { throw it.also { nextError = null } }
        val values = files.values.sortedByDescending { it.remote.messageId }
        val page = values.filter { cursor.fromMessageId == 0L || it.remote.messageId < cursor.fromMessageId }.take(limit)
        val next = page.lastOrNull()?.remote?.messageId?.let(::HistoryCursor)
        return FilesPage(page, next)
    }

    override suspend fun uploadDocument(request: UploadRequest): UploadResult {
        nextError?.let { throw it.also { nextError = null } }
        lastUpload = request
        val message = RemoteMessageId(savedChat.chatId, nextMessageId.incrementAndGet())
        val file = StoredFile(
            remote = message,
            tdlibFileId = nextMessageId.incrementAndGet().toInt(),
            originalFilename = request.displayName,
            mimeType = request.mimeType,
            sizeBytes = request.sizeBytes,
            savedAtEpochSeconds = 1,
            remoteAvailability = RemoteAvailability.AVAILABLE,
        )
        files[file.stableId] = file
        _transferUpdates.tryEmit(TransferProgress(request.transferId, request.sizeBytes ?: 0, request.sizeBytes, TransferState.COMPLETED, remote = message, tdlibFileId = file.tdlibFileId))
        return UploadResult(request.transferId, file)
    }

    override suspend fun downloadFile(request: DownloadRequest): DownloadResult {
        nextError?.let { throw it.also { nextError = null } }
        lastDownload = request
        _transferUpdates.tryEmit(TransferProgress(request.transferId, request.expectedSizeBytes ?: 0, request.expectedSizeBytes, TransferState.COMPLETED, remote = request.remote, tdlibFileId = request.tdlibFileId))
        return DownloadResult(request.transferId, request.remote, "/fake/${request.remote.messageId}", request.expectedSizeBytes)
    }

    override suspend fun cancelTransfer(transferId: TransferId, direction: TransferDirection, tdlibFileId: Int?) {
        cancelledTransfers += transferId
        _transferUpdates.tryEmit(TransferProgress(transferId, 0, null, TransferState.CANCELED, tdlibFileId = tdlibFileId))
    }

    override suspend fun deleteRemoteMessage(remote: RemoteMessageId, revoke: Boolean) {
        nextError?.let { throw it.also { nextError = null } }
        deletedRemoteMessages += remote
        files.remove(remote.value)
    }

    override suspend fun sendMetadataRecord(text: String): RemoteMessageId {
        nextError?.let { throw it.also { nextError = null } }
        metadata += text
        return RemoteMessageId(savedChat.chatId, nextMessageId.incrementAndGet())
    }

    override suspend fun close() = Unit
}
