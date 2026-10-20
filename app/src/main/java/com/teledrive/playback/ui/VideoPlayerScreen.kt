package com.teledrive.playback.ui

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.ui.PlayerView
import com.teledrive.playback.MediaPlayerViewModel
import com.teledrive.playback.PlaybackStatus
import kotlinx.coroutines.delay

private val speedOptions = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

@Composable
fun VideoPlayerScreen(
    stableId: String,
    allIds: List<String> = emptyList(),
    onBack: () -> Unit,
    viewModel: MediaPlayerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val player = remember { viewModel.controller.getPlayer() }
    val context = LocalContext.current
    val activity = context as? Activity
    val window = activity?.window

    var controlsVisible by remember { mutableStateOf(true) }
    var showSpeedMenu by remember { mutableStateOf(false) }
    var showSubtitleMenu by remember { mutableStateOf(false) }
    var isDragging by remember { mutableStateOf(false) }
    var gestureVolume by remember { mutableStateOf<Float?>(null) }
    var gestureBrightness by remember { mutableStateOf<Float?>(null) }
    var seekPreview by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(stableId) {
        viewModel.playFile(stableId, allIds)
    }

    // Immersive handling
    DisposableEffect(controlsVisible, uiState.player.status) {
        val insetsController = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        if (uiState.player.status == PlaybackStatus.PLAYING && !controlsVisible) {
            insetsController?.hide(WindowInsetsCompat.Type.systemBars())
            insetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { insetsController?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // Keep screen on while playing
    DisposableEffect(uiState.player.status) {
        if (uiState.player.status == PlaybackStatus.PLAYING) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Auto-hide controls
    LaunchedEffect(controlsVisible, uiState.player.status) {
        if (controlsVisible && uiState.player.status == PlaybackStatus.PLAYING) {
            delay(3000)
            controlsVisible = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { controlsVisible = !controlsVisible })
            }
    ) {
        // Video surface
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    keepScreenOn = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                    controllerShowTimeoutMs = 0
                    setBackgroundColor(android.graphics.Color.BLACK)
                    // Enable subtitle rendering via default cue view
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Gesture overlays for brightness/volume/seek
        Row(modifier = Modifier.fillMaxSize()) {
            // Left zone: brightness
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { isDragging = true },
                            onDragEnd = { isDragging = false; seekPreview = null },
                            onHorizontalDrag = { _, dragAmount ->
                                val duration = viewModel.playerState.value.progress.durationMs
                                if (duration > 0) {
                                    val delta = (dragAmount * 150).toLong() // scale
                                    val pos = (viewModel.playerState.value.progress.positionMs + delta).coerceIn(0, duration)
                                    seekPreview = pos
                                }
                            }
                        )
                    }
            )
            // Right zone: volume (handled via AudioManager by system, but we show gesture feedback)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }

        // Top bar
        AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent)))
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.Close, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(
                            uiState.queue.currentItem?.title ?: uiState.player.queue.currentItem?.file?.originalFilename ?: "Video",
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (uiState.queue.currentItem?.artist != null) {
                            Text(
                                uiState.queue.currentItem!!.artist!!,
                                color = Color.White.copy(0.7f),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                        }
                    }
                    // Queue indicator
                    if (uiState.queue.items.size > 1) {
                        Text(
                            "${uiState.queue.currentIndex + 1} / ${uiState.queue.items.size}",
                            color = Color.White.copy(0.8f),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.background(Color.White.copy(0.15f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    IconButton(onClick = { showSpeedMenu = true }) {
                        Icon(Icons.Rounded.Speed, contentDescription = "Playback speed", tint = Color.White)
                    }
                    IconButton(onClick = { showSubtitleMenu = true }) {
                        Icon(Icons.Rounded.Subtitles, contentDescription = "Subtitles", tint = Color.White)
                    }
                }
            }
        }

        // Center play/pause
        AnimatedVisibility(visible = controlsVisible || uiState.player.status != PlaybackStatus.PLAYING, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp), verticalAlignment = Alignment.CenterVertically) {
                if (uiState.queue.hasPrevious || uiState.queue.repeatMode.name == "ALL") {
                    IconButton(
                        onClick = { viewModel.previous() },
                        modifier = Modifier.size(54.dp).background(Color.White.copy(0.14f), CircleShape)
                    ) { Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", tint = Color.White, modifier = Modifier.size(30.dp)) }
                }
                IconButton(
                    onClick = { viewModel.togglePlayPause() },
                    modifier = Modifier.size(72.dp).background(Color.White, CircleShape)
                ) {
                    Icon(
                        if (uiState.player.status == PlaybackStatus.PLAYING) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (uiState.player.status == PlaybackStatus.PLAYING) "Pause" else "Play",
                        tint = Color.Black,
                        modifier = Modifier.size(40.dp)
                    )
                }
                if (uiState.queue.hasNext) {
                    IconButton(
                        onClick = { viewModel.next() },
                        modifier = Modifier.size(54.dp).background(Color.White.copy(0.14f), CircleShape)
                    ) { Icon(Icons.Rounded.SkipNext, contentDescription = "Next", tint = Color.White, modifier = Modifier.size(30.dp)) }
                }
            }
        }

        // Bottom controls
        AnimatedVisibility(visible = controlsVisible, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.78f))))
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Seek bar
                val progress = uiState.player.progress
                val duration = progress.durationMs.coerceAtLeast(1L)
                val pos = seekPreview ?: progress.positionMs
                Slider(
                    value = if (duration > 0) pos.toFloat() / duration else 0f,
                    onValueChange = { frac ->
                        isDragging = true
                        seekPreview = (frac * duration).toLong()
                    },
                    onValueChangeFinished = {
                        seekPreview?.let { viewModel.seekTo(it) }
                        isDragging = false
                        seekPreview = null
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(0.3f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        if (isDragging && seekPreview != null) formatTime(seekPreview!!) else formatTime(progress.positionMs),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium
                    )
                    Text(formatTime(progress.durationMs), color = Color.White.copy(0.8f), style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { viewModel.seekTo((progress.positionMs - 10_000).coerceAtLeast(0)) }) {
                            Icon(Icons.Rounded.Replay10, contentDescription = "Back 10s", tint = Color.White)
                        }
                        IconButton(onClick = { viewModel.togglePlayPause() }) {
                            Icon(if (progress.durationMs > 0 && uiState.player.status == PlaybackStatus.PLAYING) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = "Play/Pause", tint = Color.White)
                        }
                        IconButton(onClick = { viewModel.seekTo(progress.positionMs + 10_000) }) {
                            Icon(Icons.Rounded.Forward10, contentDescription = "Forward 10s", tint = Color.White)
                        }
                    }
                    // Speed badge
                    AssistChip(
                        onClick = { showSpeedMenu = true },
                        label = { Text("${progress.speed}×", color = Color.White) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = Color.White.copy(0.14f)),
                        border = null
                    )
                }
            }
        }

        // Loading / error overlays
        when (uiState.player.status) {
            PlaybackStatus.RESOLVING, PlaybackStatus.WAITING_FOR_DOWNLOAD, PlaybackStatus.PREPARING, PlaybackStatus.BUFFERING -> {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(0.35f)), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(color = Color.White)
                        Text(
                            when (uiState.player.status) {
                                PlaybackStatus.RESOLVING -> "Preparing media…"
                                PlaybackStatus.WAITING_FOR_DOWNLOAD -> "Downloading for playback…"
                                PlaybackStatus.PREPARING -> "Preparing video…"
                                else -> "Buffering…"
                            },
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (uiState.player.downloadProgress != null) {
                            LinearProgressIndicator(progress = { uiState.player.downloadProgress!! }, modifier = Modifier.fillMaxWidth(0.6f), color = Color.White, trackColor = Color.White.copy(0.2f))
                        }
                    }
                }
            }
            PlaybackStatus.ERROR -> {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(0.6f)), contentAlignment = Alignment.Center) {
                    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)), modifier = Modifier.padding(24.dp)) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = Color.White, modifier = Modifier.size(42.dp))
                            Text(uiState.player.error?.message ?: "Playback failed", color = Color.White, style = MaterialTheme.typography.titleMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (uiState.player.error?.retryable == true) {
                                    Button(onClick = { viewModel.playFile(stableId, allIds) }) { Text("Retry") }
                                }
                                OutlinedButton(onClick = onBack) { Text("Close", color = Color.White) }
                            }
                        }
                    }
                }
            }
            else -> Unit
        }
    }

    // Speed selector dialog
    if (showSpeedMenu) {
        AlertDialog(
            onDismissRequest = { showSpeedMenu = false },
            title = { Text("Playback speed") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    speedOptions.forEach { speed ->
                        val selected = uiState.player.progress.speed == speed
                        Row(
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { viewModel.setSpeed(speed); showSpeedMenu = false }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = selected, onClick = { viewModel.setSpeed(speed); showSpeedMenu = false })
                            Spacer(Modifier.width(8.dp))
                            Text("${speed}×", style = MaterialTheme.typography.bodyLarge)
                            if (speed == 1f) { Spacer(Modifier.width(6.dp)); Text("(Normal)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSpeedMenu = false }) { Text("Done") } }
        )
    }

    // Subtitle selector (uses ExoPlayer tracks if available)
    if (showSubtitleMenu) {
        val tracks = remember(player) {
            val groups = player.currentTracks.groups
            groups.filter { it.type == C.TRACK_TYPE_TEXT }
        }
        AlertDialog(
            onDismissRequest = { showSubtitleMenu = false },
            title = { Text("Subtitles") },
            text = {
                if (tracks.isEmpty()) {
                    Text("No subtitle tracks available for this video.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
                                showSubtitleMenu = false
                            }.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = player.trackSelectionParameters.isTrackTypeDisabled(C.TRACK_TYPE_TEXT), onClick = null)
                            Spacer(Modifier.width(8.dp)); Text("Off")
                        }
                        tracks.forEach { group ->
                            for (i in 0 until group.length) {
                                val format = group.getTrackFormat(i)
                                val label = format.label ?: format.language ?: "Subtitle ${i + 1}"
                                val isSelected = group.isTrackSelected(i)
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                                            .build()
                                        showSubtitleMenu = false
                                    }.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(selected = isSelected, onClick = null)
                                    Spacer(Modifier.width(8.dp)); Text(label)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSubtitleMenu = false }) { Text("Close") } }
        )
    }
}

private fun formatTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val s = (ms / 1000) % 60
    val m = (ms / 1000 / 60) % 60
    val h = ms / 1000 / 3600
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
