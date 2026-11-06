package com.teledrive.playback.ui.amplify

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teledrive.core.model.StoredFile
import com.teledrive.playback.MediaPlayerViewModel
import com.teledrive.playback.PlaybackStatus
import com.teledrive.playback.ShuffleMode
import kotlinx.coroutines.launch

/**
 * Amplify-style audio workspace: persistent rail (brand, sections, mood selector, genre
 * filters), playlist header with cover art, sortable track list, and a waveform timeline bar.
 *
 * Wide screens get the rail permanently; phones get it behind a drawer while the same
 * sections, filters and timeline stay available.
 */
@Composable
fun AmplifyAudioPlayerScreen(
    viewModel: MediaPlayerViewModel,
    onBack: () -> Unit,
    stableId: String? = null,
    allIds: List<String> = emptyList(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(stableId) {
        if (stableId != null) viewModel.playFile(stableId, allIds)
    }

    // The workspace is always dark, so keep the system bars readable while it is on screen
    // (light theme otherwise asks for dark status-bar icons).
    val view = LocalView.current
    DisposableEffect(view) {
        val window = view.context.findActivity()?.window
        if (window == null) {
            onDispose { }
        } else {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            val previousStatus = controller.isAppearanceLightStatusBars
            val previousNavigation = controller.isAppearanceLightNavigationBars
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
            onDispose {
                controller.isAppearanceLightStatusBars = previousStatus
                controller.isAppearanceLightNavigationBars = previousNavigation
            }
        }
    }

    val queue = state.queue
    val player = state.player
    val current = queue.currentItem
    val artworkFor: suspend (String) -> ImageBitmap? = remember(viewModel) { viewModel::artwork }

    var sectionName by rememberSaveable { mutableStateOf(AmplifySection.DISCOVER.name) }
    val section = runCatching { AmplifySection.valueOf(sectionName) }.getOrDefault(AmplifySection.DISCOVER)
    var sortName by rememberSaveable { mutableStateOf(AmplifySort.CUSTOM.name) }
    val sort = runCatching { AmplifySort.valueOf(sortName) }.getOrDefault(AmplifySort.CUSTOM)
    var moodName by rememberSaveable { mutableStateOf<String?>(null) }
    val mood = moodName?.let { name -> runCatching { AmplifyMood.valueOf(name) }.getOrNull() }
    var genreNames by rememberSaveable { mutableStateOf(listOf<String>()) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showSpeedSheet by remember { mutableStateOf(false) }
    var showAddTracks by remember { mutableStateOf(false) }

    val filters = AmplifyFilters(query = query, mood = mood, genres = genreNames.toSet())
    val playlistTracks = remember(queue.items, filters, sort) {
        arrangeTracks(queue.items, filters, sort)
    }
    val buckets = remember(queue.items) { genreBuckets(queue.items) }
    val libraryFiles = remember(state.audioLibrary, sort) { arrangeLibrary(state.audioLibrary, sort) }
    val libraryVisible = remember(libraryFiles, query) {
        if (query.isBlank()) libraryFiles
        else libraryFiles.filter { it.originalFilename.contains(query.trim(), ignoreCase = true) }
    }
    val genreSections = remember(playlistTracks) {
        playlistTracks
            .groupBy { genreLabel(it.item) ?: "Untagged" }
            .entries
            .sortedWith(
                compareBy<Map.Entry<String, List<AmplifyTrack>>> { if (it.key == "Untagged") 1 else 0 }
                    .thenBy { it.key.lowercase() }
            )
            .map { entry -> TrackSection(entry.key, entry.value) }
    }

    fun addLibraryTracks(files: List<StoredFile>) {
        if (files.isEmpty()) return
        val ids = files.map { it.stableId }
        if (queue.items.isEmpty()) {
            viewModel.playFile(ids.first(), ids)
        } else {
            viewModel.addAllToQueue(ids)
        }
    }

    fun selectSection(next: AmplifySection) {
        sectionName = next.name
    }

    // Mood and genre filters describe the playlist, so choosing one brings that view forward.
    fun applyMood(next: AmplifyMood?) {
        moodName = next?.name
        if (next != null) selectSection(AmplifySection.DISCOVER)
    }

    fun toggleGenreFilter(genre: String) {
        val next = if (genre in genreNames) genreNames - genre else genreNames + genre
        genreNames = next
        if (genre in next) selectSection(AmplifySection.DISCOVER)
    }

    fun clearFilters() {
        query = ""
        searchOpen = false
        moodName = null
        genreNames = emptyList()
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 880.dp
        val timelineCompact = maxWidth < 600.dp
        val drawerState = rememberDrawerState(DrawerValue.Closed)
        val scope = rememberCoroutineScope()

        BackHandler(enabled = drawerState.isOpen || searchOpen) {
            if (drawerState.isOpen) {
                scope.launch { drawerState.close() }
            } else {
                searchOpen = false
                query = ""
            }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = !wide,
            scrimColor = AmplifyColors.Scrim,
            drawerContent = {
                if (!wide) {
                    ModalDrawerSheet(
                        modifier = Modifier.width(272.dp),
                        drawerContainerColor = Color(0xFF1A1126),
                        drawerContentColor = AmplifyColors.TextPrimary,
                        drawerShape = RoundedCornerShape(topEnd = 26.dp, bottomEnd = 26.dp),
                    ) {
                        AmplifyBackground(Modifier.fillMaxSize()) {
                            AmplifySidebar(
                                section = section,
                                onSectionChange = {
                                    selectSection(it)
                                    scope.launch { drawerState.close() }
                                },
                                onSearch = {
                                    searchOpen = true
                                    scope.launch { drawerState.close() }
                                },
                                mood = mood,
                                onMoodChange = { chosen -> applyMood(chosen) },
                                genreBuckets = buckets,
                                selectedGenres = genreNames.toSet(),
                                onToggleGenre = { genre -> toggleGenreFilter(genre) },
                                onClearGenres = { genreNames = emptyList() },
                                current = current,
                                status = player.status,
                                artworkFor = artworkFor,
                                onOpenQueue = {
                                    scope.launch { drawerState.close() }
                                    showQueueSheet = true
                                },
                            )
                        }
                    }
                }
            },
        ) {
            AmplifyBackground(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxSize()) {
                    if (wide) {
                        AmplifyBackground(Modifier.width(238.dp).fillMaxHeight()) {
                            AmplifySidebar(
                                section = section,
                                onSectionChange = { next -> selectSection(next) },
                                onSearch = { searchOpen = true },
                                mood = mood,
                                onMoodChange = { chosen -> applyMood(chosen) },
                                genreBuckets = buckets,
                                selectedGenres = genreNames.toSet(),
                                onToggleGenre = { genre -> toggleGenreFilter(genre) },
                                onClearGenres = { genreNames = emptyList() },
                                current = current,
                                status = player.status,
                                artworkFor = artworkFor,
                                onOpenQueue = { showQueueSheet = true },
                            )
                        }
                        Box(Modifier.width(1.dp).fillMaxHeight().background(AmplifyColors.Hairline))
                    }

                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        AmplifyTopBar(
                            wide = wide,
                            searchOpen = searchOpen,
                            query = query,
                            onQueryChange = { query = it },
                            onToggleSearch = {
                                if (searchOpen) {
                                    searchOpen = false
                                    query = ""
                                } else {
                                    searchOpen = true
                                }
                            },
                            onOpenDrawer = { scope.launch { drawerState.open() } },
                            onBack = onBack,
                        )

                        AmplifyPlaylistHeader(
                            eyebrow = when (section) {
                                AmplifySection.DISCOVER -> "Active Playlist"
                                AmplifySection.LIBRARY -> "Library"
                                AmplifySection.QUEUE -> "Up Next"
                                AmplifySection.GENRES -> "Browse"
                            },
                            title = when (section) {
                                AmplifySection.DISCOVER -> playlistName(queue.items)
                                AmplifySection.LIBRARY -> "My Library"
                                AmplifySection.QUEUE -> "Queue"
                                AmplifySection.GENRES -> "Genres"
                            },
                            subtitle = when (section) {
                                AmplifySection.DISCOVER -> {
                                    val stats = playlistStats(queue.items)
                                    if (filters.isActive) {
                                        "${playlistTracks.size} of ${queue.items.size} Tracks · ${playlistLengthLabel(stats)}"
                                    } else {
                                        playlistSubtitle(stats)
                                    }
                                }
                                AmplifySection.LIBRARY -> librarySubtitle(state.audioLibrary)
                                AmplifySection.QUEUE -> playlistSubtitle(playlistStats(queue.items))
                                AmplifySection.GENRES -> {
                                    val stats = playlistStats(queue.items)
                                    "${buckets.size} Genres · ${stats.trackCount} Tracks"
                                }
                            },
                            current = current,
                            artworkFor = artworkFor,
                            onAddTracks = { showAddTracks = true },
                            wide = wide,
                        )

                        Spacer(Modifier.height(16.dp))

                        AmplifyPlaylistControls(
                            shuffleOn = queue.shuffleMode == ShuffleMode.ON,
                            onToggleShuffle = { viewModel.toggleShuffle() },
                            onEditQueue = { showQueueSheet = true },
                            sort = sort,
                            onSortChange = { sortName = it.name },
                            onClearFilters = { clearFilters() },
                            filtersActive = filters.isActive,
                        )

                        PlaybackStatusStrip(
                            status = player.status,
                            errorMessage = player.error?.message,
                            retryable = player.error?.retryable == true,
                            onRetry = { current?.let { viewModel.playFile(it.file.stableId) } },
                        )

                        Spacer(Modifier.height(6.dp))

                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            when (section) {
                                AmplifySection.DISCOVER -> {
                                    val filtersApplied = filters.isActive
                                    AmplifyTrackList(
                                        sections = listOf(TrackSection(null, playlistTracks)),
                                        currentStableId = current?.file?.stableId,
                                        isPlaying = player.status == PlaybackStatus.PLAYING,
                                        artworkFor = artworkFor,
                                        onPlay = { index -> viewModel.playAt(index) },
                                        onRemove = { index -> viewModel.removeFromQueue(index) },
                                        modifier = Modifier.fillMaxSize(),
                                        emptyContent = {
                                            EmptyPlayerState(
                                                title = when {
                                                    filtersApplied -> "No tracks match these filters"
                                                    queue.items.isEmpty() -> "Nothing queued yet"
                                                    else -> "No playable tracks"
                                                },
                                                message = when {
                                                    filtersApplied -> "Loosen the search, mood or genre filter to see the rest of the queue."
                                                    queue.items.isEmpty() -> "Pick audio from your Telegram library to start a listening session."
                                                    else -> "The queue does not expose any audio entries yet."
                                                },
                                                actionLabel = if (filtersApplied) "Clear filters" else "Add tracks",
                                                onAction = {
                                                    if (filtersApplied) {
                                                        clearFilters()
                                                    } else {
                                                        showAddTracks = true
                                                    }
                                                },
                                                icon = Icons.Rounded.MusicNote,
                                            )
                                        },
                                    )
                                }

                                AmplifySection.GENRES -> AmplifyTrackList(
                                    sections = genreSections,
                                    currentStableId = current?.file?.stableId,
                                    isPlaying = player.status == PlaybackStatus.PLAYING,
                                    artworkFor = artworkFor,
                                    onPlay = { index -> viewModel.playAt(index) },
                                    onRemove = { index -> viewModel.removeFromQueue(index) },
                                    modifier = Modifier.fillMaxSize(),
                                    emptyContent = {
                                        EmptyPlayerState(
                                            title = "No genre information",
                                            message = if (queue.items.isEmpty()) {
                                                "Queue tracks first; genre headings appear from embedded tags."
                                            } else {
                                                "These files carry no genre tags, so they are grouped as Untagged in the playlist."
                                            },
                                            actionLabel = "Open playlist",
                                            onAction = { selectSection(AmplifySection.DISCOVER) },
                                        )
                                    },
                                )

                                AmplifySection.LIBRARY -> AmplifyLibraryList(
                                    files = libraryVisible,
                                    queuedIds = queue.items.map { it.file.stableId }.toSet(),
                                    artworkFor = artworkFor,
                                    onPlay = { file ->
                                        viewModel.playFile(file.stableId, state.audioLibrary.map { it.stableId })
                                    },
                                    onAdd = { file -> addLibraryTracks(listOf(file)) },
                                    modifier = Modifier.fillMaxSize(),
                                    emptyContent = {
                                        EmptyPlayerState(
                                            title = if (query.isBlank()) "No audio in your library" else "No matching audio files",
                                            message = if (query.isBlank()) {
                                                "Audio saved in Telegram Saved Messages is indexed here after a sync."
                                            } else {
                                                "Try a different search term."
                                            },
                                            actionLabel = if (query.isBlank()) null else "Clear search",
                                            onAction = { query = "" },
                                        )
                                    },
                                )

                                AmplifySection.QUEUE -> AmplifyQueueList(
                                    queue = queue,
                                    isPlaying = player.status == PlaybackStatus.PLAYING,
                                    artworkFor = artworkFor,
                                    onPlay = { index -> viewModel.playAt(index) },
                                    onRemove = { index -> viewModel.removeFromQueue(index) },
                                    onMove = { from, to -> viewModel.moveInQueue(from, to) },
                                    modifier = Modifier.fillMaxSize(),
                                    emptyContent = {
                                        EmptyPlayerState(
                                            title = "The queue is empty",
                                            message = "Add a few tracks and they will line up here in playback order.",
                                            actionLabel = "Add tracks",
                                            onAction = { showAddTracks = true },
                                        )
                                    },
                                )
                            }
                        }

                        AmplifyTimelineBar(
                            player = player,
                            current = current,
                            queueRepeat = queue.repeatMode,
                            queueShuffle = queue.shuffleMode,
                            compact = timelineCompact,
                            waveform = remember(current?.file?.stableId, wide) {
                                waveformBars(current?.file?.stableId ?: "amplify-idle", if (wide) 96 else 64)
                            },
                            onSeek = { ms -> viewModel.seekTo(ms) },
                            onSeekRelative = { delta -> viewModel.seekRelative(delta) },
                            onTogglePlayPause = {
                                if (current == null) showAddTracks = true else viewModel.togglePlayPause()
                            },
                            onPreviousTrack = { viewModel.previousTrack() },
                            onNextTrack = { viewModel.next() },
                            onVolumeChange = { value -> viewModel.setVolume(value) },
                            onToggleRepeat = { viewModel.toggleRepeat() },
                            onToggleShuffle = { viewModel.toggleShuffle() },
                            onSpeedClick = { showSpeedSheet = true },
                            onOpenQueue = { showQueueSheet = true },
                        )
                    }
                }
            }
        }
    }

    if (showAddTracks) {
        AmplifyAddTracksSheet(
            files = state.audioLibrary,
            queuedIds = queue.items.map { it.file.stableId }.toSet(),
            artworkFor = artworkFor,
            onAdd = { file -> addLibraryTracks(listOf(file)) },
            onAddAll = { files -> addLibraryTracks(files) },
            onPlay = { file -> viewModel.playFile(file.stableId, state.audioLibrary.map { it.stableId }) },
            onDismiss = { showAddTracks = false },
        )
    }

    if (showSpeedSheet) {
        AmplifySpeedSheet(
            current = player.progress.speed,
            onSelect = { speed -> viewModel.setSpeed(speed) },
            onDismiss = { showSpeedSheet = false },
        )
    }

    if (showQueueSheet) {
        AmplifyQueueSheet(
            queue = queue,
            isPlaying = player.status == PlaybackStatus.PLAYING,
            artworkFor = artworkFor,
            onPlay = { index -> viewModel.playAt(index) },
            onRemove = { index -> viewModel.removeFromQueue(index) },
            onMove = { from, to -> viewModel.moveInQueue(from, to) },
            onClear = {
                viewModel.clearQueue()
                showQueueSheet = false
            },
            onDismiss = { showQueueSheet = false },
        )
    }
}

private fun librarySubtitle(files: List<StoredFile>): String {
    if (files.isEmpty()) return "No audio files indexed"
    val totalBytes = files.sumOf { it.sizeBytes ?: 0L }
    val count = if (files.size == 1) "1 audio file" else "${files.size} audio files"
    return if (totalBytes > 0L) "$count · ${sizeLabel(totalBytes)}" else count
}

private fun arrangeLibrary(files: List<StoredFile>, sort: AmplifySort): List<StoredFile> = when (sort) {
    AmplifySort.DATE_ADDED -> files.sortedByDescending { it.savedAtEpochSeconds }
    AmplifySort.DURATION, AmplifySort.ARTIST -> files.sortedBy { it.originalFilename.lowercase() }
    AmplifySort.TITLE, AmplifySort.CUSTOM -> files.sortedBy { it.originalFilename.lowercase() }
}

/** Shows download/error feedback in the player's own visual language instead of a raw Snackbar. */
@Composable
private fun PlaybackStatusStrip(
    status: PlaybackStatus,
    errorMessage: String?,
    retryable: Boolean,
    onRetry: () -> Unit,
) {
    val downloading = status == PlaybackStatus.WAITING_FOR_DOWNLOAD || status == PlaybackStatus.RESOLVING
    if (!downloading && status != PlaybackStatus.ERROR) return
    Box(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp)) {
        if (downloading) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(AmplifyColors.Panel, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    color = AmplifyColors.Accent,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    "Fetching the audio from Telegram so the timeline can scrub smoothly…",
                    style = AmplifyType.body,
                )
            }
        } else {
            AmplifyNoticeCard(
                title = "Playback problem",
                message = errorMessage ?: "This track could not be played.",
                actionLabel = if (retryable) "Retry" else null,
                onAction = if (retryable) onRetry else null,
                icon = Icons.Rounded.ErrorOutline,
                tone = AmplifyColors.Danger,
            )
        }
    }
}

@Composable
private fun EmptyPlayerState(
    title: String,
    message: String,
    actionLabel: String?,
    onAction: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Box(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 8.dp), contentAlignment = Alignment.TopCenter) {
        GlassPanel(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(22.dp),
        ) {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = AmplifyColors.Accent, modifier = Modifier.size(26.dp))
                }
                Text(title, style = AmplifyType.trackTitle, textAlign = TextAlign.Center)
                Text(message, style = AmplifyType.body, textAlign = TextAlign.Center)
                if (actionLabel != null) {
                    Spacer(Modifier.height(4.dp))
                    AmplifyPill(
                        text = actionLabel,
                        icon = Icons.Rounded.Add,
                        onClick = onAction,
                        compact = true,
                    )
                }
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current != null) {
        if (current is Activity) return current
        current = (current as? ContextWrapper)?.baseContext
    }
    return null
}
