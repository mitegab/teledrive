package com.teledrive.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaybackProgressTest {
    // Tests resume position rules embedded in PlaybackProgressStore
    @Test
    fun formatTimeHandlesHours() {
        assertEquals("0:00", formatTime(0))
        assertEquals("0:05", formatTime(5000))
        assertEquals("1:00", formatTime(60_000))
        assertEquals("1:01:01", formatTime(3661000))
        assertEquals("2:00:00", formatTime(7200000))
    }

    @Test
    fun progressStateDefaults() {
        val state = PlayerUiState()
        assertEquals(PlaybackStatus.IDLE, state.status)
        assertEquals(0L, state.progress.positionMs)
        assertEquals(1f, state.progress.speed)
    }

    @Test
    fun queueStateHelpers() {
        val file1 = com.teledrive.core.model.StoredFile(
            remote = com.teledrive.core.model.RemoteMessageId(1, 1),
            tdlibFileId = 1,
            originalFilename = "a.mp3",
            mimeType = "audio/mpeg",
            sizeBytes = 100,
            savedAtEpochSeconds = 0
        )
        val item1 = PlaybackItem(file1, "/tmp/a.mp3", "A")
        val queue = PlaybackQueueState(items = listOf(item1), currentIndex = 0)
        assertEquals(item1, queue.currentItem)
        assertEquals(1, queue.queueSize)
        assertTrue(!queue.hasNext)
        assertTrue(!queue.hasPrevious)

        val q2 = PlaybackQueueState(items = listOf(item1, item1.copy(file = file1.copy(remote = com.teledrive.core.model.RemoteMessageId(1, 2)))), currentIndex = 0, repeatMode = RepeatMode.OFF)
        assertTrue(q2.hasNext)
        assertTrue(!q2.hasPrevious)
    }
}
