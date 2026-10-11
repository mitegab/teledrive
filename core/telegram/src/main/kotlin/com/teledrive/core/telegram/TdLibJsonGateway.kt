package com.teledrive.core.telegram

import android.content.Context
import android.util.Base64
import com.teledrive.core.domain.DownloadRequest
import com.teledrive.core.domain.DownloadResult
import com.teledrive.core.domain.GatewayErrorMapper
import com.teledrive.core.domain.StorageGatewayException
import com.teledrive.core.domain.TelegramStorageGateway
import com.teledrive.core.domain.UploadRequest
import com.teledrive.core.domain.UploadResult
import com.teledrive.core.model.AuthorizationState
import com.teledrive.core.model.FilesPage
import com.teledrive.core.model.HistoryCursor
import com.teledrive.core.model.LocalAvailability
import com.teledrive.core.model.MetadataMessagesPage
import com.teledrive.core.model.MetadataRecordCodec
import com.teledrive.core.model.RemoteMessageId
import com.teledrive.core.model.SavedMessagesChat
import com.teledrive.core.model.StoredFile
import com.teledrive.core.model.TelegramUser
import com.teledrive.core.model.TransferDirection
import com.teledrive.core.model.TransferId
import com.teledrive.core.model.TransferProgress
import com.teledrive.core.model.TransferState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.JsonClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Real TDLib adapter using the official JSON-Java JNI interface.
 *
 * There is one receive loop for the process. TDLib updates are applied in the
 * order they arrive and request responses are correlated by @extra. No UI
 * class reaches JsonClient directly.
 */
class TdLibJsonGateway(
    context: Context,
    private val credentials: TelegramCredentials,
    private val applicationVersion: String = "0.1.0",
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val keyProvider: TdLibDatabaseKeyProvider = TdLibDatabaseKeyProvider(context.applicationContext),
) : TelegramStorageGateway {
    private data class MessageKey(val chatId: Long, val messageId: Long)

    private val appContext = context.applicationContext
    private val _authorizationState = MutableStateFlow<AuthorizationState>(AuthorizationState.Uninitialized)
    private val _transferUpdates = MutableSharedFlow<TransferProgress>(extraBufferCapacity = 128)
    private val _remoteMessageDeletions = MutableSharedFlow<RemoteMessageId>(extraBufferCapacity = 128)
    private val requestCounter = AtomicLong(0)
    private val pendingRequests = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private val uploadWaiters = ConcurrentHashMap<String, CompletableDeferred<StoredFile>>()
    private val uploadMessageTracker = com.teledrive.core.domain.UploadMessageTracker()
    private val downloadWaiters = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val transferByMessageId = ConcurrentHashMap<MessageKey, TransferId>()
    private val transferByFileId = ConcurrentHashMap<Int, TransferId>()
    private val transferDirections = ConcurrentHashMap<String, TransferDirection>()
    private val transferTotals = ConcurrentHashMap<String, Long?>()
    private var clientId: Int? = null
    private var receiverJob: Job? = null
    private var tdlibParametersSent = false
    private var savedMessagesChat: SavedMessagesChat? = null

    override val authorizationState: StateFlow<AuthorizationState> = _authorizationState.asStateFlow()
    override val transferUpdates: Flow<TransferProgress> = _transferUpdates.asSharedFlow()
    override val remoteMessageDeletions: Flow<RemoteMessageId> = _remoteMessageDeletions.asSharedFlow()

    override suspend fun start() {
        if (receiverJob?.isActive == true) return
        if (!credentials.isConfigured) {
            _authorizationState.value = AuthorizationState.Error(
                message = "Telegram API credentials are missing. Add TELEGRAM_API_ID and TELEGRAM_API_HASH to local.properties.",
            )
            return
        }
        if (!JsonClient.isNativeLoaded()) {
            _authorizationState.value = AuthorizationState.Error(
                message = "TDLib native libraries are not packaged. Run tools/build-tdlib-android.sh before connecting Telegram.",
            )
            return
        }

        val newClientId = synchronized(this) {
            clientId ?: JsonClient.createClientId().also { clientId = it }
        }
        receiverJob = scope.launch(Dispatchers.IO) { receiveLoop() }
        // TDLib starts delivering updates after the first request. This request is harmless and carries no user data.
        sendRaw(JSONObject().put("@type", "getOption").put("name", "version"), newClientId)
    }

    override suspend fun submitPhoneNumber(phoneNumber: String) {
        require(phoneNumber.trim().length >= 5) { "Enter the phone number in international format" }
        command(
            JSONObject()
                .put("@type", "setAuthenticationPhoneNumber")
                .put("phone_number", phoneNumber.trim())
                .put("settings", JSONObject.NULL),
        )
    }

    override suspend fun submitCode(code: String) {
        require(code.trim().isNotEmpty()) { "Enter the authentication code" }
        command(JSONObject().put("@type", "checkAuthenticationCode").put("code", code.trim()))
    }

    override suspend fun submitPassword(password: String) {
        require(password.isNotEmpty()) { "Enter the two-step verification password" }
        command(JSONObject().put("@type", "checkAuthenticationPassword").put("password", password))
    }

    override suspend fun submitEmailAddress(emailAddress: String) {
        require(emailAddress.trim().contains('@')) { "Enter a valid email address" }
        command(JSONObject().put("@type", "setAuthenticationEmailAddress").put("email_address", emailAddress.trim()))
    }

    override suspend fun submitEmailCode(code: String) {
        require(code.trim().isNotEmpty()) { "Enter the email verification code" }
        command(JSONObject().put("@type", "checkAuthenticationEmailCode").put("code", code.trim()))
    }

    override suspend fun cancelAuthorization() {
        runCatching { command(JSONObject().put("@type", "logOut")) }
            .onFailure { _authorizationState.value = AuthorizationState.Error(message = "Unable to cancel Telegram authorization") }
    }

    override suspend fun logOut() {
        command(JSONObject().put("@type", "logOut"))
        savedMessagesChat = null
    }

    override suspend fun getCurrentUser(): TelegramUser {
        val user = request(JSONObject().put("@type", "getMe"))
        val id = user.optLong("id", 0L).takeIf { it > 0 } ?: throw StorageGatewayException(
            code = "INVALID_USER",
            message = "Telegram did not return a current user",
            retryable = false,
        )
        val usernames = user.safeObject("usernames")
        val username = usernames?.optString("editable_username")?.takeIf { it.isNotBlank() }
            ?: usernames?.safeArray("active_usernames")?.optString(0)?.takeIf { it.isNotBlank() }
        return TelegramUser(
            id = id,
            firstName = user.optString("first_name"),
            lastName = user.optString("last_name"),
            username = username,
        )
    }

    override suspend fun getSavedMessagesChat(): SavedMessagesChat {
        savedMessagesChat?.let { return it }
        val user = getCurrentUser()
        val chat = request(JSONObject().put("@type", "getChat").put("chat_id", user.id))
        val chatType = chat.safeObject("type")
        val isCurrentUserChat = chatType?.type() == "chatTypePrivate" && chatType.optLong("user_id", 0L) == user.id
        if (!isCurrentUserChat) {
            throw StorageGatewayException(
                code = "SAVED_MESSAGES_NOT_FOUND",
                message = "The authenticated Telegram account did not expose its Saved Messages chat",
                retryable = false,
            )
        }
        return SavedMessagesChat(
            chatId = chat.optLong("id", user.id),
            title = chat.optString("title").ifBlank { "Saved Messages" },
        ).also { savedMessagesChat = it }
    }

    override suspend fun getFilesPage(cursor: HistoryCursor, limit: Int): FilesPage {
        val chat = getSavedMessagesChat()
        val messages = request(
            JSONObject()
                .put("@type", "getChatHistory")
                .put("chat_id", chat.chatId)
                .put("from_message_id", cursor.fromMessageId)
                .put("offset", 0)
                .put("limit", limit.coerceIn(1, 100))
                .put("only_local", false),
        ).safeArray("messages") ?: JSONArray()

        val files = buildList {
            for (index in 0 until messages.length()) {
                val message = messages.optJSONObject(index) ?: continue
                val content = message.safeObject("content")
                val text = if (content?.type() == "messageText") content.safeObject("text")?.optString("text") else null
                // Reserved TeleDrive metadata is an internal event, never a user file.
                if (MetadataRecordCodec.decode(text) != com.teledrive.core.model.MetadataDecodeResult.NotMetadata) continue
                message.toStoredFile(chat.chatId)?.let(::add)
            }
        }
        val oldestMessageId = (0 until messages.length())
            .mapNotNull { messages.optJSONObject(it)?.optLong("id", 0L)?.takeIf { id -> id > 0 } }
            .minOrNull()
        val nextCursor = oldestMessageId?.takeIf { it > 0 && (cursor.fromMessageId == 0L || it < cursor.fromMessageId) }
            ?.let(::HistoryCursor)
        return FilesPage(files = files, nextCursor = nextCursor, approximateTotalCount = null)
    }

    override suspend fun getMetadataMessagesPage(cursor: HistoryCursor, limit: Int): MetadataMessagesPage {
        val chat = getSavedMessagesChat()
        val messages = request(
            JSONObject()
                .put("@type", "getChatHistory")
                .put("chat_id", chat.chatId)
                .put("from_message_id", cursor.fromMessageId)
                .put("offset", 0)
                .put("limit", limit.coerceIn(1, 100))
                .put("only_local", false),
        ).safeArray("messages") ?: JSONArray()
        val records = buildList {
            for (index in 0 until messages.length()) {
                val message = messages.optJSONObject(index) ?: continue
                val content = message.safeObject("content") ?: continue
                if (content.type() != "messageText") continue
                val text = content.safeObject("text")?.optString("text") ?: continue
                if (text.startsWith(MetadataRecordCodec.PREFIX)) add(text)
            }
        }
        val oldest = (0 until messages.length())
            .mapNotNull { messages.optJSONObject(it)?.optLong("id", 0L)?.takeIf { id -> id > 0 } }
            .minOrNull()
        val next = oldest?.takeIf { it > 0 && (cursor.fromMessageId == 0L || it < cursor.fromMessageId) }?.let(::HistoryCursor)
        return MetadataMessagesPage(records, next)
    }

    override suspend fun uploadDocument(request: UploadRequest): UploadResult {
        val chat = getSavedMessagesChat()
        transferDirections[request.transferId.value] = TransferDirection.UPLOAD
        transferTotals[request.transferId.value] = request.sizeBytes
        emitTransfer(request.transferId, 0L, request.sizeBytes, TransferState.RUNNING)
        val waiter = CompletableDeferred<StoredFile>()
        uploadWaiters[request.transferId.value] = waiter
        uploadMessageTracker.register(request.transferId, chat.chatId)
        try {
            val message = request(
                JSONObject()
                    .put("@type", "sendMessage")
                    .put("chat_id", chat.chatId)
                    .put("topic_id", JSONObject.NULL)
                    .put("reply_to", JSONObject.NULL)
                    .put("options", JSONObject.NULL)
                    .put("reply_markup", JSONObject.NULL)
                    .put("input_message_content", inputMessageDocument(request.localPath)),
            )
            val messageId = message.optLong("id", 0L).takeIf { it > 0L }
            messageId?.let {
                uploadMessageTracker.bind(request.transferId, it)
                transferByMessageId[MessageKey(chat.chatId, it)] = request.transferId
            }
            val initial = message.toStoredFile(chat.chatId)
            initial?.tdlibFileId?.let { transferByFileId[it] = request.transferId }
            // The send response can be a provisional message or can omit usable content. In both cases,
            // keep the persisted transfer alive until a later message update supplies the final media.
            val isStillSending = message.safeObject("sending_state")?.type() == "messageSendingStatePending"
            if (initial != null && !isStillSending) {
                emitTransfer(request.transferId, request.sizeBytes ?: initial.sizeBytes ?: 0L, request.sizeBytes ?: initial.sizeBytes, TransferState.COMPLETED, initial.remote, initial.tdlibFileId)
                return UploadResult(request.transferId, initial)
            }
            val finalFile = withTimeout(30.minutes) { waiter.await() }
            emitTransfer(request.transferId, finalFile.sizeBytes ?: request.sizeBytes ?: 0L, finalFile.sizeBytes ?: request.sizeBytes, TransferState.COMPLETED, finalFile.remote, finalFile.tdlibFileId)
            return UploadResult(request.transferId, finalFile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val mapped = error.asStorageException()
            emitTransfer(request.transferId, 0L, request.sizeBytes, TransferState.FAILED, errorMessage = mapped.message)
            throw mapped
        } finally {
            uploadWaiters.remove(request.transferId.value)
            transferDirections.remove(request.transferId.value)
            transferTotals.remove(request.transferId.value)
            transferByFileId.entries.removeIf { it.value == request.transferId }
            transferByMessageId.entries.removeIf { it.value == request.transferId }
            uploadMessageTracker.remove(request.transferId)
        }
    }

    override suspend fun downloadFile(request: DownloadRequest): DownloadResult {
        if (downloadWaiters.containsKey(request.tdlibFileId)) {
            throw StorageGatewayException("TRANSFER_ALREADY_ACTIVE", "This file is already downloading", false)
        }
        transferDirections[request.transferId.value] = TransferDirection.DOWNLOAD
        transferTotals[request.transferId.value] = request.expectedSizeBytes
        val waiter = CompletableDeferred<JSONObject>()
        downloadWaiters[request.tdlibFileId] = waiter
        transferByFileId[request.tdlibFileId] = request.transferId
        emitTransfer(request.transferId, 0L, request.expectedSizeBytes, TransferState.RUNNING, request.remote, request.tdlibFileId)
        try {
            val file = request(
                JSONObject()
                    .put("@type", "downloadFile")
                    .put("file_id", request.tdlibFileId)
                    .put("priority", 32)
                    .put("offset", 0)
                    .put("limit", 0)
                    .put("synchronous", false),
            )
            if (file.completedDownload()) {
                val path = file.safeObject("local")?.optString("path")?.takeIf { it.isNotBlank() }
                    ?: throw StorageGatewayException("DOWNLOAD_PATH_MISSING", "Telegram completed the download without a local path", false)
                emitTransfer(request.transferId, file.downloadedBytes(), request.expectedSizeBytes, TransferState.COMPLETED, request.remote, request.tdlibFileId)
                return DownloadResult(request.transferId, request.remote, path, request.expectedSizeBytes)
            }
            val completed = withTimeout(30.minutes) { waiter.await() }
            val path = completed.safeObject("local")?.optString("path")?.takeIf { it.isNotBlank() }
                ?: throw StorageGatewayException("DOWNLOAD_PATH_MISSING", "Telegram completed the download without a local path", false)
            return DownloadResult(request.transferId, request.remote, path, request.expectedSizeBytes)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val mapped = error.asStorageException()
            emitTransfer(request.transferId, 0L, request.expectedSizeBytes, TransferState.FAILED, request.remote, request.tdlibFileId, mapped.message)
            throw mapped
        } finally {
            downloadWaiters.remove(request.tdlibFileId)
            transferByFileId.remove(request.tdlibFileId)
            transferDirections.remove(request.transferId.value)
            transferTotals.remove(request.transferId.value)
        }
    }

    override suspend fun cancelTransfer(transferId: TransferId, direction: TransferDirection, tdlibFileId: Int?) {
        when (direction) {
            TransferDirection.DOWNLOAD -> {
                val fileId = tdlibFileId ?: transferByFileId.entries.firstOrNull { it.value == transferId }?.key
                if (fileId != null) {
                    request(JSONObject().put("@type", "cancelDownloadFile").put("file_id", fileId).put("only_if_pending", false))
                    downloadWaiters.remove(fileId)?.cancel(CancellationException("Transfer cancelled by user"))
                    transferByFileId.remove(fileId)
                }
            }
            TransferDirection.UPLOAD -> {
                // TDLib has no general cancelUploadFile for sendMessage. Removing the temporary outgoing message is the documented best-effort cancellation boundary.
                val mappedMessageKey = transferByMessageId.entries.firstOrNull { it.value == transferId }?.key
                val temporaryMessageId = uploadMessageTracker.messageIds(transferId).firstOrNull()
                    ?: mappedMessageKey?.messageId
                if (temporaryMessageId != null) {
                    val chat = getSavedMessagesChat()
                    request(
                        JSONObject()
                            .put("@type", "deleteMessages")
                            .put("chat_id", chat.chatId)
                            .put("message_ids", JSONArray().put(temporaryMessageId))
                            .put("revoke", false),
                    )
                    transferByMessageId.entries.removeIf { it.value == transferId && it.key.messageId == temporaryMessageId }
                }
                uploadWaiters.remove(transferId.value)?.cancel(CancellationException("Transfer cancelled by user"))
            }
        }
        emitTransfer(transferId, 0L, transferTotals[transferId.value], TransferState.CANCELED)
    }

    override suspend fun deleteRemoteMessage(remote: RemoteMessageId, revoke: Boolean) {
        request(
            JSONObject()
                .put("@type", "deleteMessages")
                .put("chat_id", remote.chatId)
                .put("message_ids", JSONArray().put(remote.messageId))
                .put("revoke", revoke),
        )
    }

    override suspend fun sendMetadataRecord(text: String): RemoteMessageId {
        require(text.startsWith(MetadataRecordCodec.PREFIX)) { "Only encoded TeleDrive metadata may use this operation" }
        val chat = getSavedMessagesChat()
        val message = request(
            JSONObject()
                .put("@type", "sendMessage")
                .put("chat_id", chat.chatId)
                .put("topic_id", JSONObject.NULL)
                .put("reply_to", JSONObject.NULL)
                .put("options", JSONObject.NULL)
                .put("reply_markup", JSONObject.NULL)
                .put("input_message_content", inputMessageText(text)),
        )
        return RemoteMessageId(chat.chatId, message.optLong("id", 0L).takeIf { it > 0 } ?: throw StorageGatewayException("METADATA_RESPONSE_INVALID", "Telegram did not return the metadata message", false))
    }

    override suspend fun close() {
        if (clientId != null) runCatching { request(JSONObject().put("@type", "close")) }
        receiverJob?.cancel()
        receiverJob = null
        clientId = null
        pendingRequests.values.forEach { it.cancel() }
        pendingRequests.clear()
        scope.cancel()
        _authorizationState.value = AuthorizationState.Closed
    }

    private suspend fun command(request: JSONObject) {
        request(request)
    }

    private suspend fun request(request: JSONObject): JSONObject {
        val id = clientId ?: throw StorageGatewayException("TDLIB_NOT_STARTED", "Telegram is not initialized", false)
        val extra = requestCounter.incrementAndGet().toString()
        val response = CompletableDeferred<JSONObject>()
        pendingRequests[extra] = response
        request.put("@extra", extra)
        try {
            sendRaw(request, id)
            return withTimeout(60.seconds) { response.await() }.requireSuccessful()
        } catch (timeout: TimeoutCancellationException) {
            throw StorageGatewayException("TIMEOUT", "Telegram did not respond in time", true, timeout)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            throw error.asStorageException()
        } finally {
            pendingRequests.remove(extra)
        }
    }

    private fun sendRaw(request: JSONObject, id: Int? = null) {
        val target = id ?: clientId ?: return
        JsonClient.send(target, request.toString())
    }

    private suspend fun receiveLoop() {
        while (currentCoroutineContext().isActive) {
            val raw = try {
                JsonClient.receive(1.0)
            } catch (error: UnsatisfiedLinkError) {
                _authorizationState.value = AuthorizationState.Error(message = "TDLib native library could not be called")
                return
            } catch (_: Throwable) {
                delay(100)
                continue
            }
            if (!raw.isNullOrBlank()) runCatching { processIncoming(JSONObject(raw)) }
        }
    }

    private fun processIncoming(json: JSONObject) {
        json.optString("@extra").takeIf { it.isNotBlank() }?.let { extra ->
            pendingRequests.remove(extra)?.complete(json)
        }
        when (json.type()) {
            "updateAuthorizationState" -> handleAuthorizationState(json.safeObject("authorization_state"))
            "updateFile" -> handleFileUpdate(json.safeObject("file"))
            "updateNewMessage" -> handleNewMessage(json)
            "updateMessageContent" -> handleMessageContent(json)
            "updateMessageSendSucceeded" -> handleMessageSendSucceeded(json)
            "updateMessageSendFailed" -> handleMessageSendFailed(json)
            "updateDeleteMessages" -> handleDeleteMessages(json)
        }
    }

    private fun handleDeleteMessages(update: JSONObject) {
        update.permanentDeletedMessages().forEach(_remoteMessageDeletions::tryEmit)
    }

    private fun handleAuthorizationState(state: JSONObject?) {
        state ?: return
        when (state.type()) {
            "authorizationStateWaitTdlibParameters" -> {
                _authorizationState.value = AuthorizationState.WaitingForTdlibParameters
                if (!tdlibParametersSent) {
                    tdlibParametersSent = true
                    scope.launch {
                        runCatching {
                            val key = keyProvider.getOrCreate()
                            request(
                                buildTdlibParameters(
                                    credentials = credentials,
                                    databaseDirectory = appContext.filesDir.resolve("tdlib-data").absolutePath,
                                    filesDirectory = appContext.filesDir.resolve("tdlib-files").absolutePath,
                                    databaseKeyBase64 = Base64.encodeToString(key, Base64.NO_WRAP),
                                    applicationVersion = applicationVersion,
                                ).also {
                                    appContext.filesDir.resolve("tdlib-data").mkdirs()
                                    appContext.filesDir.resolve("tdlib-files").mkdirs()
                                },
                            )
                        }.onFailure { error ->
                            _authorizationState.value = AuthorizationState.Error(message = error.asStorageException().message)
                        }
                    }
                }
            }
            "authorizationStateWaitPhoneNumber" -> _authorizationState.value = AuthorizationState.WaitingForPhoneNumber
            "authorizationStateWaitCode" -> _authorizationState.value = AuthorizationState.WaitingForCode(state.safeObject("code_info")?.optString("phone_number_pattern"))
            "authorizationStateWaitPassword" -> _authorizationState.value = AuthorizationState.WaitingForPassword(state.optString("password_hint").takeIf { it.isNotBlank() })
            "authorizationStateWaitEmailAddress" -> _authorizationState.value = AuthorizationState.WaitingForEmailAddress(state.optBoolean("allow_apple_id"), state.optBoolean("allow_google_id"))
            "authorizationStateWaitEmailCode" -> _authorizationState.value = AuthorizationState.WaitingForEmailCode(state.safeObject("code_info")?.optString("email_address_pattern"))
            "authorizationStateWaitOtherDeviceConfirmation" -> _authorizationState.value = AuthorizationState.WaitingForOtherDeviceConfirmation(state.optString("link"))
            "authorizationStateWaitRegistration" -> _authorizationState.value = AuthorizationState.WaitingForRegistration(state.safeObject("terms_of_service")?.optString("text"))
            "authorizationStateWaitPremiumPurchase" -> _authorizationState.value = AuthorizationState.WaitingForPremiumPurchase
            "authorizationStateReady" -> _authorizationState.value = AuthorizationState.Ready
            "authorizationStateLoggingOut" -> _authorizationState.value = AuthorizationState.LoggingOut
            "authorizationStateClosing" -> _authorizationState.value = AuthorizationState.Closing
            "authorizationStateClosed" -> _authorizationState.value = AuthorizationState.Closed
        }
    }

    private fun handleFileUpdate(file: JSONObject?) {
        file ?: return
        val fileId = file.fileId() ?: return
        val transferId = transferByFileId[fileId] ?: return
        val direction = transferDirections[transferId.value] ?: return
        val total = transferTotals[transferId.value] ?: file.fileSize()
        when (direction) {
            TransferDirection.UPLOAD -> {
                val bytes = file.uploadedBytes().coerceAtLeast(0L)
                emitTransfer(transferId, bytes, total, TransferState.RUNNING, tdlibFileId = fileId)
            }
            TransferDirection.DOWNLOAD -> {
                val bytes = file.downloadedBytes().coerceAtLeast(0L)
                if (file.completedDownload()) {
                    emitTransfer(transferId, bytes, total, TransferState.COMPLETED, tdlibFileId = fileId)
                    downloadWaiters.remove(fileId)?.complete(file)
                } else {
                    emitTransfer(transferId, bytes, total, TransferState.RUNNING, tdlibFileId = fileId)
                }
            }
        }
    }

    private fun handleNewMessage(update: JSONObject) {
        val message = update.safeObject("message") ?: return
        val sendingState = message.safeObject("sending_state")?.type()
        if (sendingState != "messageSendingStatePending") return
        val chatId = message.optLong("chat_id", 0L).takeIf { it > 0L } ?: savedMessagesChat?.chatId ?: return
        val messageId = message.optLong("id", 0L).takeIf { it > 0L } ?: return
        val storedFile = message.toStoredFile(chatId) ?: return
        val transferId = uploadMessageTracker.resolve(chatId, messageId) ?: return
        completeUploadMessage(transferId, message, storedFile, requireFinalMessage = true)
    }

    private fun handleMessageContent(update: JSONObject) {
        val chatId = update.optLong("chat_id", 0L).takeIf { it > 0L } ?: savedMessagesChat?.chatId ?: return
        val messageId = update.optLong("message_id", 0L).takeIf { it > 0L } ?: return
        val content = update.safeObject("new_content") ?: return
        val message = JSONObject()
            .put("id", messageId)
            .put("chat_id", chatId)
            .put("content", content)
        val storedFile = message.toStoredFile(chatId) ?: return
        val transferId = uploadMessageTracker.resolve(chatId, messageId) ?: return
        completeUploadMessage(transferId, message, storedFile, requireFinalMessage = false)
    }

    private fun handleMessageSendSucceeded(update: JSONObject) {
        val message = update.safeObject("message") ?: return
        val chatId = message.optLong("chat_id", 0L).takeIf { it > 0L } ?: savedMessagesChat?.chatId ?: 0L
        val messageId = message.optLong("id", 0L).takeIf { it > 0L }
        val oldMessageId = update.optLong("old_message_id", 0L).takeIf { it > 0L }
        val transferId = uploadMessageTracker.resolve(chatId, messageId, oldMessageId)
            ?: oldMessageId?.let { transferByMessageId[MessageKey(chatId, it)] }
            ?: messageId?.let { transferByMessageId[MessageKey(chatId, it)] }
            ?: return
        messageId?.let {
            uploadMessageTracker.bind(transferId, it)
            transferByMessageId[MessageKey(chatId, it)] = transferId
        }
        val storedFile = message.toStoredFile(chatId) ?: return
        completeUploadMessage(transferId, message, storedFile, requireFinalMessage = false)
    }

    private fun handleMessageSendFailed(update: JSONObject) {
        val message = update.safeObject("message")
        val chatId = message?.optLong("chat_id", 0L)?.takeIf { it > 0L }
            ?: savedMessagesChat?.chatId
            ?: 0L
        val messageId = message?.optLong("id", 0L)?.takeIf { it > 0L }
        val oldMessageId = update.optLong("old_message_id", 0L).takeIf { it > 0L }
        val transferId = uploadMessageTracker.resolve(chatId, messageId, oldMessageId)
            ?: oldMessageId?.let { transferByMessageId[MessageKey(chatId, it)] }
            ?: messageId?.let { transferByMessageId[MessageKey(chatId, it)] }
            ?: return
        val error = update.safeObject("error")
        val mapped = GatewayErrorMapper.fromTdlib(error?.optInt("code"), error?.optString("message"))
        if (uploadWaiters.remove(transferId.value)?.completeExceptionally(mapped) == true) {
            uploadMessageTracker.complete(transferId)
            transferByMessageId.entries.removeIf { it.value == transferId }
            emitTransfer(transferId, 0L, transferTotals[transferId.value], TransferState.FAILED, errorMessage = mapped.message)
        }
    }

    private fun completeUploadMessage(
        transferId: TransferId,
        message: JSONObject,
        storedFile: StoredFile,
        requireFinalMessage: Boolean,
    ): Boolean {
        val stillSending = message.safeObject("sending_state")?.type() == "messageSendingStatePending"
        storedFile.tdlibFileId?.let { transferByFileId[it] = transferId }
        if (requireFinalMessage && stillSending) {
            emitTransfer(transferId, storedFile.sizeBytes ?: 0L, storedFile.sizeBytes, TransferState.RUNNING, storedFile.remote, storedFile.tdlibFileId)
            return false
        }
        val completed = uploadWaiters[transferId.value]?.complete(storedFile) == true
        if (completed) {
            uploadMessageTracker.complete(transferId)
            transferByMessageId.entries.removeIf { it.value == transferId }
            emitTransfer(transferId, storedFile.sizeBytes ?: 0L, storedFile.sizeBytes, TransferState.COMPLETED, storedFile.remote, storedFile.tdlibFileId)
        }
        return completed
    }

    private fun emitTransfer(
        transferId: TransferId,
        bytes: Long,
        total: Long?,
        state: TransferState,
        remote: RemoteMessageId? = null,
        tdlibFileId: Int? = null,
        errorMessage: String? = null,
    ) {
        _transferUpdates.tryEmit(
            TransferProgress(
                transferId = transferId,
                bytesTransferred = bytes,
                totalBytes = total,
                state = state,
                errorMessage = errorMessage,
                remote = remote,
                tdlibFileId = tdlibFileId,
            ),
        )
    }
}

private fun Throwable.asStorageException(): StorageGatewayException = when (this) {
    is StorageGatewayException -> this
    else -> GatewayErrorMapper.fromTdlib(null, message)
}
