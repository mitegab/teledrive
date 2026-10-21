package com.teledrive.files

import com.teledrive.core.domain.StorageGatewayException
import com.teledrive.core.domain.toAppError
import com.teledrive.core.model.AppError
import com.teledrive.core.model.AppErrorMapper
import com.teledrive.core.model.ErrorCategory
import com.teledrive.core.model.ErrorOperation
import com.teledrive.core.model.TeleDriveLog
import com.teledrive.core.model.TransferEvent
import com.teledrive.core.model.TransferRetryPolicy
import java.io.IOException
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
    val appError = toTransferAppError(operation)
    val retryable = appError.retryable && this !is CancellationException
    // Telegram's own flood-wait (parsed into the AppError) wins over our backoff, then an
    // explicitly supplied delay, then jittered exponential backoff.
    val delaySeconds = retryAfterSeconds?.takeIf { it > 0L }
        ?: appError.retryAfterSeconds?.takeIf { it > 0L }
        ?: TransferRetryPolicy.nextDelaySeconds(attempt)
    val retryAt = if (retryable) System.currentTimeMillis() / 1000 + delaySeconds else null
    if (!retryable) TeleDriveLog.d(appError.logLine())
    return TransferEvent.Fail(appError.userMessage, retryable, retryAt)
}

/**
 * Classifies transfer failures.
 *
 * Known gateway/storage failures keep their own classification. Anything else is only
 * retried when it is an [IOException] (a transient local/network read or write); an
 * unexpected runtime failure is reported as UNKNOWN and is never retried automatically,
 * because retrying a programming error just hides it.
 */
internal fun Throwable.toTransferAppError(operation: ErrorOperation): AppError = when (this) {
    is CancellationException -> AppErrorMapper.cancellation(operation)
    is StorageGatewayException -> toAppError(operation)
    is LocalStorageException -> toAppError(operation)
    is IOException -> AppErrorMapper.fromThrowable(this, operation)
    is SecurityException -> AppErrorMapper.fromThrowable(this, operation, ErrorCategory.ACCESS_DENIED, retryable = false)
    else -> AppErrorMapper.fromThrowable(this, operation, ErrorCategory.UNKNOWN, retryable = false)
}
