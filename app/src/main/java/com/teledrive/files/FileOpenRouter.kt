package com.teledrive.files

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.teledrive.core.model.StoredFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject

/** Opens or shares only validated app-managed bytes through a revocable content URI. */
class FileOpenRouter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localStorage: LocalFileStorage,
) {
    fun open(file: StoredFile, share: Boolean) {
        val path = file.localPath?.takeIf { localStorage.isAvailable(it, file.sizeBytes) }
            ?: throw IOException("The local cached file is no longer available")
        // A path outside the roots declared in res/xml/file_paths.xml (a stale row from an older
        // build, for instance) makes getUriForFile throw IllegalArgumentException on the caller's
        // thread; report it as an ordinary "cannot open" failure instead.
        val uri = try {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
        } catch (error: IllegalArgumentException) {
            throw IOException("This file cannot be shared from its current location. Export it instead.", error)
        }
        val mimeType = resolveMimeType(file)
        if (!share && mimeType == null) {
            throw IOException("No app can open this file type. Export it to choose another location.")
        }
        val intent = if (share) {
            ViewerIntents.share(uri, mimeType ?: "application/octet-stream", file.originalFilename)
        } else {
            // The null case is rejected above; keep the compiler-visible non-null value.
            ViewerIntents.view(uri, mimeType ?: "application/octet-stream", file.originalFilename)
        }
        try {
            context.startActivity(
                Intent.createChooser(
                    intent,
                    if (share) "Share ${file.originalFilename}" else "Open ${file.originalFilename}",
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
            )
        } catch (error: android.content.ActivityNotFoundException) {
            throw IOException("No installed Android app can open ${file.originalFilename} as ${mimeType ?: "a file"}", error)
        }
    }

    companion object {
        private val GENERIC_MIME_TYPES = setOf("application/octet-stream", "binary/octet-stream")

        fun resolveMimeType(file: StoredFile): String? {
            file.mimeType?.trim()?.takeIf { it.isNotEmpty() && it !in GENERIC_MIME_TYPES }?.let { return it }
            val extension = file.originalFilename.substringAfterLast('.', "").lowercase()
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
                ?: when (extension) {
                    "pdf" -> "application/pdf"
                    "zip" -> "application/zip"
                    "mp3" -> "audio/mpeg"
                    "m4a" -> "audio/mp4"
                    "ogg", "oga", "opus" -> "audio/ogg"
                    "mp4", "m4v" -> "video/mp4"
                    "webm" -> "video/webm"
                    "jpg", "jpeg" -> "image/jpeg"
                    "png" -> "image/png"
                    "webp" -> "image/webp"
                    "gif" -> "image/gif"
                    else -> null
                }
        }
    }
}
