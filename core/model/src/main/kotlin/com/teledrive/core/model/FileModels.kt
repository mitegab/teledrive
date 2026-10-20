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

enum class RemoteAvailability {
    UNKNOWN,
    AVAILABLE,
    UNAVAILABLE,
    DELETED,
}

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
) {
    val stableId: String get() = remote.value
    val kind: FileKind get() = FileKindDetector.detect(originalFilename, mimeType)
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
    private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "tiff", "tif", "svg", "ico")
    private val videoExtensions = setOf("mp4", "m4v", "mkv", "webm", "mov", "3gp", "avi", "flv", "wmv", "mts", "m2ts", "ts", "hevc", "mpg", "mpeg", "3g2", "f4v")
    private val audioExtensions = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "oga", "opus", "wma", "aiff", "aif", "amr", "3ga", "mka", "mid", "midi", "weba")

    fun detect(filename: String?, mimeType: String?): FileKind {
        val mime = mimeType.orEmpty().trim().lowercase()
        val extension = filename.orEmpty().substringAfterLast('.', "").lowercase()
        val genericMime = mime.isBlank() || mime in setOf("application/octet-stream", "binary/octet-stream", "*/*")
        return when {
            mime.startsWith("image/") -> FileKind.IMAGE
            mime.startsWith("video/") -> FileKind.VIDEO
            mime.startsWith("audio/") -> FileKind.AUDIO
            // Some Telegram audio is served as application/ogg
            mime in setOf("application/ogg", "application/x-ogg") -> FileKind.AUDIO
            mime.startsWith("text/") -> FileKind.TEXT
            mime == "application/pdf" -> FileKind.DOCUMENT
            mime in setOf(
                "application/zip",
                "application/x-rar-compressed",
                "application/x-7z-compressed",
                "application/gzip",
                "application/x-tar",
            ) -> FileKind.ARCHIVE
            !genericMime && mime in setOf("audio/mpeg", "audio/mp4", "audio/ogg", "audio/opus", "audio/flac", "audio/wav", "audio/x-wav", "audio/aac", "audio/webm") -> FileKind.AUDIO
            !genericMime && mime in setOf("video/mp4", "video/webm", "video/x-matroska", "video/quicktime", "video/avi", "video/x-msvideo", "video/3gpp") -> FileKind.VIDEO
            extension in imageExtensions -> FileKind.IMAGE
            extension in videoExtensions -> FileKind.VIDEO
            extension in audioExtensions -> FileKind.AUDIO
            extension in archiveExtensions -> FileKind.ARCHIVE
            extension in textExtensions -> FileKind.TEXT
            else -> FileKind.DOCUMENT
        }
    }

    fun isMediaKind(kind: FileKind): Boolean = kind == FileKind.VIDEO || kind == FileKind.AUDIO
}

object MediaSupport {
    private val supportedAudioMimes = setOf(
        "audio/mpeg", "audio/mp3", "audio/mp4", "audio/m4a", "audio/aac",
        "audio/ogg", "audio/opus", "audio/vorbis", "audio/flac", "audio/wav",
        "audio/x-wav", "audio/webm", "audio/aacp", "application/ogg"
    )
    private val supportedVideoMimes = setOf(
        "video/mp4", "video/webm", "video/x-matroska", "video/quicktime",
        "video/avi", "video/x-msvideo", "video/3gpp", "video/3gpp2",
        "video/mpeg", "video/mp2t"
    )

    fun isAudioMime(mime: String?): Boolean = mime?.lowercase()?.let { it in supportedAudioMimes || it.startsWith("audio/") } == true
    fun isVideoMime(mime: String?): Boolean = mime?.lowercase()?.let { it in supportedVideoMimes || it.startsWith("video/") } == true
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
        return truncateKeepingExtension(cleaned).ifEmpty { fallback }
    }

    /** Long names are shortened but keep their extension so file types stay detectable. */
    private fun truncateKeepingExtension(name: String): String {
        if (name.length <= maxDisplayLength) return name
        val extension = name.substringAfterLast('.', "")
        val suffix = if (extension.isEmpty() || extension == name) "" else ".$extension"
        val stem = name.removeSuffix(suffix).take((maxDisplayLength - suffix.length).coerceAtLeast(0))
        return stem + suffix
    }

    fun extension(filename: String): String = filename
        .substringAfterLast('.', "")
        .trim()
        .lowercase()

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
