package com.teledrive.core.telegram

import com.teledrive.core.model.FilenameRules
import com.teledrive.core.model.LocalAvailability
import com.teledrive.core.model.MediaType
import com.teledrive.core.model.MimeTypes
import com.teledrive.core.model.RemoteAvailability
import com.teledrive.core.model.RemoteMessageId
import com.teledrive.core.model.StoredFile
import org.json.JSONObject

/** Result of interpreting one TDLib `message`. Keeping the cases distinct is what lets callers react correctly. */
internal sealed interface MappedMessage {
    /** A file-bearing message that was mapped to an indexable record. */
    data class Media(val file: StoredFile) : MappedMessage

    /** Text, service messages and other content that never carries a stored file. */
    data object NotMedia : MappedMessage

    /** Media TeleDrive intentionally does not index (e.g. stickers, polls, paid media). */
    data class Unsupported(val contentType: String) : MappedMessage

    /** Recognised media whose file object is missing or has no usable id: Telegram data we cannot act on. */
    data class Incomplete(val contentType: String) : MappedMessage
}

/**
 * The single place that turns TDLib message JSON into [StoredFile]s. Field names follow the pinned schema
 * (TDLib 1.8.68): the file inside `document` is `document`, inside `video` it is `video`, inside `audio` it is
 * `audio`, inside `animation` it is `animation`, inside `voiceNote` it is `voice`, inside `videoNote` it is
 * `video`, and inside a `photoSize` it is `photo`. There is no generic `file` field.
 */
internal object TdMessageMapper {
    private class Spec(
        val mediaType: MediaType,
        val containerField: String,
        val fileField: String,
        val defaultMime: String?,
        val fallbackName: (messageId: Long, extension: String?) -> String,
    )

    private val specs: Map<String, Spec> = mapOf(
        "messageDocument" to Spec(MediaType.DOCUMENT, "document", "document", null) { id, ext -> "Document-$id${ext.dot()}" },
        "messageVideo" to Spec(MediaType.VIDEO, "video", "video", "video/mp4") { id, ext -> "Video-$id${ext.dot("mp4")}" },
        "messageAudio" to Spec(MediaType.AUDIO, "audio", "audio", "audio/mpeg") { id, ext -> "Audio-$id${ext.dot("mp3")}" },
        "messageAnimation" to Spec(MediaType.ANIMATION, "animation", "animation", "video/mp4") { id, ext -> "Animation-$id${ext.dot("mp4")}" },
        "messageVoiceNote" to Spec(MediaType.VOICE_NOTE, "voice_note", "voice", "audio/ogg") { id, ext -> "Voice-note-$id${ext.dot("ogg")}" },
        "messageVideoNote" to Spec(MediaType.VIDEO_NOTE, "video_note", "video", "video/mp4") { id, ext -> "Video-note-$id${ext.dot("mp4")}" },
    )

    private val deliberatelyUnsupported = setOf(
        "messageSticker",
        "messageAnimatedEmoji",
        "messagePaidMedia",
        "messagePoll",
        "messageContact",
        "messageLocation",
        "messageVenue",
        "messageDice",
        "messageGame",
        "messageInvoice",
        "messageStory",
        "messageChecklist",
        "messageUnsupported",
    )

    /**
     * Maps a message. Messages that are still being sent (`sending_state` present) are reported as
     * [RemoteAvailability.UNKNOWN] and must not be indexed by callers: their ids change when the send succeeds.
     */
    fun map(message: JSONObject, fallbackChatId: Long): MappedMessage {
        val content = message.optJSONObject("content") ?: return MappedMessage.NotMedia
        return mapContent(
            content = content,
            messageId = message.optLong("id", 0L),
            chatId = message.optLong("chat_id", fallbackChatId).takeIf { it != 0L } ?: fallbackChatId,
            date = message.optLong("date", 0L),
            confirmed = message.optJSONObject("sending_state") == null,
        )
    }

    fun mapContent(content: JSONObject, messageId: Long, chatId: Long, date: Long, confirmed: Boolean = true): MappedMessage {
        val type = content.optString("@type")
        if (messageId <= 0L) return if (type == "messagePhoto" || type in specs) MappedMessage.Incomplete(type) else MappedMessage.NotMedia
        if (type == "messagePhoto") return mapPhoto(content, messageId, chatId, date, confirmed)
        val spec = specs[type]
        if (spec == null) {
            return if (type in deliberatelyUnsupported) MappedMessage.Unsupported(type) else MappedMessage.NotMedia
        }
        val container = content.optJSONObject(spec.containerField) ?: return MappedMessage.Incomplete(type)
        val file = container.optJSONObject(spec.fileField)?.takeIf { it.optInt("id", 0) > 0 }
            ?: return MappedMessage.Incomplete(type)

        val declaredMime = MimeTypes.normalize(container.optString("mime_type").takeIf { it.isNotBlank() })
        val explicitName = container.optString("file_name").takeIf { it.isNotBlank() }
        val mime = declaredMime?.takeUnless { MimeTypes.isGeneric(it) }
            ?: MimeTypes.fromFilename(explicitName)
            ?: spec.defaultMime
            ?: declaredMime
        val name = FilenameRules.sanitize(
            explicitName ?: audioTitleName(container, mime) ?: spec.fallbackName(messageId, MimeTypes.extensionFor(mime)),
            fallback = spec.fallbackName(messageId, MimeTypes.extensionFor(mime)),
        )
        return MappedMessage.Media(
            build(
                file = file,
                chatId = chatId,
                messageId = messageId,
                date = date,
                name = name,
                mime = mime,
                caption = content.captionText(),
                mediaType = spec.mediaType,
                thumbnailFileId = container.optJSONObject("thumbnail")?.optJSONObject("file")?.optInt("id", 0)?.takeIf { it > 0 },
                duration = container.optInt("duration", 0).takeIf { it > 0 },
                confirmed = confirmed,
            ),
        )
    }

    private fun mapPhoto(content: JSONObject, messageId: Long, chatId: Long, date: Long, confirmed: Boolean): MappedMessage {
        val sizes = content.optJSONObject("photo")?.optJSONArray("sizes") ?: return MappedMessage.Incomplete("messagePhoto")
        val candidates = (0 until sizes.length()).mapNotNull { index ->
            val size = sizes.optJSONObject(index) ?: return@mapNotNull null
            val file = size.optJSONObject("photo")?.takeIf { it.optInt("id", 0) > 0 } ?: return@mapNotNull null
            Triple(size.optInt("width", 0).toLong() * size.optInt("height", 0).toLong(), index, file)
        }
        // Largest by pixel area; ties resolve to the later (usually higher-quality) entry. Never assume array order.
        val best = candidates.maxWithOrNull(compareBy({ it.first }, { it.second })) ?: return MappedMessage.Incomplete("messagePhoto")
        val smallest = candidates.minWithOrNull(compareBy({ it.first }, { it.second }))
        return MappedMessage.Media(
            build(
                file = best.third,
                chatId = chatId,
                messageId = messageId,
                date = date,
                name = "Photo-$messageId.jpg",
                mime = "image/jpeg",
                caption = content.captionText(),
                mediaType = MediaType.PHOTO,
                thumbnailFileId = smallest?.third?.optInt("id", 0)?.takeIf { it > 0 && it != best.third.optInt("id") },
                duration = null,
                confirmed = confirmed,
            ),
        )
    }

    private fun build(
        file: JSONObject,
        chatId: Long,
        messageId: Long,
        date: Long,
        name: String,
        mime: String?,
        caption: String?,
        mediaType: MediaType,
        thumbnailFileId: Int?,
        duration: Int?,
        confirmed: Boolean,
    ): StoredFile {
        val size = file.optLong("size", 0L).takeIf { it > 0 } ?: file.optLong("expected_size", 0L).takeIf { it > 0 }
        val remote = file.optJSONObject("remote")
        return StoredFile(
            remote = RemoteMessageId(chatId, messageId),
            tdlibFileId = file.optInt("id"),
            originalFilename = name,
            mimeType = mime,
            sizeBytes = size,
            savedAtEpochSeconds = date,
            caption = caption,
            remoteAvailability = remoteAvailability(remote, confirmed),
            // TDLib's private cache path is NOT a TeleDrive local copy; managed copies are tracked separately.
            localAvailability = LocalAvailability.NOT_DOWNLOADED,
            localPath = null,
            mediaType = mediaType,
            remoteUniqueId = remote?.optString("unique_id")?.takeIf { it.isNotBlank() },
            thumbnailFileId = thumbnailFileId,
            durationSeconds = duration,
        )
    }

    private fun remoteAvailability(remote: JSONObject?, confirmed: Boolean): RemoteAvailability = when {
        !confirmed -> RemoteAvailability.UNKNOWN
        remote == null -> RemoteAvailability.UNKNOWN
        remote.optBoolean("is_uploading_completed", false) -> RemoteAvailability.AVAILABLE
        remote.optString("id").isNotBlank() -> RemoteAvailability.AVAILABLE
        else -> RemoteAvailability.UNKNOWN
    }

    private fun audioTitleName(container: JSONObject, mime: String?): String? {
        val title = container.optString("title").takeIf { it.isNotBlank() } ?: return null
        val performer = container.optString("performer").takeIf { it.isNotBlank() }
        val extension = MimeTypes.extensionFor(mime) ?: "mp3"
        return (if (performer != null) "$performer - $title" else title) + ".$extension"
    }

    private fun JSONObject.captionText(): String? =
        optJSONObject("caption")?.optString("text")?.takeIf { it.isNotBlank() }

    private fun String?.dot(default: String? = null): String = (this ?: default)?.let { ".$it" }.orEmpty()
}
