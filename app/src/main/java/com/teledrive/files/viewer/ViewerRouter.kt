package com.teledrive.files.viewer

import com.teledrive.core.model.FileKind
import com.teledrive.core.model.StoredFile

sealed interface ViewerTarget {
    data object InAppImage : ViewerTarget
    data object InAppVideo : ViewerTarget
    data object InAppAudio : ViewerTarget
    data object InAppPdf : ViewerTarget
    data object InAppText : ViewerTarget
    data class External(val reason: String) : ViewerTarget
}

fun interface ViewerRouter { fun route(file: StoredFile): ViewerTarget }

/** Pure routing policy: a useful specific MIME wins, then FileKind, then filename extension. */
class DefaultViewerRouter : ViewerRouter {
    override fun route(file: StoredFile): ViewerTarget {
        val mime = file.mimeType?.trim()?.lowercase()?.takeUnless {
            it.isBlank() || it in setOf("application/octet-stream", "binary/octet-stream", "*/*")
        }
        val extension = file.originalFilename.substringAfterLast('.', "").lowercase()
        return when {
            mime?.startsWith("image/") == true || file.kind == FileKind.IMAGE -> ViewerTarget.InAppImage
            mime?.startsWith("video/") == true || file.kind == FileKind.VIDEO -> ViewerTarget.InAppVideo
            mime?.startsWith("audio/") == true || file.kind == FileKind.AUDIO -> ViewerTarget.InAppAudio
            mime == "application/pdf" || extension == "pdf" -> ViewerTarget.InAppPdf
            mime?.startsWith("text/") == true || file.kind == FileKind.TEXT -> ViewerTarget.InAppText
            extension in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif") -> ViewerTarget.InAppImage
            extension in setOf("mp4", "m4v", "webm", "mkv", "3gp", "mov") -> ViewerTarget.InAppVideo
            extension in setOf("mp3", "m4a", "aac", "ogg", "oga", "opus", "wav", "flac") -> ViewerTarget.InAppAudio
            else -> ViewerTarget.External("No built-in viewer supports this file type.")
        }
    }
}
