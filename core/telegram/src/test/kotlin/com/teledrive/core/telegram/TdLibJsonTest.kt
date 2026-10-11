package com.teledrive.core.telegram

import org.json.JSONArray
import org.json.JSONObject
import com.teledrive.core.model.LocalAvailability
import com.teledrive.core.model.RemoteMessageId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TdLibJsonTest {
    @Test
    fun mapsDocumentBackedFilesWithoutRequiringLocalBytes() {
        val pdf = message(1, documentContent("report.pdf", "application/pdf", 101))
        val zip = message(2, documentContent("archive.zip", "application/zip", 102))
        val empty = message(3, documentContent("empty.txt", "text/plain", 0))

        assertEquals("report.pdf", pdf.toStoredFile(42)?.originalFilename)
        assertEquals("application/pdf", pdf.toStoredFile(42)?.mimeType)
        assertEquals("archive.zip", zip.toStoredFile(42)?.originalFilename)
        assertEquals(102L, zip.toStoredFile(42)?.sizeBytes)
        assertEquals(0L, empty.toStoredFile(42)?.sizeBytes)
        assertEquals(LocalAvailability.NOT_DOWNLOADED, zip.toStoredFile(42)?.localAvailability)
    }

    @Test
    fun mapsAllSupportedMediaRepresentationsToRemoteOnlyRecords() {
        val contents = listOf(
            documentContent("document.bin", "application/octet-stream", 1),
            JSONObject().put("@type", "messagePhoto").put("photo", JSONObject().put("sizes", JSONArray().put(photoSize(2)))),
            mediaContent("messageVideo", "video", "clip.mp4", "video/mp4", 3),
            mediaContent("messageAudio", "audio", "song.mp3", "audio/mpeg", 4),
            mediaContent("messageAnimation", "animation", "clip.gif", "image/gif", 5),
            JSONObject().put("@type", "messageVoiceNote").put("voice_note", JSONObject().put("mime_type", "audio/ogg").put("voice", file(6, 6))),
            JSONObject().put("@type", "messageVideoNote").put("video_note", JSONObject().put("video", file(7, 7))),
            JSONObject().put("@type", "messageSticker").put("sticker", JSONObject().put("sticker", file(8, 8))),
        )

        contents.forEachIndexed { index, content ->
            val mapped = assertNotNull(
                message(index + 10L, content).toStoredFile(42),
                "content ${content.optString("@type")} was dropped",
            )
            assertEquals(LocalAvailability.NOT_DOWNLOADED, mapped.localAvailability)
            assertEquals(index + 1L, mapped.tdlibFileId?.toLong())
        }
    }

    @Test
    fun selectsTheLargestPhotoSizeRatherThanAssumingArrayOrder() {
        val content = JSONObject()
            .put("@type", "messagePhoto")
            .put("photo", JSONObject().put("sizes", JSONArray().put(photoSize(20, 2_000, 2_000)).put(photoSize(21, 100, 100))))

        assertEquals(20, message(8, content).toStoredFile(42)?.tdlibFileId)
    }

    @Test
    fun suppliesUsefulExtensionsWhenTelegramOmitsDocumentName() {
        val mapped = message(7, documentContent("", "application/pdf", 9)).toStoredFile(42)
        assertEquals("Telegram-file-7.pdf", mapped?.originalFilename)
    }

    @Test
    fun emitsOnlyPermanentRemoteDeletions() {
        val update = JSONObject()
            .put("@type", "updateDeleteMessages")
            .put("chat_id", 42)
            .put("message_ids", JSONArray().put(9).put(10))
            .put("is_permanent", true)
        val cacheOnly = JSONObject()
            .put("@type", "updateDeleteMessages")
            .put("chat_id", 42)
            .put("message_ids", JSONArray().put(11))
            .put("is_permanent", false)

        assertEquals(listOf(RemoteMessageId(42, 9), RemoteMessageId(42, 10)), update.permanentDeletedMessages())
        assertEquals(emptyList(), cacheOnly.permanentDeletedMessages())
    }

    @Test
    fun ignoresUnsupportedMessagesButDoesNotRequireAThumbnail() {
        val unsupported = message(99, JSONObject().put("@type", "messageText"))
        assertNull(unsupported.toStoredFile(42))
    }

    private fun documentContent(name: String, mime: String, id: Int): JSONObject = JSONObject()
        .put("@type", "messageDocument")
        .put("document", JSONObject().put("file_name", name).put("mime_type", mime).put("document", file(id, id.toLong())))

    private fun mediaContent(type: String, field: String, name: String, mime: String, id: Int): JSONObject = JSONObject()
        .put("@type", type)
        .put(field, JSONObject().put("file_name", name).put("mime_type", mime).put(field, file(id, id.toLong())))

    private fun photoSize(id: Int): JSONObject = photoSize(id, 0, 0)

    private fun photoSize(id: Int, width: Int, height: Int): JSONObject = JSONObject()
        .put("@type", "photoSize")
        .put("width", width)
        .put("height", height)
        .put("photo", file(id, id.toLong()))

    private fun file(id: Int, size: Long): JSONObject = JSONObject()
        .put("@type", "file")
        .put("id", id)
        .put("size", size)
        .put("remote", JSONObject().put("id", "remote-$id").put("is_uploaded", true))

    private fun message(id: Long, content: JSONObject): JSONObject = JSONObject()
        .put("@type", "message")
        .put("id", id)
        .put("chat_id", 42)
        .put("date", 100)
        .put("content", content)
}
