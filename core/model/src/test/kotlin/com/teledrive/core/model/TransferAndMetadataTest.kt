package com.teledrive.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TransferAndMetadataTest {
    private fun transfer(state: TransferState = TransferState.QUEUED) = Transfer(
        id = TransferId("t1"),
        direction = TransferDirection.UPLOAD,
        filename = "file.bin",
        totalBytes = 100,
        state = state,
    )

    @Test
    fun transferStateMachineUsesRealProgressAndDoesNotCompleteFromTimer() {
        val running = TransferStateMachine.reduce(transfer(), TransferEvent.Start)
        val progressed = TransferStateMachine.reduce(running, TransferEvent.Progress(25, 100))
        assertEquals(TransferState.RUNNING, progressed.state)
        assertEquals(0.25f, progressed.progress)
        assertEquals(TransferState.RUNNING, TransferStateMachine.reduce(progressed, TransferEvent.Progress(25, 100)).state)
        assertEquals(TransferState.COMPLETED, TransferStateMachine.reduce(progressed, TransferEvent.Complete).state)
    }

    @Test
    fun cancellationIsTerminalAndDoesNotBecomeARecoverableFailure() {
        val running = TransferStateMachine.reduce(transfer(), TransferEvent.Start)
        val canceled = TransferStateMachine.reduce(running, TransferEvent.Cancel)

        assertEquals(TransferState.CANCELED, canceled.state)
        assertEquals(null, canceled.retryAtEpochSeconds)
        assertEquals(TransferState.CANCELED, TransferStateMachine.reduce(canceled, TransferEvent.Cancel).state)
    }

    @Test
    fun failedTransfersCanRetryButCompletedTransfersCannot() {
        val failed = TransferStateMachine.reduce(transfer(TransferState.RUNNING), TransferEvent.Fail("network", true, retryAtEpochSeconds = 30))
        assertEquals(30, failed.retryAtEpochSeconds)
        assertEquals(TransferState.QUEUED, TransferStateMachine.reduce(failed, TransferEvent.Retry).state)
        assertEquals(TransferState.COMPLETED, TransferStateMachine.reduce(transfer(TransferState.COMPLETED), TransferEvent.Retry).state)
    }

    @Test
    fun nonRetryableFailureDoesNotScheduleBackgroundWork() {
        val failed = TransferStateMachine.reduce(transfer(TransferState.RUNNING), TransferEvent.Fail("bad request", false, retryAtEpochSeconds = 30))
        assertEquals(null, failed.retryAtEpochSeconds)
        assertEquals(TransferState.FAILED, failed.state)
    }

    @Test
    fun serverAndRateLimitCodesAreRetryableEvenWithGenericMessages() {
        assertTrue(TransferRetryPolicy.isRetryable("500", "Internal Server Error"))
        assertTrue(TransferRetryPolicy.isRetryable("429", "Too Many Requests"))
    }

    @Test
    fun metadataRoundTripsUnicodeAndIgnoresMalformedRecords() {
        val record = MetadataRecord(
            operationId = "op-1",
            deviceId = "device-a",
            sequence = 1,
            timestampEpochSeconds = 10,
            operation = MetadataOperation.CreateFolder("f-1", "ማህደር 📁"),
        )
        val encoded = MetadataRecordCodec.encode(record)
        val decoded = assertIs<MetadataDecodeResult.Valid>(MetadataRecordCodec.decode(encoded))
        assertEquals(record, decoded.record)
        assertIs<MetadataDecodeResult.NotMetadata>(MetadataRecordCodec.decode("ordinary message"))
        assertIs<MetadataDecodeResult.Malformed>(MetadataRecordCodec.decode(MetadataRecordCodec.PREFIX + "not-base64"))
    }

    @Test
    fun metadataMergeIsDeterministicAndIdempotent() {
        val folder = MetadataRecord(1, "create", "b", 1, 1, MetadataOperation.CreateFolder("f", "Folder"))
        val rename = MetadataRecord(1, "rename", "a", 2, 2, MetadataOperation.RenameFolder("f", "Renamed"))
        val favorite = MetadataRecord(1, "favorite", "a", 3, 3, MetadataOperation.FavoriteFile("1:2", true))
        val unfavorite = MetadataRecord(1, "unfavorite", "a", 4, 4, MetadataOperation.FavoriteFile("1:2", false))
        val snapshot = MetadataMerger.merge(listOf(rename, favorite, unfavorite, folder, folder))
        assertEquals("Renamed", snapshot.folders["f"]?.name)
        assertTrue("1:2" !in snapshot.favoriteFileIds)
        assertEquals(false, snapshot.favoriteFileStates["1:2"])
        assertEquals(4, snapshot.appliedOperationIds.size)
    }
}
