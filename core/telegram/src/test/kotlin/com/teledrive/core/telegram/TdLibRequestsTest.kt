package com.teledrive.core.telegram

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asserts the exact `@type` and required keys of every TDLib request TeleDrive
 * builds, against `td_api.tl` at the commit pinned in tools/TDLIB_VERSION.
 * TDLib silently ignores unknown fields and mis-decodes missing ones, so a
 * stale shape fails only at runtime — these fixtures pin the contract.
 */
class TdLibRequestsTest {

    @Test
    fun sendMessageMatchesThePinnedConstructor() {
        val request = TdLibRequests.sendMessage(42, TdLibRequests.inputMessageText("hello"))

        assertEquals("sendMessage", request.type())
        assertEquals(42L, request.optLong("chat_id"))
        assertTrue(request.isNull("topic_id"))
        assertTrue(request.isNull("reply_to"))
        assertTrue(request.isNull("options"))
        assertTrue(request.isNull("reply_markup"))
        assertEquals("inputMessageText", request.safeObject("input_message_content")?.type())
    }

    @Test
    fun inputMessageDocumentWrapsTheFileInAnInputDocument() {
        val content = TdLibRequests.inputMessageDocument("/data/staging/2f6c/report.pdf")

        assertEquals("inputMessageDocument", content.type())
        // td_api.tl 1.8.68: inputMessageDocument document:inputDocument caption:formattedText
        assertEquals(setOf("document", "caption"), keys(content))
        val document = content.safeObject("document")!!
        assertEquals("inputDocument", document.type())
        // inputDocument document:InputFile thumbnail:inputThumbnail disable_content_type_detection:Bool
        assertEquals(setOf("document", "thumbnail", "disable_content_type_detection"), keys(document))
        val inputFile = document.safeObject("document")!!
        assertEquals("inputFileLocal", inputFile.type())
        assertEquals("/data/staging/2f6c/report.pdf", inputFile.optString("path"))
        assertTrue(document.isNull("thumbnail"))
        assertEquals(false, document.optBoolean("disable_content_type_detection"))
        assertTrue(content.isNull("caption"))
    }

    @Test
    fun inputMessageTextMatchesThePinnedConstructor() {
        val content = TdLibRequests.inputMessageText("notes")

        assertEquals("inputMessageText", content.type())
        assertEquals(setOf("text", "link_preview_options", "clear_draft"), keys(content))
        val text = content.safeObject("text")!!
        assertEquals("formattedText", text.type())
        assertEquals("notes", text.optString("text"))
        assertTrue(text.safeArray("entities")!!.length() == 0)
        assertTrue(content.isNull("link_preview_options"))
        assertEquals(true, content.optBoolean("clear_draft"))
    }

    @Test
    fun getChatHistoryMatchesThePinnedConstructor() {
        val request = TdLibRequests.getChatHistory(chatId = 42, fromMessageId = 100, offset = 0, limit = 50, onlyLocal = false)

        assertEquals("getChatHistory", request.type())
        assertEquals(42L, request.optLong("chat_id"))
        assertEquals(100L, request.optLong("from_message_id"))
        assertEquals(0, request.optInt("offset"))
        assertEquals(50, request.optInt("limit"))
        assertEquals(false, request.optBoolean("only_local"))
    }

    @Test
    fun downloadAndCancelDownloadMatchThePinnedConstructors() {
        val download = TdLibRequests.downloadFile(fileId = 7, priority = 32, offset = 0, limit = 0, synchronous = false)
        assertEquals("downloadFile", download.type())
        assertEquals(7, download.optInt("file_id"))
        assertEquals(32, download.optInt("priority"))
        assertEquals(0L, download.optLong("offset"))
        assertEquals(0L, download.optLong("limit"))
        assertEquals(false, download.optBoolean("synchronous"))

        val cancel = TdLibRequests.cancelDownloadFile(fileId = 7, onlyIfPending = false)
        assertEquals("cancelDownloadFile", cancel.type())
        assertEquals(7, cancel.optInt("file_id"))
        assertEquals(false, cancel.optBoolean("only_if_pending"))
    }

    @Test
    fun deleteMessagesMatchesThePinnedConstructor() {
        val request = TdLibRequests.deleteMessages(chatId = 42, messageIds = listOf(5L, 6L), revoke = true)

        assertEquals("deleteMessages", request.type())
        assertEquals(42L, request.optLong("chat_id"))
        val ids: JSONArray = request.safeArray("message_ids")!!
        assertEquals(2, ids.length())
        assertEquals(5L, ids.optLong(0))
        assertEquals(6L, ids.optLong(1))
        assertEquals(true, request.optBoolean("revoke"))
    }

    @Test
    fun setTdlibParametersCarriesEveryRequiredField() {
        val request = TdLibRequests.setTdlibParameters(
            credentials = TelegramCredentials(apiId = 1234, apiHash = "hash"),
            databaseDirectory = "/data/tdlib-data",
            filesDirectory = "/data/tdlib-files",
            databaseKeyBase64 = "a2V5",
            applicationVersion = "0.1.0",
            systemLanguageCode = "en",
            deviceModel = "Harness JVM",
            systemVersion = "13",
        )

        assertEquals("setTdlibParameters", request.type())
        assertEquals(
            listOf(
                "use_test_dc", "database_directory", "files_directory", "database_encryption_key",
                "use_file_database", "use_chat_info_database", "use_message_database", "use_secret_chats",
                "api_id", "api_hash", "system_language_code", "device_model", "system_version",
                "application_version",
            ).toSet(),
            request.keys().asSequence().toSet() - "@type",
        )
        assertEquals(false, request.optBoolean("use_test_dc"))
        assertEquals("/data/tdlib-data", request.optString("database_directory"))
        assertEquals("/data/tdlib-files", request.optString("files_directory"))
        assertEquals("a2V5", request.optString("database_encryption_key"))
        assertEquals(1234, request.optInt("api_id"))
        assertEquals("hash", request.optString("api_hash"))
        assertEquals("0.1.0", request.optString("application_version"))
    }

    @Test
    fun emailCodeLoginWrapsTheCodeInEmailAddressAuthenticationCode() {
        val request = TdLibRequests.checkAuthenticationEmailCode("12345")

        assertEquals("checkAuthenticationEmailCode", request.type())
        val code = request.safeObject("code")!!
        assertEquals("emailAddressAuthenticationCode", code.type())
        assertEquals("12345", code.optString("code"))
    }

    @Test
    fun chatAndAuthorizationRequestsMatchThePinnedConstructors() {
        val getChat = TdLibRequests.getChat(42)
        assertEquals("getChat", getChat.type())
        assertEquals(42L, getChat.optLong("chat_id"))

        val create = TdLibRequests.createPrivateChat(userId = 7, force = false)
        assertEquals("createPrivateChat", create.type())
        assertEquals(7L, create.optLong("user_id"))
        assertEquals(false, create.optBoolean("force"))

        val load = TdLibRequests.loadChats(limit = 20)
        assertEquals("loadChats", load.type())
        assertTrue(load.isNull("chat_list"))
        assertEquals(20, load.optInt("limit"))

        val phone = TdLibRequests.setAuthenticationPhoneNumber("+15550001111")
        assertEquals("setAuthenticationPhoneNumber", phone.type())
        assertEquals("+15550001111", phone.optString("phone_number"))
        assertTrue(phone.isNull("settings"))

        val code = TdLibRequests.checkAuthenticationCode("12345")
        assertEquals("checkAuthenticationCode", code.type())
        assertEquals("12345", code.optString("code"))

        val password = TdLibRequests.checkAuthenticationPassword("secret")
        assertEquals("checkAuthenticationPassword", password.type())
        assertEquals("secret", password.optString("password"))

        val email = TdLibRequests.setAuthenticationEmailAddress("user@example.com")
        assertEquals("setAuthenticationEmailAddress", email.type())
        assertEquals("user@example.com", email.optString("email_address"))
    }

    private fun keys(value: JSONObject): Set<String> = value.keys().asSequence().toSet() - "@type"

    private fun JSONObject.type(): String = optString("@type")
    private fun JSONObject.safeObject(name: String): JSONObject? = optJSONObject(name)
    private fun JSONObject.safeArray(name: String): JSONArray? = optJSONArray(name)
}
