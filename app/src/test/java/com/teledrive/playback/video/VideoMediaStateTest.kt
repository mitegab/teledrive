package com.teledrive.playback.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VideoMediaStateTest {

    private fun audio(index: Int, language: String?, channelCount: Int = 2, mime: String? = "audio/mp4a-latm", bitrate: Int = 128_000) =
        TrackDescriptor(
            groupIndex = index,
            trackIndex = 0,
            kind = MediaTrackKind.AUDIO,
            language = language,
            mimeType = mime,
            channelCount = channelCount,
            bitrate = bitrate,
        )

    @Test
    fun audioTracksUseLanguageNamesAndFallBackToNumbers() {
        val options = trackOptions(listOf(audio(0, "en"), audio(1, "sw"), audio(2, null)))
        assertEquals(3, options.size)
        assertEquals("English", options[0].title)
        assertEquals("Swahili", options[1].title)
        assertEquals("Audio 3", options[2].title)
        assertEquals("AAC · Stereo · 128 kbps", options[0].detail)
    }

    @Test
    fun selectedFlagsSurviveConversion() {
        val descriptors = listOf(
            audio(0, "en").copy(selected = false),
            audio(1, "sw").copy(selected = true),
        )
        val options = trackOptions(descriptors)
        assertTrue(options[1].selected)
        assertTrue(!options[0].selected)
    }

    @Test
    fun textTracksCarryForcedBadge() {
        val options = trackOptions(
            listOf(
                TrackDescriptor(0, 0, MediaTrackKind.TEXT, language = "en", mimeType = "application/x-subrip", selected = true),
                TrackDescriptor(1, 0, MediaTrackKind.TEXT, language = "en", mimeType = "text/vtt", isForced = true),
            )
        )
        assertEquals("English", options[0].title)
        assertEquals("SRT", options[0].detail)
        assertEquals("WebVTT · Forced", options[1].detail)
        assertTrue(options[1].forced)
    }

    @Test
    fun videoTracksShowTierAndCodec() {
        val options = trackOptions(
            listOf(
                TrackDescriptor(
                    groupIndex = 0,
                    trackIndex = 0,
                    kind = MediaTrackKind.VIDEO,
                    mimeType = "video/avc",
                    width = 1920,
                    height = 1080,
                    frameRate = 60f,
                    bitrate = 8_000_000,
                    colorTransfer = COLOR_TRANSFER_ST2084,
                )
            )
        )
        assertEquals("1080p · H.264/AVC", options[0].title)
        assertEquals("1920×1080 · 60 fps · 8.0 Mbps · HDR10 (PQ)", options[0].detail)
    }

    @Test
    fun textCodecLabelsCoverCommonFormats() {
        assertEquals("SRT", textCodecLabel("application/x-subrip"))
        assertEquals("WebVTT", textCodecLabel("text/vtt"))
        assertEquals("TTML", textCodecLabel("application/ttml+xml"))
        assertEquals("ASS/SSA", textCodecLabel("text/x-ssa"))
        assertEquals("CEA-608/708", textCodecLabel("application/cea-608"))
        assertEquals("PGS", textCodecLabel("application/x-pgs"))
        assertNull(textCodecLabel(null))
    }

    @Test
    fun resumeSkipsTrivialAndNearlyFinishedPositions() {
        assertNull(resumePosition(5_000L, 600_000L))
        assertEquals(60_000L, resumePosition(60_000L, 600_000L))
        assertNull(resumePosition(598_000L, 600_000L))
        assertEquals(60_000L, resumePosition(60_000L, 0L))
        assertNull(resumePosition(0L, 600_000L))
    }

    @Test
    fun watchProgressIsClamped() {
        assertEquals(0f, watchProgressFraction(10L, 0L))
        assertEquals(0.5f, watchProgressFraction(50L, 100L))
        assertEquals(1f, watchProgressFraction(200L, 100L))
    }

    @Test
    fun remainingLabelOnlyWhenUseful() {
        assertEquals("1:00 left", remainingLabel(0L, 60_000L))
        assertEquals("0:30 left", remainingLabel(30_000L, 60_000L))
        assertNull(remainingLabel(0L, 0L))
        assertNull(remainingLabel(60_000L, 60_000L))
        assertEquals("1:00:00", formatClock(3_600_000L))
        assertEquals("0:00", formatClock(0L))
    }

    @Test
    fun captionSummaryWalksThroughOffAndNone() {
        assertEquals("None", MediaTrackSnapshot().captionSummary)
        val withText = MediaTrackSnapshot(
            text = listOf(
                MediaTrackOption(0, 0, MediaTrackKind.TEXT, "English", "SRT", selected = false),
            )
        )
        assertEquals("Off", withText.captionSummary)
        val active = withText.copy(text = withText.text.map { it.copy(selected = true) })
        assertEquals("English", active.captionSummary)
        assertTrue(active.captionsEnabled)
    }

    @Test
    fun trackKindMappingMatchesMedia3Constants() {
        assertEquals(MediaTrackKind.VIDEO, trackKindForType(1))
        assertEquals(MediaTrackKind.AUDIO, trackKindForType(2))
        assertEquals(MediaTrackKind.TEXT, trackKindForType(3))
        assertNull(trackKindForType(0))
    }
}
