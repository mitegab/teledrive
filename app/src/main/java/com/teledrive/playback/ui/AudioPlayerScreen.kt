package com.teledrive.playback.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teledrive.playback.MediaPlayerViewModel
import com.teledrive.playback.PlaybackStatus
import com.teledrive.playback.RepeatMode
import com.teledrive.playback.ShuffleMode

private val speedOptions = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

@Composable
fun AudioPlayerScreen(
    onBack: () -> Unit,
    stableId: String? = null,
    allIds: List<String> = emptyList(),
    viewModel: MediaPlayerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playerState = uiState.player
    val queue = uiState.queue
    var showQueue by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }

    val current = queue.currentItem
    val progress = playerState.progress

    LaunchedEffect(stableId) {
        if (stableId != null) viewModel.playFile(stableId, allIds)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Now Playing", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Rounded.ExpandMore, contentDescription = "Close") } },
                actions = {
                    IconButton(onClick = { showQueue = !showQueue }) { Icon(Icons.Rounded.QueueMusic, contentDescription = "Queue") }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        if (showQueue) {
            QueueSheet(
                queue = queue,
                onSelect = { idx -> viewModel.playAt(idx) },
                onRemove = { idx -> viewModel.removeFromQueue(idx) },
                onMove = { from, to -> viewModel.moveInQueue(from, to) },
                onDismiss = { showQueue = false },
                modifier = Modifier.padding(padding)
            )
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Spacer(Modifier.height(8.dp))
                // Artwork
                ArtworkView(current?.file?.originalFilename ?: "Audio", current?.localPath, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(20.dp)))

                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        current?.title ?: current?.file?.originalFilename ?: "No track",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        current?.artist ?: current?.album ?: current?.file?.mimeType ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (playerState.availability.name == "DOWNLOADING" || playerState.status == PlaybackStatus.WAITING_FOR_DOWNLOAD) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Text("Downloading…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                // Progress
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Slider(
                        value = if (progress.durationMs > 0) progress.positionMs.toFloat() / progress.durationMs else 0f,
                        onValueChange = { frac -> viewModel.seekTo((frac * progress.durationMs).toLong()) },
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(formatTime(progress.positionMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(formatTime(progress.durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // Main controls
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.toggleShuffle() }, modifier = Modifier.size(44.dp)) {
                        Icon(Icons.Rounded.Shuffle, contentDescription = "Shuffle", tint = if (queue.shuffleMode == ShuffleMode.ON) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { viewModel.previous() }, modifier = Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)) {
                        Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(32.dp))
                    }
                    FilledIconButton(onClick = { viewModel.togglePlayPause() }, modifier = Modifier.size(72.dp)) {
                        Icon(
                            if (playerState.status == PlaybackStatus.PLAYING) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (playerState.status == PlaybackStatus.PLAYING) "Pause" else "Play",
                            modifier = Modifier.size(40.dp)
                        )
                    }
                    IconButton(onClick = { viewModel.next() }, modifier = Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)) {
                        Icon(Icons.Rounded.SkipNext, contentDescription = "Next", modifier = Modifier.size(32.dp))
                    }
                    IconButton(onClick = { viewModel.toggleRepeat() }, modifier = Modifier.size(44.dp)) {
                        val icon = when (queue.repeatMode) {
                            RepeatMode.OFF -> Icons.Rounded.Repeat
                            RepeatMode.ONE -> Icons.Rounded.RepeatOne
                            RepeatMode.ALL -> Icons.Rounded.Repeat
                        }
                        Icon(icon, contentDescription = "Repeat", tint = if (queue.repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // Secondary row
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { viewModel.seekTo((progress.positionMs - 15_000).coerceAtLeast(0)) }) {
                        Icon(Icons.Rounded.Replay10, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("15s")
                    }
                    AssistChip(onClick = { showSpeed = true }, label = { Text("${progress.speed}×") }, leadingIcon = { Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(16.dp)) })
                    TextButton(onClick = { viewModel.seekTo(progress.positionMs + 15_000) }) {
                        Text("15s"); Spacer(Modifier.width(4.dp)); Icon(Icons.Rounded.Forward10, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                }

                if (playerState.status == PlaybackStatus.ERROR) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                            Text(playerState.error?.message ?: "Playback error", color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.weight(1f))
                            if (playerState.error?.retryable == true) {
                                TextButton(onClick = { current?.let { viewModel.playFile(it.file.stableId) } }) { Text("Retry") }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (showSpeed) {
        AlertDialog(
            onDismissRequest = { showSpeed = false },
            title = { Text("Playback speed") },
            text = {
                Column {
                    speedOptions.forEach { speed ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { viewModel.setSpeed(speed); showSpeed = false }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = progress.speed == speed, onClick = { viewModel.setSpeed(speed); showSpeed = false })
                            Spacer(Modifier.width(8.dp))
                            Text("${speed}×")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSpeed = false }) { Text("Done") } }
        )
    }
}

@Composable
private fun ArtworkView(name: String, localPath: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(localPath) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(localPath) {
        if (localPath == null) { bitmap = null; return@LaunchedEffect }
        // Try to extract embedded artwork via MediaMetadataRetriever already handled, but fallback to decoding file if it's image-like audio cover?
        // For now try to load directly via retriever in background
        bitmap = try {
            val retriever = android.media.MediaMetadataRetriever()
            retriever.setDataSource(localPath)
            val art = retriever.embeddedPicture
            retriever.release()
            art?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        } catch (_: Exception) { null }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap!!.asImageBitmap(), contentDescription = name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Rounded.MusicNote, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                Text(name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
private fun QueueSheet(
    queue: com.teledrive.playback.PlaybackQueueState,
    onSelect: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Queue • ${queue.items.size} tracks", style = MaterialTheme.typography.titleMedium)
            IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = "Close queue") }
        }
        if (queue.items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Queue is empty", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(bottom = 80.dp)) {
                itemsIndexed(queue.items) { index, item ->
                    val isCurrent = index == queue.currentIndex
                    Card(
                        colors = CardDefaults.cardColors(containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().clickable { onSelect(index) }
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (isCurrent) Icon(Icons.Rounded.Equalizer, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            else Text("${index + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(20.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal)
                                Text(item.artist ?: item.file.originalFilename, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { onRemove(index) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Close, contentDescription = "Remove", modifier = Modifier.size(18.dp)) }
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val s = (ms / 1000) % 60
    val m = (ms / 1000 / 60) % 60
    val h = ms / 1000 / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
