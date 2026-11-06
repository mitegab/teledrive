package com.teledrive.playback.ui.amplify

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teledrive.playback.PlaybackItem
import com.teledrive.playback.PlaybackStatus
import com.teledrive.playback.PlayerUiState
import com.teledrive.playback.RepeatMode
import com.teledrive.playback.ShuffleMode

/**
 * Waveform scrubber. Bar heights come from [waveformBars] (a stable signature per track),
 * while the played/remaining colouring and the playhead are driven by real playback position.
 * Tap or drag anywhere on the waveform to seek.
 */
@Composable
fun AmplifyWaveform(
    bars: List<Float>,
    progressFraction: Float,
    bufferedFraction: Float,
    enabled: Boolean,
    onSeekFraction: (Float) -> Unit,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 48.dp,
) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = (dragFraction ?: progressFraction).coerceIn(0f, 1f)
    val playedBrush = Brush.verticalGradient(listOf(AmplifyColors.Accent, AmplifyColors.AccentDeep))
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    if (size.width > 0) {
                        onSeekFraction((offset.x / size.width.toFloat()).coerceIn(0f, 1f))
                    }
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        if (size.width > 0) {
                            dragFraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        }
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        if (size.width > 0) {
                            dragFraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                        }
                    },
                    onDragEnd = {
                        dragFraction?.let(onSeekFraction)
                        dragFraction = null
                    },
                    onDragCancel = { dragFraction = null },
                )
            }
    ) {
        if (bars.isEmpty() || size.width <= 0f) return@Canvas
        val gap = size.width / (bars.size * 3f)
        val barWidth = gap * 2f
        val playedCount = fraction * bars.size
        val bufferedCount = bufferedFraction.coerceIn(0f, 1f) * bars.size
        bars.forEachIndexed { index, amplitude ->
            val barHeight = (size.height * amplitude).coerceAtLeast(2.dp.toPx())
            val top = (size.height - barHeight) / 2f
            val left = index * (barWidth + gap)
            val (brush, color) = when {
                index < playedCount -> playedBrush to AmplifyColors.Accent
                index < bufferedCount -> null to Color.White.copy(alpha = if (enabled) 0.26f else 0.12f)
                else -> null to Color.White.copy(alpha = if (enabled) 0.13f else 0.07f)
            }
            if (brush != null) {
                drawRoundRect(
                    brush = brush,
                    topLeft = Offset(left, top),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2f),
                )
            } else {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(left, top),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(barWidth / 2f),
                )
            }
        }
        // Playhead
        val playheadX = (fraction * size.width).coerceIn(0f, size.width)
        drawLine(
            color = Color.White.copy(alpha = 0.85f),
            start = Offset(playheadX, 0f),
            end = Offset(playheadX, size.height),
            strokeWidth = 1.5.dp.toPx(),
        )
    }
}

/**
 * Bottom transport bar: waveform, elapsed/remaining time, previous/skip-back/play/skip-forward/next,
 * volume and the queue + speed shortcuts.
 */
@Composable
fun AmplifyTimelineBar(
    player: PlayerUiState,
    current: PlaybackItem?,
    queueRepeat: RepeatMode,
    queueShuffle: ShuffleMode,
    compact: Boolean,
    waveform: List<Float>,
    onSeek: (Long) -> Unit,
    onSeekRelative: (Long) -> Unit,
    onTogglePlayPause: () -> Unit,
    onPreviousTrack: () -> Unit,
    onNextTrack: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleShuffle: () -> Unit,
    onSpeedClick: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = player.progress
    val duration = progress.durationMs.takeIf { it > 0L } ?: current?.durationMs ?: 0L
    val enabled = duration > 0L
    val fraction = if (enabled) (progress.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val bufferedFraction = if (enabled) (progress.bufferedMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val isPlaying = player.status == PlaybackStatus.PLAYING
    val isPreparing = player.status == PlaybackStatus.BUFFERING ||
        player.status == PlaybackStatus.PREPARING ||
        player.status == PlaybackStatus.WAITING_FOR_DOWNLOAD ||
        player.status == PlaybackStatus.RESOLVING

    Column(
        modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.34f)),
                )
            ),
    ) {
        AmplifyDivider()
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("AUDIO TIMELINE", style = AmplifyType.eyebrow.copy(fontSize = 9.sp))
                    if (!compact) {
                        Text(
                            current?.let { listOfNotNull(it.title, it.artist).joinToString(" — ") }
                                ?: "Queue a track to start the timeline",
                            style = AmplifyType.meta,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                AmplifyIconButton(
                    icon = if (queueRepeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    contentDescription = when (queueRepeat) {
                        RepeatMode.OFF -> "Repeat off"
                        RepeatMode.ALL -> "Repeat queue"
                        RepeatMode.ONE -> "Repeat track"
                    },
                    onClick = onToggleRepeat,
                    active = queueRepeat != RepeatMode.OFF,
                    size = 30.dp,
                    iconSize = 15.dp,
                )
                Spacer(Modifier.width(6.dp))
                AmplifyIconButton(
                    icon = Icons.Rounded.Shuffle,
                    contentDescription = "Shuffle",
                    onClick = onToggleShuffle,
                    active = queueShuffle == ShuffleMode.ON,
                    size = 30.dp,
                    iconSize = 15.dp,
                )
                Spacer(Modifier.width(6.dp))
                AmplifyPill(
                    text = "${trimSpeed(progress.speed)}×",
                    onClick = onSpeedClick,
                    compact = true,
                )
                Spacer(Modifier.width(6.dp))
                AmplifyIconButton(
                    icon = Icons.Rounded.QueueMusic,
                    contentDescription = "Open queue",
                    onClick = onOpenQueue,
                    size = 30.dp,
                    iconSize = 15.dp,
                )
            }
            Spacer(Modifier.height(4.dp))
            AmplifyWaveform(
                bars = waveform,
                progressFraction = fraction,
                bufferedFraction = bufferedFraction,
                enabled = enabled,
                onSeekFraction = { value -> onSeek((value * duration).toLong()) },
                height = if (compact) 40.dp else 52.dp,
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(0.8f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            amplifyClock(progress.positionMs),
                            style = AmplifyType.numeric.copy(color = AmplifyColors.TextPrimary, fontSize = 10.sp),
                        )
                        Text(
                            " / ${amplifyClock(if (duration > 0L) duration else null)}",
                            style = AmplifyType.numeric,
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 6.dp),
                ) {
                    AmplifyIconButton(
                        icon = Icons.Rounded.SkipPrevious,
                        contentDescription = "Previous track",
                        onClick = onPreviousTrack,
                        size = 32.dp,
                        iconSize = 17.dp,
                    )
                    AmplifyIconButton(
                        icon = Icons.Rounded.Replay10,
                        contentDescription = "Back 10 seconds",
                        onClick = { onSeekRelative(-10_000L) },
                        size = 32.dp,
                        iconSize = 17.dp,
                        enabled = enabled,
                    )
                    Box(
                        Modifier
                            .size(if (compact) 44.dp else 50.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(AmplifyColors.Accent, AmplifyColors.AccentDeep),
                                )
                            )
                            .clickable(onClick = onTogglePlayPause),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isPreparing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = AmplifyColors.BackgroundBottom,
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(
                                if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = AmplifyColors.BackgroundBottom,
                                modifier = Modifier.size(if (compact) 21.dp else 24.dp),
                            )
                        }
                    }
                    AmplifyIconButton(
                        icon = Icons.Rounded.Forward10,
                        contentDescription = "Forward 10 seconds",
                        onClick = { onSeekRelative(10_000L) },
                        size = 32.dp,
                        iconSize = 17.dp,
                        enabled = enabled,
                    )
                    AmplifyIconButton(
                        icon = Icons.Rounded.SkipNext,
                        contentDescription = "Next track",
                        onClick = onNextTrack,
                        size = 32.dp,
                        iconSize = 17.dp,
                    )
                }
                Box(Modifier.weight(0.8f), contentAlignment = Alignment.CenterEnd) {
                    if (!compact) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            AmplifyIconButton(
                                icon = if (player.volume <= 0.01f) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                                contentDescription = if (player.volume <= 0.01f) "Unmute" else "Mute",
                                onClick = { onVolumeChange(if (player.volume <= 0.01f) 1f else 0f) },
                                size = 30.dp,
                                iconSize = 16.dp,
                            )
                            Slider(
                                value = player.volume,
                                onValueChange = onVolumeChange,
                                modifier = Modifier.width(84.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = AmplifyColors.TextPrimary,
                                    activeTrackColor = AmplifyColors.Accent,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.18f),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun trimSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()
