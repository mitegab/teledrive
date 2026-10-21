package com.teledrive.core.model

import kotlin.math.max
import kotlin.math.min

/** Cheap, engine-independent classification of a PDF candidate. */
object PdfFileRules {
    private const val HEADER = "%PDF-"
    private const val MAX_PREFLIGHT_HEADER_BYTES = 1024

    fun isPdf(filename: String?, mimeType: String?): Boolean {
        val mime = mimeType.orEmpty().trim().lowercase()
        if (mime == "application/pdf" || mime == "application/x-pdf") return true
        return FilenameRules.extension(FilenameRules.sanitize(filename.orEmpty())).equals("pdf", ignoreCase = true)
    }

    fun hasPdfHeader(header: ByteArray): Boolean {
        if (header.size < HEADER.length) return false
        val prefix = header.copyOfRange(0, HEADER.length)
        return String(prefix, Charsets.ISO_8859_1) == HEADER
    }

    /**
     * Validates the local file before handing it to PdfRenderer. A zero-length or
     * non-PDF file is reported instead of surfacing an opaque renderer crash.
     */
    fun preflight(sizeBytes: Long?, header: ByteArray?): PdfPreflight = when {
        sizeBytes != null && sizeBytes <= 0L -> PdfPreflight.EMPTY
        header != null && !hasPdfHeader(header) -> PdfPreflight.NOT_PDF
        else -> PdfPreflight.OK
    }

    fun headerBytesToRead(): Int = MAX_PREFLIGHT_HEADER_BYTES
}

enum class PdfPreflight { OK, EMPTY, NOT_PDF }

data class PdfOutlineEntry(
    val title: String,
    val pageIndex: Int? = null,
    val depth: Int = 0,
    val children: List<PdfOutlineEntry> = emptyList(),
) {
    val isNavigable: Boolean get() = pageIndex != null
}

data class PdfOutlineResult(
    val entries: List<PdfOutlineEntry> = emptyList(),
    val pageCount: Int? = null,
    val encrypted: Boolean = false,
    /** Diagnostic only; never shown raw to the user. */
    val diagnostic: String? = null,
) {
    val hasOutline: Boolean get() = entries.isNotEmpty()
}

data class PdfPageGeometry(
    val widthPoints: Float,
    val heightPoints: Float,
) {
    init {
        require(widthPoints > 0f && heightPoints > 0f) { "PDF page dimensions must be positive" }
    }

    val aspectRatio: Float get() = widthPoints / heightPoints
}

/** Clamped zoom policy shared by the reader and its tests. */
data class PdfZoom(
    val scale: Float = MIN_SCALE,
) {
    fun coerced(): PdfZoom = PdfZoom(clamp(scale))

    fun zoomedBy(factor: Float): PdfZoom = PdfZoom(clamp(scale * factor))

    val isAtMinimum: Boolean get() = scale <= MIN_SCALE + 0.001f
    val isAtMaximum: Boolean get() = scale >= MAX_SCALE - 0.001f

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 6f
        const val DOUBLE_TAP_SCALE = 2.5f

        fun clamp(value: Float): Float = when {
            value.isNaN() -> MIN_SCALE
            else -> min(MAX_SCALE, max(MIN_SCALE, value))
        }
    }
}

/**
 * Per-document reading position. Persisted asynchronously; [normalized] protects the
 * reader from stale or corrupt values (for example a page index from a replaced file).
 */
data class PdfReadingProgress(
    val documentId: String,
    val pageIndex: Int,
    val pageCount: Int,
    val zoom: Float = PdfZoom.MIN_SCALE,
    val updatedAtEpochMillis: Long = 0L,
) {
    fun normalized(currentPageCount: Int): PdfReadingProgress? {
        if (documentId.isBlank() || currentPageCount <= 0) return null
        return PdfReadingProgress(
            documentId = documentId,
            pageIndex = pageIndex.coerceIn(0, currentPageCount - 1),
            pageCount = currentPageCount,
            zoom = PdfZoom.clamp(zoom),
            updatedAtEpochMillis = updatedAtEpochMillis,
        )
    }

    val isFirstPage: Boolean get() = pageIndex == 0
    val isLastPage: Boolean get() = pageIndex >= pageCount - 1

    companion object {
        fun first(documentId: String, pageCount: Int, nowMillis: Long = 0L): PdfReadingProgress =
            PdfReadingProgress(documentId, 0, pageCount.coerceAtLeast(1), PdfZoom.MIN_SCALE, nowMillis)
    }
}

/**
 * Restores a saved position without trusting it. Returns null when there is nothing
 * usable; clamps a page that is now out of range; never crashes on corrupt input.
 */
object PdfReadingProgressRules {
    fun restore(progress: PdfReadingProgress?, currentPageCount: Int): PdfReadingProgress? =
        progress?.normalized(currentPageCount)

    fun nextPage(current: PdfReadingProgress?, pageCount: Int): PdfReadingProgress? {
        val normalized = restore(current, pageCount) ?: return null
        return normalized.copy(pageIndex = (normalized.pageIndex + 1).coerceAtMost(pageCount - 1))
    }

    fun previousPage(current: PdfReadingProgress?, pageCount: Int): PdfReadingProgress? {
        val normalized = restore(current, pageCount) ?: return null
        return normalized.copy(pageIndex = (normalized.pageIndex - 1).coerceAtLeast(0))
    }

    fun forPage(documentId: String, pageIndex: Int, pageCount: Int, zoom: Float, nowMillis: Long): PdfReadingProgress? =
        PdfReadingProgress(documentId, pageIndex, pageCount, zoom, nowMillis).normalized(pageCount)
}

/** One page that contains at least one search hit. */
data class PdfSearchPageResult(
    val pageIndex: Int,
    val matchCount: Int,
)

data class PdfSearchState(
    val query: String = "",
    val isSearching: Boolean = false,
    val pages: List<PdfSearchPageResult> = emptyList(),
    val currentResultIndex: Int = 0,
    /** Set when the engine on this Android version cannot search text. */
    val unsupportedReason: String? = null,
    val error: String? = null,
) {
    val totalMatches: Int get() = pages.sumOf { it.matchCount }
    val hasResults: Boolean get() = pages.isNotEmpty()
    val currentResult: PdfSearchPageResult? get() = pages.getOrNull(currentResultIndex)

    val summary: String
        get() = when {
            unsupportedReason != null -> unsupportedReason
            error != null -> error
            isSearching -> "Searching…"
            query.isBlank() -> ""
            pages.isEmpty() -> "No matches"
            totalMatches == 1 -> "1 match"
            else -> "${currentResultIndex + 1} of $totalMatches matches"
        }
}

object PdfSearchRules {
    fun nextResult(state: PdfSearchState): PdfSearchState =
        if (state.pages.isEmpty()) state else state.copy(currentResultIndex = (state.currentResultIndex + 1) % state.pages.size)

    fun previousResult(state: PdfSearchState): PdfSearchState =
        if (state.pages.isEmpty()) state else state.copy(currentResultIndex = (state.currentResultIndex - 1 + state.pages.size) % state.pages.size)

    fun pageForResult(state: PdfSearchState): Int? = state.currentResult?.pageIndex

    fun resultForPage(state: PdfSearchState, pageIndex: Int): Int? =
        state.pages.indexOfFirst { it.pageIndex == pageIndex }.takeIf { it >= 0 }
}

/** Rendering/caching budget for page bitmaps; keeps large documents bounded. */
data class PdfRenderBudget(
    val maxCachedPages: Int = 6,
    val maxCacheBytes: Long = 48L * 1024L * 1024L,
    val maxBitmapDimensionPx: Int = 4096,
    val renderAheadPages: Int = 1,
) {
    init {
        require(maxCachedPages in 1..24)
        require(maxCacheBytes >= 4L * 1024L * 1024L)
        require(maxBitmapDimensionPx in 512..8192)
        require(renderAheadPages in 0..3)
    }

    /** Target bitmap width for a viewport, never exceeding the page bitmap cap. */
    fun targetWidth(viewportWidthPx: Int, pageAspectRatio: Float): Int {
        val width = max(1, viewportWidthPx)
        val height = (width / pageAspectRatio.coerceAtLeast(0.01f)).toInt().coerceAtLeast(1)
        val cappedWidth = min(width, maxBitmapDimensionPx)
        val cappedHeight = min(height, maxBitmapDimensionPx)
        return if (cappedHeight < height) (cappedWidth.toFloat() * cappedHeight / height).toInt().coerceAtLeast(1) else cappedWidth
    }

    fun approximateBitmapBytes(width: Int, height: Int): Long = width.toLong() * height.toLong() * 4L

    fun canCache(currentBytes: Long, nextBytes: Long, currentPages: Int): Boolean =
        currentPages < maxCachedPages && currentBytes + nextBytes <= maxCacheBytes

    fun visiblePageRange(firstVisible: Int, visibleCount: Int, pageCount: Int): IntRange {
        val start = (firstVisible - renderAheadPages).coerceAtLeast(0)
        val end = (firstVisible + visibleCount + renderAheadPages).coerceAtMost(pageCount - 1)
        return if (pageCount <= 0) IntRange.EMPTY else start..end
    }

    companion object {
        fun forDevice(maxHeapBytes: Long): PdfRenderBudget {
            val cache = (maxHeapBytes / 6).coerceIn(12L * 1024L * 1024L, 96L * 1024L * 1024L)
            return PdfRenderBudget(maxCacheBytes = cache)
        }
    }
}
