package com.teledrive.playback.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoPlaybackOptionsTest {

    @Test
    fun rotationCyclesThroughRightAngles() {
        assertEquals(90, nextRotation(0))
        assertEquals(180, nextRotation(90))
        assertEquals(270, nextRotation(180))
        assertEquals(0, nextRotation(270))
        assertEquals(90, nextRotation(360))
        assertEquals(270, normalizeRotation(-90))
        assertEquals(180, normalizeRotation(-540))
    }

    @Test
    fun quarterTurnsSwapDimensions() {
        assertFalse(rotationSwapsDimensions(0))
        assertTrue(rotationSwapsDimensions(90))
        assertFalse(rotationSwapsDimensions(180))
        assertTrue(rotationSwapsDimensions(270))
        assertTrue(rotationSwapsDimensions(-90))
    }

    @Test
    fun frameDurationFallsBackTo30fps() {
        assertEquals(33L, frameDurationMs(null))
        assertEquals(33L, frameDurationMs(0f))
        assertEquals(33L, frameDurationMs(-1f))
        assertEquals(17L, frameDurationMs(60f))
        assertEquals(8L, frameDurationMs(120f))
        assertEquals(42L, frameDurationMs(24f))
    }

    @Test
    fun frameSteppingMovesOneFrameAndClamps() {
        assertEquals(1033L, stepPosition(1000L, 60_000L, 30f))
        assertEquals(967L, stepPosition(1000L, 60_000L, 30f, forward = false))
        assertEquals(0L, stepPosition(0L, 60_000L, 30f, forward = false))
        assertEquals(60_000L, stepPosition(59_990L, 60_000L, 30f))
        assertEquals(1066L, stepPosition(1000L, 60_000L, 30f, frames = 2))
    }

    @Test
    fun remainingTimeIsClampedAndHourAware() {
        assertEquals("-0:00", remainingClock(1_000L, 0L))
        assertEquals("-0:30", remainingClock(30_000L, 60_000L))
        assertEquals("-0:00", remainingClock(90_000L, 60_000L))
        assertEquals("-1:02:03", remainingClock(60_000L, 3_783_000L))
    }

    @Test
    fun rateLabelTrimsTrailingZeros() {
        assertEquals("1×", playbackRateLabel(1f))
        assertEquals("1.5×", playbackRateLabel(1.5f))
        assertEquals("0.25×", playbackRateLabel(0.25f))
        assertEquals("2×", playbackRateLabel(2f))
    }

    @Test
    fun resolutionLabelsUseShortTier() {
        assertEquals("1920×1080", resolutionLabel(1920, 1080))
        assertEquals("Resolution unknown", resolutionLabel(0, 1080))
        assertEquals("4K", resolutionTier(3840, 2160))
        assertEquals("1440p", resolutionTier(2560, 1440))
        assertEquals("1080p", resolutionTier(1920, 1080))
        assertEquals("720p", resolutionTier(1280, 720))
        assertEquals("480p", resolutionTier(854, 480))
        assertEquals("SD", resolutionTier(640, 360))
        assertEquals("—", resolutionTier(0, 0))
    }

    @Test
    fun frameRateAndBitrateLabels() {
        assertEquals("fps unknown", frameRateLabel(null))
        assertEquals("60 fps", frameRateLabel(60f))
        assertEquals("23.98 fps", frameRateLabel(23.976f))
        assertEquals("8.0 Mbps", bitrateLabel(8_000_000))
        assertEquals("320 kbps", bitrateLabel(320_000))
        assertNull(bitrateLabel(null))
        assertNull(bitrateLabel(0))
    }

    @Test
    fun codecLabelsCoverTheSupportedFormats() {
        assertEquals("H.264/AVC (avc1.64001f)", codecLabel("video/avc", "avc1.64001f"))
        assertEquals("H.264/AVC", codecLabel("video/avc", null))
        assertEquals("H.265/HEVC (hvc1.2.4.L120.B0)", codecLabel("video/hevc", "hvc1.2.4.L120.B0"))
        assertEquals("H.265/HEVC", codecLabel("video/h265", null))
        assertEquals("AV1", codecLabel("video/av01", null))
        assertEquals("VP9", codecLabel("video/x-vnd.on2.vp9", null))
        assertEquals("MPEG-4", codecLabel("video/mp4v-es", null))
        assertEquals("AAC", codecLabel("audio/mp4a-latm", null))
        assertEquals("Opus", codecLabel("audio/opus", null))
        assertEquals("FLAC", codecLabel("audio/flac", null))
        assertEquals("E-AC-3", codecLabel("audio/eac3", null))
        assertEquals("Codec unknown", codecLabel(null, null))
        assertEquals("Codec unknown", codecLabel("", " "))
    }

    @Test
    fun hdrMappingFollowsColourTransfer() {
        assertEquals("SDR", hdrFromColorTransfer(COLOR_TRANSFER_SDR))
        assertEquals("SDR", hdrFromColorTransfer(COLOR_TRANSFER_GAMMA_2_2))
        assertEquals("HDR10 (PQ)", hdrFromColorTransfer(COLOR_TRANSFER_ST2084))
        assertEquals("HLG", hdrFromColorTransfer(COLOR_TRANSFER_HLG))
        assertEquals("Unknown", hdrFromColorTransfer(null))
        assertEquals("Unknown", hdrFromColorTransfer(99))
    }

    @Test
    fun languageAndChannelLabels() {
        assertEquals("English", languageLabel("en"))
        assertEquals("Swahili", languageLabel("sw"))
        assertNull(languageLabel(null))
        assertNull(languageLabel("und"))
        assertEquals("Mono", channelCountLabel(1))
        assertEquals("Stereo", channelCountLabel(2))
        assertEquals("6 ch (surround)", channelCountLabel(6))
        assertNull(channelCountLabel(0))
    }

    @Test
    fun defaultsFitTheWholeFrame() {
        val options = VideoPlaybackOptions()
        assertEquals(VideoAspect.FIT, options.aspect)
        assertEquals(0, options.rotationDegrees)
        assertEquals(ScreenOrientation.AUTO, options.orientation)
        assertEquals(10_000L, options.skipIntervalMs)
        assertTrue(options.captionsEnabled)
        assertEquals(CaptionSize.MEDIUM, options.captionSize)
        assertEquals(CaptionPalette.WHITE_SHADOW, options.captionPalette)
        assertFalse(options.showRemainingTime)
        assertTrue(skipIntervalChoices.contains(options.skipIntervalMs))
    }
}
