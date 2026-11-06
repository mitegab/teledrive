package com.teledrive.playback.ui.video

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.teledrive.playback.AudioEffectsState
import com.teledrive.playback.RepeatMode
import com.teledrive.playback.ui.amplify.AmplifyColors
import com.teledrive.playback.ui.amplify.AmplifyDivider
import com.teledrive.playback.ui.amplify.AmplifyPill
import com.teledrive.playback.ui.amplify.AmplifySectionLabel
import com.teledrive.playback.ui.amplify.AmplifySelect
import com.teledrive.playback.ui.amplify.AmplifySheet
import com.teledrive.playback.ui.amplify.AmplifyType
import com.teledrive.playback.ui.amplify.GlassPanel
import com.teledrive.playback.ui.amplify.amplifyClickable
import com.teledrive.playback.ui.amplify.sizeLabel
import com.teledrive.playback.video.CaptionPalette
import com.teledrive.playback.video.CaptionSize
import com.teledrive.playback.video.EngineSupport
import com.teledrive.playback.video.MediaTrackOption
import com.teledrive.playback.video.MediaTrackSnapshot
import com.teledrive.playback.video.ScreenOrientation
import com.teledrive.playback.video.VideoAspect
import com.teledrive.playback.video.VideoDecoderStats
import com.teledrive.playback.video.VideoDimensions
import com.teledrive.playback.video.VideoPlaybackOptions
import com.teledrive.playback.video.audioCapabilities
import com.teledrive.playback.video.bitrateLabel
import com.teledrive.playback.video.channelCountLabel
import com.teledrive.playback.video.codecLabel
import com.teledrive.playback.video.formatClock
import com.teledrive.playback.video.frameRateLabel
import com.teledrive.playback.video.hdrFromColorTransfer
import com.teledrive.playback.video.languageLabel
import com.teledrive.playback.video.playbackRateLabel
import com.teledrive.playback.video.resolutionLabel
import com.teledrive.playback.video.skipIntervalChoices
import com.teledrive.playback.video.videoCapabilities
import com.teledrive.playback.video.videoExtensionLabel
import kotlin.math.roundToInt

val videoSpeedOptions = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

data class VideoSettingsState(
    val options: VideoPlaybackOptions = VideoPlaybackOptions(),
    val speed: Float = 1f,
    val volume: Float = 1f,
    val tracks: MediaTrackSnapshot = MediaTrackSnapshot(),
    val dimensions: VideoDimensions = VideoDimensions(),
    val decoder: VideoDecoderStats? = null,
    val durationMs: Long = 0L,
    val fileSizeBytes: Long? = null,
    val filename: String = "",
    val mimeType: String? = null,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffleEnabled: Boolean = false,
    val audioEffects: AudioEffectsState = AudioEffectsState(),
    val resumeMs: Long = 0L,
)

enum class VideoSettingsTab(val label: String, val icon: ImageVector) {
    PLAYBACK("Playback", Icons.Rounded.Speed),
    SUBTITLES("Captions", Icons.Rounded.Subtitles),
    AUDIO("Audio", Icons.Rounded.GraphicEq),
    DISPLAY("Display", Icons.Rounded.Tune),
    INFO("Info", Icons.Rounded.Info),
}

@Composable
fun VideoSettingsSheet(
    state: VideoSettingsState,
    onDismiss: () -> Unit,
    onSpeed: (Float) -> Unit,
    onVolume: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onSkipInterval: (Long) -> Unit,
    onRepeatMode: (RepeatMode) -> Unit,
    onToggleShuffle: () -> Unit,
    onRestart: () -> Unit,
    onResume: () -> Unit,
    onCaptionsEnabled: (Boolean) -> Unit,
    onCaptionSize: (CaptionSize) -> Unit,
    onCaptionPalette: (CaptionPalette) -> Unit,
    onSelectTextTrack: (MediaTrackOption) -> Unit,
    onAutomaticText: () -> Unit,
    onSelectAudioTrack: (MediaTrackOption) -> Unit,
    onAutomaticAudio: () -> Unit,
    onSelectVideoTrack: (MediaTrackOption) -> Unit,
    onAspect: (VideoAspect) -> Unit,
    onRotate: () -> Unit,
    onResetRotation: () -> Unit,
    onOrientation: (ScreenOrientation) -> Unit,
    onEqualizerEnabled: (Boolean) -> Unit,
    onEqualizerPreset: (Int) -> Unit,
    onEqualizerBand: (Int, Int) -> Unit,
    onLoudnessGain: (Int) -> Unit,
    onResetAudioEffects: () -> Unit,
) {
    var tab by remember { mutableStateOf(VideoSettingsTab.PLAYBACK) }
    AmplifySheet(onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 26.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Video settings", style = AmplifyType.sectionTitle, color = AmplifyColors.TextPrimary)
                    Text(
                        state.filename.takeIf { it.isNotBlank() } ?: "Nothing playing",
                        style = AmplifyType.meta,
                        color = AmplifyColors.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VideoSettingsTab.entries.forEach { entry ->
                    AmplifyPill(
                        text = entry.label,
                        icon = entry.icon,
                        selected = tab == entry,
                        compact = true,
                        onClick = { tab = entry },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (tab) {
                    VideoSettingsTab.PLAYBACK -> PlaybackTab(
                        state = state,
                        onSpeed = onSpeed,
                        onVolume = onVolume,
                        onToggleMute = onToggleMute,
                        onSkipInterval = onSkipInterval,
                        onRepeatMode = onRepeatMode,
                        onToggleShuffle = onToggleShuffle,
                        onRestart = onRestart,
                        onResume = onResume,
                    )
                    VideoSettingsTab.SUBTITLES -> SubtitlesTab(
                        state = state,
                        onCaptionsEnabled = onCaptionsEnabled,
                        onCaptionSize = onCaptionSize,
                        onCaptionPalette = onCaptionPalette,
                        onSelectTextTrack = onSelectTextTrack,
                        onAutomaticText = onAutomaticText,
                    )
                    VideoSettingsTab.AUDIO -> AudioTab(
                        state = state,
                        onSelectAudioTrack = onSelectAudioTrack,
                        onAutomaticAudio = onAutomaticAudio,
                        onEqualizerEnabled = onEqualizerEnabled,
                        onEqualizerPreset = onEqualizerPreset,
                        onEqualizerBand = onEqualizerBand,
                        onLoudnessGain = onLoudnessGain,
                        onResetAudioEffects = onResetAudioEffects,
                    )
                    VideoSettingsTab.DISPLAY -> DisplayTab(
                        state = state,
                        onAspect = onAspect,
                        onRotate = onRotate,
                        onResetRotation = onResetRotation,
                        onOrientation = onOrientation,
                        onSelectVideoTrack = onSelectVideoTrack,
                    )
                    VideoSettingsTab.INFO -> InfoTab(state = state)
                }
            }
        }
    }
}

// ---- tabs ----------------------------------------------------------------------

@Composable
private fun PlaybackTab(
    state: VideoSettingsState,
    onSpeed: (Float) -> Unit,
    onVolume: (Float) -> Unit,
    onToggleMute: () -> Unit,
    onSkipInterval: (Long) -> Unit,
    onRepeatMode: (RepeatMode) -> Unit,
    onToggleShuffle: () -> Unit,
    onRestart: () -> Unit,
    onResume: () -> Unit,
) {
    SectionCard("Speed") {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            videoSpeedOptions.forEach { option ->
                AmplifyPill(
                    text = playbackRateLabel(option),
                    selected = kotlin.math.abs(state.speed - option) < 0.001f,
                    compact = true,
                    onClick = { onSpeed(option) },
                )
            }
        }
    }

    SectionCard("Volume") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            VolumeSlider(volume = state.volume, onVolumeChange = onVolume, width = 200.dp)
            Spacer(Modifier.width(12.dp))
            Text("${(state.volume * 100).roundToInt()}%", style = AmplifyType.numeric)
            Spacer(Modifier.weight(1f))
            AmplifyPill(
                text = if (state.volume <= 0.01f) "Unmute" else "Mute",
                selected = state.volume <= 0.01f,
                compact = true,
                onClick = onToggleMute,
            )
        }
    }

    SectionCard("Timeline") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Double-tap skip", style = AmplifyType.label, modifier = Modifier.weight(1f))
            AmplifySelect(
                options = skipIntervalChoices,
                optionLabel = { "${it / 1000} s" },
                selected = state.options.skipIntervalMs,
                onSelect = onSkipInterval,
            )
        }
    }

    SectionCard("Repeat & shuffle") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RepeatMode.entries.forEach { mode ->
                AmplifyPill(
                    text = when (mode) {
                        RepeatMode.OFF -> "Repeat off"
                        RepeatMode.ONE -> "Repeat one"
                        RepeatMode.ALL -> "Repeat all"
                    },
                    selected = state.repeatMode == mode,
                    compact = true,
                    onClick = { onRepeatMode(mode) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        AmplifyPill(
            text = if (state.shuffleEnabled) "Shuffle on" else "Shuffle off",
            selected = state.shuffleEnabled,
            compact = true,
            onClick = onToggleShuffle,
        )
    }

    SectionCard("Position") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AmplifyPill(text = "Restart", compact = true, onClick = onRestart)
            if (state.resumeMs > 0L) {
                AmplifyPill(
                    text = "Resume ${formatClock(state.resumeMs)}",
                    compact = true,
                    selected = true,
                    onClick = onResume,
                )
            }
        }
    }
}

@Composable
private fun SubtitlesTab(
    state: VideoSettingsState,
    onCaptionsEnabled: (Boolean) -> Unit,
    onCaptionSize: (CaptionSize) -> Unit,
    onCaptionPalette: (CaptionPalette) -> Unit,
    onSelectTextTrack: (MediaTrackOption) -> Unit,
    onAutomaticText: () -> Unit,
) {
    SectionCard("Captions") {
        ToggleRow(
            title = "Show captions",
            subtitle = if (state.tracks.hasText) {
                "${state.tracks.text.size} track(s) in this file"
            } else {
                "No subtitle tracks in this container"
            },
            checked = state.options.captionsEnabled,
            onCheckedChange = onCaptionsEnabled,
        )
        Spacer(Modifier.height(6.dp))
        TrackChoiceRow(
            title = "Automatic",
            detail = "Let the player follow the container's default flag",
            selected = state.tracks.hasText && !state.tracks.captionsEnabled,
            onClick = onAutomaticText,
        )
        state.tracks.text.forEach { option ->
            TrackChoiceRow(
                title = option.title,
                detail = option.detail,
                selected = option.selected,
                onClick = { onSelectTextTrack(option) },
            )
        }
        if (!state.tracks.hasText) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Embedded SRT, WebVTT, TTML, ASS/SSA, CEA-608/708, DVB and PGS tracks are read " +
                    "from the container by Media3. Sidecar files are not merged automatically.",
                style = AmplifyType.meta,
                color = AmplifyColors.TextMuted,
            )
        }
    }

    SectionCard("Text size") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CaptionSize.entries.forEach { size ->
                AmplifyPill(
                    text = size.label,
                    selected = state.options.captionSize == size,
                    compact = true,
                    onClick = { onCaptionSize(size) },
                )
            }
        }
    }

    SectionCard("Style") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            CaptionPalette.entries.forEach { palette ->
                AmplifyPill(
                    text = palette.label,
                    selected = state.options.captionPalette == palette,
                    compact = true,
                    onClick = { onCaptionPalette(palette) },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Embedded ASS/SSA styling is replaced by this palette so captions stay readable " +
                "against bright scenes.",
            style = AmplifyType.meta,
            color = AmplifyColors.TextMuted,
        )
    }
}

@Composable
private fun AudioTab(
    state: VideoSettingsState,
    onSelectAudioTrack: (MediaTrackOption) -> Unit,
    onAutomaticAudio: () -> Unit,
    onEqualizerEnabled: (Boolean) -> Unit,
    onEqualizerPreset: (Int) -> Unit,
    onEqualizerBand: (Int, Int) -> Unit,
    onLoudnessGain: (Int) -> Unit,
    onResetAudioEffects: () -> Unit,
) {
    val audio = state.tracks.audioStream
    SectionCard("Audio track") {
        if (state.tracks.audio.isEmpty()) {
            Text("No audio tracks reported yet.", style = AmplifyType.meta, color = AmplifyColors.TextMuted)
        }
        state.tracks.audio.forEach { option ->
            TrackChoiceRow(
                title = option.title,
                detail = option.detail,
                selected = option.selected,
                onClick = { onSelectAudioTrack(option) },
            )
        }
        if (state.tracks.audio.size > 1) {
            Spacer(Modifier.height(4.dp))
            AmplifyPill(text = "Automatic", compact = true, onClick = onAutomaticAudio)
        }
    }

    SectionCard("Audio stream") {
        InfoRow("Codec", codecLabel(audio.sampleMimeType, audio.codecs))
        channelCountLabel(audio.channelCount)?.let { InfoRow("Channels", it) }
        if (audio.sampleRate > 0) InfoRow("Sample rate", "${audio.sampleRate / 1000} kHz")
        bitrateLabel(audio.bitrate)?.let { InfoRow("Bitrate", it) }
        languageLabel(audio.language)?.let { InfoRow("Language", it) }
    }

    SectionCard("Equalizer ${if (state.audioEffects.available) "" else "· unavailable"}") {
        val effects = state.audioEffects
        ToggleRow(
            title = "Equalizer",
            subtitle = effects.message ?: "Attached to the player's audio session",
            checked = effects.enabled,
            enabled = effects.available && effects.presets.isNotEmpty(),
            onCheckedChange = onEqualizerEnabled,
        )
        if (effects.presets.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            AmplifySelect(
                options = effects.presets.indices.toList(),
                optionLabel = { effects.presets.getOrNull(it) ?: "Preset" },
                selected = effects.presetIndex.coerceIn(0, effects.presets.size - 1),
                onSelect = onEqualizerPreset,
                leadingLabel = "Preset",
            )
        }
        effects.bands.forEach { band ->
            Spacer(Modifier.height(6.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(band.label, style = AmplifyType.label, modifier = Modifier.width(56.dp))
                    androidx.compose.material3.Slider(
                        value = band.levelMb.toFloat(),
                        onValueChange = { onEqualizerBand(band.index, it.roundToInt()) },
                        valueRange = effects.minLevelMb.toFloat()..effects.maxLevelMb.toFloat(),
                        enabled = effects.enabled,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        String.format(java.util.Locale.getDefault(), "%+.1f dB", band.levelMb / 100f),
                        style = AmplifyType.numeric,
                        modifier = Modifier.width(58.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Amplification", style = AmplifyType.label, modifier = Modifier.width(96.dp))
            androidx.compose.material3.Slider(
                value = effects.gainMb.toFloat(),
                onValueChange = { onLoudnessGain(it.roundToInt()) },
                valueRange = 0f..effects.maxGainMb.toFloat(),
                enabled = effects.available,
                modifier = Modifier.weight(1f),
            )
            Text(
                String.format(java.util.Locale.getDefault(), "%.1f dB", effects.gainMb / 100f),
                style = AmplifyType.numeric,
                modifier = Modifier.width(58.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        AmplifyPill(text = "Reset effects", compact = true, onClick = onResetAudioEffects)
    }

    CapabilityCard(title = "Audio engine", rows = audioCapabilities())
}

@Composable
private fun DisplayTab(
    state: VideoSettingsState,
    onAspect: (VideoAspect) -> Unit,
    onRotate: () -> Unit,
    onResetRotation: () -> Unit,
    onOrientation: (ScreenOrientation) -> Unit,
    onSelectVideoTrack: (MediaTrackOption) -> Unit,
) {
    val stream = state.tracks.videoStream
    SectionCard("Picture") {
        InfoRow("Decoded size", resolutionLabel(state.dimensions.width, state.dimensions.height))
        InfoRow("Stream", resolutionLabel(stream.width, stream.height))
        InfoRow("Frame rate", frameRateLabel(stream.frameRate))
        InfoRow("Dynamic range", hdrFromColorTransfer(stream.colorTransfer))
    }

    SectionCard("Aspect ratio") {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            VideoAspect.entries.forEach { aspect ->
                AmplifyPill(
                    text = aspect.label,
                    selected = state.options.aspect == aspect,
                    compact = true,
                    onClick = { onAspect(aspect) },
                )
            }
        }
    }

    SectionCard("Rotation & orientation") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Video rotation", style = AmplifyType.label, modifier = Modifier.weight(1f))
            Text("${state.options.rotationDegrees}°", style = AmplifyType.numeric)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AmplifyPill(text = "Rotate 90°", compact = true, onClick = onRotate)
            AmplifyPill(text = "Reset", compact = true, onClick = onResetRotation)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ScreenOrientation.entries.forEach { orientation ->
                AmplifyPill(
                    text = orientation.label,
                    selected = state.options.orientation == orientation,
                    compact = true,
                    onClick = { onOrientation(orientation) },
                )
            }
        }
    }

    if (state.tracks.video.size > 1) {
        SectionCard("Video track") {
            state.tracks.video.forEach { option ->
                TrackChoiceRow(
                    title = option.title,
                    detail = option.detail,
                    selected = option.selected,
                    onClick = { onSelectVideoTrack(option) },
                )
            }
        }
    }

    CapabilityCard(title = "Video engine", rows = videoCapabilities())
}

@Composable
private fun InfoTab(state: VideoSettingsState) {
    val stream = state.tracks.videoStream
    val audio = state.tracks.audioStream
    SectionCard("File") {
        InfoRow("Name", state.filename.ifBlank { "—" })
        InfoRow("Container", videoExtensionLabel(state.filename))
        InfoRow("Size", state.fileSizeBytes?.let(::sizeLabel) ?: "Unknown")
        InfoRow("Duration", formatClock(state.durationMs))
        InfoRow("MIME", state.mimeType ?: "Unknown")
    }
    SectionCard("Video") {
        InfoRow("Codec", codecLabel(stream.sampleMimeType, stream.codecs))
        InfoRow("Resolution", resolutionLabel(stream.width, stream.height))
        InfoRow("Frame rate", frameRateLabel(stream.frameRate))
        InfoRow("Bitrate", bitrateLabel(stream.bitrate) ?: "Unknown")
        InfoRow("HDR", hdrFromColorTransfer(stream.colorTransfer))
        InfoRow("Video tracks", state.tracks.video.size.toString())
    }
    SectionCard("Audio") {
        InfoRow("Codec", codecLabel(audio.sampleMimeType, audio.codecs))
        InfoRow("Channels", channelCountLabel(audio.channelCount) ?: "Unknown")
        InfoRow("Language", languageLabel(audio.language) ?: "Untagged")
        InfoRow("Audio tracks", state.tracks.audio.size.toString())
        InfoRow("Subtitle tracks", state.tracks.text.size.toString())
    }
    SectionCard("Decoder") {
        InfoRow("Pipeline", "MediaCodec via ExoPlayer (hardware when available)")
        val decoder = state.decoder
        if (decoder == null) {
            InfoRow("Counters", "Not available")
        } else {
            InfoRow("Frames rendered", decoder.renderedFrames.toString())
            InfoRow("Frames dropped", decoder.droppedFrames.toString())
        }
        InfoRow("Speed", playbackRateLabel(state.speed))
    }
}

// ---- building blocks -----------------------------------------------------------

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        AmplifySectionLabel(title)
        Spacer(Modifier.height(6.dp))
        GlassPanel(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
            content()
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = AmplifyType.meta, modifier = Modifier.weight(1f))
        Text(
            value,
            style = AmplifyType.label,
            color = AmplifyColors.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = AmplifyType.label, color = AmplifyColors.TextPrimary)
            if (subtitle != null) {
                Text(subtitle, style = AmplifyType.meta, color = AmplifyColors.TextMuted, maxLines = 2)
            }
        }
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = AmplifyColors.AccentDeep,
                checkedTrackColor = AmplifyColors.AccentSoft,
                uncheckedThumbColor = AmplifyColors.TextMuted,
                uncheckedTrackColor = Color.White.copy(alpha = 0.10f),
            ),
        )
    }
}

@Composable
private fun TrackChoiceRow(
    title: String,
    detail: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) AmplifyColors.AccentSoft else Color.Transparent)
            .amplifyClickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = AmplifyType.label,
                color = if (selected) AmplifyColors.Accent else AmplifyColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Text(detail, style = AmplifyType.meta, color = AmplifyColors.TextMuted, maxLines = 1)
            }
        }
        if (selected) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = AmplifyColors.Accent, modifier = Modifier.size(15.dp))
        }
    }
}

@Composable
private fun CapabilityCard(
    title: String,
    rows: List<com.teledrive.playback.video.CapabilityRow>,
) {
    Column(Modifier.fillMaxWidth()) {
        AmplifySectionLabel(title)
        Spacer(Modifier.height(6.dp))
        GlassPanel(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp)) {
            rows.forEachIndexed { index, row ->
                if (index > 0) {
                    Spacer(Modifier.height(8.dp))
                    AmplifyDivider()
                    Spacer(Modifier.height(8.dp))
                }
                Row(verticalAlignment = Alignment.Top) {
                    Spacer(
                        Modifier
                            .padding(top = 4.dp)
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                when (row.support) {
                                    EngineSupport.SUPPORTED -> Color(0xFF8BE28B)
                                    EngineSupport.DEVICE_DEPENDENT -> AmplifyColors.Accent
                                    EngineSupport.NOT_EXPOSED -> AmplifyColors.TextMuted.copy(alpha = 0.6f)
                                }
                            ),
                    )
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                row.name,
                                style = AmplifyType.label.copy(fontSize = 11.5.sp),
                                color = AmplifyColors.TextPrimary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(row.support.label, style = AmplifyType.meta, color = AmplifyColors.TextMuted)
                        }
                        Text(row.detail, style = AmplifyType.meta, color = AmplifyColors.TextMuted)
                    }
                }
            }
        }
    }
}
