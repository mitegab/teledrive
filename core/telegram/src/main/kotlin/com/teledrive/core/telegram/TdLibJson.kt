package com.teledrive.core.telegram

import android.os.Build
import com.teledrive.core.domain.GatewayErrorMapper
import com.teledrive.core.domain.StorageGatewayException
import com.teledrive.core.model.FileKind
import com.teledrive.core.model.FilenameRules
import com.teledrive.core.model.LocalAvailability
import com.teledrive.core.model.RemoteAvailability
import com.teledrive.core.model.RemoteMessageId
import com.teledrive.core.model.StoredFile
import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject.type(): String = optString("@type")

internal fun JSONObject.safeObject(name: String): JSONObject? = optJSONObject(name)

internal fun JSONObject.safeArray(name: String): JSONArray? = optJSONArray(name)

private fun JSONArray.asObjects(): Sequence<JSONObject> =
    (0 until length()).asSequence().mapNotNull { index -> optJSONObject(index) }

internal fun JSONObject.requireSuccessful(): JSONObject {
    if (type() == "error") {
        throw GatewayErrorMapper.fromTdlib(optInt("code"), optString("message"))
    }
    return this
}

internal fun JSONObject.errorOrNull(): StorageGatewayException? = if (type() == "error") {
    GatewayErrorMapper.fromTdlib(optInt("code"), optString("message"))
} else null

internal fun JSONObject.fileSize(): Long? = optLong("size", -1L).takeIf { it >= 0L }

internal fun JSONObject.fileId(): Int? = optInt("id", 0).takeIf { it > 0 }

internal fun JSONObject.localAvailability(): LocalAvailability {
    val local = safeObject("local") ?: return LocalAvailability.NOT_DOWNLOADED
    return when {
        local.optBoolean("is_downloading_completed", false) -> LocalAvailability.AVAILABLE
        local.optBoolean("is_downloading_active", false) -> LocalAvailability.DOWNLOADING
        local.optString("path").isNotBlank() && local.optLong("downloaded_size", 0L) > 0L -> LocalAvailability.PARTIAL
        else -> LocalAvailability.NOT_DOWNLOADED
    }
}

internal fun JSONObject.remoteAvailability(): RemoteAvailability {
    val remote = safeObject("remote") ?: return RemoteAvailability.UNAVAILABLE
    return when {
        remote.optBoolean("is_uploading_active", false) -> RemoteAvailability.UNKNOWN
        remote.optBoolean("is_uploading_completed", false) -> RemoteAvailability.AVAILABLE
        remote.optLong("id", 0L) > 0L -> RemoteAvailability.AVAILABLE
        else -> RemoteAvailability.UNKNOWN
    }
}

internal fun JSONObject.extractFile(): JSONObject? {
    val contentType = type()
    return when (contentType) {
        "messageDocument" -> safeObject("document")?.safeObject("document")
        "messagePhoto" -> safeObject("photo")?.safeArray("sizes")?.let { sizes ->
            sizes.asObjects()
                .maxByOrNull { size ->
                    val width = size.optLong("width", 0L).coerceAtLeast(0L)
                    val height = size.optLong("height", 0L).coerceAtLeast(0L)
                    val pixels = width * height
                    if (pixels > 0L) pixels else size.safeObject("photo")?.fileSize() ?: -1L
                }
                ?.safeObject("photo")
        }
        "messageVideo" -> safeObject("video")?.safeObject("video")
        "messageAudio" -> safeObject("audio")?.safeObject("audio")
        "messageAnimation" -> safeObject("animation")?.safeObject("animation")
        "messageVoiceNote" -> safeObject("voice_note")?.safeObject("voice")
        "messageVideoNote" -> safeObject("video_note")?.safeObject("video")
        "messageSticker" -> safeObject("sticker")?.safeObject("sticker")
        else -> null
    }
}

internal fun JSONObject.extractFileName(fallback: String): String {
    val contentType = type()
    val content = when (contentType) {
        "messageDocument" -> safeObject("document")
        "messageVideo" -> safeObject("video")
        "messageAudio" -> safeObject("audio")
        "messageAnimation" -> safeObject("animation")
        "messageVoiceNote" -> safeObject("voice_note")
        "messageVideoNote" -> safeObject("video_note")
        "messageSticker" -> safeObject("sticker")
        else -> null
    }
    val explicit = content?.optString("file_name")?.takeIf { it.isNotBlank() }
    val mimeType = content?.optString("mime_type")?.takeIf { it.isNotBlank() }
    val extension = mimeType.defaultExtension()
    val fallbackWithExtension = if (explicit == null && extension.isNotEmpty() && !fallback.endsWith(extension, ignoreCase = true)) fallback + extension else fallback
    return FilenameRules.displayName(explicit ?: fallbackWithExtension, mimeType)
}

private fun String?.defaultExtension(): String = when (this?.lowercase()) {
    "application/pdf" -> ".pdf"
    "application/zip" -> ".zip"
    "application/x-rar-compressed" -> ".rar"
    "application/x-7z-compressed" -> ".7z"
    "application/gzip" -> ".gz"
    "text/plain" -> ".txt"
    "image/jpeg" -> ".jpg"
    "image/png" -> ".png"
    "image/webp" -> ".webp"
    "audio/mpeg" -> ".mp3"
    "audio/ogg" -> ".ogg"
    "video/mp4" -> ".mp4"
    else -> ""
}

internal fun JSONObject.extractMimeType(): String? {
    val contentType = type()
    val content = when (contentType) {
        "messageDocument" -> safeObject("document")
        "messageVideo" -> safeObject("video")
        "messageAudio" -> safeObject("audio")
        "messageAnimation" -> safeObject("animation")
        "messageVoiceNote" -> safeObject("voice_note")
        "messageVideoNote" -> safeObject("video_note")
        "messageSticker" -> safeObject("sticker")
        else -> null
    }
    return content?.optString("mime_type")?.takeIf { it.isNotBlank() }
        ?: when (contentType) {
            "messagePhoto" -> "image/jpeg"
            "messageVoiceNote" -> "audio/ogg"
            "messageVideoNote" -> "video/mp4"
            "messageSticker" -> "image/webp"
            else -> null
        }
}

internal fun JSONObject.extractCaption(): String? = safeObject("caption")?.optString("text")?.takeIf { it.isNotBlank() }

internal fun JSONObject.permanentDeletedMessages(): List<RemoteMessageId> {
    if (!optBoolean("is_permanent", false)) return emptyList()
    val chatId = optLong("chat_id", 0L).takeIf { it > 0L } ?: return emptyList()
    val messageIds = safeArray("message_ids") ?: return emptyList()
    return buildList {
        for (index in 0 until messageIds.length()) {
            messageIds.optLong(index, 0L).takeIf { it > 0L }?.let { add(RemoteMessageId(chatId, it)) }
        }
    }
}

internal fun JSONObject.toStoredFile(savedChatId: Long): StoredFile? {
    val messageId = optLong("id", 0L).takeIf { it > 0L } ?: return null
    val content = safeObject("content") ?: return null
    val contentType = content.type()
    if (contentType !in setOf("messageDocument", "messagePhoto", "messageVideo", "messageAudio", "messageAnimation", "messageVoiceNote", "messageVideoNote", "messageSticker")) {
        return null
    }
    val file = content.extractFile() ?: return null
    val fallback = when (contentType) {
        "messagePhoto" -> "Photo-$messageId.jpg"
        "messageVideo" -> "Video-$messageId.mp4"
        "messageAudio" -> "Audio-$messageId.mp3"
        "messageAnimation" -> "Animation-$messageId.gif"
        "messageVoiceNote" -> "Voice-note-$messageId.ogg"
        "messageVideoNote" -> "Video-note-$messageId.mp4"
        "messageSticker" -> "Sticker-$messageId.webp"
        else -> "Telegram-file-$messageId"
    }
    val filename = content.extractFileName(fallback)
    // TDLib's local cache is intentionally not exposed as TeleDrive's durable offline copy.
    // DownloadCoordinator copies completed bytes into app-managed storage before setting AVAILABLE.
    val remoteId = RemoteMessageId(optLong("chat_id", savedChatId), messageId)
    return StoredFile(
        remote = remoteId,
        tdlibFileId = file.fileId(),
        originalFilename = filename,
        mimeType = content.extractMimeType(),
        sizeBytes = file.fileSize(),
        savedAtEpochSeconds = optLong("date", 0L),
        caption = content.extractCaption(),
        remoteAvailability = file.remoteAvailability(),
        localAvailability = LocalAvailability.NOT_DOWNLOADED,
        localPath = null,
    )
}

internal fun JSONObject.uploadedBytes(): Long {
    val remote = safeObject("remote")
    val local = safeObject("local")
    return maxOf(
        remote?.optLong("uploaded_size", 0L) ?: 0L,
        local?.optLong("uploaded_size", 0L) ?: 0L,
        local?.optLong("downloaded_size", 0L) ?: 0L,
    )
}

internal fun JSONObject.downloadedBytes(): Long = safeObject("local")?.optLong("downloaded_size", 0L) ?: 0L

internal fun JSONObject.completedDownload(): Boolean = safeObject("local")?.optBoolean("is_downloading_completed", false) == true

internal fun JSONObject.completedUpload(): Boolean = safeObject("remote")?.optBoolean("is_uploading_completed", false) == true

internal fun inputMessageDocument(path: String): JSONObject = JSONObject()
    .put("@type", "inputMessageDocument")
    .put("document", JSONObject().put("@type", "inputFileLocal").put("path", path))
    .put("thumbnail", JSONObject.NULL)
    .put("disable_content_type_detection", false)
    .put("caption", JSONObject.NULL)

internal fun inputMessageText(text: String): JSONObject = JSONObject()
    .put("@type", "inputMessageText")
    .put("text", JSONObject().put("@type", "formattedText").put("text", text).put("entities", JSONArray()))
    .put("link_preview_options", JSONObject.NULL)
    .put("clear_draft", true)

internal fun buildTdlibParameters(
    credentials: TelegramCredentials,
    databaseDirectory: String,
    filesDirectory: String,
    databaseKeyBase64: String,
    applicationVersion: String,
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
    .put("system_language_code", java.util.Locale.getDefault().toLanguageTag())
    .put("device_model", "${Build.MANUFACTURER} ${Build.MODEL}".trim())
    .put("system_version", Build.VERSION.RELEASE.orEmpty())
    .put("application_version", applicationVersion)
