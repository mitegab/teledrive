package com.teledrive.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineModelsTest {
    private val probe = LocalFileProbe(exists = true, sizeBytes = 1_000L, readable = true)
    private val missing = LocalFileProbe.Missing

    private fun file(
        id: Long = 1L,
        local: LocalAvailability = LocalAvailability.NOT_DOWNLOADED,
        path: String? = null,
        kindName: String = "report.pdf",
    ) = StoredFile(
        remote = RemoteMessageId(1L, id),
        tdlibFileId = id.toInt(),
        originalFilename = kindName,
        mimeType = "application/pdf",
        sizeBytes = 1_000L,
        savedAtEpochSeconds = 10L,
        localAvailability = local,
        localPath = path,
    )

    @Test
    fun aVerifiedCopyIsTheOnlyThingConsideredAvailableOffline() {
        assertTrue(OfflinePolicy.isUsableOffline(probe, 1_000L))

        assertFalse(OfflinePolicy.isUsableOffline(missing, 1_000L))
        assertFalse(OfflinePolicy.isUsableOffline(probe.copy(readable = false), 1_000L))
        assertFalse(OfflinePolicy.isUsableOffline(probe.copy(sizeBytes = 0L), 0L))
        assertFalse(OfflinePolicy.isUsableOffline(probe, 2_000L))
        assertTrue(OfflinePolicy.isUsableOffline(probe, null))
    }

    @Test
    fun staleRecordsAreDistinguishedFromDamagedBytes() {
        assertEquals(OfflineRecordAction.KEEP, OfflinePolicy.reconcile("/downloads/a/report.pdf", 1_000L, probe))
        assertEquals(OfflineRecordAction.CLEAR_RECORD, OfflinePolicy.reconcile("/downloads/a/report.pdf", 1_000L, missing))
        assertEquals(
            OfflineRecordAction.DELETE_BYTES_AND_CLEAR,
            OfflinePolicy.reconcile("/downloads/a/report.pdf", 1_000L, probe.copy(sizeBytes = 512L)),
        )
        assertEquals(OfflineRecordAction.CLEAR_RECORD, OfflinePolicy.reconcile(null, 1_000L, missing))
    }

    @Test
    fun activeTransfersTakePrecedenceOverTheRecordedState() {
        fun active(state: TransferState) = Transfer(
            id = TransferId("t"),
            direction = TransferDirection.DOWNLOAD,
            filename = "report.pdf",
            state = state,
        )
        assertEquals(
            OfflineAvailability.PREPARING,
            OfflinePolicy.stateFor(null, 1_000L, missing, active(TransferState.PREPARING)).availability,
        )
        assertEquals(
            OfflineAvailability.DOWNLOADING,
            OfflinePolicy.stateFor(null, 1_000L, missing, active(TransferState.RUNNING)).availability,
        )
        assertEquals(
            OfflineAvailability.VERIFYING,
            OfflinePolicy.stateFor(null, 1_000L, missing, active(TransferState.VERIFYING)).availability,
        )
        assertEquals(
            OfflineAvailability.AVAILABLE,
            OfflinePolicy.stateFor("/downloads/a/report.pdf", 1_000L, probe, active(TransferState.COMPLETED)).availability,
        )
    }

    @Test
    fun missingBytesWithARecordNeedsAttentionAndCanBeRestarted() {
        val state = OfflinePolicy.stateFor("/downloads/a/report.pdf", 1_000L, missing)
        assertEquals(OfflineAvailability.NEEDS_ATTENTION, state.availability)
        assertTrue(state.retryable)
        assertFalse(state.isAvailableOffline)
        assertTrue(OfflinePolicy.canStartDownload(hasRemoteFile = true, state.availability))
        assertFalse(OfflinePolicy.canStartDownload(hasRemoteFile = false, state.availability))
    }

    @Test
    fun failedAndCanceledTransfersSurfaceTheirUserMessage() {
        val failed = Transfer(
            id = TransferId("t"),
            direction = TransferDirection.DOWNLOAD,
            filename = "report.pdf",
            state = TransferState.FAILED,
            errorMessage = AppErrorMessages.forCategory(ErrorCategory.NETWORK_UNAVAILABLE),
            retryAtEpochSeconds = 5L,
        )
        val failedState = OfflinePolicy.stateFor(null, 1_000L, missing, failed)
        assertEquals(OfflineAvailability.FAILED, failedState.availability)
        assertTrue(failedState.retryable)
        assertTrue(failedState.message!!.contains("connection", ignoreCase = true))

        val canceled = OfflinePolicy.stateFor(null, 1_000L, missing, failed.copy(state = TransferState.CANCELED))
        assertEquals(OfflineAvailability.CANCELED, canceled.availability)
        assertFalse(canceled.retryable)
    }

    @Test
    fun staleDeclaredAvailabilityIsDetected() {
        assertTrue(OfflinePolicy.recordIsStale(declaredAvailable = true, probe = missing, expectedSizeBytes = 1_000L))
        assertFalse(OfflinePolicy.recordIsStale(declaredAvailable = true, probe = probe, expectedSizeBytes = 1_000L))
        assertFalse(OfflinePolicy.recordIsStale(declaredAvailable = false, probe = missing, expectedSizeBytes = 1_000L))
    }

    @Test
    fun offlineQueryOnlyReturnsVerifiedCopies() {
        val files = listOf(
            file(id = 1, local = LocalAvailability.AVAILABLE, path = "/downloads/1/a.pdf"),
            file(id = 2, local = LocalAvailability.NOT_DOWNLOADED),
            file(id = 3, local = LocalAvailability.DOWNLOADING),
        )
        val offline = files.queryFiles(FileQuery(localOnly = true))
        assertEquals(1, offline.size)
        assertEquals(1L, offline.single().remote.messageId)
        assertEquals(3, files.queryFiles(FileQuery()).size)
    }

    @Test
    fun downloadSnapshotsDescribeOnlyRealStates() {
        val snapshot = DownloadSnapshot(stableId = "1:1", state = TransferState.RUNNING, bytesTransferred = 500, totalBytes = 1_000)
        assertEquals(0.5f, snapshot.progressFraction!!, 0.001f)
        assertTrue(snapshot.canCancel)
        assertFalse(snapshot.canRetry)

        val failed = DownloadSnapshot(stableId = "1:1", state = TransferState.FAILED, errorMessage = "Download failed.", retryable = true)
        assertTrue(failed.canRetry)
        assertFalse(failed.canCancel)
        assertEquals("Download failed.", failed.statusLabel)

        val completed = DownloadSnapshot(stableId = "1:1", state = TransferState.COMPLETED, isOfflineAvailable = true)
        assertTrue(completed.canRemoveOfflineCopy)
        assertEquals("Available offline", completed.statusLabel)
        assertFalse(DownloadSnapshot.idle("1:1").isActive)
    }

    @Test
    fun snapshotTransitionsClearTransientSpeeds() {
        val running = DownloadSnapshot(
            stableId = "1:1",
            state = TransferState.RUNNING,
            bytesTransferred = 500,
            totalBytes = 1_000,
            bytesPerSecond = 5_000,
            etaSeconds = 10,
        )
        val failed = running.asFailure(AppErrorMapper.fromCode("NETWORK_ERROR", null, ErrorOperation.DOWNLOAD))
        assertNull(failed.bytesPerSecond)
        assertNull(failed.etaSeconds)
        assertTrue(failed.retryable)
        assertFalse(failed.isOfflineAvailable)

        val canceled = running.asCanceled()
        assertNull(canceled.errorMessage)
        assertEquals(TransferState.CANCELED, canceled.state)

        val done = running.asCompleted(1_000)
        assertTrue(done.isOfflineAvailable)
        assertEquals(1_000L, done.bytesTransferred)
    }

    @Test
    fun offlineByteTotalsIgnoreUnverifiedRecords() {
        val files = listOf(
            file(id = 1, local = LocalAvailability.AVAILABLE, path = "/a.pdf"),
            file(id = 2, local = LocalAvailability.AVAILABLE, path = "/b.pdf"),
            file(id = 3, local = LocalAvailability.NOT_DOWNLOADED),
        )
        val total = OfflinePolicy.totalOfflineBytes(files) { stored ->
            if (stored.remote.messageId == 1L) LocalFileProbe(true, 700L, true) else LocalFileProbe.Missing
        }
        assertEquals(700L, total)
    }
}
