package com.teledrive.core.telegram

import org.json.JSONObject

/**
 * Builders for the TDLib 1.8.68 JSON requests TeleDrive sends. Shapes are kept
 * in one place so JVM tests can assert the exact `@type` and required keys
 * against `td_api.tl` (tools/TDLIB_VERSION).
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

    fun logOut(): JSONObject = JSONObject().put("@type", "logOut")

    fun close(): JSONObject = JSONObject().put("@type", "close")

    fun getOption(name: String): JSONObject = JSONObject().put("@type", "getOption").put("name", name)
}
