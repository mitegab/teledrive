package com.teledrive.playback.ui.amplify

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
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
import com.teledrive.core.model.StoredFile
import com.teledrive.playback.PlaybackQueueState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AmplifySheet(
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF17101F),
        contentColor = AmplifyColors.TextPrimary,
        scrimColor = AmplifyColors.Scrim,
        dragHandle = { BottomSheetDefaults.DragHandle(color = AmplifyColors.HairlineStrong) },
    ) {
        content()
    }
}

/** Library browser matching the "Add Tracks" affordance in the reference playlist header. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmplifyAddTracksSheet(
    files: List<StoredFile>,
    queuedIds: Set<String>,
    artworkFor: suspend (String) -> ImageBitmap?,
    onAdd: (StoredFile) -> Unit,
    onAddAll: (List<StoredFile>) -> Unit,
    onPlay: (StoredFile) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val available = files.filter { it.stableId !in queuedIds }
    val visible = available.filter { file ->
        query.isBlank() || file.originalFilename.contains(query.trim(), ignoreCase = true)
    }
    AmplifySheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Add tracks", style = AmplifyType.sectionTitle)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${available.size} audio file${if (available.size == 1) "" else "s"} not in this queue",
                        style = AmplifyType.body,
                    )
                }
                if (available.isNotEmpty()) {
                    AmplifyPill(
                        text = "Add all",
                        onClick = { onAddAll(available) },
                        compact = true,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            AmplifySearchField(
                value = query,
                onValueChange = { query = it },
                placeholder = "Search your audio library",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            AmplifyLibraryList(
                files = visible,
                queuedIds = queuedIds,
                artworkFor = artworkFor,
                onPlay = { file ->
                    onPlay(file)
                    onDismiss()
                },
                onAdd = onAdd,
                modifier = Modifier.heightIn(max = 420.dp),
                emptyContent = {
                    Column(Modifier.padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (files.isEmpty()) "No audio files indexed yet"
                            else "Every audio file is already queued",
                            style = AmplifyType.trackTitle,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if (files.isEmpty()) "Audio saved in Telegram Saved Messages appears here after sync."
                            else "New audio files show up here as soon as TeleDrive indexes them.",
                            style = AmplifyType.body,
                        )
                    }
                },
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** Playback speed picker; the values mirror what ExoPlayer can honour. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmplifySpeedSheet(
    current: Float,
    onSelect: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
    AmplifySheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Text("Playback speed", style = AmplifyType.sectionTitle)
            Spacer(Modifier.height(4.dp))
            Text("Applies to the queue and is kept for the next track.", style = AmplifyType.body)
            Spacer(Modifier.height(14.dp))
            speeds.forEach { speed ->
                val selected = kotlin.math.abs(speed - current) < 0.01f
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) AmplifyColors.AccentSoft else Color.Transparent)
                        .clickable {
                            onSelect(speed)
                            onDismiss()
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (speed == 1f) "Normal (1×)" else "${trimSpeedLabel(speed)}×",
                        style = AmplifyType.label,
                        color = if (selected) AmplifyColors.Accent else AmplifyColors.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Icon(
                            Icons.Rounded.Check,
                            contentDescription = null,
                            tint = AmplifyColors.Accent,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/** Live queue with reorder/remove controls. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AmplifyQueueSheet(
    queue: PlaybackQueueState,
    isPlaying: Boolean,
    artworkFor: suspend (String) -> ImageBitmap?,
    onPlay: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    AmplifySheet(onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Queue", style = AmplifyType.sectionTitle)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${queue.items.size} track${if (queue.items.size == 1) "" else "s"} · ${playlistLengthLabel(playlistStats(queue.items))}",
                        style = AmplifyType.body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (queue.items.isNotEmpty()) {
                    AmplifyPill(text = "Clear", onClick = onClear, compact = true)
                }
            }
            Spacer(Modifier.height(8.dp))
            AmplifyQueueList(
                queue = queue,
                isPlaying = isPlaying,
                artworkFor = artworkFor,
                onPlay = { index ->
                    onPlay(index)
                    onDismiss()
                },
                onRemove = onRemove,
                onMove = onMove,
                modifier = Modifier.heightIn(max = 440.dp),
                emptyContent = {
                    Box(Modifier.fillMaxWidth().padding(vertical = 28.dp), contentAlignment = Alignment.Center) {
                        Text("The queue is empty", style = AmplifyType.body)
                    }
                },
            )
            Spacer(Modifier.height(20.dp))
        }
    }
}

private fun trimSpeedLabel(speed: Float): String =
    if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()
