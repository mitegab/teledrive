package com.teledrive.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaClassificationTest {
    @Test
    fun detectsVideoAndAudioByExtensionWhenMimeGeneric() {
        // Generic octet-stream should fall back to extension
        assertEquals(FileKind.VIDEO, FileKindDetector.detect("movie.mp4", "application/octet-stream"))
        assertEquals(FileKind.VIDEO, FileKindDetector.detect("clip.mkv", null))
        assertEquals(FileKind.VIDEO, FileKindDetector.detect("film.webm", "binary/octet-stream"))
        assertEquals(FileKind.AUDIO, FileKindDetector.detect("song.mp3", "application/octet-stream"))
        assertEquals(FileKind.AUDIO, FileKindDetector.detect("track.flac", null))
        assertEquals(FileKind.AUDIO, FileKindDetector.detect("podcast.m4a", ""))
        assertEquals(FileKind.AUDIO, FileKindDetector.detect("audio.opus", "*/*"))
    }

    @Test
    fun detectsImageByExtensionFallback() {
        assertEquals(FileKind.IMAGE, FileKindDetector.detect("photo.heic", null))
        assertEquals(FileKind.IMAGE, FileKindDetector.detect("image.avif", "application/octet-stream"))
    }

    @Test
    fun preservesMimePriorityOverExtension() {
        // If mime says image, even with video extension, mime wins
        assertEquals(FileKind.IMAGE, FileKindDetector.detect("clip.mp4", "image/jpeg"))
        assertEquals(FileKind.VIDEO, FileKindDetector.detect("photo.jpg", "video/mp4"))
        assertEquals(FileKind.AUDIO, FileKindDetector.detect("video.mp4", "audio/mpeg"))
    }

    @Test
    fun handlesArchiveAndTextCorrectly() {
        assertEquals(FileKind.ARCHIVE, FileKindDetector.detect("backup.zip", "application/octet-stream"))
        assertEquals(FileKind.TEXT, FileKindDetector.detect("notes.md", null))
        assertEquals(FileKind.DOCUMENT, FileKindDetector.detect("report.pdf", "application/pdf"))
        assertEquals(FileKind.DOCUMENT, FileKindDetector.detect("report.docx", null))
    }

    @Test
    fun supportsUppercaseExtensions() {
        assertEquals(FileKind.VIDEO, FileKindDetector.detect("MOVIE.MP4", null))
        assertEquals(FileKind.AUDIO, FileKindDetector.detect("SONG.MP3", null))
    }

    @Test
    fun isMediaKindHelper() {
        assertTrue(FileKindDetector.isMediaKind(FileKind.VIDEO))
        assertTrue(FileKindDetector.isMediaKind(FileKind.AUDIO))
        assertFalse(FileKindDetector.isMediaKind(FileKind.IMAGE))
        assertFalse(FileKindDetector.isMediaKind(FileKind.DOCUMENT))
    }
}
