package com.teledrive.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfModelsTest {

    @Test
    fun pdfClassificationUsesMimeThenExtension() {
        assertTrue(PdfFileRules.isPdf("report.pdf", null))
        assertTrue(PdfFileRules.isPdf("REPORT.PDF", "application/octet-stream"))
        assertTrue(PdfFileRules.isPdf("document", "application/pdf"))
        assertFalse(PdfFileRules.isPdf("notes.txt", "text/plain"))
        assertFalse(PdfFileRules.isPdf(null, null))
        assertFalse(PdfFileRules.isPdf("pdf", null))
    }

    @Test
    fun preflightRejectsEmptyAndNonPdfFilesBeforeRendering() {
        assertEquals(PdfPreflight.EMPTY, PdfFileRules.preflight(0L, null))
        assertEquals(PdfPreflight.NOT_PDF, PdfFileRules.preflight(10L, "not a pdf".toByteArray()))
        assertEquals(PdfPreflight.OK, PdfFileRules.preflight(10L, "%PDF-1.7".toByteArray()))
        assertEquals(PdfPreflight.OK, PdfFileRules.preflight(null, null))
    }

    @Test
    fun zoomStaysWithinUsableBounds() {
        assertEquals(PdfZoom.MIN_SCALE, PdfZoom(-3f).coerced().scale)
        assertEquals(PdfZoom.MAX_SCALE, PdfZoom(99f).coerced().scale)
        assertEquals(2f, PdfZoom(1f).zoomedBy(2f).scale)
        assertEquals(PdfZoom.MAX_SCALE, PdfZoom(5f).zoomedBy(4f).scale)
        assertTrue(PdfZoom(Float.NaN).coerced().isAtMinimum)
        assertTrue(PdfZoom(PdfZoom.MAX_SCALE).isAtMaximum)
    }

    @Test
    fun readingProgressKeepsASafeScrollOffset() {
        val weird = PdfReadingProgress("1:2", 3, 10, scrollOffsetFraction = 2.5f).normalized(10)!!
        assertTrue(weird.scrollOffsetFraction in 0f..0.95f)
        val nan = PdfReadingProgress("1:2", 3, 10, scrollOffsetFraction = Float.NaN).normalized(10)!!
        assertEquals(0f, nan.scrollOffsetFraction)
        val saved = PdfReadingProgressRules.forPage("1:2", 4, 10, 2f, 5L, 0.4f)!!
        assertEquals(4, saved.pageIndex)
        assertEquals(0.4f, saved.scrollOffsetFraction)
    }

    @Test
    fun readingProgressIsClampedToTheCurrentDocument() {
        val stored = PdfReadingProgress("1:2", pageIndex = 99, pageCount = 40, zoom = 12f)
        val normalized = stored.normalized(currentPageCount = 20)!!
        assertEquals(19, normalized.pageIndex)
        assertEquals(20, normalized.pageCount)
        assertEquals(PdfZoom.MAX_SCALE, normalized.zoom)

        val negative = PdfReadingProgress("1:2", pageIndex = -5, pageCount = 3).normalized(3)!!
        assertEquals(0, negative.pageIndex)
    }

    @Test
    fun invalidStoredProgressIsIgnoredInsteadOfCrashing() {
        assertNull(PdfReadingProgress("", 2, 3).normalized(3))
        assertNull(PdfReadingProgress("1:1", 2, 3).normalized(0))
        assertNull(PdfReadingProgressRules.restore(null, 10))

        val restored = PdfReadingProgressRules.restore(PdfReadingProgress("1:1", 4, 50), 10)!!
        assertEquals(4, restored.pageIndex)
        assertEquals(10, restored.pageCount)
    }

    @Test
    fun pageNavigationNeverLeavesTheDocument() {
        val progress = PdfReadingProgress("1:1", 0, 3)
        assertEquals(1, PdfReadingProgressRules.nextPage(progress, 3)!!.pageIndex)
        assertEquals(0, PdfReadingProgressRules.previousPage(progress, 3)!!.pageIndex)

        val last = PdfReadingProgress("1:1", 2, 3)
        assertEquals(2, PdfReadingProgressRules.nextPage(last, 3)!!.pageIndex)
        assertEquals(1, PdfReadingProgressRules.previousPage(last, 3)!!.pageIndex)
        assertNull(PdfReadingProgressRules.nextPage(null, 0))
    }

    @Test
    fun searchStateReportsCountsAndWrapsNavigation() {
        val empty = PdfSearchState(query = "invoice")
        assertEquals("No matches", empty.summary)

        val searching = empty.copy(isSearching = true)
        assertEquals("Searching…", searching.summary)

        val unsupported = PdfSearchState(unsupportedReason = "Text search needs Android 15 or newer.")
        assertEquals("Text search needs Android 15 or newer.", unsupported.summary)

        val results = PdfSearchState(
            query = "invoice",
            pages = listOf(PdfSearchPageResult(2, 2), PdfSearchPageResult(7, 3)),
        )
        assertEquals(5, results.totalMatches)
        assertEquals(2, PdfSearchRules.pageForResult(results))
        assertEquals(1, PdfSearchRules.nextResult(results).currentResultIndex)
        assertEquals(1, PdfSearchRules.previousResult(results).currentResultIndex)
        assertEquals("1 of 5 matches", results.summary)
        assertEquals(1, PdfSearchRules.resultForPage(results, 7))
        assertNull(PdfSearchRules.resultForPage(results, 9))
        assertNull(PdfSearchRules.pageForResult(PdfSearchState()))
    }

    @Test
    fun renderBudgetBoundsCacheAndBitmapSizes() {
        val budget = PdfRenderBudget(maxCachedPages = 4, maxCacheBytes = 16L * 1024 * 1024, maxBitmapDimensionPx = 2048)
        assertTrue(budget.canCache(0, 4L * 1024 * 1024, 0))
        assertFalse(budget.canCache(0, 20L * 1024 * 1024, 0))
        assertFalse(budget.canCache(16L * 1024 * 1024, 1, 3))

        assertEquals(1080, budget.targetWidth(1080, 0.75f))
        val capped = budget.targetWidth(4000, 1f)
        assertTrue(capped <= 2048)

        assertEquals(0..2, budget.visiblePageRange(0, 1, 10))
        assertEquals(8..9, budget.visiblePageRange(9, 1, 10))
        assertEquals(IntRange.EMPTY, budget.visiblePageRange(0, 3, 0))
    }

    @Test
    fun deviceBudgetsStayWithinSaneBounds() {
        val tiny = PdfRenderBudget.forDevice(64L * 1024 * 1024)
        assertTrue(tiny.maxCacheBytes in 12L * 1024 * 1024..96L * 1024 * 1024)
        val large = PdfRenderBudget.forDevice(2L * 1024 * 1024 * 1024)
        assertTrue(large.maxCacheBytes <= 96L * 1024 * 1024)
    }

    @Test
    fun invalidPageGeometryIsRejected() {
        runCatching { PdfPageGeometry(0f, 10f) }.also { assertTrue(it.isFailure) }
        assertEquals(0.75f, PdfPageGeometry(600f, 800f).aspectRatio, 0.0001f)
    }

    @Test
    fun outlineEntriesOnlyNavigateWhenTheyHaveAPage() {
        val entry = PdfOutlineEntry("Chapter 1", pageIndex = 3)
        assertTrue(entry.isNavigable)
        assertFalse(PdfOutlineEntry("Appendix").isNavigable)
        assertFalse(PdfOutlineResult(entries = emptyList()).hasOutline)
        assertTrue(PdfOutlineResult(entries = listOf(entry)).hasOutline)
    }
}
