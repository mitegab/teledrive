package com.teledrive.core.domain

import com.teledrive.core.model.AppError
import com.teledrive.core.model.AppErrorMapper
import com.teledrive.core.model.ErrorOperation
import kotlinx.coroutines.CancellationException

/**
 * The gateway already classifies TDLib failures; this translates that classification
 * (plus coroutine cancellation) into the app-wide error model. Cancellation is never
 * reported as an operation failure.
 */
fun StorageGatewayException.toAppError(operation: ErrorOperation): AppError =
    AppErrorMapper.fromCode(code, message, operation, retryable)

fun Throwable.toAppError(operation: ErrorOperation): AppError = when (this) {
    is CancellationException -> AppErrorMapper.cancellation(operation)
    is StorageGatewayException -> toAppError(operation)
    else -> AppErrorMapper.fromThrowable(this, operation)
}

/** True when the failure means the session must be re-established before retrying. */
val AppError.requiresReconnect: Boolean
    get() = needsAuthentication
