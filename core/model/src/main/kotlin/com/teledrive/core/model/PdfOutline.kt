package com.teledrive.core.model

import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * Bounded, seekable byte access used by [PdfOutlineParser]. The app implements this
 * with a `RandomAccessFile`; tests use [ByteArrayPdfSource]. The parser never loads
 * a whole document into memory.
 */
interface PdfByteSource {
    val size: Long
    fun read(offset: Long, count: Int): ByteArray
}

class ByteArrayPdfSource(private val bytes: ByteArray) : PdfByteSource {
    override val size: Long get() = bytes.size.toLong()
    override fun read(offset: Long, count: Int): ByteArray {
        if (offset < 0L || offset >= bytes.size) return ByteArray(0)
        val start = offset.toInt()
        val end = (start + count).coerceAtMost(bytes.size)
        return bytes.copyOfRange(start, end)
    }
}

/**
 * Reads the document's page tree and bookmark outline directly from the PDF object
 * graph. The platform `PdfRenderer` renders pages but exposes no outline API, so this
 * is the only way to offer real section navigation without a native PDF dependency.
 *
 * Deliberately tolerant: encrypted, damaged, filter-unsupported, or unusually large
 * documents simply yield no outline (and a diagnostic), never an exception. Page
 * numbers are only reported when they can be resolved through the actual page tree.
 */
class PdfOutlineParser(
    private val maxFileSizeBytes: Long = 128L * 1024L * 1024L,
    private val maxOutlineEntries: Int = 2_000,
    private val maxPages: Int = 200_000,
) {
    private companion object {
        const val SCAN_CHUNK = 256 * 1024
        const val SCAN_OVERLAP = 160
        const val OBJECT_HEADER_WINDOW = 32 * 1024
        const val MAX_STREAM_BYTES = 16L * 1024L * 1024L
        const val MAX_OBJECT_STREAMS = 64
        const val MAX_EMBEDDED_OBJECTS = 20_000
        const val MAX_OUTLINE_DEPTH = 8
        val WHITESPACE = setOf(' ', '\n', '\r', '\t', '\u000C', '\u0000')
    }

    fun parse(source: PdfByteSource): PdfOutlineResult {
        if (source.size <= 0L) return PdfOutlineResult(diagnostic = "empty document")
        if (source.size > maxFileSizeBytes) return PdfOutlineResult(diagnostic = "outline scan skipped for very large file")
        return try {
            Reader(source).read()
        } catch (error: Exception) {
            PdfOutlineResult(diagnostic = AppErrorMapper.sanitizeCause("${error::class.java.simpleName}: ${error.message}"))
        }
    }

    fun parse(bytes: ByteArray): PdfOutlineResult = parse(ByteArrayPdfSource(bytes))

    private inner class Reader(private val source: PdfByteSource) {
        private val objects = HashMap<Int, Long>()
        private val embedded = HashMap<Int, ByteArray>()
        private var objectStreamsIndexed = false

        fun read(): PdfOutlineResult {
            indexObjects()
            val encrypted = hasRawToken("/Encrypt")
            var catalog = findCatalog()
            var pagesRoot = catalog?.let { refValue(it, "Pages") }
            var pageObjects = pagesRoot?.let { collectPages(it) } ?: emptyList()
            if (catalog == null || pageObjects.isEmpty()) {
                // Catalogs/pages frequently live inside compressed object streams.
                ensureObjectStreamsIndexed()
                if (catalog == null) catalog = findCatalog()
                pagesRoot = catalog?.let { refValue(it, "Pages") } ?: pagesRoot
                if (pageObjects.isEmpty()) pageObjects = pagesRoot?.let { collectPages(it) } ?: emptyList()
            }
            val pageIndexes = pageObjects.withIndex().associate { (index, number) -> number to index }
            val outlineRoot = catalog?.let { refValue(it, "Outlines") }
            val entries = outlineRoot?.let { readOutlineSiblings(firstRefOf(it), depth = 0, pageIndexes, HashSet(), intArrayOf(0)) } ?: emptyList()
            return PdfOutlineResult(
                entries = entries,
                pageCount = pageObjects.size.takeIf { it > 0 },
                encrypted = encrypted,
                diagnostic = when {
                    catalog == null -> "catalog not found"
                    pageObjects.isEmpty() -> "page tree not readable"
                    entries.isEmpty() -> "document has no outline"
                    else -> null
                },
            )
        }

        // ---- object index -------------------------------------------------------

        private fun indexObjects() {
            var offset = 0L
            var lastEnd = -1L
            val pattern = Regex("(\\d{1,10})[\\s\\u0000]+(\\d{1,5})[\\s\\u0000]+obj\\b")
            while (offset < source.size && objects.size < 250_000) {
                val chunk = source.read(offset, SCAN_CHUNK)
                if (chunk.isEmpty()) break
                val text = String(chunk, Charsets.ISO_8859_1)
                for (match in pattern.findAll(text)) {
                    val absolute = offset + match.range.first
                    if (absolute <= lastEnd) continue
                    val number = match.groupValues[1].toIntOrNull() ?: continue
                    objects.putIfAbsent(number, absolute)
                    lastEnd = absolute
                }
                if (chunk.size < SCAN_CHUNK) break
                offset += (SCAN_CHUNK - SCAN_OVERLAP).toLong()
            }
        }

        private fun hasRawToken(token: String): Boolean {
            var offset = 0L
            val needle = token.toByteArray(Charsets.ISO_8859_1)
            while (offset < source.size) {
                val chunk = source.read(offset, SCAN_CHUNK)
                if (chunk.isEmpty()) break
                if (indexOf(chunk, needle) >= 0) return true
                if (chunk.size < SCAN_CHUNK) break
                offset += (SCAN_CHUNK - SCAN_OVERLAP).toLong()
            }
            return false
        }

        private fun objectHeader(number: Int): String? {
            embedded[number]?.let { return String(it, Charsets.ISO_8859_1) }
            val offset = objects[number] ?: return null
            val chunk = source.read(offset, OBJECT_HEADER_WINDOW)
            if (chunk.isEmpty()) return null
            val text = String(chunk, Charsets.ISO_8859_1)
            val objIndex = text.indexOf("obj")
            if (objIndex < 0) return null
            val bodyStart = objIndex + 3
            val streamIndex = text.indexOf("stream", bodyStart)
            val endIndex = listOf(text.indexOf("endobj", bodyStart), streamIndex).filter { it > bodyStart }.minOrNull()
            return if (endIndex == null) text.substring(bodyStart) else text.substring(bodyStart, endIndex)
        }

        private fun intObject(number: Int): Int? {
            val header = objectHeader(number) ?: return null
            return Regex("^\\s*(\\d{1,10})").find(header)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }

        private fun findCatalog(): String? {
            for ((number, offset) in objects.entries.sortedBy { it.value }) {
                if (number in embedded) continue
                val header = objectHeader(number) ?: continue
                if (!header.contains("/Catalog")) continue
                val dict = extractDict(header) ?: continue
                if (nameValue(dict, "Type") == "Catalog") return dict
            }
            for ((_, body) in embedded) {
                val text = String(body, Charsets.ISO_8859_1)
                if (!text.contains("/Catalog")) continue
                val dict = extractDict(text) ?: continue
                if (nameValue(dict, "Type") == "Catalog") return dict
            }
            // Fallback: last /Root reference in the trailer region.
            val trailerStart = (source.size - 512L * 1024L).coerceAtLeast(0L)
            val tail = String(source.read(trailerStart, (source.size - trailerStart).toInt()), Charsets.ISO_8859_1)
            val root = Regex("/Root\\s+(\\d+)\\s+\\d+\\s+R").findAll(tail).lastOrNull() ?: return null
            val rootNumber = root.groupValues[1].toIntOrNull() ?: return null
            return objectHeader(rootNumber)?.let { extractDict(it) }
        }

        private fun ensureObjectStreamsIndexed() {
            if (objectStreamsIndexed) return
            objectStreamsIndexed = true
            var offset = 0L
            var streams = 0
            val needle = "/ObjStm".toByteArray(Charsets.ISO_8859_1)
            while (offset < source.size && streams < MAX_OBJECT_STREAMS && embedded.size < MAX_EMBEDDED_OBJECTS) {
                val chunk = source.read(offset, SCAN_CHUNK)
                if (chunk.isEmpty()) break
                val haystack = String(chunk, Charsets.ISO_8859_1)
                var searchFrom = 0
                while (true) {
                    val hit = haystack.indexOf("/ObjStm", searchFrom)
                    if (hit < 0) break
                    searchFrom = hit + 1
                    val absolute = offset + hit
                    val owner = objectNumberBefore(absolute) ?: continue
                    if (objects[owner] == null) continue
                    decodeObjectStream(owner)?.let { streams++ }
                    if (streams >= MAX_OBJECT_STREAMS || embedded.size >= MAX_EMBEDDED_OBJECTS) break
                }
                if (chunk.size < SCAN_CHUNK) break
                offset += (SCAN_CHUNK - SCAN_OVERLAP).toLong()
            }
        }

        private fun objectNumberBefore(offset: Long): Int? {
            val windowStart = (offset - 4096L).coerceAtLeast(0L)
            val window = String(source.read(windowStart, (offset - windowStart).toInt()), Charsets.ISO_8859_1)
            return Regex("(\\d{1,10})\\s+\\d{1,5}\\s+obj").findAll(window).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()
        }

        private fun decodeObjectStream(owner: Int): ByteArray? {
            val header = objectHeader(owner) ?: return null
            val dict = extractDict(header) ?: return null
            if (nameValue(dict, "Type") != "ObjStm") return null
            val count = intValue(dict, "N") ?: return null
            val first = intValue(dict, "First") ?: return null
            val data = decodeStream(owner, header, dict) ?: return null
            if (first < 0 || first > data.size) return null
            val headerText = String(data.copyOfRange(0, first), Charsets.ISO_8859_1)
            val numbers = Regex("(\\d+)\\s+(\\d+)").findAll(headerText).take(count).toList()
            for ((index, match) in numbers.withIndex()) {
                val number = match.groupValues[1].toIntOrNull() ?: continue
                val relative = match.groupValues[2].toIntOrNull() ?: continue
                val start = first + relative
                if (start < 0 || start > data.size) continue
                val end = numbers.getOrNull(index + 1)?.groupValues?.get(2)?.toIntOrNull()?.let { first + it } ?: data.size
                val boundedEnd = end.coerceIn(start, data.size)
                embedded.putIfAbsent(number, data.copyOfRange(start, boundedEnd))
                if (embedded.size >= MAX_EMBEDDED_OBJECTS) break
            }
            return data
        }

        private fun decodeStream(owner: Int, header: String, dict: String): ByteArray? {
            val offset = objects[owner] ?: return null
            val raw = source.read(offset, OBJECT_HEADER_WINDOW)
            val text = String(raw, Charsets.ISO_8859_1)
            val streamIndex = text.indexOf("stream")
            if (streamIndex < 0) return null
            var dataStart = streamIndex + "stream".length
            if (dataStart < text.length && text[dataStart] == '\r') dataStart++
            if (dataStart < text.length && text[dataStart] == '\n') dataStart++
            val length = intValue(dict, "Length") ?: refValue(dict, "Length")?.let { intObject(it) } ?: return null
            if (length <= 0L || length > MAX_STREAM_BYTES) return null
            val absoluteStart = offset + dataStart
            val rawData = source.read(absoluteStart, length.toInt())
            if (rawData.isEmpty()) return null
            val filter = filterNames(dict)
            return when {
                filter.isEmpty() -> rawData
                filter == listOf("FlateDecode") || filter == listOf("Fl") -> inflate(rawData)
                else -> null
            }
        }

        // ---- page tree ----------------------------------------------------------

        private fun collectPages(rootRef: Int): List<Int> {
            val pages = ArrayList<Int>()
            val visited = HashSet<Int>()
            fun walk(number: Int, depth: Int) {
                if (depth > 64 || pages.size >= maxPages || !visited.add(number)) return
                val header = objectHeader(number) ?: return
                val dict = extractDict(header) ?: return
                val type = nameValue(dict, "Type")
                val kidRefs = refArray(dict, "Kids")
                when {
                    type == "Pages" || kidRefs.isNotEmpty() -> kidRefs.forEach { walk(it, depth + 1) }
                    type == "Page" || dict.contains("/Parent") -> if (pages.size < maxPages) pages += number
                }
            }
            walk(rootRef, 0)
            return pages
        }

        // ---- outline ------------------------------------------------------------

        private fun firstRefOf(outlinesRef: Int): Int? {
            val header = objectHeader(outlinesRef) ?: return null
            val dict = extractDict(header) ?: return null
            return refValue(dict, "First")
        }

        private fun readOutlineSiblings(
            firstNumber: Int?,
            depth: Int,
            pageIndexes: Map<Int, Int>,
            visited: MutableSet<Int>,
            counter: IntArray,
        ): List<PdfOutlineEntry> {
            if (firstNumber == null || depth > MAX_OUTLINE_DEPTH) return emptyList()
            val result = ArrayList<PdfOutlineEntry>()
            var current = firstNumber
            while (current != null && counter[0] < maxOutlineEntries) {
                if (!visited.add(current)) break
                val header = objectHeader(current) ?: break
                val dict = extractDict(header) ?: break
                counter[0]++
                val title = stringValue(dict, "Title")?.trim()?.takeIf { it.isNotEmpty() }
                val pageIndex = resolveDestination(dict, pageIndexes)
                val children = readOutlineSiblings(refValue(dict, "First"), depth + 1, pageIndexes, visited, counter)
                if (title != null) {
                    result += PdfOutlineEntry(
                        title = title.take(200),
                        pageIndex = pageIndex,
                        depth = depth,
                        children = children,
                    )
                } else if (children.isNotEmpty()) {
                    result += children
                }
                current = refValue(dict, "Next")
            }
            return result
        }

        private fun resolveDestination(node: String, pageIndexes: Map<Int, Int>): Int? {
            refArray(node, "Dest").firstOrNull()?.let { return pageIndexes[it] }
            val action = findInlineDict(node, "A") ?: return null
            val destinationRef = refArray(action, "D").firstOrNull() ?: return null
            return pageIndexes[destinationRef]
        }
    }

    // ---- pure text utilities ----------------------------------------------------

    private fun extractDict(text: String): String? {
        val start = text.indexOf("<<")
        if (start < 0) return null
        var depth = 0
        var index = start
        while (index < text.length - 1) {
            when {
                text.startsWith("<<", index) -> { depth++; index += 2 }
                text.startsWith(">>", index) -> {
                    depth--
                    index += 2
                    if (depth == 0) return text.substring(start + 2, index - 2)
                }
                text[index] == '(' -> index = skipLiteralString(text, index) + 1
                text[index] == '<' -> {
                    val end = text.indexOf('>', index + 1)
                    index = if (end < 0) text.length else end + 1
                }
                text[index] == '%' -> {
                    val end = text.indexOf('\n', index)
                    index = if (end < 0) text.length else end + 1
                }
                else -> index++
            }
        }
        return null
    }

    private fun findInlineDict(body: String, key: String): String? {
        val keyIndex = findKey(body, key) ?: return null
        val start = body.indexOf("<<", keyIndex)
        if (start < 0) return null
        val between = body.substring(keyIndex + key.length + 1, start)
        if (between.any { !WHITESPACE.contains(it) }) return null
        return extractDict(body.substring(start))
    }

    private fun findKey(body: String, key: String): Int? {
        val regex = Regex("/${Regex.escape(key)}(?![A-Za-z0-9#._-])")
        return regex.find(body)?.range?.first
    }

    private fun nameValue(body: String, key: String): String? =
        Regex("/${Regex.escape(key)}\\s*/([A-Za-z0-9#._+-]+)").find(body)?.groupValues?.getOrNull(1)

    private fun intValue(body: String, key: String): Int? {
        val match = Regex("/${Regex.escape(key)}\\s+(-?\\d+)(\\s+\\d+\\s+R)?").find(body) ?: return null
        if (match.groupValues[2].isNotBlank()) return null
        return match.groupValues[1].toIntOrNull()
    }

    private fun refValue(body: String, key: String): Int? =
        Regex("/${Regex.escape(key)}\\s+(\\d+)\\s+\\d+\\s+R").find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun refArray(body: String, key: String): List<Int> {
        val keyIndex = findKey(body, key) ?: return emptyList()
        val start = body.indexOf('[', keyIndex)
        if (start < 0) return emptyList()
        var depth = 0
        var index = start
        while (index < body.length) {
            when (body[index]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) {
                        val content = body.substring(start + 1, index)
                        return Regex("(\\d+)\\s+\\d+\\s+R").findAll(content).mapNotNull { it.groupValues[1].toIntOrNull() }.toList()
                    }
                }
                '(' -> index = skipLiteralString(body, index)
            }
            index++
        }
        return emptyList()
    }

    private fun stringValue(body: String, key: String): String? {
        val keyIndex = findKey(body, key) ?: return null
        var index = keyIndex + key.length + 1
        while (index < body.length && WHITESPACE.contains(body[index])) index++
        if (index >= body.length) return null
        return when (body[index]) {
            '(' -> {
                val end = skipLiteralString(body, index)
                val raw = body.substring(index + 1, end)
                decodeText(unescapeLiteral(raw))
            }
            '<' -> {
                if (body.startsWith("<<", index)) return null
                val end = body.indexOf('>', index + 1)
                if (end < 0) return null
                decodeText(hexToBytes(body.substring(index + 1, end)))
            }
            else -> null
        }
    }

    private fun skipLiteralString(text: String, start: Int): Int {
        var depth = 0
        var index = start
        while (index < text.length) {
            when (text[index]) {
                '\\' -> index++
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return index
                }
            }
            index++
        }
        return text.lastIndex.coerceAtLeast(start)
    }

    private fun unescapeLiteral(raw: String): ByteArray {
        val output = ArrayList<Byte>(raw.length)
        var index = 0
        while (index < raw.length) {
            val char = raw[index]
            if (char != '\\') {
                output += char.code.toByte()
                index++
                continue
            }
            index++
            if (index >= raw.length) break
            when (val escaped = raw[index]) {
                'n' -> { output += '\n'.code.toByte(); index++ }
                'r' -> { output += '\r'.code.toByte(); index++ }
                't' -> { output += '\t'.code.toByte(); index++ }
                'b' -> { output += '\b'.code.toByte(); index++ }
                'f' -> { output += '\u000C'.code.toByte(); index++ }
                '(', ')', '\\' -> { output += escaped.code.toByte(); index++ }
                '\r' -> { index++; if (index < raw.length && raw[index] == '\n') index++ }
                '\n' -> index++
                in '0'..'7' -> {
                    var digits = 0
                    var value = 0
                    while (digits < 3 && index < raw.length && raw[index] in '0'..'7') {
                        value = value * 8 + (raw[index] - '0')
                        index++
                        digits++
                    }
                    output += value.toByte()
                }
                else -> { output += escaped.code.toByte(); index++ }
            }
        }
        return output.toByteArray()
    }

    private fun hexToBytes(raw: String): ByteArray {
        val digits = raw.filter { it.isLetterOrDigit() }
        val padded = if (digits.length % 2 == 0) digits else digits + "0"
        return ByteArray(padded.length / 2) { index ->
            padded.substring(index * 2, index * 2 + 2).toIntOrNull(16)?.toByte() ?: 0
        }
    }

    private fun decodeText(bytes: ByteArray): String = when {
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        else -> String(bytes, Charsets.ISO_8859_1)
    }.trimEnd('\u0000')

    private fun filterNames(dict: String): List<String> {
        val keyIndex = findKey(dict, "Filter") ?: return emptyList()
        val names = Regex("/[A-Za-z0-9]+").findAll(dict.substring(keyIndex + 7)).map { it.value.removePrefix("/") }.toList()
        return names.take(3)
    }

    private fun inflate(data: ByteArray): ByteArray? = try {
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var guard = 0
            while (!inflater.finished() && guard < 4096) {
                val read = inflater.inflate(buffer)
                if (read == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else {
                    output.write(buffer, 0, read)
                }
                guard++
            }
            output.toByteArray().takeIf { it.isNotEmpty() }
        } finally {
            inflater.end()
        }
    } catch (_: DataFormatException) {
        null
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (index in 0..haystack.size - needle.size) {
            for (offset in needle.indices) {
                if (haystack[index + offset] != needle[offset]) continue@outer
            }
            return index
        }
        return -1
    }
}
