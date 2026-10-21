package com.teledrive.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferPolicyTest {
    private fun transfer(state: TransferState = TransferState.QUEUED, attempt: Int = 0) = Transfer(
        id = TransferId("t1"),
        direction = TransferDirection.DOWNLOAD,
        filename = "report.pdf",
        totalBytes = 1_000L,
        state = state,
        attempt = attempt,
    )

    private fun step(current: Transfer, event: TransferEvent) = TransferStateMachine.reduce(current, event)

    @Test
    fun aDownloadMovesThroughPreparingRunningVerifyingAndCompleted() {
        val queued = transfer()
        val preparing = step(queued, TransferEvent.Start)
        assertEquals(TransferState.PREPARING, preparing.state)

        val running = step(preparing, TransferEvent.Progress(400, 1_000))
        assertEquals(TransferState.RUNNING, running.state)
        assertEquals(400L, running.bytesTransferred)

        val verifying = step(running, TransferEvent.Verify)
        assertEquals(TransferState.VERIFYING, verifying.state)

        val completed = step(verifying, TransferEvent.Complete)
        assertEquals(TransferState.COMPLETED, completed.state)
        assertEquals(1_000L, completed.bytesTransferred)
    }

    @Test
    fun progressDuringVerificationDoesNotFlipBackToRunning() {
        val verifying = transfer(TransferState.VERIFYING)
        assertEquals(TransferState.VERIFYING, step(verifying, TransferEvent.Progress(900, 1_000)).state)
    }

    @Test
    fun terminalStatesAreNotOverwrittenByLaterEvents() {
        val completed = transfer(TransferState.COMPLETED)
        assertEquals(TransferState.COMPLETED, step(completed, TransferEvent.Progress(1, 2)).state)
        assertEquals(TransferState.COMPLETED, step(completed, TransferEvent.Complete).state)
        assertEquals(TransferState.COMPLETED, step(completed, TransferEvent.Cancel).state)
        assertEquals(TransferState.COMPLETED, step(completed, TransferEvent.Fail("x", true)).state)

        val canceled = transfer(TransferState.CANCELED)
        assertEquals(TransferState.CANCELED, step(canceled, TransferEvent.Complete).state)
    }

    @Test
    fun cancellationIsAllowedFromEveryActiveStage() {
        listOf(TransferState.QUEUED, TransferState.PREPARING, TransferState.RUNNING, TransferState.VERIFYING).forEach { state ->
            assertEquals("cancel from $state", TransferState.CANCELED, step(transfer(state), TransferEvent.Cancel).state)
        }
    }

    @Test
    fun failuresCarryRetryDelayOnlyWhenRetryable() {
        val retryable = step(transfer(TransferState.RUNNING), TransferEvent.Fail("offline", retryable = true, retryAtEpochSeconds = 120))
        assertEquals(TransferState.FAILED, retryable.state)
        assertEquals(120L, retryable.retryAtEpochSeconds)

        val permanent = step(transfer(TransferState.RUNNING), TransferEvent.Fail("gone", retryable = false, retryAtEpochSeconds = 120))
        assertEquals(TransferState.FAILED, permanent.state)
        assertNull(permanent.retryAtEpochSeconds)
    }

    @Test
    fun retryResetsToQueuedAndCountsTheAttempt() {
        val failed = transfer(TransferState.FAILED, attempt = 1).copy(errorMessage = "offline", retryAtEpochSeconds = 5)
        val retried = step(failed, TransferEvent.Retry)
        assertEquals(TransferState.QUEUED, retried.state)
        assertEquals(2, retried.attempt)
        assertNull(retried.errorMessage)
        assertNull(retried.retryAtEpochSeconds)
    }

    @Test
    fun retryIsIgnoredWhileATransferIsStillActive() {
        assertEquals(TransferState.RUNNING, step(transfer(TransferState.RUNNING), TransferEvent.Retry).state)
    }

    @Test
    fun automaticRetriesAreBoundedAndRequireATransientError() {
        assertTrue(TransferRetryPolicy.shouldAutoRetry(0, retryable = true))
        assertTrue(TransferRetryPolicy.shouldAutoRetry(2, retryable = true))
        assertFalse(TransferRetryPolicy.shouldAutoRetry(3, retryable = true))
        assertFalse(TransferRetryPolicy.shouldAutoRetry(0, retryable = false))
    }

    @Test
    fun backoffGrowsExponentiallyAndJitterStaysWithinBounds() {
        assertEquals(60L, TransferRetryPolicy.nextDelaySeconds(1))
        assertEquals(900L, TransferRetryPolicy.nextDelaySeconds(9))

        repeat(20) { index ->
            val jitter = index / 20.0
            val delay = TransferRetryPolicy.nextDelaySeconds(1, jitter)
            assertTrue("delay $delay must stay bounded", delay in 45L..90L)
        }
        assertEquals(900L, TransferRetryPolicy.nextDelaySeconds(9, 0.99))
    }

    @Test
    fun cancellationIsNotConvertedIntoAnAutomaticRetry() {
        val canceled = step(transfer(TransferState.RUNNING), TransferEvent.Cancel)
        assertEquals(TransferState.CANCELED, canceled.state)
        assertFalse(TransferRetryPolicy.shouldAutoRetry(canceled.attempt, retryable = false))
    }

    @Test
    fun downloadLimitsRejectUnboundedConcurrency() {
        runCatching { DownloadLimits(maxConcurrentTransfers = 0) }.also { assertTrue(it.isFailure) }
        runCatching { DownloadLimits(maxConcurrentTransfers = 50) }.also { assertTrue(it.isFailure) }
        assertEquals(3, DownloadLimits.Default.maxConcurrentTransfers)
    }

    @Test
    fun storageMarginScalesWithFileSizeAndRejectsUnknownAvailability() {
        val limits = DownloadLimits(baseFreeSpaceMarginBytes = 100L, freeSpaceMarginFraction = 0.10)
        assertEquals(1_100L, limits.requiredFreeBytes(1_000L))
        assertEquals(100L, limits.requiredFreeBytes(null))
        assertTrue(limits.hasEnoughSpace(1_100L, 1_000L))
        assertFalse(limits.hasEnoughSpace(1_099L, 1_000L))
        assertFalse(limits.hasEnoughSpace(-1L, null))
    }

    @Test
    fun speedIsUnknownUntilEnoughTimeHasPassed() {
        val estimator = TransferSpeedEstimator()
        assertNull(estimator.sample(0, 0).bytesPerSecond)
        assertNull(estimator.sample(500_000, 500).bytesPerSecond)
    }

    @Test
    fun speedAndEtaBecomeAvailableForAConsistentTransfer() {
        val estimator = TransferSpeedEstimator()
        estimator.sample(0, 0)
        val snapshot = estimator.sample(1_000_000, 1_000)
        assertEquals(1_000_000L, snapshot.bytesPerSecond)
        assertTrue(snapshot.isStable)
        val withTotal = estimator.snapshot(totalBytes = 5_000_000L, nowMillis = 1_500)
        assertEquals(4_000_000L / withTotal.bytesPerSecond!!.coerceAtLeast(1L), withTotal.etaSeconds ?: -1L)
    }

    @Test
    fun etaIsWithheldWhenTheRateIsUnstable() {
        val estimator = TransferSpeedEstimator()
        estimator.sample(0, 0)
        estimator.sample(1_000_000, 1_000)
        val unstable = estimator.sample(1_000_010, 1_100)
        assertFalse(unstable.isStable)
        assertNull(unstable.etaSeconds)
    }

    @Test
    fun decreasingBytesResetTheEstimatorForARetry() {
        val estimator = TransferSpeedEstimator()
        estimator.sample(0, 0)
        estimator.sample(1_000_000, 1_000)
        val afterReset = estimator.sample(10_000, 2_000)
        assertNull(afterReset.bytesPerSecond)
        assertEquals(0L, afterReset.elapsedMillis)
    }

    @Test
    fun progressWritesAreThrottledButNeverDroppedAtCompletion() {
        val limits = DownloadLimits.Default
        assertTrue(TransferProgressPolicy.shouldPersistProgress(null, 0, 1, TransferState.RUNNING, 10, limits))
        assertFalse(TransferProgressPolicy.shouldPersistProgress(1_000, 1_000, 2_000, TransferState.RUNNING, 1_500, limits))
        assertTrue(TransferProgressPolicy.shouldPersistProgress(1_000, 1_000, 1_000 + limits.progressPersistMinDeltaBytes, TransferState.RUNNING, 1_100, limits))
        assertTrue(TransferProgressPolicy.shouldPersistProgress(1_000, 1_000, 1_001, TransferState.RUNNING, 2_100, limits))
        assertTrue(TransferProgressPolicy.shouldPersistProgress(1_000, 1_000, 1_001, TransferState.COMPLETED, 1_100, limits))
    }

    @Test
    fun uiRefreshAndPercentagesStaySane() {
        assertTrue(TransferProgressPolicy.shouldRefreshUi(null, 0))
        assertFalse(TransferProgressPolicy.shouldRefreshUi(1_000, 1_200))
        assertTrue(TransferProgressPolicy.shouldRefreshUi(1_000, 1_300))
        assertEquals(50, TransferProgressPolicy.progressPercent(500, 1_000))
        assertEquals(100, TransferProgressPolicy.progressPercent(2_000, 1_000))
        assertNull(TransferProgressPolicy.progressPercent(10, null))
    }

    @Test
    fun stallDetectionDoesNotFireWithoutEvidence() {
        assertFalse(TransferProgressPolicy.isStalled(null, 10_000))
        assertFalse(TransferProgressPolicy.isStalled(10_000, 20_000))
        assertTrue(TransferProgressPolicy.isStalled(1_000, 200_000))
    }

    @Test
    fun speedAndEtaLabelsAreOnlyProducedWhenMeaningful() {
        assertNull(TransferProgressPolicy.humanizeSpeed(null))
        assertNull(TransferProgressPolicy.humanizeSpeed(0))
        assertEquals("1 KB/s", TransferProgressPolicy.humanizeSpeed(1_024))
        assertEquals("1.5 MB/s", TransferProgressPolicy.humanizeSpeed(1_572_864))
        assertNull(TransferProgressPolicy.humanizeEta(null))
        assertEquals("45s left", TransferProgressPolicy.humanizeEta(45))
        assertEquals("2m left", TransferProgressPolicy.humanizeEta(125))
    }
}
