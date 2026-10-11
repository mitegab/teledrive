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

internal fun JSONObject.requireSuccessful(): JSONObject {
    if (type() == "error") {
        throw GatewayErrorMapper.fromTdlib(optInt("code"), optString("message"))
    }
    return this
}

internal fun JSONObject.errorOrNull(): StorageGatewayException? = if (type() == "error") {
    GatewayErrorMapper.fromTdlib(optInt("code"), optString("message"))
} else null

internal fun JSONObject.fileSize(): Long? = optLong("size", 0L).takeIf { it > 0L }

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
        "messageDocument" -> safeObject("document")?.safeObject("file")
        "messagePhoto" -> safeObject("photo")?.safeArray("sizes")?.let { sizes ->
            (sizes.length() - 1 downTo 0).asSequence()
                .mapNotNull { sizes.optJSONObject(it)?.safeObject("photo") }
                .firstOrNull()
        }
        "messageVideo" -> safeObject("video")?.safeObject("file")
        "messageAudio" -> safeObject("audio")?.safeObject("file")
        "messageAnimation" -> safeObject("animation")?.safeObject("file")
        "messageVoiceNote" -> safeObject("voice_note")?.safeObject("file")
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
        else -> null
    }
    val explicit = content?.optString("file_name")?.takeIf { it.isNotBlank() }
    return FilenameRules.displayName(explicit ?: fallback, content?.optString("mime_type"))
}

internal fun JSONObject.extractMimeType(): String? {
    val contentType = type()
    val content = when (contentType) {
        "messageDocument" -> safeObject("document")
        "messageVideo" -> safeObject("video")
        "messageAudio" -> safeObject("audio")
        "messageAnimation" -> safeObject("animation")
        else -> null
    }
    return content?.optString("mime_type")?.takeIf { it.isNotBlank() }
        ?: when (contentType) {
            "messagePhoto" -> "image/jpeg"
            "messageVoiceNote" -> "audio/ogg"
            else -> null
        }
}

internal fun JSONObject.extractCaption(): String? = safeObject("caption")?.optString("text")?.takeIf { it.isNotBlank() }

internal fun JSONObject.toStoredFile(savedChatId: Long): StoredFile? {
    val messageId = optLong("id", 0L).takeIf { it > 0L } ?: return null
    val content = safeObject("content") ?: return null
    val contentType = content.type()
    if (contentType !in setOf("messageDocument", "messagePhoto", "messageVideo", "messageAudio", "messageAnimation", "messageVoiceNote")) {
        return null
    }
    val file = content.extractFile() ?: return null
    val fallback = when (contentType) {
        "messagePhoto" -> "Photo-$messageId.jpg"
        "messageVoiceNote" -> "Voice-note-$messageId.ogg"
        else -> "Telegram-file-$messageId"
    }
    val filename = content.extractFileName(fallback)
    val local = file.safeObject("local")
    val localPath = local?.optString("path")?.takeIf { it.isNotBlank() }
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
        localAvailability = file.localAvailability(),
        localPath = localPath,
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

// TDLib 1.8.68: inputMessageDocument.document is an inputDocument (not a bare InputFile);
// the InputFile, thumbnail and content-type detection flag live inside inputDocument.
internal fun inputMessageDocument(path: String): JSONObject = JSONObject()
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
