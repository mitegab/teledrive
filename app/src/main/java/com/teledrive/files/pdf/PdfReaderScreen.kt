package com.teledrive.files.pdf

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OfflinePin
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teledrive.core.model.PdfOutlineEntry
import kotlinx.coroutines.delay
import kotlin.math.min

private val ReaderBackground = Color(0xFF101014)
private val PageBackground = Color(0xFFFFFFFF)
private val PlaceholderBackground = Color(0xFF1C1C22)
private val HighlightColor = Color(0x66FFD54F)

/**
 * In-app PDF reader built on the platform [android.graphics.pdf.PdfRenderer].
 *
 * Only pages near the viewport are rendered, bitmaps are bounded by
 * [com.teledrive.core.model.PdfRenderBudget], rendering runs on IO threads and the
 * renderer/file descriptor are released when the ViewModel is cleared.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfReaderScreen(
    onBack: () -> Unit,
    viewModel: PdfReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.notice?.id) {
        val notice = state.notice ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(notice.message)
        viewModel.dismissNotice(notice.id)
    }

    var showSearch by rememberSaveable { mutableStateOf(false) }
    var searchText by rememberSaveable { mutableStateOf("") }
    var jumpOnFirstResult by remember { mutableStateOf(false) }
    var showOutline by remember { mutableStateOf(false) }
    var showJumpDialog by remember { mutableStateOf(false) }
    var jumpText by rememberSaveable { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    var pendingPage by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(state.search.isSearching, state.search.pages.size) {
        if (jumpOnFirstResult && !state.search.isSearching && state.search.hasResults) {
            pendingPage = state.search.currentResult?.pageIndex
            jumpOnFirstResult = false
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, contentDescription = "Back") }
                },
                title = {
                    Column {
                        Text(state.title, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                if (state.pageCount > 0) "Page ${state.currentPage + 1} of ${state.pageCount}" else "PDF",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (state.offlineAvailable) {
                                Icon(
                                    Icons.Rounded.OfflinePin,
                                    contentDescription = "Available offline",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp),
                                )
                                Text(
                                    "Offline",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            showSearch = !showSearch
                            if (!showSearch) viewModel.clearSearch()
                        },
                        enabled = state.phase == PdfReaderPhase.READY,
                    ) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = if (state.canSearch) "Search in PDF" else "Search needs Android 15 or newer",
                        )
                    }
                    IconButton(onClick = { showOutline = true }, enabled = state.phase == PdfReaderPhase.READY) {
                        Icon(Icons.Rounded.Menu, contentDescription = "Outline")
                    }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "More actions")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Fit width") },
                                onClick = { viewModel.fitWidth(); showMenu = false },
                                enabled = state.phase == PdfReaderPhase.READY,
                            )
                            DropdownMenuItem(
                                text = { Text("Fit page") },
                                onClick = { viewModel.fitPage(); showMenu = false },
                                enabled = state.phase == PdfReaderPhase.READY,
                            )
                            DropdownMenuItem(
                                text = { Text("Zoom in") },
                                onClick = { viewModel.zoomIn(); showMenu = false },
                                enabled = state.phase == PdfReaderPhase.READY,
                            )
                            DropdownMenuItem(
                                text = { Text("Zoom out") },
                                onClick = { viewModel.zoomOut(); showMenu = false },
                                enabled = state.phase == PdfReaderPhase.READY,
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text("Go to page") },
                                onClick = { showJumpDialog = true; showMenu = false },
                                enabled = state.phase == PdfReaderPhase.READY,
                            )
                            DropdownMenuItem(
                                text = { Text("Open with another app") },
                                onClick = { viewModel.openExternal(share = false); showMenu = false },
                                leadingIcon = { Icon(Icons.Rounded.OpenInNew, contentDescription = null) },
                            )
                            DropdownMenuItem(
                                text = { Text("Share") },
                                onClick = { viewModel.openExternal(share = true); showMenu = false },
                                leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                            )
                            DropdownMenuItem(
                                text = { Text("File details") },
                                onClick = { showDetails = true; showMenu = false },
                                leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null) },
                            )
                            if (state.offlineAvailable) {
                                DropdownMenuItem(
                                    text = { Text("Remove offline copy") },
                                    onClick = { viewModel.removeOfflineCopy(); showMenu = false },
                                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (showSearch && state.phase == PdfReaderPhase.READY) {
                PdfSearchBar(
                    query = searchText,
                    onQueryChange = { searchText = it },
                    state = state,
                    onSearch = {
                        jumpOnFirstResult = true
                        viewModel.search(searchText)
                    },
                    onPrevious = { pendingPage = viewModel.previousSearchResult() },
                    onNext = { pendingPage = viewModel.nextSearchResult() },
                    onClose = {
                        showSearch = false
                        searchText = ""
                        viewModel.clearSearch()
                    },
                )
            }
            Box(modifier = Modifier.fillMaxSize()) {
                when (state.phase) {
                    PdfReaderPhase.LOADING -> PdfLoadingPane(state = state, onCancel = viewModel::cancelLoading)
                    PdfReaderPhase.PASSWORD_REQUIRED -> PdfPasswordPane(
                        error = state.passwordError,
                        onSubmit = viewModel::submitPassword,
                        onOpenExternal = { viewModel.openExternal(share = false) },
                    )
                    PdfReaderPhase.FAILED -> PdfFailurePane(
                        failure = state.failure,
                        onRetry = viewModel::retry,
                        onOpenExternal = { viewModel.openExternal(share = false) },
                        onRemoveOffline = viewModel::removeOfflineCopy,
                        onBack = onBack,
                    )
                    PdfReaderPhase.READY -> PdfContent(
                        state = state,
                        viewModel = viewModel,
                        pendingPage = pendingPage,
                        onPendingPageConsumed = { pendingPage = null },
                    )
                }
            }
        }
    }

    if (showJumpDialog) {
        val requestedPage = jumpText.toIntOrNull()
        val validPage = requestedPage != null && requestedPage in 1..state.pageCount
        AlertDialog(
            onDismissRequest = { showJumpDialog = false },
            title = { Text("Go to page") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = jumpText,
                        onValueChange = { value -> jumpText = value.filter(Char::isDigit).take(7) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            jumpText.toIntOrNull()?.let { page ->
                                if (page in 1..state.pageCount) {
                                    pendingPage = page - 1
                                    showJumpDialog = false
                                    jumpText = ""
                                }
                            }
                        }),
                        label = { Text("Page number (1–${state.pageCount.coerceAtLeast(1)})") },
                        isError = jumpText.isNotEmpty() && !validPage,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = validPage,
                    onClick = {
                        jumpText.toIntOrNull()?.let { page ->
                            pendingPage = page - 1
                            showJumpDialog = false
                            jumpText = ""
                        }
                    },
                ) { Text("Go") }
            },
            dismissButton = { TextButton(onClick = { showJumpDialog = false }) { Text("Cancel") } },
        )
    }

    if (showOutline) {
        ModalBottomSheet(onDismissRequest = { showOutline = false }) {
            PdfOutlineSheet(
                state = state,
                onNavigate = { page ->
                    pendingPage = page
                    showOutline = false
                },
            )
        }
    }

    if (showDetails) {
        val sizeLabel = state.download?.totalBytes?.let(::formatBytes) ?: "size unknown"
        AlertDialog(
            onDismissRequest = { showDetails = false },
            title = { Text("File details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(state.title, style = MaterialTheme.typography.titleSmall)
                    Text("Pages: ${state.pageCount}", style = MaterialTheme.typography.bodyMedium)
                    Text("Size: $sizeLabel", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (state.offlineAvailable) "Saved on this device and readable without Telegram." else "Not saved on this device yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Rendered by the Android PDF engine. Text search: ${if (state.canSearch) "available" else "not available on this Android version"}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showDetails = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun PdfLoadingPane(state: PdfReaderUiState, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(18.dp))
        Text("Preparing “${state.title}”", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        val download = state.download
        if (download?.isActive == true) {
            LinearProgressIndicator(progress = { download.progressFraction ?: 0f }, modifier = Modifier.fillMaxWidth(0.8f))
            Spacer(Modifier.height(8.dp))
            Text(
                listOfNotNull(download.transferredLabel, download.totalBytes?.let(::formatBytes), download.speedLabel, download.etaLabel)
                    .joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text("Downloading for offline reading…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(18.dp))
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun PdfPasswordPane(error: String?, onSubmit: (String) -> Unit, onOpenExternal: () -> Unit) {
    // Never saved or logged; the field lives only in composition memory.
    var password by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(12.dp))
        Text("This PDF is password protected", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Enter the document password. It is used only to open this file and is never stored.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (password.isNotEmpty()) onSubmit(password) }),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Button(onClick = { onSubmit(password) }, enabled = password.isNotEmpty()) { Text("Unlock") }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onOpenExternal) { Text("Open with another app") }
    }
}

@Composable
private fun PdfFailurePane(
    failure: PdfReaderFailure?,
    onRetry: () -> Unit,
    onOpenExternal: () -> Unit,
    onRemoveOffline: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(failure?.message ?: "This PDF could not be opened.", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(18.dp))
        if (failure?.canRetryDownload == true) {
            Button(onClick = onRetry) { Icon(Icons.Rounded.Refresh, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Try again") }
        }
        if (failure?.canOpenExternal == true) {
            TextButton(onClick = onOpenExternal) { Text("Open with another app") }
        }
        if (failure?.canRemoveOfflineCopy == true) {
            TextButton(onClick = onRemoveOffline) { Text("Remove offline copy") }
        }
        TextButton(onClick = onBack) { Text("Back to files") }
    }
}

@Composable
private fun PdfSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    state: PdfReaderUiState,
    onSearch: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Search text") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    trailingIcon = {
                        if (state.search.isSearching) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    },
                )
                IconButton(onClick = onPrevious, enabled = state.search.hasResults) {
                    Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Previous match")
                }
                IconButton(onClick = onNext, enabled = state.search.hasResults) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Next match")
                }
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = "Close search") }
            }
            if (state.search.summary.isNotBlank()) {
                Text(
                    state.search.summary,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfOutlineSheet(state: PdfReaderUiState, onNavigate: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
        Text("Outline", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(10.dp))
        when (state.outlineState) {
            PdfOutlineState.LOADING, PdfOutlineState.NOT_LOADED -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Reading the document outline…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            PdfOutlineState.UNAVAILABLE -> Text(
                "This PDF has no navigable outline, or its outline could not be read.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PdfOutlineState.LOADED -> LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                items(count = state.outline.size, key = { index -> index }) { index ->
                    val entry = state.outline[index]
                    PdfOutlineRow(entry = entry, onNavigate = onNavigate)
                }
            }
        }
    }
}

@Composable
private fun PdfOutlineRow(entry: PdfOutlineEntry, onNavigate: (Int) -> Unit) {
    TextButton(
        onClick = { entry.pageIndex?.let(onNavigate) },
        enabled = entry.isNavigable,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            entry.title,
            modifier = Modifier.padding(start = (entry.depth * 14).dp).fillMaxWidth(),
            fontWeight = if (entry.depth == 0) FontWeight.Medium else FontWeight.Normal,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun PdfContent(
    state: PdfReaderUiState,
    viewModel: PdfReaderViewModel,
    pendingPage: Int?,
    onPendingPageConsumed: () -> Unit,
) {
    val firstPage = state.currentPage.coerceIn(0, (state.pageCount - 1).coerceAtLeast(0))
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = firstPage)
    val density = LocalDensity.current
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var restored by remember { mutableStateOf(false) }

    LaunchedEffect(pendingPage) {
        pendingPage?.let { page ->
            listState.scrollToItem(page.coerceIn(0, (state.pageCount - 1).coerceAtLeast(0)))
            onPendingPageConsumed()
        }
    }
    LaunchedEffect(state.pageCount) {
        if (!restored && state.restoredOffsetFraction > 0f) {
            delay(220)
            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull()
            if (info != null) {
                listState.scrollToItem(info.index, (info.size * state.restoredOffsetFraction).toInt())
            }
        }
        restored = true
    }
    LaunchedEffect(state.zoom) {
        if (state.zoom <= 1f) {
            panX = 0f
            panY = 0f
        }
    }

    val firstVisible by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    val visibleCount by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.size } }
    LaunchedEffect(firstVisible, visibleCount, state.pageCount) {
        if (state.pageCount > 0) {
            viewModel.onVisiblePages(firstVisible, (firstVisible + visibleCount - 1).coerceAtLeast(firstVisible))
        }
    }
    LaunchedEffect(firstVisible) {
        viewModel.onPageVisible(firstVisible, 0f)
    }
    LaunchedEffect(listState.isScrollInProgress, firstVisible) {
        if (!listState.isScrollInProgress) {
            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == firstVisible }
            val fraction = if (info != null && info.size > 0) {
                (listState.firstVisibleItemScrollOffset.toFloat() / info.size).coerceIn(0f, 0.95f)
            } else 0f
            viewModel.onPageVisible(firstVisible, fraction)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(ReaderBackground)) {
        val pageWidth = maxWidth
        val pageHeight: Dp = if (state.fitMode == PdfFitMode.PAGE) {
            maxHeight
        } else {
            (pageWidth / state.pageAspectRatio.coerceAtLeast(0.1f)).coerceAtLeast(1.dp)
        }
        val widthPx = with(density) { pageWidth.toPx().toInt() }
        val heightPx = with(density) { pageHeight.toPx() }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(count = state.pageCount, key = { index -> index }) { index ->
                PdfPage(
                    index = index,
                    bitmap = state.pages[index],
                    pageError = state.pageErrors[index],
                    pageHeight = pageHeight,
                    widthPx = widthPx,
                    aspectRatio = state.pageAspectRatio,
                    fitMode = state.fitMode,
                    zoom = state.zoom,
                    pan = Offset(panX, panY),
                    highlights = state.searchBounds[index].orEmpty(),
                    pageWidthPoints = state.searchPageWidths[index] ?: 0,
                    onRequest = { viewModel.requestPage(index, widthPx) },
                    onRetry = { viewModel.retryPage(index, widthPx) },
                    onPan = { delta ->
                        val maxX = (widthPx * (state.zoom - 1f) / 2f).coerceAtLeast(0f)
                        val maxY = (heightPx * (state.zoom - 1f) / 2f).coerceAtLeast(0f)
                        panX = if (state.zoom > 1f) (panX + delta.x).coerceIn(-maxX, maxX) else 0f
                        panY = if (state.zoom > 1f) (panY + delta.y).coerceIn(-maxY, maxY) else 0f
                    },
                    onZoom = { factor -> viewModel.setZoom((state.zoom * factor).coerceIn(1f, 6f)) },
                    onDoubleTap = {
                        if (state.zoom > 1f) {
                            viewModel.setZoom(1f)
                        } else {
                            viewModel.setZoom(2.5f)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun PdfPage(
    index: Int,
    bitmap: Bitmap?,
    pageError: String?,
    pageHeight: Dp,
    widthPx: Int,
    aspectRatio: Float,
    fitMode: PdfFitMode,
    zoom: Float,
    pan: Offset,
    highlights: List<RectF>,
    pageWidthPoints: Int,
    onRequest: () -> Unit,
    onRetry: () -> Unit,
    onPan: (Offset) -> Unit,
    onZoom: (Float) -> Unit,
    onDoubleTap: () -> Unit,
) {
    LaunchedEffect(index, widthPx) { onRequest() }
    val gestureModifier = if (zoom > 1f) {
        Modifier.pointerInput(zoom) {
            detectTransformGestures { _, gesturePan, gestureZoom, _ ->
                onPan(gesturePan)
                if (gestureZoom != 1f) onZoom(gestureZoom)
            }
        }
    } else {
        Modifier
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(pageHeight)
            .background(PlaceholderBackground)
            .clipToBounds()
            .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onDoubleTap() }) }
            .then(gestureModifier),
        contentAlignment = Alignment.Center,
    ) {
        when {
            bitmap != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = pan.x, translationY = pan.y),
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Page ${index + 1}",
                        contentScale = if (fitMode == PdfFitMode.WIDTH) ContentScale.FillWidth else ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (highlights.isNotEmpty() && pageWidthPoints > 0) {
                        Canvas(Modifier.fillMaxSize()) {
                            val contentWidth = if (fitMode == PdfFitMode.WIDTH) size.width else min(size.width, size.height * aspectRatio)
                            val contentHeight = contentWidth / aspectRatio.coerceAtLeast(0.01f)
                            val offsetX = (size.width - contentWidth) / 2f
                            val offsetY = (size.height - contentHeight) / 2f
                            val scale = contentWidth / pageWidthPoints
                            highlights.forEach { rect ->
                                drawRect(
                                    color = HighlightColor,
                                    topLeft = Offset(offsetX + rect.left * scale, offsetY + rect.top * scale),
                                    size = Size(rect.width() * scale, rect.height() * scale),
                                )
                            }
                        }
                    }
                }
            }
            pageError != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pageError, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onRetry) { Text("Retry page") }
            }
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.dp)
                Text("Page ${index + 1}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB9B9C3))
            }
        }
        if (bitmap != null && zoom > 1f) {
            Surface(
                color = Color(0xAA000000),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Text(
                    "${(zoom * 100).toInt()}%",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024} KB"
    bytes < 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
    else -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
}
