package com.teledrive.playback.ui.amplify

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistRemove
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teledrive.core.model.LocalAvailability
import com.teledrive.core.model.StoredFile
import com.teledrive.playback.PlaybackQueueState

data class TrackSection(val title: String?, val tracks: List<AmplifyTrack>)

/**
 * Playlist list. One composable serves Discover, genre browsing and the queue: sections with a
 * title render as genre headings, a single untitled section renders as a flat playlist.
 */
@Composable
fun AmplifyTrackList(
    sections: List<TrackSection>,
    currentStableId: String?,
    isPlaying: Boolean,
    artworkFor: suspend (String) -> ImageBitmap?,
    onPlay: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
    emptyContent: @Composable () -> Unit = {},
) {
    val total = sections.sumOf { it.tracks.size }
    if (total == 0) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { emptyContent() }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = 6.dp, bottom = 18.dp),
    ) {
        item(key = "track-column-header") { TrackColumnHeader() }
        sections.forEach { section ->
            val title = section.title
            if (title != null) {
                item(key = "section-$title") {
                    GenreSectionHeader(title, section.tracks.size)
                }
            }
            items(section.tracks, key = { it.item.file.stableId }) { track ->
                TrackRow(
                    track = track,
                    isCurrent = track.item.file.stableId == currentStableId,
                    isPlaying = isPlaying && track.item.file.stableId == currentStableId,
                    artworkFor = artworkFor,
                    onPlay = { onPlay(track.index) },
                    onRemove = if (track.item.file.stableId == currentStableId) null else ({ onRemove(track.index) }),
                )
            }
        }
    }
}

@Composable
private fun TrackColumnHeader() {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width(30.dp))
            Text("TITLE", style = AmplifyType.eyebrow.copy(fontSize = 9.sp), modifier = Modifier.weight(1f))
            Text("TIME", style = AmplifyType.eyebrow.copy(fontSize = 9.sp))
            Spacer(Modifier.width(34.dp))
        }
        AmplifyDivider(Modifier.padding(horizontal = 22.dp))
    }
}

@Composable
private fun GenreSectionHeader(title: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = AmplifyType.sectionTitle.copy(fontSize = 15.sp), modifier = Modifier.weight(1f))
        Text("$count TRACKS", style = AmplifyType.eyebrow.copy(fontSize = 9.sp))
    }
}

@Composable
private fun TrackRow(
    track: AmplifyTrack,
    isCurrent: Boolean,
    isPlaying: Boolean,
    artworkFor: suspend (String) -> ImageBitmap?,
    onPlay: () -> Unit,
    onRemove: (() -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val item = track.item
    val rowShape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp)
            .clip(rowShape)
            .background(if (isCurrent) AmplifyColors.AccentSoft.copy(alpha = 0.24f) else Color.Transparent)
            .then(
                if (isCurrent) Modifier.border(1.dp, AmplifyColors.Accent.copy(alpha = 0.28f), rowShape)
                else Modifier
            )
            .clickable(onClick = onPlay)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) {
            if (isCurrent) {
                AmplifyEqualizer(playing = isPlaying, modifier = Modifier.size(13.dp))
            } else {
                Text(
                    "${track.index + 1}",
                    style = AmplifyType.numeric,
                    color = AmplifyColors.TextMuted,
                )
            }
        }
        AmplifyArtwork(
            title = item.title,
            seed = item.file.stableId,
            localPath = item.localPath,
            loadArtwork = artworkFor,
            modifier = Modifier.size(38.dp),
            shape = RoundedCornerShape(9.dp),
            iconSize = 15.dp,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = AmplifyType.trackTitle,
                color = if (isCurrent) AmplifyColors.Accent else AmplifyColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                rowSubtitle(item.artist, item.album, item.file.localAvailability),
                style = AmplifyType.meta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (item.file.localAvailability != LocalAvailability.AVAILABLE) {
                Icon(
                    Icons.Rounded.CloudDownload,
                    contentDescription = "Needs download before playback",
                    tint = AmplifyColors.TextMuted,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                amplifyClock(item.durationMs),
                style = AmplifyType.numeric,
                color = AmplifyColors.TextSecondary,
            )
            Box {
                AmplifyIconButton(
                    icon = Icons.Rounded.MoreVert,
                    contentDescription = "Track actions",
                    onClick = { menuOpen = true },
                    size = 30.dp,
                    iconSize = 16.dp,
                )
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = AmplifyColors.Menu,
                ) {
                    DropdownMenuItem(
                        text = { Text("Play now", style = AmplifyType.label) },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.PlayArrow,
                                contentDescription = null,
                                tint = AmplifyColors.Accent,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onPlay()
                        },
                        colors = MenuDefaults.itemColors(textColor = AmplifyColors.TextPrimary),
                    )
                    if (onRemove != null) {
                        DropdownMenuItem(
                            text = { Text("Remove from queue", style = AmplifyType.label) },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.PlaylistRemove,
                                    contentDescription = null,
                                    tint = AmplifyColors.Danger,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onRemove()
                            },
                            colors = MenuDefaults.itemColors(textColor = AmplifyColors.TextPrimary),
                        )
                    }
                }
            }
        }
    }
}

private fun rowSubtitle(
    artist: String?,
    album: String?,
    availability: LocalAvailability,
): String {
    val parts = listOfNotNull(
        artist?.takeIf { it.isNotBlank() },
        album?.takeIf { it.isNotBlank() },
        offlineStateLabel(availability),
    )
    return parts.joinToString(" · ").ifEmpty { "Audio" }
}

/** Audio files that are indexed but not necessarily queued yet ("Add Tracks" browser). */
@Composable
fun AmplifyLibraryList(
    files: List<StoredFile>,
    queuedIds: Set<String>,
    artworkFor: suspend (String) -> ImageBitmap?,
    onPlay: (StoredFile) -> Unit,
    onAdd: (StoredFile) -> Unit,
    modifier: Modifier = Modifier,
    emptyContent: @Composable () -> Unit = {},
) {
    if (files.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { emptyContent() }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = 6.dp, bottom = 18.dp),
    ) {
        items(files, key = { it.stableId }) { file ->
            val queued = file.stableId in queuedIds
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onPlay(file) }
                    .padding(horizontal = 8.dp, vertical = 7.dp)
                    .heightIn(min = 44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AmplifyArtwork(
                    title = file.originalFilename,
                    seed = file.stableId,
                    localPath = file.localPath,
                    loadArtwork = artworkFor,
                    modifier = Modifier.size(38.dp),
                    shape = RoundedCornerShape(9.dp),
                    iconSize = 15.dp,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        file.originalFilename.substringBeforeLast('.'),
                        style = AmplifyType.trackTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Saved ${savedDate(file.savedAtEpochSeconds)} · ${sizeLabel(file.sizeBytes)}",
                        style = AmplifyType.meta,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box {
                    if (queued) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Rounded.Check,
                                contentDescription = "Already queued",
                                tint = AmplifyColors.Accent,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Queued", style = AmplifyType.meta, color = AmplifyColors.Accent)
                        }
                    } else {
                        AmplifyIconButton(
                            icon = Icons.Rounded.Add,
                            contentDescription = "Add to queue",
                            onClick = { onAdd(file) },
                            size = 30.dp,
                            iconSize = 16.dp,
                        )
                    }
                }
            }
        }
    }
}

/** Up-next manager: select, reorder and prune the live queue. */
@Composable
fun AmplifyQueueList(
    queue: PlaybackQueueState,
    isPlaying: Boolean,
    artworkFor: suspend (String) -> ImageBitmap?,
    onPlay: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    modifier: Modifier = Modifier,
    emptyContent: @Composable () -> Unit = {},
) {
    if (queue.items.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { emptyContent() }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(top = 6.dp, bottom = 18.dp),
    ) {
        items(queue.items.size, key = { queue.items[it].file.stableId }) { index ->
            val item = queue.items[index]
            val isCurrent = index == queue.currentIndex
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 2.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isCurrent) AmplifyColors.AccentSoft.copy(alpha = 0.24f) else Color.Transparent)
                    .clickable { onPlay(index) }
                    .padding(horizontal = 8.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(26.dp), contentAlignment = Alignment.Center) {
                    if (isCurrent) {
                        AmplifyEqualizer(playing = isPlaying, modifier = Modifier.size(13.dp))
                    } else {
                        Text("${index + 1}", style = AmplifyType.numeric)
                    }
                }
                AmplifyArtwork(
                    title = item.title,
                    seed = item.file.stableId,
                    localPath = item.localPath,
                    loadArtwork = artworkFor,
                    modifier = Modifier.size(38.dp),
                    shape = RoundedCornerShape(9.dp),
                    iconSize = 15.dp,
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        item.title,
                        style = AmplifyType.trackTitle,
                        color = if (isCurrent) AmplifyColors.Accent else AmplifyColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (isCurrent) "Playing now" else (item.artist ?: item.album ?: "Up next"),
                        style = AmplifyType.meta,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(amplifyClock(item.durationMs), style = AmplifyType.numeric)
                if (!isCurrent) {
                    AmplifyIconButton(
                        icon = Icons.Rounded.ArrowUpward,
                        contentDescription = "Move up",
                        onClick = { if (index > 0) onMove(index, index - 1) },
                        size = 28.dp,
                        iconSize = 14.dp,
                        enabled = index > 0,
                    )
                    AmplifyIconButton(
                        icon = Icons.Rounded.ArrowDownward,
                        contentDescription = "Move down",
                        onClick = { if (index < queue.items.size - 1) onMove(index, index + 1) },
                        size = 28.dp,
                        iconSize = 14.dp,
                        enabled = index < queue.items.size - 1,
                    )
                }
                if (!isCurrent) {
                    AmplifyIconButton(
                        icon = Icons.Rounded.PlaylistRemove,
                        contentDescription = "Remove from queue",
                        onClick = { onRemove(index) },
                        size = 28.dp,
                        iconSize = 14.dp,
                    )
                }
            }
        }
    }
}
