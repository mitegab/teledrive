package com.teledrive.playback.ui.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.ClosedCaptionOff
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teledrive.playback.ui.amplify.AmplifyColors
import com.teledrive.playback.ui.amplify.AmplifyIconButton
import com.teledrive.playback.ui.amplify.AmplifyPill
import com.teledrive.playback.ui.amplify.AmplifyType
import com.teledrive.playback.ui.amplify.amplifyClickable
import com.teledrive.playback.video.formatClock
import com.teledrive.playback.video.playbackRateLabel
import com.teledrive.playback.video.remainingClock

/** Top overlay: title, technical badge, queue position and the window-level actions. */
@Composable
fun VideoTopBar(
    title: String,
    subtitle: String?,
    badge: String?,
    queueLabel: String?,
    captionsOn: Boolean,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onLibrary: () -> Unit,
    onLock: () -> Unit,
    onPip: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent),
                )
            )
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AmplifyIconButton(
            icon = Icons.Rounded.KeyboardArrowLeft,
            contentDescription = "Back",
            onClick = onBack,
            size = 38.dp,
            iconSize = 22.dp,
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = AmplifyType.trackTitle.copy(fontSize = 14.sp),
                color = AmplifyColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(subtitle, badge).filter { it.isNotBlank() }.joinToString(" · ")
            if (meta.isNotBlank()) {
                Text(
                    meta,
                    style = AmplifyType.meta,
                    color = AmplifyColors.TextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (queueLabel != null) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(queueLabel, style = AmplifyType.numeric, color = AmplifyColors.TextPrimary)
            }
            Spacer(Modifier.width(8.dp))
        }
        if (!captionsOn) {
            Icon(
                Icons.Rounded.ClosedCaptionOff,
                contentDescription = "Captions off",
                tint = AmplifyColors.TextMuted,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        AmplifyIconButton(
            icon = Icons.Rounded.VideoLibrary,
            contentDescription = "Video library",
            onClick = onLibrary,
        )
        Spacer(Modifier.width(6.dp))
        AmplifyIconButton(
            icon = Icons.Rounded.PictureInPictureAlt,
            contentDescription = "Picture in picture",
            onClick = onPip,
        )
        Spacer(Modifier.width(6.dp))
        AmplifyIconButton(
            icon = Icons.Rounded.Lock,
            contentDescription = "Lock controls",
            onClick = onLock,
        )
        Spacer(Modifier.width(6.dp))
        AmplifyIconButton(
            icon = Icons.Rounded.Settings,
            contentDescription = "Playback settings",
            onClick = onSettings,
        )
    }
}

/** Centre transport: frame steps flank the skip buttons, which flank play/pause. */
@Composable
fun VideoCenterControls(
    playing: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    skipSeconds: Int,
    modifier: Modifier = Modifier,
    onPrevious: () -> Unit,
    onSkipBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSkipForward: () -> Unit,
    onNext: () -> Unit,
    onFrameBack: () -> Unit,
    onFrameForward: () -> Unit,
) {
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleAction(
            icon = Icons.Rounded.SkipPrevious,
            description = "Previous video",
            enabled = hasPrevious,
            size = 46.dp,
            onClick = onPrevious,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                CircleAction(
                    icon = Icons.Rounded.Replay10,
                    description = "Back $skipSeconds seconds",
                    size = 52.dp,
                    onClick = onSkipBack,
                )
                Box(
                    Modifier
                        .size(74.dp)
                        .clip(CircleShape)
                        .background(AmplifyColors.TextPrimary)
                        .border(1.dp, AmplifyColors.Accent.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    AmplifyIconButton(
                        icon = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (playing) "Pause" else "Play",
                        onClick = onPlayPause,
                        size = 74.dp,
                        iconSize = 36.dp,
                    )
                }
                CircleAction(
                    icon = Icons.Rounded.Forward10,
                    description = "Forward $skipSeconds seconds",
                    size = 52.dp,
                    onClick = onSkipForward,
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FrameStepButton(Icons.Rounded.KeyboardArrowLeft, "Previous frame", onFrameBack)
                Text(
                    "frame by frame",
                    style = AmplifyType.meta,
                    color = AmplifyColors.TextMuted,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                FrameStepButton(Icons.Rounded.KeyboardArrowRight, "Next frame", onFrameForward)
            }
        }
        CircleAction(
            icon = Icons.Rounded.SkipNext,
            description = "Next video",
            enabled = hasNext,
            size = 46.dp,
            onClick = onNext,
        )
    }
}

@Composable
private fun CircleAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    size: androidx.compose.ui.unit.Dp,
    enabled: Boolean = true,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 0.14f else 0.05f))
            .amplifyClickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (enabled) AmplifyColors.TextPrimary else AmplifyColors.TextMuted.copy(alpha = 0.5f),
            modifier = Modifier.size(size * 0.46f),
        )
    }
}

@Composable
private fun FrameStepButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(Color.White.copy(alpha = 0.10f))
            .amplifyClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = AmplifyColors.TextPrimary, modifier = Modifier.size(18.dp))
    }
}

/** Time ruler + quick actions stacked at the bottom edge. */
@Composable
fun VideoBottomBar(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    localPath: String?,
    playing: Boolean,
    speed: Float,
    volume: Float,
    captionsOn: Boolean,
    captionsAvailable: Boolean,
    showRemaining: Boolean,
    orientationLabel: String,
    modifier: Modifier = Modifier,
    onSeek: (Long) -> Unit,
    onScrubStart: () -> Unit,
    onScrubEnd: () -> Unit,
    onToggleTimeMode: () -> Unit,
    onPlayPause: () -> Unit,
    onVolumeChange: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onSpeedClick: () -> Unit,
    onCaptionsClick: () -> Unit,
    onOrientationClick: () -> Unit,
    onPip: () -> Unit,
    onLibrary: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f)),
                )
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatClock(positionMs),
                style = AmplifyType.numeric,
                color = AmplifyColors.TextPrimary,
                modifier = Modifier.amplifyClickable(onClick = onToggleTimeMode),
            )
            Spacer(Modifier.width(10.dp))
            VideoScrubber(
                positionMs = positionMs,
                bufferedMs = bufferedMs,
                durationMs = durationMs,
                localPath = localPath,
                modifier = Modifier.weight(1f),
                onScrubStart = onScrubStart,
                onScrubEnd = onScrubEnd,
                onSeek = onSeek,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                if (showRemaining) remainingClock(positionMs, durationMs) else formatClock(durationMs),
                style = AmplifyType.numeric,
                color = AmplifyColors.TextSecondary,
                modifier = Modifier.amplifyClickable(onClick = onToggleTimeMode),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AmplifyIconButton(
                icon = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                onClick = onPlayPause,
                size = 34.dp,
            )
            AmplifyIconButton(
                icon = if (volume <= 0.01f) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                contentDescription = if (volume <= 0.01f) "Unmute" else "Mute",
                onClick = onToggleMute,
                size = 34.dp,
                active = volume <= 0.01f,
            )
            VolumeSlider(volume = volume, onVolumeChange = onVolumeChange)
            AmplifyPill(
                text = playbackRateLabel(speed),
                icon = Icons.Rounded.Speed,
                onClick = onSpeedClick,
                compact = true,
            )
            AmplifyPill(
                text = if (captionsOn) "CC on" else "CC off",
                icon = if (captionsOn) Icons.Rounded.ClosedCaption else Icons.Rounded.ClosedCaptionOff,
                onClick = onCaptionsClick,
                selected = captionsOn && captionsAvailable,
                compact = true,
            )
            AmplifyPill(
                text = orientationLabel,
                icon = Icons.Rounded.ScreenRotation,
                onClick = onOrientationClick,
                compact = true,
            )
            AmplifyPill(
                text = "Playlist",
                icon = Icons.Rounded.VideoLibrary,
                onClick = onLibrary,
                compact = true,
            )
            AmplifyPill(text = "PiP", onClick = onPip, compact = true)
            AmplifyPill(
                text = "Settings",
                icon = Icons.Rounded.Settings,
                onClick = onSettings,
                compact = true,
            )
        }
    }
}

/** Compact volume slider: 96dp wide so it fits next to the transport buttons. */
@Composable
fun VolumeSlider(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp = 96.dp,
) {
    androidx.compose.material3.Slider(
        value = volume.coerceIn(0f, 1f),
        onValueChange = onVolumeChange,
        modifier = modifier.width(width),
        colors = androidx.compose.material3.SliderDefaults.colors(
            thumbColor = AmplifyColors.TextPrimary,
            activeTrackColor = AmplifyColors.Accent,
            inactiveTrackColor = Color.White.copy(alpha = 0.22f),
        ),
    )
}

/** Transient HUD shown while dragging for brightness/volume or double-tapping to seek. */
@Composable
fun GestureHud(
    icon: ImageVector?,
    text: String,
    modifier: Modifier = Modifier,
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = true,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Row(
            modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = AmplifyColors.Accent, modifier = Modifier.size(18.dp))
            }
            Text(text, style = AmplifyType.label, color = AmplifyColors.TextPrimary)
        }
    }
}

/** Shown while the controls are locked: only the unlock affordance survives. */
@Composable
fun LockedChrome(
    modifier: Modifier = Modifier,
    onUnlock: () -> Unit,
) {
    Box(modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.BottomStart) {
        Row(
            Modifier
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.6f))
                .amplifyClickable(onClick = onUnlock)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Rounded.LockOpen, contentDescription = "Unlock controls", tint = AmplifyColors.Accent, modifier = Modifier.size(16.dp))
            Text("Tap to unlock", style = AmplifyType.meta, color = AmplifyColors.TextSecondary)
        }
    }
}

/** Status banner for buffering / errors, kept separate from the transport so it can stay visible. */
@Composable
fun VideoStatusBanner(text: String, isError: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = text.isNotBlank(), enter = fadeIn(), exit = fadeOut()) {
        Row(
            modifier
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (isError) AmplifyColors.Danger.copy(alpha = 0.18f)
                    else Color.Black.copy(alpha = 0.62f)
                )
                .border(
                    1.dp,
                    if (isError) AmplifyColors.Danger.copy(alpha = 0.45f) else AmplifyColors.Hairline,
                    RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text,
                style = AmplifyType.label,
                color = if (isError) AmplifyColors.Danger else AmplifyColors.TextSecondary,
            )
        }
    }
}
