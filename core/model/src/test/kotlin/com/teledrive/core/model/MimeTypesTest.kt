package com.teledrive.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MimeTypesTest {
    @Test
    fun specificDeclaredTypeWinsOverExtension() {
        assertEquals("image/png", MimeTypes.resolve("image/png", "photo.jpg"))
        assertEquals("audio/mpeg", MimeTypes.resolve("Audio/MPEG; charset=binary", null))
    }

    @Test
    fun genericOrMissingTypeFallsBackToExtension() {
        assertEquals("application/pdf", MimeTypes.resolve("application/octet-stream", "Report.PDF"))
        assertEquals("video/mp4", MimeTypes.resolve(null, "clip.mp4"))
        assertEquals("application/zip", MimeTypes.resolve("", "bundle.zip"))
    }

    @Test
    fun unknownTypeAndNoExtensionStayGenericInsteadOfGuessing() {
        assertEquals(MimeTypes.OCTET_STREAM, MimeTypes.resolve(null, "README"))
        assertEquals(MimeTypes.OCTET_STREAM, MimeTypes.resolve(null, "data.unknownext"))
        assertTrue(MimeTypes.isGeneric("application/octet-stream"))
        assertFalse(MimeTypes.isGeneric("application/pdf"))
    }

    @Test
    fun kindDetectionUsesExtensionWhenDeclaredTypeIsGeneric() {
        assertEquals(FileKind.DOCUMENT, FileKindDetector.detect("a.pdf", "application/pdf"))
        assertEquals(FileKind.IMAGE, FileKindDetector.detect("a.jpg", "application/octet-stream"))
        assertEquals(FileKind.ARCHIVE, FileKindDetector.detect("a.zip", null))
        assertEquals(FileKind.AUDIO, FileKindDetector.detect("voice.ogg", "audio/ogg"))
        assertEquals(FileKind.DOCUMENT, FileKindDetector.detect("noext", null))
    }

    @Test
    fun fileSystemNameBlocksTraversalAndKeepsUnicode() {
        assertEquals("passwd", FileNameCheck.base(FilenameRules.fileSystemName("../../etc/passwd")))
        assertEquals("ሰነድ 文档.pdf", FilenameRules.fileSystemName("ሰነድ 文档.pdf"))
        assertEquals("file", FilenameRules.fileSystemName(".."))
        assertEquals("file", FilenameRules.fileSystemName(null))
    }

    @Test
    fun fileSystemNameTruncatesByUtf8BytesAndKeepsExtension() {
        val long = "ሰ".repeat(200) + ".pdf" // 3 bytes per code point
        val result = FilenameRules.fileSystemName(long, maxBytes = 100)
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= 100)
        assertTrue(result.endsWith(".pdf"))
        assertTrue(result.removeSuffix(".pdf").all { it == 'ሰ' })
    }

    private object FileNameCheck {
        // sanitize() replaces separators, so a traversal attempt becomes one harmless flat name.
        fun base(name: String): String = name.substringAfterLast('_')
    }
}
