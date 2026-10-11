package com.teledrive.core.model

import kotlin.math.max
import kotlin.math.min

enum class TransferDirection {
    UPLOAD,
    DOWNLOAD,
}

/**
 * Lifecycle of an upload or download.
 *
 * Uploads:   QUEUED -> PREPARING -> RUNNING (uploading) -> FINALIZING (waiting for Telegram's confirmed message) -> COMPLETED
 * Downloads: QUEUED -> PREPARING -> RUNNING (downloading) -> FINALIZING (verifying/publishing the local copy) -> COMPLETED
 *
 * FAILED and CANCELED are terminal until an explicit retry. Persisted as text; unknown values degrade to FAILED.
 */
enum class TransferState {
    QUEUED,
    PREPARING,
    RUNNING,
    FINALIZING,
    COMPLETED,
    FAILED,
    CANCELED,
    ;

    /** True while the transfer may still make progress and should be resumed or cancellable. */
    val isActive: Boolean get() = this == QUEUED || this == PREPARING || this == RUNNING || this == FINALIZING
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
    data object Prepare : TransferEvent
    data object Start : TransferEvent
    data object Finalize : TransferEvent
    data class Progress(val bytes: Long, val totalBytes: Long?) : TransferEvent
    data object Complete : TransferEvent
    data class Fail(val message: String, val retryable: Boolean, val retryAtEpochSeconds: Long? = null) : TransferEvent
    data object Cancel : TransferEvent
    data object Retry : TransferEvent
}

object TransferStateMachine {
    fun reduce(current: Transfer, event: TransferEvent): Transfer {
        return when (event) {
            TransferEvent.Prepare -> if (current.state in setOf(TransferState.QUEUED, TransferState.FAILED)) {
                current.copy(state = TransferState.PREPARING, errorMessage = null, retryAtEpochSeconds = null)
            } else current

            TransferEvent.Start -> if (current.state in setOf(TransferState.QUEUED, TransferState.PREPARING, TransferState.FAILED)) {
                current.copy(state = TransferState.RUNNING, errorMessage = null, retryAtEpochSeconds = null)
            } else current

            TransferEvent.Finalize -> if (current.state in setOf(TransferState.PREPARING, TransferState.RUNNING)) {
                current.copy(state = TransferState.FINALIZING, errorMessage = null, retryAtEpochSeconds = null)
            } else current

            is TransferEvent.Progress -> if (current.state == TransferState.RUNNING || current.state == TransferState.PREPARING) {
                current.copy(
                    bytesTransferred = max(0, event.bytes),
                    totalBytes = event.totalBytes ?: current.totalBytes,
                )
            } else current

            // A late confirmation must not resurrect a transfer the user cancelled.
            TransferEvent.Complete -> if (current.state == TransferState.CANCELED) current else current.copy(
                state = TransferState.COMPLETED,
                bytesTransferred = current.totalBytes ?: current.bytesTransferred,
                errorMessage = null,
                retryAtEpochSeconds = null,
            )

            is TransferEvent.Fail -> if (current.state == TransferState.CANCELED) current else current.copy(
                state = TransferState.FAILED,
                errorMessage = event.message,
                retryAtEpochSeconds = event.retryAtEpochSeconds.takeIf { event.retryable },
            )

            TransferEvent.Cancel -> if (current.state.isActive) {
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
        val normalizedMessage = message.orEmpty().uppercase()
        return listOf("NETWORK", "TIMEOUT", "FLOOD_WAIT", "CONNECTION").any(normalizedMessage::contains)
    }

    fun nextDelaySeconds(attempt: Int): Long = (30L * (1L shl attempt.coerceIn(0, 5))).coerceAtMost(900L)
}
