package com.teledrive.core.telegram

import com.teledrive.core.domain.StorageGatewayException
import com.teledrive.core.model.TelegramUser
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SavedMessagesChatResolverTest {

    @Test
    fun chatNotFoundMissFallsBackToCreatePrivateChatThenCaches() = runBlocking {
        val sender = ScriptedTdLib()
        sender.on("getChat") { tdError(400, "Chat not found") }
        sender.on("createPrivateChat") { savedMessagesChat() }
        val resolver = SavedMessagesChatResolver(sender::send, { me() })

        val chat = resolver.resolve()

        assertEquals(42L, chat.chatId)
        assertEquals(listOf("getChat", "createPrivateChat"), sender.requestTypes)
        val create = sender.requests[1]
        assertEquals("createPrivateChat", create.type())
        assertEquals(7L, create.optLong("user_id"))
        assertEquals(false, create.optBoolean("force"))

        // Second resolve is answered from the cache without touching TDLib.
        resolver.resolve()
        assertEquals(listOf("getChat", "createPrivateChat"), sender.requestTypes)
    }

    @Test
    fun invalidationForcesFreshResolution() = runBlocking {
        val sender = ScriptedTdLib()
        sender.on("getChat") { tdError(400, "Chat not found") }
        sender.on("createPrivateChat") { savedMessagesChat() }
        val resolver = SavedMessagesChatResolver(sender::send, { me() })
        resolver.resolve()

        resolver.invalidate()

        assertEquals(null, resolver.cached())
        resolver.resolve()
        assertEquals(listOf("getChat", "createPrivateChat", "getChat", "createPrivateChat"), sender.requestTypes)
    }

    @Test
    fun chatNotFoundDuringOperationSelfHealsExactlyOnce() = runBlocking {
        val sender = ScriptedTdLib()
        sender.on("getChat") { tdError(400, "Chat not found") }
        sender.on("createPrivateChat") { savedMessagesChat() }
        val resolver = SavedMessagesChatResolver(sender::send, { me() })
        var attempts = 0

        val result = resolver.withChat {
            attempts++
            if (attempts == 1) throw StorageGatewayException("CHAT_NOT_FOUND", "Chat not found", false)
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(2, attempts)
        // The stale cache was invalidated and re-resolved for the retry.
        assertEquals(
            listOf("getChat", "createPrivateChat", "getChat", "createPrivateChat"),
            sender.requestTypes,
        )
    }

    @Test
    fun persistentChatNotFoundIsRetriedOnlyOnce() = runBlocking {
        val sender = ScriptedTdLib()
        sender.on("getChat") { tdError(400, "Chat not found") }
        sender.on("createPrivateChat") { savedMessagesChat() }
        val resolver = SavedMessagesChatResolver(sender::send, { me() })
        var attempts = 0

        try {
            resolver.withChat {
                attempts++
                throw StorageGatewayException("CHAT_NOT_FOUND", "Chat not found", false)
            }
            fail("expected CHAT_NOT_FOUND to propagate")
        } catch (error: StorageGatewayException) {
            assertEquals("CHAT_NOT_FOUND", error.code)
        }
        assertEquals(2, attempts)
    }

    @Test
    fun coldClientLoadsTheMainChatListBeforeGivingUp() = runBlocking {
        val sender = ScriptedTdLib()
        sender.on("getChat") { tdError(400, "Chat not found") }
        sender.on("createPrivateChat") { tdError(400, "Chat not found") }
        // Once the main chat list is loaded, the Saved Messages chat becomes known.
        sender.on("loadChats") {
            sender.on("getChat") { savedMessagesChat() }
            JSONObject().put("@type", "ok")
        }
        val resolver = SavedMessagesChatResolver(sender::send, { me() })

        val chat = resolver.resolve()

        assertEquals(42L, chat.chatId)
        assertEquals(listOf("getChat", "createPrivateChat", "loadChats", "getChat"), sender.requestTypes)
        val load = sender.requests[2]
        assertEquals("loadChats", load.type())
        assertTrue(load.isNull("chat_list"))
    }

    @Test
    fun resolutionPathIsLoggedWithoutSecrets() = runBlocking {
        val sender = ScriptedTdLib()
        sender.on("getChat") { tdError(400, "Chat not found") }
        sender.on("createPrivateChat") { savedMessagesChat() }
        val resolver = SavedMessagesChatResolver(sender::send, { me() })
        val lines = mutableListOf<String>()
        com.teledrive.core.model.TeleDriveLog.sink = { lines += it }
        try {
            resolver.resolve()
        } finally {
            com.teledrive.core.model.TeleDriveLog.sink = null
        }

        assertEquals(listOf("chat.resolve path=createPrivateChat chat_id=42"), lines)
    }

    @Test
    fun rejectsAChatThatIsNotTheCurrentUserPrivateChat() = runBlocking {
        val sender = ScriptedTdLib()
        val wrongChat = JSONObject()
            .put("@type", "chat")
            .put("id", 99)
            .put("type", JSONObject().put("@type", "chatTypePrivate").put("user_id", 8))
            .put("title", "Someone else")
        sender.on("getChat") { wrongChat }
        sender.on("createPrivateChat") { wrongChat }
        sender.on("loadChats") { JSONObject().put("@type", "ok") }
        val resolver = SavedMessagesChatResolver(sender::send, { me() })

        try {
            resolver.resolve()
            fail("expected SAVED_MESSAGES_NOT_FOUND")
        } catch (error: StorageGatewayException) {
            assertEquals("SAVED_MESSAGES_NOT_FOUND", error.code)
        }
    }

    private fun me() = TelegramUser(7, "Test", "User")

    private fun savedMessagesChat(): JSONObject = JSONObject()
        .put("@type", "chat")
        .put("id", 42)
        .put("type", JSONObject().put("@type", "chatTypePrivate").put("user_id", 7))
        .put("title", "Saved Messages")

    private fun tdError(code: Int, message: String): JSONObject = JSONObject()
        .put("@type", "error")
        .put("code", code)
        .put("message", message)
}

/** Records each request and answers it from per-@type handlers, like a scripted TDLib. */
private class ScriptedTdLib {
    val requests = mutableListOf<JSONObject>()
    private val handlers = mutableMapOf<String, () -> JSONObject>()

    val requestTypes: List<String> get() = requests.map { it.type() }

    fun on(type: String, handler: () -> JSONObject) {
        handlers[type] = handler
    }

    suspend fun send(request: JSONObject): JSONObject {
        requests += request
        val response = handlers[request.type()]
            ?: error("no scripted response for ${request.type()}")
        val json = response()
        if (json.type() == "error") {
            throw StorageGatewayException("TD_ERROR", json.optString("message"), false)
        }
        return json
    }
}
