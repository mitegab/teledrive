package com.teledrive.files

import com.teledrive.core.domain.StorageGatewayException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException

class TransferFailuresTest {
    @Test
    fun cancellationIsNotConvertedIntoRetryableBackgroundWork() {
        val failure = CancellationException("user canceled").toTransferFailure(attempt = 3)

        assertFalse(failure.retryable)
        assertNull(failure.retryAtEpochSeconds)
    }

    @Test
    fun gatewayRetryabilityIsPreservedAndBackoffIsAssigned() {
        val retryable = StorageGatewayException("NETWORK_ERROR", "offline", retryable = true)
        val permanent = StorageGatewayException("BAD_REQUEST", "invalid", retryable = false)

        val retryFailure = retryable.toTransferFailure(1)
        assertTrue(retryFailure.retryable)
        val retryAt = retryFailure.retryAtEpochSeconds
        assertTrue(retryAt != null)
        assertTrue(retryAt - System.currentTimeMillis() / 1000 in 59L..60L)
        assertFalse(permanent.toTransferFailure(1).retryable)
        assertNull(permanent.toTransferFailure(1).retryAtEpochSeconds)
    }
}
