package com.teledrive.files

import com.teledrive.core.domain.StorageGatewayException
import com.teledrive.core.model.TransferEvent
import com.teledrive.core.model.TransferRetryPolicy
import kotlinx.coroutines.CancellationException

internal fun Throwable.toTransferFailure(attempt: Int): TransferEvent.Fail {
    val retryable = when (this) {
        is CancellationException -> false
        is StorageGatewayException -> retryable
        else -> true
    }
    val retryAt = if (retryable) {
        System.currentTimeMillis() / 1000 + TransferRetryPolicy.nextDelaySeconds(attempt)
    } else null
    return TransferEvent.Fail(message ?: "The transfer failed", retryable, retryAt)
}
