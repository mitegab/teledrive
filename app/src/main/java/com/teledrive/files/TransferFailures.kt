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
    // The coordinator already classified this failure. Re-classifying the wrapper would
    // replace a precise reason (storage, corrupt bytes, session) with a generic sentence.
    is DownloadException -> error
    is StorageGatewayException -> toAppError(operation)
    is LocalStorageException -> toAppError(operation)
    is IOException -> AppErrorMapper.fromThrowable(this, operation)
    is SecurityException -> AppErrorMapper.fromThrowable(this, operation, ErrorCategory.ACCESS_DENIED, retryable = false)
    else -> AppErrorMapper.fromThrowable(this, operation, ErrorCategory.UNKNOWN, retryable = false)
}

/**
 * Error raised for a completed download, with the coordinator's own classification preserved.
 * Screens that start a download must use this instead of the generic domain mapper, or every
 * non-network cause is shown as a network failure.
 */
fun Throwable.toDownloadAppError(): AppError = toTransferAppError(ErrorOperation.DOWNLOAD)

/**
 * Opening a verified local copy can fail on its own (no handler installed, provider or file
 * removed). It must never be reported as a download failure.
 */
fun localOpenFailure(): AppError = AppError(
    category = ErrorCategory.LOCAL_FILE_INACCESSIBLE,
    operation = ErrorOperation.STORAGE,
    userMessage = "TeleDrive couldn't open the saved copy. The file may have been removed, or no installed app can open this format.",
    retryable = false,
    diagnosticCode = "OPEN_FAILED",
)
