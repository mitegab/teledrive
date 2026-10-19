package com.teledrive.files

import android.net.Uri
import android.content.Intent
import com.teledrive.core.model.RemoteMessageId
import com.teledrive.core.model.StoredFile
import org.junit.Assert.assertEquals
import org.junit.Test

class FileOpenRouterTest {
    @Test
    fun resolvesViewerMimeTypesFromTelegramMetadataOrSafeFilenameFallback() {
        assertEquals("application/pdf", FileOpenRouter.resolveMimeType(file("report.pdf", null)))
        assertEquals("application/pdf", FileOpenRouter.resolveMimeType(file("report.pdf", "application/octet-stream")))
        assertEquals("audio/mpeg", FileOpenRouter.resolveMimeType(file("song.mp3", null)))
        assertEquals("video/mp4", FileOpenRouter.resolveMimeType(file("clip.mp4", null)))
        assertEquals("application/vnd.custom", FileOpenRouter.resolveMimeType(file("unknown.bin", "application/vnd.custom")))
        assertEquals("image/jpeg", FileOpenRouter.resolveMimeType(file("PHOTO.JPEG", null)))
        assertEquals(null, FileOpenRouter.resolveMimeType(file("mystery.zzz", "application/octet-stream")))
    }

    @Test
    fun viewIntentKeepsUriAndMimeAndReadGrant() {
        val uri = Uri.parse("content://example/item")
        val intent = ViewerIntents.view(uri, "video/mp4", "clip.mp4")
        assertEquals(uri, intent.data)
        assertEquals("video/mp4", intent.type)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(true, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun shareIntentCarriesStreamUriAndReadGrant() {
        val uri = Uri.parse("content://example/item")
        val intent = ViewerIntents.share(uri, "image/jpeg", "photo.jpg")
        assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM))
        assertEquals("image/jpeg", intent.type)
        assertEquals(true, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    private fun file(name: String, mime: String?) = StoredFile(
        remote = RemoteMessageId(1, 1),
        tdlibFileId = 1,
        originalFilename = name,
        mimeType = mime,
        sizeBytes = 1,
        savedAtEpochSeconds = 1,
    )
}
