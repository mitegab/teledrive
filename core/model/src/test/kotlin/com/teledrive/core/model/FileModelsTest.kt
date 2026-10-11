package com.teledrive.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileModelsTest {
    @Test
    fun detectsMimeAndExtensionKinds() {
        assertEquals(FileKind.IMAGE, FileKindDetector.detect("photo.webp", "image/webp"))
        assertEquals(FileKind.VIDEO, FileKindDetector.detect("clip.bin", "video/mp4"))
        assertEquals(FileKind.ARCHIVE, FileKindDetector.detect("archive.TAR.GZ", null))
        assertEquals(FileKind.TEXT, FileKindDetector.detect("notes", "text/plain"))
        assertEquals(FileKind.DOCUMENT, FileKindDetector.detect("report.docx", null))
    }

    @Test
    fun sanitizesPathsWithoutDestroyingUnicode() {
        val name = FilenameRules.sanitize("  ሰነድ/2026\u0000.pdf  ")
        assertEquals("ሰነድ_2026.pdf", name)
        assertEquals("pdf", FilenameRules.extension(name))
    }

    @Test
    fun suppliesSafeFallbackForBlankNames() {
        assertTrue(FilenameRules.displayName("", "image/png").isNotBlank())
        assertEquals("Document", FilenameRules.displayName(null, "application/octet-stream"))
    }
}
