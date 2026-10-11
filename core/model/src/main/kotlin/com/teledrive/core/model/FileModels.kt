package com.teledrive.core.model

/** Stable identity of a Telegram message in a chat. Never expose this as a user-editable id. */
data class RemoteMessageId(
    val chatId: Long,
    val messageId: Long,
) {
    val value: String get() = "$chatId:$messageId"
}

enum class FileKind {
    DOCUMENT,
    IMAGE,
    VIDEO,
    AUDIO,
    ARCHIVE,
    TEXT,
    UNKNOWN,
}

/**
 * How Telegram represents the content. Distinct from [FileKind] (a user-facing category derived from name/MIME):
 * a voice note and a music file are both AUDIO kind but need different handling.
 */
enum class MediaType {
    PHOTO,
    DOCUMENT,
    VIDEO,
    AUDIO,
    VOICE_NOTE,
    VIDEO_NOTE,
    ANIMATION,
    STICKER,
    UNKNOWN,
}

enum class RemoteAvailability {
    UNKNOWN,
    AVAILABLE,
    UNAVAILABLE,
    DELETED,
}

/**
 * State of TeleDrive's own managed copy of the bytes. It is independent of [RemoteAvailability]: a file can be
 * known remotely and absent locally. TDLib's private cache path is never recorded as a local copy.
 */
enum class LocalAvailability {
    NOT_DOWNLOADED,
    DOWNLOADING,
    AVAILABLE,
    PARTIAL,
    FAILED,
}

data class StoredFile(
    val remote: RemoteMessageId,
    val tdlibFileId: Int?,
    val originalFilename: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val savedAtEpochSeconds: Long,
    val caption: String? = null,
    val folderId: String? = null,
    val favorite: Boolean = false,
    val remoteAvailability: RemoteAvailability = RemoteAvailability.UNKNOWN,
    val localAvailability: LocalAvailability = LocalAvailability.NOT_DOWNLOADED,
    val localPath: String? = null,
    val mediaType: MediaType = MediaType.UNKNOWN,
    /** TDLib `remoteFile.unique_id`: stays the same when TDLib re-issues a file id, so it can re-identify the bytes. */
    val remoteUniqueId: String? = null,
    val thumbnailFileId: Int? = null,
    val durationSeconds: Int? = null,
    /** Size of the managed local copy when it was last verified; used to detect truncated or replaced files. */
    val localSizeBytes: Long? = null,
) {
    val stableId: String get() = remote.value
    val kind: FileKind get() = FileKindDetector.detect(originalFilename, mimeType)
    val displayName: String get() = originalFilename
    val isRemoteDeleted: Boolean get() = remoteAvailability == RemoteAvailability.DELETED
}

data class Folder(
    val id: String,
    val name: String,
    val parentId: String? = null,
    val createdAtEpochSeconds: Long,
    val modifiedAtEpochSeconds: Long,
)

enum class SortField {
    NAME,
    SIZE,
    DATE,
    TYPE,
}

enum class SortDirection {
    ASCENDING,
    DESCENDING,
}

data class FileSort(
    val field: SortField = SortField.DATE,
    val direction: SortDirection = SortDirection.DESCENDING,
)

data class FileQuery(
    val text: String = "",
    val kinds: Set<FileKind> = emptySet(),
    val folderId: String? = null,
    val favoritesOnly: Boolean = false,
    val minSizeBytes: Long? = null,
    val maxSizeBytes: Long? = null,
    val minSavedAtEpochSeconds: Long? = null,
    val maxSavedAtEpochSeconds: Long? = null,
)

object FileKindDetector {
    private val archiveExtensions = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz")
    private val textExtensions = setOf("txt", "md", "csv", "json", "xml", "yaml", "yml", "log", "html", "htm")

    fun detect(filename: String?, mimeType: String?): FileKind {
        // A generic declared type (application/octet-stream) must not hide what the extension says.
        val mime = MimeTypes.resolve(mimeType, filename).takeUnless { it == MimeTypes.OCTET_STREAM }.orEmpty()
        val extension = filename.orEmpty().substringAfterLast('.', "").lowercase()
        return when {
            mime.startsWith("image/") -> FileKind.IMAGE
            mime.startsWith("video/") -> FileKind.VIDEO
            mime.startsWith("audio/") -> FileKind.AUDIO
            mime.startsWith("text/") -> FileKind.TEXT
            mime in setOf(
                "application/zip",
                "application/x-rar-compressed",
                "application/x-7z-compressed",
                "application/gzip",
                "application/x-tar",
            ) -> FileKind.ARCHIVE
            extension in archiveExtensions -> FileKind.ARCHIVE
            extension in textExtensions -> FileKind.TEXT
            else -> FileKind.DOCUMENT
        }
    }
}

object FilenameRules {
    private const val maxDisplayLength = 255
    private val unsafeSeparators = setOf('/', '\\', '\u0000')

    /** Keeps Unicode intact while preventing path traversal and control characters in display names. */
    fun sanitize(raw: String?, fallback: String = "Untitled file"): String {
        val cleaned = raw.orEmpty()
            .replace(Regex("[\\u0000-\\u001F\\u007F]"), "")
            .map { if (it in unsafeSeparators) '_' else it }
            .joinToString("")
            .trim()
            .ifEmpty { fallback }
        return cleaned.take(maxDisplayLength).ifEmpty { fallback }
    }

    fun extension(filename: String): String = filename
        .substringAfterLast('.', "")
        .trim()
        .lowercase()

    /**
     * A name that is safe to create on a Linux/Android filesystem: no separators or control characters, at most
     * [maxBytes] UTF-8 bytes (the filesystem limit is in bytes, not characters), extension preserved, never empty,
     * never "." or "..".
     */
    fun fileSystemName(raw: String?, fallback: String = "file", maxBytes: Int = 200): String {
        var name = sanitize(raw, fallback).trim('.', ' ').ifEmpty { fallback }
        if (name.toByteArray(Charsets.UTF_8).size <= maxBytes) return name
        val extension = extension(name).takeIf { it.isNotEmpty() && it.length <= 12 }?.let { ".$it" }.orEmpty()
        val stem = name.removeSuffix(extension)
        val budget = (maxBytes - extension.toByteArray(Charsets.UTF_8).size).coerceAtLeast(1)
        val truncated = StringBuilder()
        var used = 0
        var index = 0
        while (index < stem.length) {
            val codePoint = stem.codePointAt(index)
            val chars = Character.toChars(codePoint)
            val size = String(chars).toByteArray(Charsets.UTF_8).size
            if (used + size > budget) break
            truncated.append(chars)
            used += size
            index += chars.size
        }
        name = truncated.toString().trimEnd('.', ' ').ifEmpty { fallback } + extension
        return name
    }

    fun displayName(filename: String?, mimeType: String?): String = sanitize(
        filename,
        fallback = when (FileKindDetector.detect(filename, mimeType)) {
            FileKind.IMAGE -> "Image"
            FileKind.VIDEO -> "Video"
            FileKind.AUDIO -> "Audio"
            FileKind.ARCHIVE -> "Archive"
            FileKind.TEXT -> "Text file"
            else -> "Document"
        },
    )
}
