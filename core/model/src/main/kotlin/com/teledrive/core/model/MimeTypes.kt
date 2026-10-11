package com.teledrive.core.model

/**
 * Platform-independent MIME helper. A file extension is only a *hint*: it is used when the declared type is
 * missing or generic, and never as proof that the bytes are valid.
 */
object MimeTypes {
    const val OCTET_STREAM = "application/octet-stream"

    private val byExtension: Map<String, String> = mapOf(
        "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "webp" to "image/webp",
        "gif" to "image/gif", "bmp" to "image/bmp", "heic" to "image/heic", "heif" to "image/heif",
        "svg" to "image/svg+xml",
        "pdf" to "application/pdf",
        "zip" to "application/zip", "rar" to "application/x-rar-compressed", "7z" to "application/x-7z-compressed",
        "gz" to "application/gzip", "tgz" to "application/gzip", "tar" to "application/x-tar",
        "bz2" to "application/x-bzip2", "xz" to "application/x-xz",
        "txt" to "text/plain", "md" to "text/markdown", "csv" to "text/csv", "html" to "text/html", "htm" to "text/html",
        "json" to "application/json", "xml" to "application/xml", "yaml" to "text/yaml", "yml" to "text/yaml",
        "log" to "text/plain",
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "aac" to "audio/aac", "ogg" to "audio/ogg", "oga" to "audio/ogg",
        "opus" to "audio/opus", "wav" to "audio/wav", "flac" to "audio/flac", "amr" to "audio/amr",
        "mp4" to "video/mp4", "m4v" to "video/mp4", "webm" to "video/webm", "mkv" to "video/x-matroska",
        "mov" to "video/quicktime", "3gp" to "video/3gpp", "avi" to "video/x-msvideo",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls" to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "apk" to "application/vnd.android.package-archive",
    )

    /** Preferred extension for a MIME type (the first mapping wins, so the table order above is deliberate). */
    private val byMime: Map<String, String> = buildMap {
        byExtension.forEach { (extension, mime) -> putIfAbsent(mime, extension) }
        put("image/jpeg", "jpg")
        put("audio/mpeg", "mp3")
        put("audio/ogg", "ogg")
        put("application/gzip", "gz")
        put("text/plain", "txt")
    }

    fun normalize(mime: String?): String? = mime
        ?.substringBefore(';')
        ?.trim()
        ?.lowercase()
        ?.takeIf { it.isNotEmpty() }

    fun isGeneric(mime: String?): Boolean {
        val normalized = normalize(mime) ?: return true
        return normalized == OCTET_STREAM || normalized == "*/*" || normalized == "binary/octet-stream"
    }

    fun fromFilename(filename: String?): String? {
        val extension = filename.orEmpty().substringAfterLast('.', "").trim().lowercase()
        return byExtension[extension]
    }

    fun extensionFor(mime: String?): String? = byMime[normalize(mime)]

    /** The best available type: a specific declared type, else the extension's type, else [OCTET_STREAM]. */
    fun resolve(declared: String?, filename: String?): String {
        val normalized = normalize(declared)
        if (normalized != null && !isGeneric(normalized)) return normalized
        return fromFilename(filename) ?: OCTET_STREAM
    }
}
