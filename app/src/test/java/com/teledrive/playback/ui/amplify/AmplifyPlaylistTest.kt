package com.teledrive.playback.ui.amplify

import com.teledrive.core.model.LocalAvailability
import com.teledrive.core.model.RemoteMessageId
import com.teledrive.core.model.StoredFile
import com.teledrive.playback.PlaybackItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AmplifyPlaylistTest {

    private fun item(
        id: Long,
        title: String,
        artist: String? = null,
        album: String? = null,
        genre: String? = null,
        durationMs: Long? = 180_000L,
        savedAtEpochSeconds: Long = id,
        availability: LocalAvailability = LocalAvailability.NOT_DOWNLOADED,
    ): PlaybackItem = PlaybackItem(
        file = StoredFile(
            remote = RemoteMessageId(1, id),
            tdlibFileId = id.toInt(),
            originalFilename = "$title.mp3",
            mimeType = "audio/mpeg",
            sizeBytes = 1_000_000,
            savedAtEpochSeconds = savedAtEpochSeconds,
            localAvailability = availability,
            localPath = null,
        ),
        localPath = "",
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        genre = genre,
    )

    private val playlist = listOf(
        item(1, "Midnight Sun", "Alina Baraz", "Night Drive", "Lo-Fi", 205_000L, 10),
        item(2, "Waves", "Tame Impala", "Night Drive", "Indie", 252_000L, 20),
        item(3, "Motion Sickness", "Phoebe Bridgers", "Night Drive", null, 218_000L, 30),
        item(4, "Discover", "Tame Impala", "Night Drive", "Indie", 205_000L, 40),
        item(5, "Untagged Thing", null, null, null, null, 50),
    )

    @Test
    fun playlistNamePrefersTheSharedAlbum() {
        assertEquals("Night Drive", playlistName(playlist))
        assertEquals("Untagged Thing", playlistName(listOf(playlist.last())))
        assertEquals("Audio Queue", playlistName(emptyList()))
    }

    @Test
    fun playlistStatsOnlyCountKnownDurations() {
        val stats = playlistStats(playlist)
        assertEquals(5, stats.trackCount)
        assertEquals(4, stats.knownDurations)
        assertEquals(880_000L, stats.totalDurationMs)
        assertEquals("14m+", playlistLengthLabel(stats))
        assertEquals("5 Tracks · 14m+", playlistSubtitle(stats))
    }

    @Test
    fun clockAndDurationFormatting() {
        assertEquals("0:00", amplifyClock(0L))
        assertEquals("--:--", amplifyClock(null))
        assertEquals("3:45", amplifyClock(225_000L))
        assertEquals("1:02:03", amplifyClock(3_723_000L))
        assertEquals("2h 58m", longDuration(10_680_000L))
        assertEquals("0m", longDuration(0L))
    }

    @Test
    fun genreBucketsUseRealTagsOnly() {
        val buckets = genreBuckets(playlist)
        assertEquals(listOf("Indie" to 2, "Lo-Fi" to 1), buckets.map { it.genre to it.count })
        assertEquals("Genre 17", genreLabel(item(9, "Numbered", genre = "17")))
        assertNull(genreLabel(item(10, "No tag")))
    }

    @Test
    fun searchAndGenreFiltersNarrowThePlaylist() {
        assertEquals(
            listOf(1),
            arrangeTracks(playlist, AmplifyFilters(query = "waves"), AmplifySort.CUSTOM).map { it.index },
        )
        assertEquals(
            listOf(1, 3),
            arrangeTracks(playlist, AmplifyFilters(genres = setOf("Indie")), AmplifySort.CUSTOM).map { it.index },
        )
        assertEquals(
            listOf(0, 1, 3),
            arrangeTracks(playlist, AmplifyFilters(genres = setOf("Indie", "Lo-Fi")), AmplifySort.CUSTOM).map { it.index },
        )
    }

    @Test
    fun moodFiltersUseMetadataKeywords() {
        val chill = arrangeTracks(playlist, AmplifyFilters(mood = AmplifyMood.CHILL), AmplifySort.CUSTOM)
        assertTrue(chill.any { it.item.title == "Midnight Sun" })

        // Nothing in this playlist is tagged as instrumental/classical, so Focus stays empty.
        assertTrue(arrangeTracks(playlist, AmplifyFilters(mood = AmplifyMood.FOCUS), AmplifySort.CUSTOM).isEmpty())

        // "Night Drive" is a real album name on the files, so late-night matches it.
        assertTrue(arrangeTracks(playlist, AmplifyFilters(mood = AmplifyMood.LATE_NIGHT), AmplifySort.CUSTOM).isNotEmpty())
    }

    @Test
    fun genreFiltersToggleOff() {
        assertEquals(setOf("Pop"), AmplifyFilters().toggleGenre("Pop").genres)
        assertTrue(AmplifyFilters(genres = setOf("Pop")).toggleGenre("Pop").genres.isEmpty())
        assertFalse(AmplifyFilters(query = "   ").isActive)
        assertTrue(AmplifyFilters(mood = AmplifyMood.CHILL).isActive)
    }

    @Test
    fun sortingKeepsOriginalQueueIndices() {
        val byTitle = arrangeTracks(playlist, AmplifyFilters(), AmplifySort.TITLE)
        assertEquals(
            listOf("Discover", "Midnight Sun", "Motion Sickness", "Untagged Thing", "Waves"),
            byTitle.map { it.item.title },
        )
        assertTrue(byTitle.all { playlist[it.index].title == it.item.title })

        assertEquals(4, arrangeTracks(playlist, AmplifyFilters(), AmplifySort.ARTIST).last().index)
        assertEquals(4, arrangeTracks(playlist, AmplifyFilters(), AmplifySort.DURATION).last().index)
        assertEquals(4, arrangeTracks(playlist, AmplifyFilters(), AmplifySort.DATE_ADDED).first().index)
    }

    @Test
    fun offlineAndSizeLabels() {
        assertNull(offlineStateLabel(LocalAvailability.AVAILABLE))
        assertEquals("In Telegram", offlineStateLabel(LocalAvailability.NOT_DOWNLOADED))
        assertEquals("Downloading", offlineStateLabel(LocalAvailability.DOWNLOADING))
        assertEquals("4.2 MB", sizeLabel(4_200_000L))
        assertEquals("Size unknown", sizeLabel(null))
        assertEquals("Date unknown", savedDate(0L))
    }

    @Test
    fun waveformIsDeterministicAndBounded() {
        val first = waveformBars("1:42", 64)
        assertEquals(first, waveformBars("1:42", 64))
        assertTrue(first != waveformBars("1:43", 64))
        assertEquals(64, first.size)
        assertTrue(first.all { it in 0.12f..1f })
        assertTrue(waveformBars("anything", 0).isEmpty())
    }
}
