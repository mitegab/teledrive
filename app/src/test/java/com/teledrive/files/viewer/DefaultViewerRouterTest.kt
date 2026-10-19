package com.teledrive.files.viewer

import com.teledrive.core.model.FileKind
import com.teledrive.core.model.RemoteMessageId
import com.teledrive.core.model.StoredFile
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultViewerRouterTest {
    private val router = DefaultViewerRouter()

    @Test fun routesByUsefulMimeThenKindAndExtension() {
        assertEquals(ViewerTarget.InAppImage, router.route(file("x", "image/jpeg")))
        assertEquals(ViewerTarget.InAppVideo, router.route(file("clip", "video/mp4")))
        assertEquals(ViewerTarget.InAppAudio, router.route(file("track", "audio/mpeg")))
        assertEquals(ViewerTarget.InAppImage, router.route(file("PHOTO.PNG", null)))
        assertEquals(ViewerTarget.InAppAudio, router.route(file("A.MP3", "application/octet-stream")))
        assertEquals(ViewerTarget.InAppPdf, router.route(file("x.pdf", "application/octet-stream")))
        assertEquals(ViewerTarget.InAppText, router.route(file("x.txt", null)))
        assertEquals(true, router.route(file("mystery.zzz", "application/octet-stream")) is ViewerTarget.External)
    }

    private fun file(name: String, mime: String?) = StoredFile(
        remote = RemoteMessageId(1, 2), tdlibFileId = 1, originalFilename = name,
        mimeType = mime, sizeBytes = 10, savedAtEpochSeconds = 1,
    )
}
