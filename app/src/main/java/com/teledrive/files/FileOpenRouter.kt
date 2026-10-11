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
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", File(path))
        val mimeType = resolveMimeType(file)
        val intent = if (share) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri)
        } else {
            Intent(Intent.ACTION_VIEW, uri)
        }.apply {
            type = mimeType
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = android.content.ClipData.newRawUri(file.originalFilename, uri)
        }
        if (context.packageManager.queryIntentActivities(intent, 0).isEmpty()) {
            throw IOException("No installed Android app can open ${file.originalFilename} as $mimeType")
        }
        context.startActivity(
            Intent.createChooser(
                intent,
                if (share) "Share ${file.originalFilename}" else "Open ${file.originalFilename}",
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    companion object {
        private val GENERIC_MIME_TYPES = setOf("application/octet-stream", "binary/octet-stream")

        fun resolveMimeType(file: StoredFile): String {
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
                    else -> "*/*"
                }
        }
    }
}
