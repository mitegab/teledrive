package com.teledrive.playback.ui.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.teledrive.playback.ui.amplify.AmplifyColors
import com.teledrive.playback.ui.amplify.AmplifyType
import com.teledrive.playback.video.formatClock
import kotlin.math.roundToInt

fun fractionOf(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return 0f
    return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}

fun positionForFraction(fraction: Float, durationMs: Long): Long {
    if (durationMs <= 0L) return 0L
    return (fraction.coerceIn(0f, 1f) * durationMs.toFloat()).toLong().coerceIn(0L, durationMs)
}

/**
 * Timeline with a frame preview while scrubbing.
 *
 * Tapping seeks immediately; dragging only *previews* — the player is seeked once on release,
 * so a long drag across a large file does not fire dozens of seeks.
 */
@Composable
fun VideoScrubber(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    localPath: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onScrubStart: () -> Unit = {},
    onScrubEnd: () -> Unit = {},
    onSeek: (Long) -> Unit,
) {
    var widthPx by remember { mutableStateOf(0) }
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    var dragging by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val bubbleWidth = 168.dp
    val bubbleWidthPx = with(density) { bubbleWidth.toPx() }

    val shownPosition = scrubMs ?: positionMs
    val fraction = fractionOf(shownPosition, durationMs)

    Column(modifier.fillMaxWidth()) {
        AnimatedVisibility(visible = dragging, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxWidth().height(96.dp)) {
                val available = (widthPx - bubbleWidthPx).coerceAtLeast(0f)
                val centre = fraction * widthPx
                val x = (centre - bubbleWidthPx / 2f).coerceIn(0f, available)
                Row(
                    modifier = Modifier
                        .offset { IntOffset(x.roundToInt(), 0) }
                        .width(bubbleWidth)
                        .padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    VideoFramePreview(
                        localPath = localPath,
                        timeMs = shownPosition,
                        modifier = Modifier
                            .width(bubbleWidth)
                            .height(90.dp),
                    )
                }
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        "${formatClock(shownPosition)} / ${formatClock(durationMs)}",
                        style = AmplifyType.numeric,
                        color = AmplifyColors.TextPrimary,
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .onSizeChanged { widthPx = it.width }
                .pointerInput(enabled, durationMs, widthPx) {
                    if (!enabled || durationMs <= 0L || widthPx <= 0) return@pointerInput
                    detectTapGestures { offset ->
                        onSeek(positionForFraction(offset.x / widthPx.toFloat(), durationMs))
                    }
                }
                .pointerInput(enabled, durationMs, widthPx, localPath) {
                    if (!enabled || durationMs <= 0L || widthPx <= 0) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            scrubMs = positionForFraction(offset.x / widthPx.toFloat(), durationMs)
                            onScrubStart()
                        },
                        onDragEnd = {
                            val target = scrubMs
                            dragging = false
                            scrubMs = null
                            onScrubEnd()
                            if (target != null) onSeek(target)
                        },
                        onDragCancel = {
                            dragging = false
                            scrubMs = null
                            onScrubEnd()
                        },
                        onHorizontalDrag = { change, _ ->
                            scrubMs = positionForFraction(
                                change.position.x / widthPx.toFloat(),
                                durationMs,
                            )
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            val played = fraction
            val buffered = fractionOf(bufferedMs, durationMs)
            Canvas(Modifier.fillMaxWidth().height(26.dp)) {
                val trackHeight = if (dragging) 6.dp.toPx() else 4.dp.toPx()
                val centreY = size.height / 2f
                val radius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
                val trackTop = centreY - trackHeight / 2f

                drawRoundRect(
                    color = Color.White.copy(alpha = 0.18f),
                    topLeft = Offset(0f, trackTop),
                    size = Size(size.width, trackHeight),
                    cornerRadius = radius,
                )
                if (buffered > 0f) {
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.32f),
                        topLeft = Offset(0f, trackTop),
                        size = Size(size.width * buffered, trackHeight),
                        cornerRadius = radius,
                    )
                }
                if (played > 0f) {
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            listOf(AmplifyColors.AccentDeep, AmplifyColors.Accent),
                        ),
                        topLeft = Offset(0f, trackTop),
                        size = Size(size.width * played, trackHeight),
                        cornerRadius = radius,
                    )
                }
                val thumbRadius = if (dragging) 8.dp.toPx() else 6.dp.toPx()
                val thumbX = size.width * played
                if (dragging) {
                    drawCircle(
                        color = AmplifyColors.Accent.copy(alpha = 0.25f),
                        radius = thumbRadius * 2.1f,
                        center = Offset(thumbX, centreY),
                    )
                }
                drawCircle(
                    color = AmplifyColors.TextPrimary,
                    radius = thumbRadius,
                    center = Offset(thumbX, centreY),
                )
                drawCircle(
                    color = AmplifyColors.AccentDeep,
                    radius = thumbRadius * 0.55f,
                    center = Offset(thumbX, centreY),
                )
            }
        }
    }
}

/** Small tick row used above the scrubber to hint where the seek buttons will jump. */
@Composable
fun SeekHintRow(
    positionMs: Long,
    durationMs: Long,
    skipIntervalMs: Long,
    modifier: Modifier = Modifier,
) {
    val back = (positionMs - skipIntervalMs).coerceAtLeast(0L)
    val forward = (positionMs + skipIntervalMs).coerceAtMost(durationMs)
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("−${skipIntervalMs / 1000}s → ${formatClock(back)}", style = AmplifyType.meta)
        Text("+${skipIntervalMs / 1000}s → ${formatClock(forward)}", style = AmplifyType.meta)
    }
}
