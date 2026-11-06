package com.teledrive.playback.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VideoPlaylistTest {

    private fun entry(
        id: String,
        name: String,
        size: Long = 1_000L,
        savedAt: Long = 100L,
        duration: Long = 60_000L,
        favorite: Boolean = false,
        resume: Long = 0L,
    ) = VideoLibraryEntry(
        stableId = id,
        filename = name,
        sizeBytes = size,
        savedAtEpochSeconds = savedAt,
        durationMs = duration,
        favorite = favorite,
        resumeMs = resume,
    )

    @Test
    fun playlistEncodeDecodeRoundTrips() {
        val playlists = listOf(
            VideoPlaylist("p1", "Weekend", listOf("1:2", "3:4"), 100L),
            VideoPlaylist("p2", "Docs", emptyList(), 200L),
        )
        val decoded = decodePlaylists(encodePlaylists(playlists))
        assertEquals(playlists, decoded)
    }

    @Test
    fun decodeToleratesJunkAndMissingBlocks() {
        assertTrue(decodePlaylists(null).isEmpty())
        assertTrue(decodePlaylists("").isEmpty())
        assertTrue(decodePlaylists("garbage").isEmpty())
        val decoded = decodePlaylists("p1\t100\tTrip\t1:2,3:4")
        assertEquals(1, decoded.size)
        assertEquals(listOf("1:2", "3:4"), decoded[0].stableIds)
        assertEquals("Playlist", decodePlaylists("p2\t0\t\t").first().name)
    }

    @Test
    fun playlistNamesAreSanitised() {
        assertEquals("A B", sanitizePlaylistName("  A\nB  "))
        assertEquals(60, sanitizePlaylistName("x".repeat(120)).length)
        assertEquals("New playlist", defaultPlaylistName(emptyList(), 0L))
        assertEquals("New playlist 2", defaultPlaylistName(listOf(VideoPlaylist("1", "New playlist")), 0L))
        assertEquals("New playlist 3", defaultPlaylistName(
            listOf(VideoPlaylist("1", "New playlist"), VideoPlaylist("2", "New playlist 2")), 0L
        ))
    }

    @Test
    fun dragAndDropMovesEntries() {
        val ids = listOf("a", "b", "c", "d")
        assertEquals(listOf("b", "c", "a", "d"), moveEntry(ids, 0, 2))
        assertEquals(listOf("d", "a", "b", "c"), moveEntry(ids, 3, 0))
        assertEquals(ids, moveEntry(ids, 1, 1))
        assertEquals(ids, moveEntry(ids, 9, 0))
        assertEquals(listOf("a", "b", "c", "d"), moveEntry(ids, 9, 4))
    }

    @Test
    fun addAndRemoveEntriesKeepOrderWithoutDuplicates() {
        assertEquals(listOf("a", "b", "c"), addEntry(listOf("a", "b"), "c"))
        assertEquals(listOf("a", "b"), addEntry(listOf("a", "b"), "a"))
        assertEquals(listOf("x", "a", "b"), addEntry(listOf("a", "b"), "x", at = 0))
        assertEquals(listOf("a", "c"), removeEntry(listOf("a", "b", "c"), "b"))
        assertEquals(listOf("a", "b"), addEntry(listOf("a", "b"), "  "))
    }

    @Test
    fun searchMatchesFilenameCaseInsensitively() {
        val entries = listOf(entry("1", "Holiday.mp4"), entry("2", "meeting.mkv"), entry("3", "Holiday 2.mov"))
        assertEquals(listOf("1", "3"), filterAndSortVideos(entries, query = "holiday").map { it.stableId }.sorted())
        assertEquals(3, filterAndSortVideos(entries, query = "  ").size)
    }

    @Test
    fun filtersCoverFavoritesAndContinueWatching() {
        val entries = listOf(
            entry("1", "a.mp4", favorite = true),
            entry("2", "b.mp4", resume = 30_000L),
            entry("3", "c.mp4"),
        )
        assertEquals(listOf("1"), filterAndSortVideos(entries, favoritesOnly = true).map { it.stableId })
        assertEquals(listOf("2"), filterAndSortVideos(entries, inProgressOnly = true).map { it.stableId })
    }

    @Test
    fun sortFieldsAndDirectionsWork() {
        val entries = listOf(
            entry("1", "Bravo.mp4", size = 300L, savedAt = 10L, duration = 90_000L),
            entry("2", "alpha.mp4", size = 100L, savedAt = 30L, duration = 30_000L),
            entry("3", "Charlie.mp4", size = 200L, savedAt = 20L, duration = 120_000L),
        )
        assertEquals(listOf("2", "1", "3"), filterAndSortVideos(entries, sort = VideoSort(VideoSortField.NAME, true)).map { it.stableId })
        assertEquals(listOf("3", "1", "2"), filterAndSortVideos(entries, sort = VideoSort(VideoSortField.NAME, false)).map { it.stableId })
        assertEquals(listOf("2", "3", "1"), filterAndSortVideos(entries, sort = VideoSort(VideoSortField.SIZE, true)).map { it.stableId })
        assertEquals(listOf("2", "1", "3"), filterAndSortVideos(entries, sort = VideoSort(VideoSortField.DURATION, true)).map { it.stableId })
        assertEquals(listOf("2", "3", "1"), filterAndSortVideos(entries, sort = VideoSort(VideoSortField.DATE, false)).map { it.stableId })
    }

    @Test
    fun sortToggleFlipsDirectionAndKeepsField() {
        val sort = VideoSort(VideoSortField.DURATION, false)
        assertEquals(VideoSort(VideoSortField.DURATION, true), sort.withField(VideoSortField.DURATION))
        assertEquals(VideoSort(VideoSortField.NAME, true), sort.withField(VideoSortField.NAME))
        assertTrue(sort.toggled().ascending)
    }

    @Test
    fun recentlyPlayedUsesProgressAndRecency() {
        val entries = listOf(
            entry("1", "a.mp4", savedAt = 10L, resume = 5_000L),
            entry("2", "b.mp4", savedAt = 40L, resume = 5_000L),
            entry("3", "c.mp4", savedAt = 30L),
        )
        val recent = recentlyPlayed(entries, limit = 5)
        assertEquals(listOf("2", "1"), recent.map { it.stableId })
        assertEquals(1, recentlyPlayed(entries, limit = 1).size)
    }

    @Test
    fun entryDerivedFields() {
        val e = entry("1", "clip.MKV", resume = 15_000L, duration = 60_000L)
        assertEquals("MKV", e.extension)
        assertEquals("0.25", e.watchedFraction.toString())
        assertTrue(e.hasProgress)
        assertEquals("VIDEO", videoExtensionLabel(null))
        assertEquals("WEBM", videoExtensionLabel("a.webm"))
        assertEquals("XYZ", videoExtensionLabel("a.xyz"))
    }

    @Test
    fun idListsAreParsedAndDeduplicated() {
        assertEquals(listOf("1:2", "3:4"), parseIdList("1:2, 3:4,1:2"))
        assertEquals(emptyList<String>(), parseIdList(null))
        assertTrue(parseIdList(" , ").isEmpty())
        assertEquals("2 videos", VideoPlaylist("p", "n", listOf("a", "b")).summary)
        assertEquals("1 video", VideoPlaylist("p", "n", listOf("a")).summary)
        assertTrue(VideoPlaylist("p", "n").isEmpty)
    }
}
