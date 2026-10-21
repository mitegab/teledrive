package com.teledrive.files.pdf

import android.graphics.Bitmap
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teledrive.core.domain.FileIndexRepository
import com.teledrive.core.domain.toAppError
import com.teledrive.core.model.AppErrorMessages
import com.teledrive.core.model.DownloadSnapshot
import com.teledrive.core.model.ErrorCategory
import com.teledrive.core.model.ErrorOperation
import com.teledrive.core.model.LocalAvailability
import com.teledrive.core.model.PdfFileRules
import com.teledrive.core.model.PdfOutlineEntry
import com.teledrive.core.model.PdfOutlineParser
import com.teledrive.core.model.PdfOutlineResult
import com.teledrive.core.model.PdfPreflight
import com.teledrive.core.model.PdfReadingProgress
import com.teledrive.core.model.PdfReadingProgressRules
import com.teledrive.core.model.PdfRenderBudget
import com.teledrive.core.model.PdfSearchPageResult
import com.teledrive.core.model.PdfSearchRules
import com.teledrive.core.model.PdfSearchState
import com.teledrive.core.model.PdfZoom
import com.teledrive.core.model.StoredFile
import com.teledrive.core.model.TeleDriveLog
import com.teledrive.core.model.TransferProgressPolicy
import com.teledrive.files.DownloadCoordinator
import com.teledrive.files.FileOpenRouter
import com.teledrive.files.LocalFileStorage
import com.teledrive.files.UiNotice
import com.teledrive.preferences.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class PdfReaderPhase { LOADING, PASSWORD_REQUIRED, READY, FAILED }

enum class PdfFitMode { WIDTH, PAGE }

enum class PdfOutlineState { NOT_LOADED, LOADING, LOADED, UNAVAILABLE }

/** Recovery options attached to a failure; only real actions are offered. */
data class PdfReaderFailure(
    val message: String,
    val canRetryDownload: Boolean = false,
    val canOpenExternal: Boolean = false,
    val canRemoveOfflineCopy: Boolean = false,
    val needsPassword: Boolean = false,
)

data class PdfReaderUiState(
    val stableId: String = "",
    val phase: PdfReaderPhase = PdfReaderPhase.LOADING,
    val title: String = "PDF",
    val pageCount: Int = 0,
    /** Width / height of the first page, used for placeholder geometry. */
    val pageAspectRatio: Float = 1f / 1.4142f,
    val pages: Map<Int, Bitmap> = emptyMap(),
    val renderingPages: Set<Int> = emptySet(),
    val currentPage: Int = 0,
    /** Offset into the restored page; consumed once by the scroll surface. */
    val restoredOffsetFraction: Float = 0f,
    val zoom: Float = PdfZoom.MIN_SCALE,
    val fitMode: PdfFitMode = PdfFitMode.WIDTH,
    val offlineAvailable: Boolean = false,
    val failure: PdfReaderFailure? = null,
    val passwordError: String? = null,
    val download: DownloadSnapshot? = null,
    val outline: List<PdfOutlineEntry> = emptyList(),
    val outlineState: PdfOutlineState = PdfOutlineState.NOT_LOADED,
    val search: PdfSearchState = PdfSearchState(),
    /** Highlight rectangles in page points, per page, for the current search. */
    val searchBounds: Map<Int, List<RectF>> = emptyMap(),
    /** Page width in points for each highlighted page, used to scale the rectangles. */
    val searchPageWidths: Map<Int, Int> = emptyMap(),
    val pageErrors: Map<Int, String> = emptyMap(),
    val notice: UiNotice? = null,
) {
    val progressFraction: Float? get() = download?.progressFraction
    val speedLabel: String? get() = download?.speedLabel
    val etaLabel: String? get() = download?.etaLabel
    val transferredLabel: String? get() = download?.transferredLabel
    val hasOutline: Boolean get() = outline.isNotEmpty()
    val canSearch: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
}

/**
 * Owns the one open [PdfDocument] for this screen.
 *
 * Rendering never runs on the main thread, only the pages near the viewport are
 * rendered, the bitmap cache is bounded, and stale render jobs are cancelled when
 * the visible range changes. Closing the screen releases the page cache, the
 * renderer and its file descriptor.
 */
@HiltViewModel
class PdfReaderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: FileIndexRepository,
    private val localStorage: LocalFileStorage,
    private val downloads: DownloadCoordinator,
    private val preferences: PreferencesRepository,
    private val fileOpenRouter: FileOpenRouter,
) : ViewModel() {
    private val stableId: String = Uri.decode(savedStateHandle.get<String>("stableId").orEmpty())
    private val _state = MutableStateFlow(PdfReaderUiState(stableId = stableId))
    val state: StateFlow<PdfReaderUiState> = _state.asStateFlow()

    private var document: PdfDocument? = null
    private var loadedFile: StoredFile? = null
    private var currentOffsetFraction = 0f

    private val budget = PdfRenderBudget.forDevice(Runtime.getRuntime().maxMemory())
    private val renderJobs = ConcurrentHashMap<Int, Job>()
    private val inFlight = HashSet<Int>()
    private val cacheLock = Any()
    private val cache = LinkedHashMap<Int, Bitmap>(8, 0.75f, true)
    private var cacheBytes = 0L
    private var visibleRange: IntRange = IntRange.EMPTY

    private var loadJob: Job? = null
    private var outlineJob: Job? = null
    private var searchJob: Job? = null
    private var persistDebounce: Job? = null
    /** Survives [onCleared] long enough to flush the last reading position. */
    private val persistScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        viewModelScope.launch {
            downloads.statuses.collect { statuses ->
                _state.update { it.copy(download = statuses[stableId]) }
            }
        }
        load()
    }

    // ---- opening --------------------------------------------------------------

    fun load() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            closeDocument()
            _state.value = PdfReaderUiState(stableId = stableId)
            val stored = repository.observeFile(stableId).first()
            if (stored == null) {
                fail(AppErrorMessages.forCategory(ErrorCategory.LOCAL_FILE_MISSING), canOpenExternal = false)
                return@launch
            }
            loadedFile = stored
            _state.update {
                it.copy(
                    title = stored.originalFilename,
                    offlineAvailable = stored.localAvailability == LocalAvailability.AVAILABLE,
                )
            }
            val file = try {
                downloads.download(stored)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = (error as? com.teledrive.files.DownloadException)?.error?.userMessage
                    ?: error.toAppError(ErrorOperation.DOWNLOAD).userMessage
                fail(message, canRetryDownload = true, canOpenExternal = false)
                return@launch
            }
            loadedFile = file
            val path = file.localPath
            if (path.isNullOrBlank()) {
                fail(AppErrorMessages.forCategory(ErrorCategory.LOCAL_FILE_MISSING), canRetryDownload = true)
                return@launch
            }
            val probe = localStorage.probe(path, file.sizeBytes)
            if (!probe.exists || !probe.readable) {
                fail(AppErrorMessages.forCategory(ErrorCategory.LOCAL_FILE_MISSING), canRetryDownload = true)
                return@launch
            }
            val header = localStorage.readHeader(path, PdfFileRules.headerBytesToRead())
            when (PdfFileRules.preflight(probe.sizeBytes, header)) {
                PdfPreflight.EMPTY -> {
                    fail("This PDF is empty (0 bytes).", canRetryDownload = true, canRemoveOfflineCopy = true)
                    return@launch
                }
                PdfPreflight.NOT_PDF -> {
                    fail(
                        "This file does not contain a PDF document, even though its name suggests one.",
                        canOpenExternal = true,
                        canRemoveOfflineCopy = true,
                    )
                    return@launch
                }
                PdfPreflight.OK -> Unit
            }
            _state.update { it.copy(offlineAvailable = true) }
            openDocument(File(path), password = null)
        }
    }

    fun retry() = load()

    /** Stops the in-flight download and leaves a retryable failure instead of an endless spinner. */
    fun cancelLoading() {
        downloads.cancel(stableId)
        loadJob?.cancel()
        _state.update {
            it.copy(
                phase = PdfReaderPhase.FAILED,
                failure = PdfReaderFailure("Loading was canceled.", canRetryDownload = true, canOpenExternal = false),
            )
        }
    }

    fun submitPassword(password: String) {
        if (password.isEmpty()) return
        val file = loadedFile ?: return
        val path = file.localPath ?: return
        viewModelScope.launch {
            _state.update { it.copy(passwordError = null) }
            openDocument(File(path), password = password)
        }
    }

    private suspend fun openDocument(file: File, password: String?) {
        try {
            val opened = withContext(Dispatchers.IO) { PdfDocument.open(file, password) }
            document = opened
            if (opened.pageCount <= 0) {
                opened.close()
                document = null
                fail(AppErrorMessages.forCategory(ErrorCategory.CORRUPT_FILE), canRetryDownload = true)
                return
            }
            val ratio = opened.firstPageWidthPoints.toFloat() / opened.firstPageHeightPoints.toFloat()
            val stored = withContext(Dispatchers.IO) { preferences.pdfProgress(stableId) }
            val restored = PdfReadingProgressRules.restore(stored, opened.pageCount)
            currentOffsetFraction = restored?.scrollOffsetFraction ?: 0f
            _state.update {
                it.copy(
                    phase = PdfReaderPhase.READY,
                    pageCount = opened.pageCount,
                    pageAspectRatio = ratio.coerceIn(0.1f, 10f),
                    currentPage = restored?.pageIndex ?: 0,
                    restoredOffsetFraction = restored?.scrollOffsetFraction ?: 0f,
                    zoom = restored?.zoom ?: PdfZoom.MIN_SCALE,
                    passwordError = null,
                    failure = null,
                )
            }
            loadOutline(file)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (passwordRequired: PdfPasswordRequiredException) {
            _state.update {
                it.copy(
                    phase = PdfReaderPhase.PASSWORD_REQUIRED,
                    passwordError = if (password == null) null else "That password was not accepted. Try again.",
                )
            }
        } catch (unsupported: PdfEncryptionUnsupportedException) {
            fail(
                "This PDF is password protected and this Android version cannot unlock it in the app. Open it with another PDF app.",
                canOpenExternal = true,
            )
        } catch (error: Exception) {
            TeleDriveLog.d("pdf.open_failed", "error" to error.message)
            fail(AppErrorMessages.forCategory(ErrorCategory.PDF_RENDERING_FAILED), canRetryDownload = true, canOpenExternal = true)
        }
    }

    private fun fail(
        message: String,
        canRetryDownload: Boolean = false,
        canOpenExternal: Boolean = true,
        canRemoveOfflineCopy: Boolean = false,
        needsPassword: Boolean = false,
    ) {
        document?.let { doc ->
            doc.close()
            TeleDriveLog.d("pdf.closed", "reason" to "failure", "page_count" to doc.pageCount)
        }
        document = null
        clearCache()
        _state.update {
            it.copy(
                phase = PdfReaderPhase.FAILED,
                failure = PdfReaderFailure(
                    message = message,
                    canRetryDownload = canRetryDownload,
                    canOpenExternal = canOpenExternal,
                    canRemoveOfflineCopy = canRemoveOfflineCopy,
                    needsPassword = needsPassword,
                ),
            )
        }
    }

    // ---- outline --------------------------------------------------------------

    private fun loadOutline(file: File) {
        outlineJob?.cancel()
        _state.update { it.copy(outlineState = PdfOutlineState.LOADING) }
        outlineJob = viewModelScope.launch {
            val result: PdfOutlineResult = withContext(Dispatchers.IO) {
                try {
                    FilePdfByteSource(file).use { source -> PdfOutlineParser().parse(source) }
                } catch (error: Exception) {
                    PdfOutlineResult(diagnostic = error.message)
                }
            }
            if (result.encrypted && result.entries.isEmpty()) {
                _state.update { it.copy(outlineState = PdfOutlineState.UNAVAILABLE) }
                return@launch
            }
            _state.update {
                it.copy(
                    outline = result.entries,
                    outlineState = if (result.hasOutline) PdfOutlineState.LOADED else PdfOutlineState.UNAVAILABLE,
                )
            }
            if (result.diagnostic != null) TeleDriveLog.d("pdf.outline", "diagnostic" to result.diagnostic)
        }
    }

    // ---- rendering ------------------------------------------------------------

    /** Called by each visible page; deduplicated by page index and by cached bytes. */
    fun requestPage(pageIndex: Int, viewportWidthPx: Int) {
        val doc = document ?: return
        val snapshot = _state.value
        if (snapshot.phase != PdfReaderPhase.READY || pageIndex !in 0 until snapshot.pageCount) return
        if (viewportWidthPx <= 0) return
        synchronized(cacheLock) {
            if (cache.containsKey(pageIndex)) return
            if (!inFlight.add(pageIndex)) return
        }
        val targetWidth = budget.targetWidth(viewportWidthPx, snapshot.pageAspectRatio)
        _state.update { it.copy(renderingPages = it.renderingPages + pageIndex, pageErrors = it.pageErrors - pageIndex) }
        val job = viewModelScope.launch(Dispatchers.IO) {
            try {
                val bitmap = doc.renderPage(pageIndex, targetWidth, budget.maxBitmapDimensionPx)
                ensureActive()
                if (document !== doc) return@launch
                insertBitmap(pageIndex, bitmap)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                TeleDriveLog.d("pdf.render_failed", "page" to pageIndex, "error" to error.message)
                _state.update {
                    it.copy(pageErrors = it.pageErrors + (pageIndex to "This page could not be rendered. Try scrolling away and back."))
                }
            } finally {
                synchronized(cacheLock) { inFlight.remove(pageIndex) }
                currentCoroutineContext()[Job]?.let { running -> renderJobs.remove(pageIndex, running) }
                _state.update { it.copy(renderingPages = it.renderingPages - pageIndex) }
            }
        }
        renderJobs[pageIndex] = job
    }

    private fun insertBitmap(pageIndex: Int, bitmap: Bitmap) {
        val published: Map<Int, Bitmap>
        synchronized(cacheLock) {
            cache.remove(pageIndex)?.let { cacheBytes -= sizeOf(it) }
            cache[pageIndex] = bitmap
            cacheBytes += sizeOf(bitmap)
            val keep = expand(visibleRange)
            while ((cache.size > budget.maxCachedPages || cacheBytes > budget.maxCacheBytes) && cache.size > 1) {
                val victim = cache.entries.firstOrNull { it.key !in keep }?.key ?: break
                val removed = cache.remove(victim) ?: break
                cacheBytes -= sizeOf(removed)
            }
            published = LinkedHashMap(cache)
        }
        _state.update { it.copy(pages = published) }
    }

    /** Called when the visible page window changes; cancels stale work and trims memory. */
    fun onVisiblePages(firstVisible: Int, lastVisible: Int) {
        val snapshot = _state.value
        if (snapshot.phase != PdfReaderPhase.READY) return
        val count = (lastVisible - firstVisible + 1).coerceAtLeast(1)
        val range = budget.visiblePageRange(firstVisible, count, snapshot.pageCount)
        visibleRange = range
        renderJobs.forEach { (page, job) -> if (page !in range) job.cancel() }
        val published: Map<Int, Bitmap>
        synchronized(cacheLock) {
            val keep = expand(range)
            val evicted = cache.keys.filter { it !in keep }
            evicted.forEach { page ->
                cache.remove(page)?.let { cacheBytes -= sizeOf(it) }
            }
            published = LinkedHashMap(cache)
        }
        _state.update {
            if (it.pages.keys == published.keys) it
            else it.copy(pages = published)
        }
    }

    private fun expand(range: IntRange): Set<Int> {
        if (range.isEmpty()) return emptySet()
        return (range.first - budget.renderAheadPages..range.last + budget.renderAheadPages).toSet()
    }

    /** Called when a newer render failed and the user asked for another attempt. */
    fun retryPage(pageIndex: Int, viewportWidthPx: Int) {
        _state.update { it.copy(pageErrors = it.pageErrors - pageIndex) }
        synchronized(cacheLock) { cache.remove(pageIndex)?.let { cacheBytes -= sizeOf(it) } }
        requestPage(pageIndex, viewportWidthPx)
    }

    // ---- page position / zoom / progress --------------------------------------

    fun onPageVisible(pageIndex: Int, offsetFraction: Float) {
        currentOffsetFraction = offsetFraction.coerceIn(0f, 0.95f)
        if (_state.value.currentPage != pageIndex) _state.update { it.copy(currentPage = pageIndex) }
        schedulePersist()
    }

    fun setZoom(scale: Float) {
        val next = PdfZoom(scale).coerced()
        if (next.scale == _state.value.zoom) return
        _state.update { it.copy(zoom = next.scale) }
        schedulePersist()
    }

    fun zoomIn() = setZoom(_state.value.zoom * 1.5f)
    fun zoomOut() = setZoom(_state.value.zoom / 1.5f)

    fun fitWidth() {
        _state.update { it.copy(fitMode = PdfFitMode.WIDTH, zoom = PdfZoom.MIN_SCALE) }
        schedulePersist()
    }

    fun fitPage() {
        _state.update { it.copy(fitMode = PdfFitMode.PAGE, zoom = PdfZoom.MIN_SCALE) }
        schedulePersist()
    }

    fun resetGesturePan() = Unit

    private fun schedulePersist() {
        persistDebounce?.cancel()
        persistDebounce = viewModelScope.launch {
            delay(600)
            saveProgressNow()
        }
    }

    private fun saveProgressNow() {
        val snapshot = _state.value
        if (snapshot.phase != PdfReaderPhase.READY || snapshot.pageCount <= 0) return
        val progress: PdfReadingProgress = PdfReadingProgressRules.forPage(
            documentId = stableId,
            pageIndex = snapshot.currentPage,
            pageCount = snapshot.pageCount,
            zoom = snapshot.zoom,
            nowMillis = System.currentTimeMillis(),
            scrollOffsetFraction = currentOffsetFraction,
        ) ?: return
        persistScope.launch {
            try {
                preferences.setPdfProgress(progress)
            } catch (error: Exception) {
                TeleDriveLog.d("pdf.progress_failed", "error" to error.message)
            }
        }
    }

    // ---- search ---------------------------------------------------------------

    fun search(rawQuery: String) {
        val query = rawQuery.trim()
        searchJob?.cancel()
        val doc = document ?: return
        if (query.length < 2) {
            _state.update { it.copy(search = PdfSearchState(), searchBounds = emptyMap(), searchPageWidths = emptyMap()) }
            return
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            _state.update {
                it.copy(
                    search = PdfSearchState(
                        query = query,
                        unsupportedReason = "In-app text search needs Android 15 or newer. This device can still render the PDF.",
                    ),
                    searchBounds = emptyMap(),
                    searchPageWidths = emptyMap(),
                )
            }
            return
        }
        searchJob = viewModelScope.launch {
            _state.update { it.copy(search = PdfSearchState(query = query, isSearching = true), searchBounds = emptyMap(), searchPageWidths = emptyMap()) }
            val pages = mutableListOf<PdfSearchPageResult>()
            val bounds = mutableMapOf<Int, List<RectF>>()
            val widths = mutableMapOf<Int, Int>()
            try {
                for (page in 0 until doc.pageCount) {
                    currentCoroutineContext().ensureActive()
                    val hits = withContext(Dispatchers.IO) { doc.searchPage(page, query) }
                    currentCoroutineContext().ensureActive()
                    if (hits.matchCount > 0) {
                        pages += PdfSearchPageResult(page, hits.matchCount)
                        bounds[page] = hits.bounds
                        widths[page] = hits.pageWidthPoints
                    }
                    if (page % 8 == 0 || page == doc.pageCount - 1) {
                        val partial = pages.toList()
                        val partialBounds = bounds.toMap()
                        val partialWidths = widths.toMap()
                        _state.update {
                            it.copy(
                                search = it.search.copy(pages = partial, currentResultIndex = 0),
                                searchBounds = partialBounds,
                                searchPageWidths = partialWidths,
                            )
                        }
                    }
                }
                _state.update {
                    it.copy(search = it.search.copy(isSearching = false, pages = pages.toList(), currentResultIndex = 0))
                }
                TeleDriveLog.d("pdf.search", "pages" to pages.size, "matches" to pages.sumOf { it.matchCount })
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                TeleDriveLog.d("pdf.search_failed", "error" to error.message)
                _state.update {
                    it.copy(search = it.search.copy(isSearching = false, error = "Search stopped because the document became unreadable."))
                }
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _state.update { it.copy(search = PdfSearchState(), searchBounds = emptyMap(), searchPageWidths = emptyMap()) }
    }

    /** Returns the page to scroll to, or null when there are no results. */
    fun nextSearchResult(): Int? {
        val next = PdfSearchRules.nextResult(_state.value.search)
        _state.update { it.copy(search = next) }
        return PdfSearchRules.pageForResult(next)
    }

    fun previousSearchResult(): Int? {
        val previous = PdfSearchRules.previousResult(_state.value.search)
        _state.update { it.copy(search = previous) }
        return PdfSearchRules.pageForResult(previous)
    }

    // ---- file actions ---------------------------------------------------------

    fun openExternal(share: Boolean) {
        val file = loadedFile ?: return
        viewModelScope.launch {
            try {
                fileOpenRouter.open(file, share)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update {
                    it.copy(notice = UiNotice(error.toAppError(ErrorOperation.STORAGE).userMessage, isError = true))
                }
            }
        }
    }

    fun removeOfflineCopy() {
        val file = loadedFile ?: return
        viewModelScope.launch {
            try {
                downloads.removeLocalCopy(file)
                _state.update {
                    it.copy(
                        offlineAvailable = false,
                        notice = UiNotice("Removed the offline copy of ${file.originalFilename}"),
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update {
                    it.copy(notice = UiNotice(error.toAppError(ErrorOperation.STORAGE).userMessage, isError = true))
                }
            }
        }
    }

    fun dismissNotice(id: Long) = _state.update { current ->
        if (current.notice?.id == id) current.copy(notice = null) else current
    }

    // ---- cleanup --------------------------------------------------------------

    private fun closeDocument() {
        val doc = document ?: return
        document = null
        doc.close()
        TeleDriveLog.d("pdf.closed", "page_count" to doc.pageCount)
    }

    private fun clearCache() {
        synchronized(cacheLock) {
            cache.clear()
            cacheBytes = 0L
        }
        _state.update { it.copy(pages = emptyMap(), renderingPages = emptySet()) }
    }

    private fun sizeOf(bitmap: Bitmap): Long = bitmap.allocationByteCount.toLong()

    override fun onCleared() {
        renderJobs.values.forEach { it.cancel() }
        loadJob?.cancel()
        outlineJob?.cancel()
        searchJob?.cancel()
        persistDebounce?.cancel()
        // Flush the last position after the ViewModel scope is gone; the write is tiny.
        saveProgressNow()
        closeDocument()
        clearCache()
        super.onCleared()
    }
}
