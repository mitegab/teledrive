package com.teledrive.core.model

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfOutlineParserTest {
    private val parser = PdfOutlineParser()

    private fun pdf(objects: List<String>, root: Int = 1, trailerExtra: String = ""): ByteArray {
        val builder = StringBuilder("%PDF-1.7\n")
        objects.forEachIndexed { index, body ->
            builder.append(index + 1).append(" 0 obj\n").append(body).append("\nendobj\n")
        }
        builder.append("trailer\n<< /Root ").append(root).append(" 0 R ").append(trailerExtra).append(">>\n%%EOF\n")
        return builder.toString().toByteArray(Charsets.ISO_8859_1)
    }

    private fun basicObjects(outlineRoot: String = "<< /Type /Outlines /First 7 0 R /Last 9 0 R /Count 3 >>"): List<String> = listOf(
        "<< /Type /Catalog /Pages 2 0 R /Outlines 5 0 R >>",
        "<< /Type /Pages /Count 3 /Kids [3 0 R 4 0 R 6 0 R] >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>",
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>",
        outlineRoot,
        "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] >>",
        "<< /Title (Chapter One) /Parent 5 0 R /Dest [3 0 R /XYZ 0 792 0] /First 8 0 R /Next 9 0 R >>",
        "<< /Title <FEFF004300680061007000740065007200200031002E0031> /Parent 7 0 R /Dest [4 0 R /Fit] >>",
        "<< /Title (Chapter Two) /Dest [6 0 R /Fit] /Prev 7 0 R >>",
    )

    @Test
    fun readsNestedOutlineWithRealPageIndexes() {
        val result = parser.parse(pdf(basicObjects()))
        assertFalse(result.encrypted)
        assertEquals(3, result.pageCount)
        assertEquals(2, result.entries.size)

        val chapterOne = result.entries[0]
        assertEquals("Chapter One", chapterOne.title)
        assertEquals(0, chapterOne.pageIndex)
        assertEquals(0, chapterOne.depth)
        assertEquals(1, chapterOne.children.size)
        assertEquals("Chapter 1.1", chapterOne.children.single().title)
        assertEquals(1, chapterOne.children.single().pageIndex)
        assertEquals(1, chapterOne.children.single().depth)

        val chapterTwo = result.entries[1]
        assertEquals("Chapter Two", chapterTwo.title)
        assertEquals(2, chapterTwo.pageIndex)
        assertNull(result.diagnostic)
    }

    @Test
    fun decodesEscapedLiteralTitles() {
        val objects = basicObjects().toMutableList()
        objects[6] = "<< /Title (Titles \\(draft\\) \\\\ final) /Dest [3 0 R /Fit] >>"
        val result = parser.parse(pdf(objects))
        assertEquals("Titles (draft) \\ final", result.entries.single().title)
    }

    @Test
    fun resolvesGoToActionsAndSkipsUnresolvableDestinations() {
        val objects = basicObjects().toMutableList()
        objects[6] = "<< /Title (Via action) /A << /S /GoTo /D [4 0 R /Fit] >> /Next 9 0 R >>"
        objects[8] = "<< /Title (Named destination) /Dest /ChapterThree >>"
        val result = parser.parse(pdf(objects))
        assertEquals(2, result.entries.size)
        assertEquals(1, result.entries[0].pageIndex)
        assertNull(result.entries[1].pageIndex)
        assertFalse(result.entries[1].isNavigable)
    }

    @Test
    fun documentsWithoutAnOutlineStillReportThePageCount() {
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Count 2 /Kids [3 0 R 4 0 R] >>",
            "<< /Type /Page /Parent 2 0 R >>",
            "<< /Type /Page /Parent 2 0 R >>",
        )
        val result = parser.parse(pdf(objects))
        assertEquals(2, result.pageCount)
        assertTrue(result.entries.isEmpty())
        assertEquals("document has no outline", result.diagnostic)
    }

    @Test
    fun encryptionFlagIsDetectedWithoutTryingToDecrypt() {
        val result = parser.parse(pdf(basicObjects(), trailerExtra = "/Encrypt 12 0 R "))
        assertTrue(result.encrypted)
        assertEquals(2, result.entries.size)
    }

    @Test
    fun cyclicOutlineLinksTerminateInsteadOfLoopingForever() {
        val objects = basicObjects().toMutableList()
        objects[6] = "<< /Title (Loop) /Dest [3 0 R /Fit] /Next 7 0 R >>"
        val result = parser.parse(pdf(objects))
        assertEquals(1, result.entries.size)
        assertEquals(0, result.entries.single().pageIndex)
    }

    @Test
    fun garbledOrEmptyFilesNeverThrow() {
        val random = parser.parse("%PDF-1.7 not really a pdf".toByteArray())
        assertTrue(random.entries.isEmpty())
        assertNull(random.pageCount)

        val empty = parser.parse(ByteArray(0))
        assertTrue(empty.entries.isEmpty())
        assertEquals("empty document", empty.diagnostic)

        val truncated = parser.parse("%PDF-1.7\n1 0 obj\n<< /Type".toByteArray())
        assertTrue(truncated.entries.isEmpty())
    }

    @Test
    fun veryLargeDocumentsSkipTheOutlineScanInsteadOfReadingEverything() {
        val smallLimit = PdfOutlineParser(maxFileSizeBytes = 32L)
        val result = smallLimit.parse(pdf(basicObjects()))
        assertEquals("outline scan skipped for very large file", result.diagnostic)
        assertTrue(result.entries.isEmpty())
    }

    @Test
    fun objectsInsideCompressedObjectStreamsAreResolved() {
        val bodies = listOf(
            "<< /Type /Catalog /Pages 2 0 R /Outlines 6 0 R >>",
            "<< /Type /Pages /Count 2 /Kids [3 0 R 4 0 R] >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>",
            "<< /Title (From object stream) /Dest [4 0 R /Fit] >>",
        )
        val header = StringBuilder()
        val body = ByteArrayOutputStream()
        bodies.forEachIndexed { index, text ->
            header.append(index + 1).append(' ').append(body.size()).append(' ')
            body.write(text.toByteArray(Charsets.ISO_8859_1))
        }
        val headerBytes = header.toString().toByteArray(Charsets.ISO_8859_1)
        val decoded = headerBytes + body.toByteArray()
        val compressed = deflate(decoded)

        val builder = StringBuilder("%PDF-1.7\n")
        builder.append("6 0 obj\n<< /Type /Outlines /First 5 0 R >>\nendobj\n")
        builder.append("10 0 obj\n<< /Type /ObjStm /N 5 /First ").append(headerBytes.size)
            .append(" /Length ").append(compressed.size).append(" /Filter /FlateDecode >>\nstream\n")
        val prefix = builder.toString().toByteArray(Charsets.ISO_8859_1)
        val suffix = "\nendstream\nendobj\ntrailer\n<< /Root 1 0 R >>\n%%EOF\n".toByteArray(Charsets.ISO_8859_1)
        val bytes = prefix + compressed + suffix

        val result = parser.parse(bytes)
        assertEquals(2, result.pageCount)
        assertEquals(1, result.entries.size)
        assertEquals("From object stream", result.entries.single().title)
        assertEquals(1, result.entries.single().pageIndex)
    }

    @Test
    fun byteArraySourceClampsReadsAtTheEnd() {
        val source = ByteArrayPdfSource(byteArrayOf(1, 2, 3, 4))
        assertEquals(4L, source.size)
        assertEquals(2, source.read(2, 10).size)
        assertEquals(0, source.read(10, 10).size)
        assertEquals(0, source.read(-1, 10).size)
    }

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(input)
        deflater.finish()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (!deflater.finished()) {
            val read = deflater.deflate(buffer)
            output.write(buffer, 0, read)
        }
        deflater.end()
        return output.toByteArray()
    }
}
