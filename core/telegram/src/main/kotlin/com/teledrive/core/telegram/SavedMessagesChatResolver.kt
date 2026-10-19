package com.teledrive.core.telegram

import com.teledrive.core.domain.StorageGatewayException
import com.teledrive.core.model.SavedMessagesChat
import com.teledrive.core.model.TelegramUser
import org.json.JSONObject

/**
 * Resolves and caches the account's Saved Messages chat.
 *
 * TDLib's `getChat` only answers for chats this client already knows. Right after
 * login the Saved Messages chat is often not loaded yet, so `getChat(me.id)`
 * answers `error 400 "Chat not found"`. `createPrivateChat` (td_api.tl) loads or
 * creates the chat for a user id and returns it, so resolution is:
 *
 *  1. `getChat(me.id)` when the chat is already known (fast, offline);
 *  2. `createPrivateChat(me.id, force = false)` to load or create it;
 *  3. `loadChats` for a cold client, then `getChat` again.
 *
 * The result is cached until [invalidate]; operations that fail with
 * `CHAT_NOT_FOUND` after the cached chat goes stale self-heal once through
 * [withChat].
 */
internal class SavedMessagesChatResolver(
    private val send: suspend (JSONObject) -> JSONObject,
    private val currentUser: suspend () -> TelegramUser,
) {
    @Volatile
    private var cached: SavedMessagesChat? = null

    fun cached(): SavedMessagesChat? = cached

    fun invalidate() {
        cached = null
    }

    suspend fun resolve(): SavedMessagesChat {
        cached?.let { return it }
        val user = currentUser()
        var lastError: Throwable? = null
        try {
            return adopt(send(TdLibRequests.getChat(user.id)), user)
        } catch (error: Throwable) {
            lastError = error
        }
        try {
            return adopt(send(TdLibRequests.createPrivateChat(user.id, force = false)), user)
        } catch (error: Throwable) {
            lastError = error
        }
        // Cold clients may need the main chat list loaded before any chat is known.
        try {
            send(TdLibRequests.loadChats(limit = LOAD_CHATS_LIMIT))
            return adopt(send(TdLibRequests.getChat(user.id)), user)
        } catch (error: Throwable) {
            lastError = error
        }
        throw StorageGatewayException(
            code = SAVED_MESSAGES_NOT_FOUND,
            message = "The authenticated Telegram account did not expose its Saved Messages chat",
            retryable = false,
            cause = lastError,
        )
    }

    /**
     * Runs [block] against the resolved chat. A `CHAT_NOT_FOUND` failure (stale
     * cached chat after logout on another device, chat database reset, ...) is
     * healed once: cache invalidated, chat re-resolved, block retried once.
     */
    suspend fun <T> withChat(block: suspend (SavedMessagesChat) -> T): T {
        var attempt = 0
        while (true) {
            val chat = resolve()
            try {
                return block(chat)
            } catch (error: StorageGatewayException) {
                if (error.code == CHAT_NOT_FOUND && attempt == 0) {
                    attempt++
                    invalidate()
                    continue
                }
                throw error
            }
        }
    }

    private fun adopt(chat: JSONObject, user: TelegramUser): SavedMessagesChat {
        val chatType = chat.safeObject("type")
        val isCurrentUserChat = chatType?.type() == "chatTypePrivate" &&
            chatType.optLong("user_id", 0L) == user.id
        val chatId = chat.optLong("id", 0L)
        if (!isCurrentUserChat || chatId <= 0L) {
            throw StorageGatewayException(
                code = SAVED_MESSAGES_NOT_FOUND,
                message = "The authenticated Telegram account did not expose its Saved Messages chat",
                retryable = false,
            )
        }
        return SavedMessagesChat(
            chatId = chatId,
            title = chat.optString("title").ifBlank { "Saved Messages" },
        ).also { cached = it }
    }

    private companion object {
        const val LOAD_CHATS_LIMIT = 20
        const val CHAT_NOT_FOUND = "CHAT_NOT_FOUND"
        const val SAVED_MESSAGES_NOT_FOUND = "SAVED_MESSAGES_NOT_FOUND"
    }
}
