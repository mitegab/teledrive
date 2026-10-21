package com.teledrive.files

import com.teledrive.core.domain.toAppError
import com.teledrive.core.model.ErrorOperation
import com.teledrive.core.model.TeleDriveLog
import com.teledrive.core.model.TransferEvent
import com.teledrive.core.model.TransferRetryPolicy
import kotlinx.coroutines.CancellationException

/**
 * Converts a transfer failure into the persisted failure event.
 *
 * Retryability comes from the centralized error model (unknown failures are not
 * retried), the server-requested delay wins over our backoff, and the stored message
 * is the user-facing sentence rather than a raw exception.
 */
internal fun Throwable.toTransferFailure(
    attempt: Int,
    operation: ErrorOperation = ErrorOperation.UNKNOWN,
    retryAfterSeconds: Long? = null,
): TransferEvent.Fail {
    val appError = toAppError(operation)
    val retryable = appError.retryable && this !is CancellationException
    val delaySeconds = retryAfterSeconds?.takeIf { it > 0L }
        ?: TransferRetryPolicy.nextDelaySeconds(attempt)
    val retryAt = if (retryable) System.currentTimeMillis() / 1000 + delaySeconds else null
    if (!retryable) TeleDriveLog.d(appError.logLine())
    return TransferEvent.Fail(appError.userMessage, retryable, retryAt)
}
