package com.teledrive.playback

import com.teledrive.core.model.RemoteMessageId
import com.teledrive.core.model.StoredFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PlaybackQueueTest {

    private fun file(id: Long, name: String = "file$id.mp3"): StoredFile = StoredFile(
        remote = RemoteMessageId(1, id),
        tdlibFileId = id.toInt(),
        originalFilename = name,
        mimeType = "audio/mpeg",
        sizeBytes = 1000,
        savedAtEpochSeconds = 0
    )

    private fun item(id: Long): PlaybackItem = PlaybackItem(
        file = file(id),
        localPath = "/tmp/$id.mp3",
        title = "Title $id"
    )

    @Test
    fun setQueueAndNavigation() {
        val q = PlaybackQueue()
        q.setQueue(listOf(item(1), item(2), item(3)), 0)
        assertEquals(3, q.state.value.items.size)
        assertEquals(0, q.state.value.currentIndex)
        assertEquals(1L, q.current()?.file?.remote?.messageId)

        val next = q.next()
        assertEquals(2L, next?.file?.remote?.messageId)
        assertEquals(1, q.state.value.currentIndex)

        val prev = q.previous()
        assertEquals(1L, prev?.file?.remote?.messageId)
        assertEquals(0, q.state.value.currentIndex)
    }

    @Test
    fun handlesEndOfQueue() {
        val q = PlaybackQueue()
        q.setQueue(listOf(item(1), item(2)), 1)
        assertNull(q.next())
        assertEquals(1, q.state.value.currentIndex)
    }

    @Test
    fun repeatAllWraps() {
        val q = PlaybackQueue()
        q.setQueue(listOf(item(1), item(2)), 1)
        q.setRepeatMode(RepeatMode.ALL)
        val next = q.next()
        assertEquals(1L, next?.file?.remote?.messageId)
        assertEquals(0, q.state.value.currentIndex)

        q.setQueue(listOf(item(1), item(2)), 0)
        q.setRepeatMode(RepeatMode.ALL)
        val prev = q.previous()
        assertEquals(2L, prev?.file?.remote?.messageId)
    }

    @Test
    fun removeAndReorder() {
        val q = PlaybackQueue()
        q.setQueue(listOf(item(1), item(2), item(3)), 1)
        q.removeAt(0)
        assertEquals(2, q.state.value.items.size)
        assertEquals(0, q.state.value.currentIndex) // previously index 1 now 0 after removal before

        q.setQueue(listOf(item(1), item(2), item(3)), 0)
        q.move(0, 2)
        assertEquals(3L, q.state.value.items[0].file.remote.messageId)
        assertEquals(3, q.state.value.items.size)
    }

    @Test
    fun addToQueueAvoidsDuplicates() {
        val q = PlaybackQueue()
        q.setQueue(listOf(item(1)), 0)
        q.addToQueue(item(1))
        assertEquals(1, q.state.value.items.size)
        q.addToQueue(item(2))
        assertEquals(2, q.state.value.items.size)
    }

    @Test
    fun shuffleCreatesOrder() {
        val q = PlaybackQueue()
        q.setQueue(listOf(item(1), item(2), item(3), item(4)), 0)
        q.setShuffle(true)
        assertEquals(ShuffleMode.ON, q.state.value.shuffleMode)
        assertEquals(4, q.state.value.shuffledOrder.size)
        // Current index should be first in shuffled order
        assertEquals(0, q.state.value.shuffledOrder[0])

        q.setShuffle(false)
        assertEquals(ShuffleMode.OFF, q.state.value.shuffleMode)
        assertTrue(q.state.value.shuffledOrder.isEmpty())
    }

    @Test
    fun toggleRepeatCycles() {
        val q = PlaybackQueue()
        q.setQueue(listOf(item(1)), 0)
        assertEquals(RepeatMode.OFF, q.state.value.repeatMode)
        assertEquals(RepeatMode.ALL, q.toggleRepeat())
        assertEquals(RepeatMode.ONE, q.toggleRepeat())
        assertEquals(RepeatMode.OFF, q.toggleRepeat())
    }
}
