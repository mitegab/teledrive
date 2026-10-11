package com.teledrive.files

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
