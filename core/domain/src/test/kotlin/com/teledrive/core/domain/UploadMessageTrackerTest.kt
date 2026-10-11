package com.teledrive.core.domain

import com.teledrive.core.model.TransferId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UploadMessageTrackerTest {
    @Test
    fun provisionalAndFinalMessageIdsResolveToTheSameTransfer() {
        val tracker = UploadMessageTracker()
        val transfer = TransferId("upload-1")
        tracker.register(transfer, chatId = 42, provisionalMessageId = 10)
        tracker.bind(transfer, 11)

        assertEquals(transfer, tracker.resolve(42, messageId = 11))
        assertEquals(transfer, tracker.resolve(42, messageId = 11, oldMessageId = 10))
    }

    @Test
    fun aNewMessageCanBeCorrelatedBeforeTheSendResponseIsResumed() {
        val tracker = UploadMessageTracker()
        val transfer = TransferId("upload-2")
        tracker.register(transfer, chatId = 42)

        assertEquals(transfer, tracker.resolve(chatId = 42, messageId = 20))
        assertEquals(setOf(20L), tracker.messageIds(transfer))
    }

    @Test
    fun ambiguousConcurrentUploadsAreNotGuessed() {
        val tracker = UploadMessageTracker()
        tracker.register(TransferId("upload-a"), chatId = 42)
        tracker.register(TransferId("upload-b"), chatId = 42)

        assertNull(tracker.resolve(chatId = 42, messageId = 20))
    }

    @Test
    fun messageIdsFromAnotherChatAreNotCorrelated() {
        val tracker = UploadMessageTracker()
        val transfer = TransferId("upload-cross-chat")
        tracker.register(transfer, chatId = 42, provisionalMessageId = 30)

        assertNull(tracker.resolve(chatId = 99, messageId = 30))
        assertEquals(transfer, tracker.resolve(chatId = 42, messageId = 30))
    }

    @Test
    fun completionRemovesAllAliasesAndMakesDuplicateUpdatesNoOps() {
        val tracker = UploadMessageTracker()
        val transfer = TransferId("upload-3")
        tracker.register(transfer, chatId = 42, provisionalMessageId = 30)
        tracker.bind(transfer, 31)
        tracker.complete(transfer)

        assertNull(tracker.resolve(42, messageId = 31, oldMessageId = 30))
        assertEquals(emptySet(), tracker.messageIds(transfer))
    }
}
