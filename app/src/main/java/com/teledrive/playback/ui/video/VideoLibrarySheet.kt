package com.teledrive.playback.ui.video

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.teledrive.playback.PlaybackItem
import com.teledrive.playback.PlaybackQueueState
import com.teledrive.playback.RepeatMode
import com.teledrive.playback.ShuffleMode
import com.teledrive.playback.ui.amplify.AmplifyColors
import com.teledrive.playback.ui.amplify.AmplifyDivider
import com.teledrive.playback.ui.amplify.AmplifyIconButton
import com.teledrive.playback.ui.amplify.AmplifyPill
import com.teledrive.playback.ui.amplify.AmplifySearchField
import com.teledrive.playback.ui.amplify.AmplifySectionLabel
import com.teledrive.playback.ui.amplify.AmplifySheet
import com.teledrive.playback.ui.amplify.AmplifyType
import com.teledrive.playback.ui.amplify.amplifyClickable
import com.teledrive.playback.ui.amplify.sizeLabel
import com.teledrive.playback.video.VideoLibraryEntry
import com.teledrive.playback.video.VideoLibraryUiState
import com.teledrive.playback.video.VideoPlaylist
import com.teledrive.playback.video.VideoSortField
import com.teledrive.playback.video.formatClock
import com.teledrive.playback.video.remainingLabel

enum class VideoLibraryTab(val label: String) {
    LIBRARY("Library"),
    CONTINUE("Continue"),
    RECENT("Recent"),
    FAVORITES("Favourites"),
    PLAYLISTS("Playlists"),
    QUEUE("Queue"),
}

@Composable
fun VideoLibrarySheet(
    library: VideoLibraryUiState,
    queue: PlaybackQueueState,
    currentStableId: String?,
    onDismiss: () -> Unit,
    onQuery: (String) -> Unit,
    onSortField: (VideoSortField) -> Unit,
    onToggleSortDirection: () -> Unit,
    onToggleFavorites: () -> Unit,
    onToggleInProgress: () -> Unit,
    onPlay: (VideoLibraryEntry) -> Unit,
    onAddToQueue: (VideoLibraryEntry, Boolean) -> Unit,
    onToggleFavorite: (VideoLibraryEntry) -> Unit,
    onCreatePlaylist: (String?) -> Unit,
    onRenamePlaylist: (VideoPlaylist, String) -> Unit,
    onDeletePlaylist: (VideoPlaylist) -> Unit,
    onPlayPlaylist: (VideoPlaylist) -> Unit,
    onMoveInPlaylist: (VideoPlaylist, Int, Int) -> Unit,
    onToggleInPlaylist: (VideoPlaylist, VideoLibraryEntry) -> Unit,
    onClearRecents: () -> Unit,
    onPlayQueueIndex: (Int) -> Unit,
    onRemoveQueueIndex: (Int) -> Unit,
    onMoveQueue: (Int, Int) -> Unit,
    onClearQueue: () -> Unit,
    onToggleShuffle: () -> Unit,
    onRepeatMode: (RepeatMode) -> Unit,
) {
    var tab by remember { mutableStateOf(VideoLibraryTab.LIBRARY) }
    var openPlaylistId by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<VideoPlaylist?>(null) }
    var creating by remember { mutableStateOf(false) }

    val openPlaylist = openPlaylistId?.let { library.playlist(it) }

    AmplifySheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (openPlaylist != null) openPlaylist.name else "Video library",
                        style = AmplifyType.sectionTitle,
                        color = AmplifyColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (openPlaylist != null) openPlaylist.summary else "${library.all.size} videos indexed",
                        style = AmplifyType.meta,
                        color = AmplifyColors.TextMuted,
                    )
                }
                if (openPlaylist != null) {
                    AmplifyPill(text = "Back", compact = true, onClick = { openPlaylistId = null })
                    Spacer(Modifier.width(6.dp))
                }
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.07f))
                        .amplifyClickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close", tint = AmplifyColors.TextSecondary, modifier = Modifier.size(16.dp))
                }
            }

            if (openPlaylist == null) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    VideoLibraryTab.entries.forEach { entry ->
                        AmplifyPill(
                            text = entry.label,
                            selected = tab == entry,
                            compact = true,
                            onClick = { tab = entry },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            when {
                openPlaylist != null -> PlaylistDetail(
                    playlist = openPlaylist,
                    library = library,
                    currentStableId = currentStableId,
                    onPlayPlaylist = { onPlayPlaylist(openPlaylist) },
                    onRename = { renaming = openPlaylist },
                    onDelete = {
                        onDeletePlaylist(openPlaylist)
                        openPlaylistId = null
                    },
                    onMove = { from, to -> onMoveInPlaylist(openPlaylist, from, to) },
                    onToggleMembership = { entry -> onToggleInPlaylist(openPlaylist, entry) },
                    onPlay = onPlay,
                )
                tab == VideoLibraryTab.PLAYLISTS -> PlaylistsTab(
                    playlists = library.playlists,
                    onCreate = { creating = true },
                    onOpen = { openPlaylistId = it.id },
                    onPlay = onPlayPlaylist,
                    onDelete = onDeletePlaylist,
                )
                tab == VideoLibraryTab.QUEUE -> QueueTab(
                    queue = queue,
                    onPlayIndex = onPlayQueueIndex,
                    onRemoveIndex = onRemoveQueueIndex,
                    onMove = onMoveQueue,
                    onClear = onClearQueue,
                    onToggleShuffle = onToggleShuffle,
                    onRepeatMode = onRepeatMode,
                )
                else -> LibraryListTab(
                    library = library,
                    tab = tab,
                    currentStableId = currentStableId,
                    onQuery = onQuery,
                    onSortField = onSortField,
                    onToggleSortDirection = onToggleSortDirection,
                    onToggleFavorites = onToggleFavorites,
                    onToggleInProgress = onToggleInProgress,
                    onPlay = onPlay,
                    onAddToQueue = onAddToQueue,
                    onToggleFavorite = onToggleFavorite,
                    onClearRecents = onClearRecents,
                )
            }
        }
    }

    if (creating) {
        NameDialog(
            title = "New playlist",
            initial = "",
            confirmLabel = "Create",
            onDismiss = { creating = false },
            onConfirm = { name ->
                onCreatePlaylist(name.takeIf { it.isNotBlank() })
                creating = false
            },
        )
    }

    renaming?.let { playlist ->
        NameDialog(
            title = "Rename playlist",
            initial = playlist.name,
            confirmLabel = "Save",
            onDismiss = { renaming = null },
            onConfirm = { name ->
                onRenamePlaylist(playlist, name)
                renaming = null
            },
        )
    }
}

@Composable
private fun LibraryListTab(
    library: VideoLibraryUiState,
    tab: VideoLibraryTab,
    currentStableId: String?,
    onQuery: (String) -> Unit,
    onSortField: (VideoSortField) -> Unit,
    onToggleSortDirection: () -> Unit,
    onToggleFavorites: () -> Unit,
    onToggleInProgress: () -> Unit,
    onPlay: (VideoLibraryEntry) -> Unit,
    onAddToQueue: (VideoLibraryEntry, Boolean) -> Unit,
    onToggleFavorite: (VideoLibraryEntry) -> Unit,
    onClearRecents: () -> Unit,
) {
    val entries = when (tab) {
        VideoLibraryTab.CONTINUE -> library.continueWatching
        VideoLibraryTab.RECENT -> library.recentlyPlayed
        VideoLibraryTab.FAVORITES -> library.favorites
        else -> library.visible
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AmplifySearchField(
                value = library.query,
                onValueChange = onQuery,
                modifier = Modifier.weight(1f),
                placeholder = "Search by filename",
            )
            Spacer(Modifier.width(8.dp))
            AmplifyIconButton(
                icon = Icons.Rounded.Sort,
                contentDescription = "Sort direction",
                onClick = onToggleSortDirection,
                active = library.sort.ascending,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VideoSortField.entries.forEach { field ->
                AmplifyPill(
                    text = if (library.sort.field == field) {
                        "${field.label} ${if (library.sort.ascending) "↑" else "↓"}"
                    } else {
                        field.label
                    },
                    selected = library.sort.field == field,
                    compact = true,
                    onClick = { onSortField(field) },
                )
            }
            AmplifyPill(
                text = "Favourites",
                icon = Icons.Rounded.Favorite,
                selected = library.favoritesOnly,
                compact = true,
                onClick = onToggleFavorites,
            )
            AmplifyPill(
                text = "In progress",
                icon = Icons.Rounded.History,
                selected = library.inProgressOnly,
                compact = true,
                onClick = onToggleInProgress,
            )
            if (tab == VideoLibraryTab.RECENT) {
                AmplifyPill(text = "Clear", compact = true, onClick = onClearRecents)
            }
        }
        Spacer(Modifier.height(10.dp))
        if (entries.isEmpty()) {
            Text(
                if (library.all.isEmpty()) {
                    "No videos indexed yet. Files with a video extension are listed here: " +
                        "MP4, MKV, AVI, MOV, WebM, FLV, TS and friends."
                } else {
                    "Nothing matches these filters."
                },
                style = AmplifyType.meta,
                color = AmplifyColors.TextMuted,
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(entries, key = { it.stableId }) { entry ->
                    VideoRow(
                        entry = entry,
                        isCurrent = entry.stableId == currentStableId,
                        onClick = { onPlay(entry) },
                        onToggleFavorite = { onToggleFavorite(entry) },
                        trailing = {
                            AmplifyIconButton(
                                icon = Icons.Rounded.PlaylistAdd,
                                contentDescription = "Add to queue",
                                onClick = { onAddToQueue(entry, false) },
                                size = 28.dp,
                                iconSize = 14.dp,
                            )
                        },
                    )
                    AmplifyDivider()
                }
            }
        }
    }
}

@Composable
private fun PlaylistsTab(
    playlists: List<VideoPlaylist>,
    onCreate: () -> Unit,
    onOpen: (VideoPlaylist) -> Unit,
    onPlay: (VideoPlaylist) -> Unit,
    onDelete: (VideoPlaylist) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AmplifyPill(text = "New playlist", icon = Icons.Rounded.Add, compact = true, onClick = onCreate)
            Spacer(Modifier.weight(1f))
            Text("${playlists.size} saved", style = AmplifyType.meta)
        }
        Spacer(Modifier.height(10.dp))
        if (playlists.isEmpty()) {
            Text(
                "Playlists you create are stored on this device and can be reordered by dragging.",
                style = AmplifyType.meta,
                color = AmplifyColors.TextMuted,
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(playlists, key = { it.id }) { playlist ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .amplifyClickable(onClick = { onOpen(playlist) })
                            .padding(vertical = 9.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.VideoLibrary,
                            contentDescription = null,
                            tint = AmplifyColors.Accent,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(playlist.name, style = AmplifyType.label, color = AmplifyColors.TextPrimary, maxLines = 1)
                            Text(playlist.summary, style = AmplifyType.meta, color = AmplifyColors.TextMuted)
                        }
                        AmplifyIconButton(
                            icon = Icons.Rounded.PlayArrow,
                            contentDescription = "Play playlist",
                            onClick = { onPlay(playlist) },
                            size = 28.dp,
                            iconSize = 15.dp,
                        )
                        Spacer(Modifier.width(4.dp))
                        AmplifyIconButton(
                            icon = Icons.Rounded.Delete,
                            contentDescription = "Delete playlist",
                            onClick = { onDelete(playlist) },
                            size = 28.dp,
                            iconSize = 14.dp,
                        )
                    }
                    AmplifyDivider()
                }
            }
        }
    }
}

@Composable
private fun PlaylistDetail(
    playlist: VideoPlaylist,
    library: VideoLibraryUiState,
    currentStableId: String?,
    onPlayPlaylist: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onToggleMembership: (VideoLibraryEntry) -> Unit,
    onPlay: (VideoLibraryEntry) -> Unit,
) {
    val entries = library.playlistEntries(playlist)
    var adding by remember(playlist.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            AmplifyPill(text = "Play", icon = Icons.Rounded.PlayArrow, compact = true, onClick = onPlayPlaylist)
            AmplifyPill(text = "Add videos", icon = Icons.Rounded.PlaylistAdd, compact = true, onClick = { adding = !adding })
            AmplifyPill(text = "Rename", icon = Icons.Rounded.Edit, compact = true, onClick = onRename)
            AmplifyPill(text = "Delete", icon = Icons.Rounded.Delete, compact = true, onClick = onDelete)
        }
        Spacer(Modifier.height(10.dp))
        if (adding) {
            AmplifySectionLabel("Toggle membership")
            Spacer(Modifier.height(6.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
                items(library.all, key = { it.stableId }) { entry ->
                    val included = playlist.stableIds.contains(entry.stableId)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (included) AmplifyColors.AccentSoft else Color.Transparent)
                            .amplifyClickable(onClick = { onToggleMembership(entry) })
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            entry.filename,
                            style = AmplifyType.label,
                            color = AmplifyColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            if (included) Icons.Rounded.Check else Icons.Rounded.Add,
                            contentDescription = null,
                            tint = if (included) AmplifyColors.Accent else AmplifyColors.TextMuted,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    AmplifyDivider()
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        if (entries.isEmpty() && !adding) {
            Text("This playlist is empty. Use “Add videos” to fill it.", style = AmplifyType.meta, color = AmplifyColors.TextMuted)
        } else {
            ReorderableRows(
                entries = entries,
                currentStableId = currentStableId,
                onMove = onMove,
                onPlay = onPlay,
                trailing = { entry ->
                    AmplifyIconButton(
                        icon = Icons.Rounded.PlaylistRemove,
                        contentDescription = "Remove from playlist",
                        onClick = { onToggleMembership(entry) },
                        size = 28.dp,
                        iconSize = 14.dp,
                    )
                },
            )
        }
    }
}

/** Long-press a row, then drag it up or down; the move is committed on release. */
@Composable
private fun ReorderableRows(
    entries: List<VideoLibraryEntry>,
    currentStableId: String?,
    onMove: (Int, Int) -> Unit,
    onPlay: (VideoLibraryEntry) -> Unit,
    trailing: @Composable (VideoLibraryEntry) -> Unit,
) {
    val rowHeightPx = with(LocalDensity.current) { 62.dp.toPx() }
    var draggingIndex by remember(entries.size) { mutableStateOf(-1) }
    var dragOffset by remember(entries.size) { mutableStateOf(0f) }

    Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
        entries.forEachIndexed { index, entry ->
            val dragging = draggingIndex == index
            Row(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        if (dragging) {
                            translationY = dragOffset
                            alpha = 0.92f
                        }
                    }
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        when {
                            dragging -> AmplifyColors.AccentSoft
                            entry.stableId == currentStableId -> Color.White.copy(alpha = 0.05f)
                            else -> Color.Transparent
                        }
                    )
                    .amplifyClickable(onClick = { onPlay(entry) })
                    .padding(horizontal = 6.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(28.dp)
                        .pointerInput(entry.stableId, entries.size) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingIndex = index
                                    dragOffset = 0f
                                },
                                onDragEnd = {
                                    val from = draggingIndex
                                    val delta = (dragOffset / rowHeightPx).toInt()
                                    val to = (index + delta).coerceIn(0, entries.size - 1)
                                    if (from >= 0 && to != from) onMove(from, to)
                                    draggingIndex = -1
                                    dragOffset = 0f
                                },
                                onDragCancel = {
                                    draggingIndex = -1
                                    dragOffset = 0f
                                },
                                onDrag = { _, delta -> dragOffset += delta.y },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.DragIndicator,
                        contentDescription = "Drag to reorder",
                        tint = AmplifyColors.TextMuted,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.filename,
                        style = AmplifyType.label,
                        color = AmplifyColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            entry.extension,
                            entry.durationMs?.let(::formatClock),
                            entry.resumeMs.takeIf { it > 0L }?.let { "resume ${formatClock(it)}" },
                        ).joinToString(" · "),
                        style = AmplifyType.meta,
                        color = AmplifyColors.TextMuted,
                        maxLines = 1,
                    )
                }
                trailing(entry)
            }
            if (index != entries.lastIndex) AmplifyDivider()
        }
    }
}

@Composable
private fun QueueTab(
    queue: PlaybackQueueState,
    onPlayIndex: (Int) -> Unit,
    onRemoveIndex: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onClear: () -> Unit,
    onToggleShuffle: () -> Unit,
    onRepeatMode: (RepeatMode) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AmplifyPill(
                text = if (queue.shuffleMode == ShuffleMode.ON) "Shuffle on" else "Shuffle off",
                icon = Icons.Rounded.Shuffle,
                selected = queue.shuffleMode == ShuffleMode.ON,
                compact = true,
                onClick = onToggleShuffle,
            )
            RepeatMode.entries.forEach { mode ->
                AmplifyPill(
                    text = when (mode) {
                        RepeatMode.OFF -> "Repeat off"
                        RepeatMode.ONE -> "Repeat one"
                        RepeatMode.ALL -> "Repeat all"
                    },
                    icon = Icons.Rounded.Repeat,
                    selected = queue.repeatMode == mode,
                    compact = true,
                    onClick = { onRepeatMode(mode) },
                )
            }
            AmplifyPill(text = "Clear queue", compact = true, onClick = onClear)
        }
        Spacer(Modifier.height(10.dp))
        if (queue.items.isEmpty()) {
            Text("Queue is empty.", style = AmplifyType.meta, color = AmplifyColors.TextMuted)
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                itemsIndexed(queue.items) { index, item ->
                    QueueRow(
                        index = index,
                        item = item,
                        current = index == queue.currentIndex,
                        isFirst = index == 0,
                        isLast = index == queue.items.lastIndex,
                        onPlay = { onPlayIndex(index) },
                        onRemove = { onRemoveIndex(index) },
                        onMove = onMove,
                    )
                    AmplifyDivider()
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    index: Int,
    item: PlaybackItem,
    current: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onPlay: () -> Unit,
    onRemove: () -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (current) Color.White.copy(alpha = 0.05f) else Color.Transparent)
            .amplifyClickable(onClick = onPlay)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            (index + 1).toString().padStart(2, '0'),
            style = AmplifyType.numeric,
            modifier = Modifier.width(24.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                item.file.originalFilename,
                style = AmplifyType.label,
                color = if (current) AmplifyColors.Accent else AmplifyColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    item.durationMs?.let(::formatClock),
                    item.file.sizeBytes?.let(::sizeLabel),
                ).joinToString(" · "),
                style = AmplifyType.meta,
                color = AmplifyColors.TextMuted,
                maxLines = 1,
            )
        }
        AmplifyIconButton(
            icon = Icons.Rounded.KeyboardArrowUp,
            contentDescription = "Move up",
            onClick = { if (!isFirst) onMove(index, index - 1) },
            size = 26.dp,
            iconSize = 14.dp,
            enabled = !isFirst,
        )
        AmplifyIconButton(
            icon = Icons.Rounded.KeyboardArrowDown,
            contentDescription = "Move down",
            onClick = { if (!isLast) onMove(index, index + 1) },
            size = 26.dp,
            iconSize = 14.dp,
            enabled = !isLast,
        )
        AmplifyIconButton(
            icon = Icons.Rounded.PlaylistRemove,
            contentDescription = "Remove from queue",
            onClick = onRemove,
            size = 26.dp,
            iconSize = 14.dp,
        )
    }
}

@Composable
private fun VideoRow(
    entry: VideoLibraryEntry,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isCurrent) Color.White.copy(alpha = 0.05f) else Color.Transparent)
            .amplifyClickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VideoFramePreview(
            localPath = entry.localPath,
            timeMs = entry.resumeMs,
            modifier = Modifier.width(84.dp).height(48.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.filename,
                style = AmplifyType.label,
                color = if (isCurrent) AmplifyColors.Accent else AmplifyColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    entry.extension,
                    entry.durationMs?.let(::formatClock),
                    entry.sizeBytes?.let(::sizeLabel),
                    remainingLabel(entry.resumeMs, entry.durationMs ?: 0L),
                ).joinToString(" · "),
                style = AmplifyType.meta,
                color = AmplifyColors.TextMuted,
                maxLines = 1,
            )
            if (entry.hasProgress) {
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.12f)),
                ) {
                    Spacer(
                        Modifier
                            .fillMaxWidth(entry.watchedFraction.coerceIn(0.01f, 1f))
                            .height(3.dp)
                            .background(AmplifyColors.Accent),
                    )
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        AmplifyIconButton(
            icon = if (entry.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            contentDescription = if (entry.favorite) "Remove favourite" else "Add favourite",
            onClick = onToggleFavorite,
            size = 28.dp,
            iconSize = 14.dp,
            active = entry.favorite,
        )
        Spacer(Modifier.width(4.dp))
        trailing()
    }
}

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = AmplifyType.sectionTitle, color = AmplifyColors.TextPrimary) },
        text = {
            BasicTextField(
                value = value,
                onValueChange = { value = it.take(60) },
                singleLine = true,
                textStyle = AmplifyType.label.copy(color = AmplifyColors.TextPrimary),
                cursorBrush = SolidColor(AmplifyColors.Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.07f))
                    .border(1.dp, AmplifyColors.Hairline, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) {
                Text(confirmLabel, style = AmplifyType.label, color = AmplifyColors.Accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", style = AmplifyType.label, color = AmplifyColors.TextSecondary)
            }
        },
        containerColor = AmplifyColors.Menu,
        textContentColor = AmplifyColors.TextPrimary,
    )
}

