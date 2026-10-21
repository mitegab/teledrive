package com.teledrive.core.telegram

import com.teledrive.core.telegram.TelegramCredentials
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builders for the TDLib 1.8.68 JSON requests TeleDrive sends. Field names and
 * nesting are kept in one place and asserted by JVM tests against `td_api.tl`
 * at the commit pinned in tools/TDLIB_VERSION.
 */
internal object TdLibRequests {
    fun getMe(): JSONObject = JSONObject().put("@type", "getMe")

    fun getChat(chatId: Long): JSONObject = JSONObject()
        .put("@type", "getChat")
        .put("chat_id", chatId)

    /** td_api.tl: `createPrivateChat user_id:int53 force:Bool = Chat` */
    fun createPrivateChat(userId: Long, force: Boolean): JSONObject = JSONObject()
        .put("@type", "createPrivateChat")
        .put("user_id", userId)
        .put("force", force)

    /** td_api.tl: `loadChats chat_list:ChatList limit:int32 = Ok` (null loads the main list). */
    fun loadChats(limit: Int): JSONObject = JSONObject()
        .put("@type", "loadChats")
        .put("chat_list", JSONObject.NULL)
        .put("limit", limit)

    /** td_api.tl: `getChatHistory chat_id:int53 from_message_id:int53 offset:int32 limit:int32 only_local:Bool = Messages` */
    fun getChatHistory(chatId: Long, fromMessageId: Long, offset: Int, limit: Int, onlyLocal: Boolean): JSONObject = JSONObject()
        .put("@type", "getChatHistory")
        .put("chat_id", chatId)
        .put("from_message_id", fromMessageId)
        .put("offset", offset)
        .put("limit", limit)
        .put("only_local", onlyLocal)

    /** td_api.tl: `sendMessage chat_id:int53 topic_id:MessageTopic reply_to:InputMessageReplyTo options:messageSendOptions reply_markup:ReplyMarkup input_message_content:InputMessageContent = Message` */
    fun sendMessage(chatId: Long, content: JSONObject): JSONObject = JSONObject()
        .put("@type", "sendMessage")
        .put("chat_id", chatId)
        .put("topic_id", JSONObject.NULL)
        .put("reply_to", JSONObject.NULL)
        .put("options", JSONObject.NULL)
        .put("reply_markup", JSONObject.NULL)
        .put("input_message_content", content)

    /**
     * td_api.tl: `inputMessageDocument document:inputDocument caption:formattedText = InputMessageContent`
     * where `inputDocument document:InputFile thumbnail:inputThumbnail disable_content_type_detection:Bool`.
     * Older TDLib versions took `document:InputFile` plus top-level thumbnail and
     * disable_content_type_detection fields — that shape mis-decodes on 1.8.68.
     */
    fun inputMessageDocument(path: String): JSONObject = JSONObject()
        .put("@type", "inputMessageDocument")
        .put(
            "document",
            JSONObject()
                .put("@type", "inputDocument")
                .put("document", JSONObject().put("@type", "inputFileLocal").put("path", path))
                .put("thumbnail", JSONObject.NULL)
                .put("disable_content_type_detection", false),
        )
        .put("caption", JSONObject.NULL)

    /** td_api.tl: `inputMessageText text:formattedText link_preview_options:linkPreviewOptions clear_draft:Bool = InputMessageContent` */
    fun inputMessageText(text: String): JSONObject = JSONObject()
        .put("@type", "inputMessageText")
        .put("text", formattedText(text))
        .put("link_preview_options", JSONObject.NULL)
        .put("clear_draft", true)

    /** td_api.tl: `downloadFile file_id:int32 priority:int32 offset:int53 limit:int53 synchronous:Bool = File` */
    fun downloadFile(fileId: Int, priority: Int, offset: Long, limit: Long, synchronous: Boolean): JSONObject = JSONObject()
        .put("@type", "downloadFile")
        .put("file_id", fileId)
        .put("priority", priority)
        .put("offset", offset)
        .put("limit", limit)
        .put("synchronous", synchronous)

    /** td_api.tl: `getFile file_id:int32 = File` — used to re-read the authoritative local path. */
    fun getFile(fileId: Int): JSONObject = JSONObject()
        .put("@type", "getFile")
        .put("file_id", fileId)

    /** td_api.tl: `cancelDownloadFile file_id:int32 only_if_pending:Bool = Ok` */
    fun cancelDownloadFile(fileId: Int, onlyIfPending: Boolean): JSONObject = JSONObject()
        .put("@type", "cancelDownloadFile")
        .put("file_id", fileId)
        .put("only_if_pending", onlyIfPending)

    /** td_api.tl: `deleteMessages chat_id:int53 message_ids:vector<int53> revoke:Bool = Ok` */
    fun deleteMessages(chatId: Long, messageIds: List<Long>, revoke: Boolean): JSONObject = JSONObject()
        .put("@type", "deleteMessages")
        .put("chat_id", chatId)
        .put("message_ids", JSONArray().apply { messageIds.forEach { put(it) } })
        .put("revoke", revoke)

    fun setAuthenticationPhoneNumber(phoneNumber: String): JSONObject = JSONObject()
        .put("@type", "setAuthenticationPhoneNumber")
        .put("phone_number", phoneNumber)
        .put("settings", JSONObject.NULL)

    fun checkAuthenticationCode(code: String): JSONObject = JSONObject()
        .put("@type", "checkAuthenticationCode")
        .put("code", code)

    fun checkAuthenticationPassword(password: String): JSONObject = JSONObject()
        .put("@type", "checkAuthenticationPassword")
        .put("password", password)

    fun setAuthenticationEmailAddress(emailAddress: String): JSONObject = JSONObject()
        .put("@type", "setAuthenticationEmailAddress")
        .put("email_address", emailAddress)

    /**
     * td_api.tl: `checkAuthenticationEmailCode code:EmailAddressAuthentication = Ok`
     * where `emailAddressAuthenticationCode code:string = EmailAddressAuthentication`.
     * The code must be wrapped, not sent as a bare string.
     */
    fun checkAuthenticationEmailCode(code: String): JSONObject = JSONObject()
        .put("@type", "checkAuthenticationEmailCode")
        .put("code", JSONObject().put("@type", "emailAddressAuthenticationCode").put("code", code))

    /** td_api.tl: `setTdlibParameters use_test_dc:Bool database_directory:string files_directory:string database_encryption_key:bytes use_file_database:Bool use_chat_info_database:Bool use_message_database:Bool use_secret_chats:Bool api_id:int32 api_hash:string system_language_code:string device_model:string system_version:string application_version:string = Ok` */
    fun setTdlibParameters(
        credentials: TelegramCredentials,
        databaseDirectory: String,
        filesDirectory: String,
        databaseKeyBase64: String,
        applicationVersion: String,
        systemLanguageCode: String,
        deviceModel: String,
        systemVersion: String,
    ): JSONObject = JSONObject()
        .put("@type", "setTdlibParameters")
        .put("use_test_dc", false)
        .put("database_directory", databaseDirectory)
        .put("files_directory", filesDirectory)
        .put("database_encryption_key", databaseKeyBase64)
        .put("use_file_database", true)
        .put("use_chat_info_database", true)
        .put("use_message_database", true)
        .put("use_secret_chats", false)
        .put("api_id", credentials.apiId)
        .put("api_hash", credentials.apiHash)
        .put("system_language_code", systemLanguageCode)
        .put("device_model", deviceModel)
        .put("system_version", systemVersion)
        .put("application_version", applicationVersion)

    fun logOut(): JSONObject = JSONObject().put("@type", "logOut")

    fun close(): JSONObject = JSONObject().put("@type", "close")

    fun getOption(name: String): JSONObject = JSONObject().put("@type", "getOption").put("name", name)

    fun formattedText(text: String): JSONObject = JSONObject()
        .put("@type", "formattedText")
        .put("text", text)
        .put("entities", JSONArray())
}
