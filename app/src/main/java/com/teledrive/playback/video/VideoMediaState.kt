package com.teledrive.playback.video

import java.util.Locale

/**
 * Media3-free description of what a file actually contains.
 *
 * [com.teledrive.playback.PlaybackController] converts Media3 `Tracks`/`Format` into these
 * descriptors; everything the UI needs (labels, badges, ordering) is derived here so it can be
 * unit tested without an Android runtime.
 */

enum class MediaTrackKind { VIDEO, AUDIO, TEXT }

data class TrackDescriptor(
    val groupIndex: Int,
    val trackIndex: Int,
    val kind: MediaTrackKind,
    val language: String? = null,
    val label: String? = null,
    val mimeType: String? = null,
    val codecs: String? = null,
    val channelCount: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Float = -1f,
    val bitrate: Int = 0,
    val colorTransfer: Int? = null,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    val selected: Boolean = false,
)

data class MediaTrackOption(
    val groupIndex: Int,
    val trackIndex: Int,
    val kind: MediaTrackKind,
    val title: String,
    val detail: String?,
    val selected: Boolean,
    val forced: Boolean = false,
)

data class VideoStreamInfo(
    val width: Int = 0,
    val height: Int = 0,
    val frameRate: Float = -1f,
    val sampleMimeType: String? = null,
    val codecs: String? = null,
    val bitrate: Int? = null,
    val colorTransfer: Int? = null,
) {
    val known: Boolean get() = width > 0 && height > 0
    val hdr: String get() = hdrFromColorTransfer(colorTransfer)
}

data class AudioStreamInfo(
    val sampleMimeType: String? = null,
    val codecs: String? = null,
    val channelCount: Int = 0,
    val sampleRate: Int = 0,
    val language: String? = null,
    val bitrate: Int? = null,
) {
    val known: Boolean get() = codecs != null || sampleMimeType != null || channelCount > 0
}

/** Snapshot of ExoPlayer's decoder counters, read while the info panel is open. */
data class VideoDecoderStats(
    val renderedFrames: Long = 0L,
    val droppedFrames: Long = 0L,
    val activeCodecs: String? = null,
) {
    val hasFrames: Boolean get() = renderedFrames > 0L
}

/** Decoded picture size as reported by the player, before any display rotation. */
data class VideoDimensions(val width: Int = 0, val height: Int = 0) {
    val known: Boolean get() = width > 0 && height > 0
    val aspectRatio: Float get() = if (known) width.toFloat() / height.toFloat() else 16f / 9f
}

data class MediaTrackSnapshot(
    val video: List<MediaTrackOption> = emptyList(),
    val audio: List<MediaTrackOption> = emptyList(),
    val text: List<MediaTrackOption> = emptyList(),
    val videoStream: VideoStreamInfo = VideoStreamInfo(),
    val audioStream: AudioStreamInfo = AudioStreamInfo(),
) {
    val hasVideo: Boolean get() = video.isNotEmpty() || videoStream.known
    val hasAudio: Boolean get() = audio.isNotEmpty()
    val hasText: Boolean get() = text.isNotEmpty()
    val captionsEnabled: Boolean get() = text.any { it.selected }
    val selectedText: MediaTrackOption? get() = text.firstOrNull { it.selected }
    val selectedAudio: MediaTrackOption? get() = audio.firstOrNull { it.selected }

    fun options(kind: MediaTrackKind): List<MediaTrackOption> = when (kind) {
        MediaTrackKind.VIDEO -> video
        MediaTrackKind.AUDIO -> audio
        MediaTrackKind.TEXT -> text
    }

    /** Chip caption for the subtitle menu: the active track, or "Off". */
    val captionSummary: String
        get() = selectedText?.title ?: if (hasText) "Off" else "None"
}

fun textCodecLabel(mimeType: String?): String? {
    val mime = mimeType?.lowercase(Locale.ROOT).orEmpty()
    return when {
        mime.isBlank() -> null
        mime.contains("subrip") || mime.contains("srt") -> "SRT"
        mime.contains("vtt") || mime.contains("webvtt") -> "WebVTT"
        mime.contains("ttml") -> "TTML"
        mime.contains("ssa") -> "ASS/SSA"
        mime.contains("cea") || mime.contains("608") || mime.contains("708") -> "CEA-608/708"
        mime.contains("dvb") -> "DVB"
        mime.contains("pgs") -> "PGS"
        mime.contains("tx3g") -> "tx3g"
        else -> mime.substringAfter('/').uppercase(Locale.ROOT)
    }
}

/**
 * Turns raw descriptors into display options.
 *
 * Ordering keeps the file's own track order so the UI matches what ffprobe/MediaInfo shows.
 * Numbering is only used when a track has neither a language nor a label.
 */
fun trackOptions(descriptors: List<TrackDescriptor>): List<MediaTrackOption> {
    val counters = mutableMapOf<MediaTrackKind, Int>()
    return descriptors.map { descriptor ->
        val index = (counters[descriptor.kind] ?: 0) + 1
        counters[descriptor.kind] = index
        val language = languageLabel(descriptor.language)
        val title = when (descriptor.kind) {
            MediaTrackKind.AUDIO -> language
                ?: descriptor.label?.takeIf { it.isNotBlank() }
                ?: "Audio $index"
            MediaTrackKind.TEXT -> language
                ?: descriptor.label?.takeIf { it.isNotBlank() }
                ?: "Subtitles $index"
            MediaTrackKind.VIDEO -> {
                val codec = codecLabel(descriptor.mimeType, null)
                if (descriptor.width > 0 && descriptor.height > 0) {
                    "${resolutionTier(descriptor.width, descriptor.height)} · $codec"
                } else {
                    descriptor.label?.takeIf { it.isNotBlank() } ?: "Video $index"
                }
            }
        }
        val detail = when (descriptor.kind) {
            MediaTrackKind.AUDIO -> listOfNotNull(
                codecLabel(descriptor.mimeType, descriptor.codecs).takeIf { it != "Codec unknown" },
                channelCountLabel(descriptor.channelCount),
                bitrateLabel(descriptor.bitrate),
                language?.let { if (descriptor.label != null) it else null },
            ).joinToString(" · ").ifBlank { null }
            MediaTrackKind.TEXT -> listOfNotNull(
                textCodecLabel(descriptor.mimeType),
                if (descriptor.isForced) "Forced" else null,
                if (descriptor.isDefault) "Default" else null,
            ).joinToString(" · ").ifBlank { null }
            MediaTrackKind.VIDEO -> listOfNotNull(
                resolutionLabel(descriptor.width, descriptor.height).takeIf { descriptor.width > 0 },
                frameRateLabel(descriptor.frameRate).takeIf { descriptor.frameRate > 0f },
                bitrateLabel(descriptor.bitrate),
                hdrFromColorTransfer(descriptor.colorTransfer).takeIf { it != "Unknown" },
            ).joinToString(" · ").ifBlank { null }
        }
        MediaTrackOption(
            groupIndex = descriptor.groupIndex,
            trackIndex = descriptor.trackIndex,
            kind = descriptor.kind,
            title = title,
            detail = detail,
            selected = descriptor.selected,
            forced = descriptor.isForced,
        )
    }
}

/**
 * Where "Resume playback" should jump to, or null when there is nothing meaningful to resume.
 * Matches [com.teledrive.playback.PlaybackProgressStore], which drops positions within 5s of
 * the end, and additionally ignores trivial positions right at the start.
 */
fun resumePosition(savedMs: Long, durationMs: Long, minMs: Long = 15_000L): Long? {
    if (savedMs < minMs) return null
    if (durationMs > 0L && savedMs >= durationMs - 5_000L) return null
    return savedMs
}

/** 0f..1f watch progress for the "continue watching" shelf. */
fun watchProgressFraction(positionMs: Long, durationMs: Long): Float {
    if (durationMs <= 0L) return 0f
    return (positionMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0).toFloat()
}

/** "12:34 left" for the library cards; null when the duration is unknown. */
fun remainingLabel(positionMs: Long, durationMs: Long): String? {
    if (durationMs <= 0L) return null
    val remaining = (durationMs - positionMs).coerceAtLeast(0L)
    if (remaining <= 0L) return null
    return "${formatClock(remaining)} left"
}

fun formatClock(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }
}

/** Map a Media3 track type constant to our kind, used by the controller adapter. */
fun trackKindForType(type: Int): MediaTrackKind? = when (type) {
    1 -> MediaTrackKind.VIDEO
    2 -> MediaTrackKind.AUDIO
    3 -> MediaTrackKind.TEXT
    else -> null
}
