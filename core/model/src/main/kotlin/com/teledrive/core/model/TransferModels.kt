package com.teledrive.core.model

import kotlin.math.max
import kotlin.math.min

enum class TransferDirection {
    UPLOAD,
    DOWNLOAD,
}

enum class TransferState {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELED,
}

data class TransferId(val value: String)

data class Transfer(
    val id: TransferId,
    val direction: TransferDirection,
    val filename: String,
    val sourcePath: String? = null,
    val remote: RemoteMessageId? = null,
    val tdlibFileId: Int? = null,
    val bytesTransferred: Long = 0,
    val totalBytes: Long? = null,
    val state: TransferState = TransferState.QUEUED,
    val errorMessage: String? = null,
    val attempt: Int = 0,
    val retryAtEpochSeconds: Long? = null,
) {
    val progress: Float?
        get() = totalBytes?.takeIf { it > 0 }?.let {
            (bytesTransferred.toDouble() / it.toDouble()).toFloat().coerceIn(0f, 1f)
        }
}

data class TransferProgress(
    val transferId: TransferId,
    val bytesTransferred: Long,
    val totalBytes: Long?,
    val state: TransferState,
    val errorMessage: String? = null,
    val remote: RemoteMessageId? = null,
    val tdlibFileId: Int? = null,
)

sealed interface TransferEvent {
    data object Start : TransferEvent
    data class Progress(val bytes: Long, val totalBytes: Long?) : TransferEvent
    data object Complete : TransferEvent
    data class Fail(val message: String, val retryable: Boolean, val retryAtEpochSeconds: Long? = null) : TransferEvent
    data object Cancel : TransferEvent
    data object Retry : TransferEvent
}

object TransferStateMachine {
    fun reduce(current: Transfer, event: TransferEvent): Transfer {
        return when (event) {
            TransferEvent.Start -> if (current.state in setOf(TransferState.QUEUED, TransferState.FAILED)) {
                current.copy(state = TransferState.RUNNING, errorMessage = null, retryAtEpochSeconds = null)
            } else current

            is TransferEvent.Progress -> if (current.state == TransferState.RUNNING) {
                current.copy(
                    bytesTransferred = max(0, event.bytes),
                    totalBytes = event.totalBytes ?: current.totalBytes,
                )
            } else current

            TransferEvent.Complete -> current.copy(
                state = TransferState.COMPLETED,
                bytesTransferred = current.totalBytes ?: current.bytesTransferred,
                errorMessage = null,
                retryAtEpochSeconds = null,
            )

            is TransferEvent.Fail -> current.copy(
                state = TransferState.FAILED,
                errorMessage = event.message,
                retryAtEpochSeconds = event.retryAtEpochSeconds.takeIf { event.retryable },
            )

            TransferEvent.Cancel -> if (current.state in setOf(TransferState.QUEUED, TransferState.RUNNING)) {
                current.copy(state = TransferState.CANCELED, errorMessage = null, retryAtEpochSeconds = null)
            } else current

            TransferEvent.Retry -> if (current.state == TransferState.FAILED) {
                current.copy(
                    state = TransferState.QUEUED,
                    attempt = current.attempt + 1,
                    errorMessage = null,
                    retryAtEpochSeconds = null,
                    bytesTransferred = min(current.bytesTransferred, current.totalBytes ?: current.bytesTransferred),
                )
            } else current
        }
    }
}

object TransferRetryPolicy {
    private val retryableCodes = setOf(
        "NETWORK_ERROR",
        "CONNECTION_ERROR",
        "TIMEOUT",
        "TEMPORARY_SERVER_ERROR",
        "FLOOD_WAIT",
        "FILE_REFERENCE_EXPIRED",
    )

    fun isRetryable(code: String?, message: String? = null): Boolean {
        val normalizedCode = code.orEmpty().uppercase()
        if (normalizedCode in retryableCodes) return true
        normalizedCode.toIntOrNull()?.let { numericCode ->
            if (numericCode == 429 || numericCode >= 500) return true
        }
        val normalizedMessage = message.orEmpty().uppercase()
        return listOf("NETWORK", "TIMEOUT", "FLOOD_WAIT", "CONNECTION").any(normalizedMessage::contains)
    }

    fun nextDelaySeconds(attempt: Int): Long = (30L * (1L shl attempt.coerceIn(0, 5))).coerceAtMost(900L)
}
