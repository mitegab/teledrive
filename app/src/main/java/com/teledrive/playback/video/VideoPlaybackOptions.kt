package com.teledrive.playback.video

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Pure video playback/display rules for the video workspace.
 *
 * Nothing here touches Media3 or Android, so the maths behind frame stepping, rotation,
 * aspect cycling and format labels can be unit tested on the JVM.
 */

/** How a video is fitted into the surface. Mirrors Media3's AspectRatioFrameLayout modes. */
enum class VideoAspect(val label: String) {
    FIT("Fit"),
    ZOOM("Zoom"),
    STRETCH("Stretch"),
}

enum class ScreenOrientation(val label: String) {
    AUTO("Auto"),
    LANDSCAPE("Landscape"),
    PORTRAIT("Portrait"),
}

enum class CaptionSize(val label: String, val scale: Float) {
    SMALL("S", 0.8f),
    MEDIUM("M", 1f),
    LARGE("L", 1.3f),
    EXTRA_LARGE("XL", 1.6f),
}

/** Caption palettes. Colours are ARGB longs so this file stays free of UI imports. */
enum class CaptionPalette(
    val label: String,
    val foreground: Long,
    val background: Long,
    val outlined: Boolean,
) {
    WHITE_SHADOW("White", 0xFFFFFFFF, 0x00000000, true),
    YELLOW_SHADOW("Yellow", 0xFFFFE082, 0x00000000, true),
    BLACK_BOX("Boxed", 0xFFFFFFFF, 0xCC000000, false),
    CYAN_BOX("Cyan", 0xFF7FE7FF, 0xCC001018, false),
}

data class VideoPlaybackOptions(
    val aspect: VideoAspect = VideoAspect.FIT,
    val rotationDegrees: Int = 0,
    val orientation: ScreenOrientation = ScreenOrientation.AUTO,
    val skipIntervalMs: Long = 10_000L,
    val captionsEnabled: Boolean = true,
    val captionSize: CaptionSize = CaptionSize.MEDIUM,
    val captionPalette: CaptionPalette = CaptionPalette.WHITE_SHADOW,
    val showRemainingTime: Boolean = false,
)

val skipIntervalChoices: List<Long> = listOf(5_000L, 10_000L, 15_000L, 30_000L)

fun normalizeRotation(degrees: Int): Int = ((degrees % 360) + 360) % 360

/** Rotates the video surface in 90° steps: 0 → 90 → 180 → 270 → 0. */
fun nextRotation(current: Int): Int = normalizeRotation(current + 90)

fun rotationSwapsDimensions(degrees: Int): Boolean {
    val normalized = normalizeRotation(degrees)
    return normalized == 90 || normalized == 270
}

/**
 * Duration of a single frame. Media3 reports [frameRate] as -1 when the container does not
 * carry it, so assume a conservative 30 fps in that case.
 */
fun frameDurationMs(frameRate: Float?): Long {
    if (frameRate == null || frameRate <= 0f) return 33L
    return (1000.0 / frameRate).roundToLong().coerceIn(1L, 1000L)
}

/**
 * Position after stepping [frames] frames. Used by the frame-by-frame controls; the player
 * itself is paused and seeks with exact (not keyframe-snapped) parameters.
 */
fun stepPosition(
    positionMs: Long,
    durationMs: Long,
    frameRate: Float?,
    frames: Int = 1,
    forward: Boolean = true,
): Long {
    val step = frameDurationMs(frameRate) * frames.coerceAtLeast(1)
    val target = if (forward) positionMs + step else positionMs - step
    val upperBound = if (durationMs > 0L) durationMs else Long.MAX_VALUE
    return target.coerceIn(0L, upperBound)
}

/** "-1:23" style remaining time; clamp at zero rather than showing negative values. */
fun remainingClock(positionMs: Long, durationMs: Long): String {
    if (durationMs <= 0L) return "-0:00"
    val remaining = (durationMs - positionMs).coerceAtLeast(0L)
    val totalSeconds = remaining / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.getDefault(), "-%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.getDefault(), "-%d:%02d", minutes, seconds)
    }
}

fun playbackRateLabel(speed: Float): String {
    val rounded = (speed * 100f).roundToLong() / 100f
    val text = if (rounded == rounded.toLong().toFloat()) rounded.toLong().toString() else rounded.toString()
    return "$text×"
}

fun resolutionLabel(width: Int, height: Int): String {
    if (width <= 0 || height <= 0) return "Resolution unknown"
    val shortSide = minOf(width, height)
    val longSide = maxOf(width, height)
    return "$longSide×$shortSide"
}

/** Friendly tier for badges: 4K, 1440p, 1080p, 720p, 480p, SD. */
fun resolutionTier(width: Int, height: Int): String {
    val shortSide = minOf(width, height)
    return when {
        shortSide <= 0 -> "—"
        shortSide >= 2000 -> "4K"
        shortSide >= 1400 -> "1440p"
        shortSide >= 1000 -> "1080p"
        shortSide >= 700 -> "720p"
        shortSide >= 460 -> "480p"
        else -> "SD"
    }
}

fun frameRateLabel(frameRate: Float?): String {
    if (frameRate == null || frameRate <= 0f) return "fps unknown"
    val rounded = (frameRate * 100f).roundToLong() / 100f
    val text = if (rounded == rounded.toLong().toFloat()) rounded.toLong().toString() else rounded.toString()
    return "$text fps"
}

fun bitrateLabel(bitsPerSecond: Int?): String? {
    if (bitsPerSecond == null || bitsPerSecond <= 0) return null
    return when {
        bitsPerSecond >= 1_000_000 -> String.format(Locale.getDefault(), "%.1f Mbps", bitsPerSecond / 1_000_000.0)
        bitsPerSecond >= 1_000 -> String.format(Locale.getDefault(), "%.0f kbps", bitsPerSecond / 1_000.0)
        else -> "$bitsPerSecond bps"
    }
}

/** Human codec name from a Media3/MediaCodec MIME type, falling back to the RFC 6381 codec string. */
fun codecLabel(sampleMimeType: String?, codecs: String?): String {
    val mime = sampleMimeType?.lowercase(Locale.ROOT).orEmpty()
    val friendly = when {
        mime.contains("avc") || mime.contains("h264") -> "H.264/AVC"
        mime.contains("hevc") || mime.contains("h265") -> "H.265/HEVC"
        mime.contains("av01") -> "AV1"
        mime.contains("vp9") -> "VP9"
        mime.contains("vp8") -> "VP8"
        mime.contains("mp4v") -> "MPEG-4"
        mime.contains("mpeg2") -> "MPEG-2"
        mime.contains("eac3") -> "E-AC-3"
        mime.contains("ac3") -> "AC-3"
        mime.contains("ac-4") -> "AC-4"
        mime.contains("opus") -> "Opus"
        mime.contains("vorbis") -> "Vorbis"
        mime.contains("flac") -> "FLAC"
        mime.contains("mpeg") && mime.startsWith("audio/") -> "MP3"
        mime.contains("aac") || mime.contains("mp4a") -> "AAC"
        mime.contains("dts") -> "DTS"
        mime.contains("truehd") -> "Dolby TrueHD"
        mime.contains("pcm") || mime.contains("raw") -> "PCM"
        mime.isBlank() -> null
        else -> mime.substringAfter('/').uppercase(Locale.ROOT)
    }
    val detail = codecs?.takeIf { it.isNotBlank() }
    return when {
        friendly != null && detail != null -> "$friendly ($detail)"
        friendly != null -> friendly
        detail != null -> detail
        else -> "Codec unknown"
    }
}

/**
 * Colour transfer as reported by MediaFormat/Media3 [androidx.media3.common.C].
 * ST2084 (PQ) covers HDR10/HDR10+; dynamic metadata is not exposed by the platform here.
 */
fun hdrFromColorTransfer(colorTransfer: Int?): String = when (colorTransfer) {
    COLOR_TRANSFER_ST2084 -> "HDR10 (PQ)"
    COLOR_TRANSFER_HLG -> "HLG"
    COLOR_TRANSFER_SDR, COLOR_TRANSFER_GAMMA_2_2, COLOR_TRANSFER_GAMMA_2_8 -> "SDR"
    null -> "Unknown"
    else -> "Unknown"
}

const val COLOR_TRANSFER_LINEAR = 1
const val COLOR_TRANSFER_SDR = 3
const val COLOR_TRANSFER_GAMMA_2_2 = 4
const val COLOR_TRANSFER_GAMMA_2_8 = 5
const val COLOR_TRANSFER_ST2084 = 6
const val COLOR_TRANSFER_HLG = 7

/** ISO 639 code → display name, e.g. "en" → "English". Unknown codes are shown as-is. */
fun languageLabel(language: String?): String? {
    val code = language?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() && it != "und" } ?: return null
    val display = runCatching { Locale.forLanguageTag(code).displayLanguage }.getOrNull()
    return display?.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) } ?: code.uppercase(Locale.ROOT)
}

fun channelCountLabel(channelCount: Int): String? = when {
    channelCount <= 0 -> null
    channelCount == 1 -> "Mono"
    channelCount == 2 -> "Stereo"
    channelCount <= 6 -> "$channelCount ch (surround)"
    else -> "$channelCount ch"
}

/**
 * Human description of what a device/decoder can do with this file. Media3 does not expose
 * HDR tone-mapping, deinterlacing, denoising or upscaling switches, so the UI can only report
 * what the file carries and what the player actually renders.
 */
fun renderSummary(
    width: Int,
    height: Int,
    frameRate: Float?,
    hdr: String,
    renderedFrames: Long?,
    droppedFrames: Long?,
): String {
    val parts = mutableListOf(
        resolutionLabel(width, height),
        frameRateLabel(frameRate),
        hdr,
    )
    if (renderedFrames != null && renderedFrames > 0) parts.add("$renderedFrames frames rendered")
    if (droppedFrames != null && droppedFrames > 0) parts.add("$droppedFrames dropped")
    return parts.joinToString(" · ")
}
