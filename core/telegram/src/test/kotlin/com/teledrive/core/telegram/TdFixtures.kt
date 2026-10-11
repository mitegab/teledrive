package com.teledrive.core.telegram

import org.json.JSONArray
import org.json.JSONObject

/**
 * TDLib-shaped JSON for tests. Field names and nesting come from the pinned schema
 * (tools/.tdlib-source/td/generate/scheme/td_api.tl, TDLib 1.8.68), not from guesses:
 *
 *   document  ... document:file          messageDocument  document:document caption:formattedText
 *   video     ... video:file             messageVideo     video:video ...
 *   audio     ... audio:file             messageAudio     audio:audio caption:formattedText
 *   animation ... animation:file         messageAnimation animation:animation ...
 *   voiceNote ... voice:file             messageVoiceNote voice_note:voiceNote ...
 *   videoNote ... video:file             messageVideoNote video_note:videoNote ...
 *   sticker   ... sticker:file           messageSticker   sticker:sticker ...
 *   photoSize ... photo:file             messagePhoto     photo:photo caption:formattedText ...
 */
internal object TdFixtures {
    const val CHAT_ID = 4242L

    fun file(
        id: Int,
        size: Long = 1024,
        remoteId: String = "remote-$id",
        uploadingCompleted: Boolean = true,
        uploadingActive: Boolean = false,
        localPath: String = "",
        downloadedCompleted: Boolean = false,
        downloadedSize: Long = 0,
    ): JSONObject = JSONObject()
        .put("@type", "file")
        .put("id", id)
        .put("size", size)
        .put("expected_size", size)
        .put(
            "local",
            JSONObject().put("@type", "localFile").put("path", localPath)
                .put("can_be_downloaded", true).put("is_downloading_active", false)
                .put("is_downloading_completed", downloadedCompleted).put("downloaded_size", downloadedSize),
        )
        .put(
            "remote",
            JSONObject().put("@type", "remoteFile").put("id", remoteId).put("unique_id", "u$id")
                .put("is_uploading_active", uploadingActive).put("is_uploading_completed", uploadingCompleted)
                .put("uploaded_size", if (uploadingCompleted) size else 0),
        )

    fun caption(text: String): JSONObject =
        JSONObject().put("@type", "formattedText").put("text", text).put("entities", JSONArray())

    fun message(
        id: Long,
        content: JSONObject,
        chatId: Long = CHAT_ID,
        date: Long = 1_700_000_000L + id,
        sendingState: JSONObject? = null,
        outgoing: Boolean = true,
    ): JSONObject = JSONObject()
        .put("@type", "message")
        .put("id", id)
        .put("chat_id", chatId)
        .put("date", date)
        .put("is_outgoing", outgoing)
        .apply { if (sendingState != null) put("sending_state", sendingState) }
        .put("content", content)

    fun pending(sendingId: Int): JSONObject =
        JSONObject().put("@type", "messageSendingStatePending").put("sending_id", sendingId)

    fun failedState(): JSONObject =
        JSONObject().put("@type", "messageSendingStateFailed").put("can_retry", true)

    fun document(
        file: JSONObject,
        fileName: String? = "report.pdf",
        mimeType: String? = "application/pdf",
        caption: String = "",
    ): JSONObject = JSONObject()
        .put("@type", "messageDocument")
        .put(
            "document",
            JSONObject().put("@type", "document").apply {
                if (fileName != null) put("file_name", fileName)
                if (mimeType != null) put("mime_type", mimeType)
            }.put("document", file),
        )
        .put("caption", caption(caption))

    fun video(file: JSONObject, fileName: String = "clip.mp4", mimeType: String = "video/mp4"): JSONObject = JSONObject()
        .put("@type", "messageVideo")
        .put(
            "video",
            JSONObject().put("@type", "video").put("duration", 12).put("width", 640).put("height", 360)
                .put("file_name", fileName).put("mime_type", mimeType).put("video", file),
        )
        .put("caption", caption(""))

    fun audio(file: JSONObject, fileName: String = "song.mp3", mimeType: String = "audio/mpeg"): JSONObject = JSONObject()
        .put("@type", "messageAudio")
        .put(
            "audio",
            JSONObject().put("@type", "audio").put("duration", 200).put("title", "Song").put("performer", "Artist")
                .put("file_name", fileName).put("mime_type", mimeType).put("audio", file),
        )
        .put("caption", caption(""))

    fun voiceNote(file: JSONObject, mimeType: String = "audio/ogg"): JSONObject = JSONObject()
        .put("@type", "messageVoiceNote")
        .put("voice_note", JSONObject().put("@type", "voiceNote").put("duration", 5).put("mime_type", mimeType).put("voice", file))
        .put("caption", caption(""))

    fun animation(file: JSONObject, fileName: String = "loop.mp4", mimeType: String = "video/mp4"): JSONObject = JSONObject()
        .put("@type", "messageAnimation")
        .put(
            "animation",
            JSONObject().put("@type", "animation").put("duration", 3).put("width", 320).put("height", 240)
                .put("file_name", fileName).put("mime_type", mimeType).put("animation", file),
        )
        .put("caption", caption(""))

    fun videoNote(file: JSONObject): JSONObject = JSONObject()
        .put("@type", "messageVideoNote")
        .put("video_note", JSONObject().put("@type", "videoNote").put("duration", 7).put("length", 240).put("video", file))

    fun sticker(file: JSONObject, format: String = "stickerFormatWebp"): JSONObject = JSONObject()
        .put("@type", "messageSticker")
        .put(
            "sticker",
            JSONObject().put("@type", "sticker").put("emoji", "😀").put("width", 512).put("height", 512)
                .put("format", JSONObject().put("@type", format)).put("sticker", file),
        )

    fun photo(vararg sizes: Triple<Int, Int, JSONObject>, caption: String = ""): JSONObject = JSONObject()
        .put("@type", "messagePhoto")
        .put(
            "photo",
            JSONObject().put("@type", "photo").put(
                "sizes",
                JSONArray().apply {
                    sizes.forEach { (w, h, f) ->
                        put(JSONObject().put("@type", "photoSize").put("type", "x").put("width", w).put("height", h).put("photo", f))
                    }
                },
            ),
        )
        .put("caption", caption(caption))

    fun text(text: String): JSONObject =
        JSONObject().put("@type", "messageText").put("text", caption(text))

    fun poll(): JSONObject = JSONObject().put("@type", "messagePoll")

    fun updateNewMessage(message: JSONObject): JSONObject =
        JSONObject().put("@type", "updateNewMessage").put("message", message)

    fun updateSendSucceeded(message: JSONObject, oldMessageId: Long): JSONObject =
        JSONObject().put("@type", "updateMessageSendSucceeded").put("message", message).put("old_message_id", oldMessageId)

    fun updateSendFailed(message: JSONObject, oldMessageId: Long, code: Int, text: String): JSONObject =
        JSONObject().put("@type", "updateMessageSendFailed").put("message", message).put("old_message_id", oldMessageId)
            .put("error", JSONObject().put("@type", "error").put("code", code).put("message", text))

    fun updateMessageContent(chatId: Long, messageId: Long, content: JSONObject): JSONObject =
        JSONObject().put("@type", "updateMessageContent").put("chat_id", chatId).put("message_id", messageId).put("new_content", content)

    fun updateDeleteMessages(chatId: Long, vararg ids: Long, permanent: Boolean = true, fromCache: Boolean = false): JSONObject =
        JSONObject().put("@type", "updateDeleteMessages").put("chat_id", chatId)
            .put("message_ids", JSONArray().apply { ids.forEach { put(it) } })
            .put("is_permanent", permanent).put("from_cache", fromCache)
}
