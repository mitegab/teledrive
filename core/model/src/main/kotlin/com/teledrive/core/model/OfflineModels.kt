package com.teledrive.core.model

/** Result of inspecting the bytes on disk for an offline record. */
data class LocalFileProbe(
    val exists: Boolean,
    val sizeBytes: Long? = null,
    val readable: Boolean = exists,
) {
    companion object {
        val Missing = LocalFileProbe(exists = false, sizeBytes = null, readable = false)
    }
}

/** How an offline record relates to the file that is actually on disk. */
enum class OfflineRecordAction {
    /** Record and bytes agree. */
    KEEP,

    /** The record says a copy exists but the bytes are gone or cannot be read. */
    CLEAR_RECORD,

    /** Bytes exist but are the wrong size/empty; remove them and clear the record. */
    DELETE_BYTES_AND_CLEAR,
}

enum class OfflineAvailability {
    NOT_DOWNLOADED,
    QUEUED,
    PREPARING,
    DOWNLOADING,
    VERIFYING,
    AVAILABLE,
    FAILED,
    CANCELED,
    /** A record exists but its bytes are damaged; the user must download again. */
    NEEDS_ATTENTION,
}

/** Offline view of a single file, derived from local bytes plus any active transfer. */
data class OfflineFileState(
    val availability: OfflineAvailability,
    val localPath: String? = null,
    val message: String? = null,
    val retryable: Boolean = false,
) {
    val isAvailableOffline: Boolean get() = availability == OfflineAvailability.AVAILABLE
    val isActiveTransfer: Boolean
        get() = availability in setOf(
            OfflineAvailability.QUEUED,
            OfflineAvailability.PREPARING,
            OfflineAvailability.DOWNLOADING,
            OfflineAvailability.VERIFYING,
        )
}

object OfflinePolicy {
    /**
     * A local copy is offline-ready only when it exists, is readable, is not empty,
     * and (when Telegram reported a size) matches that size exactly.
     */
    fun isUsableOffline(probe: LocalFileProbe, expectedSizeBytes: Long?): Boolean {
        if (!probe.exists || !probe.readable) return false
        val size = probe.sizeBytes ?: return false
        if (size <= 0L) return false
        return expectedSizeBytes == null || expectedSizeBytes < 0L || size == expectedSizeBytes
    }

    fun reconcile(
        recordedPath: String?,
        expectedSizeBytes: Long?,
        probe: LocalFileProbe,
    ): OfflineRecordAction {
        if (recordedPath.isNullOrBlank()) {
            // Bytes without a record are never adopted implicitly.
            return if (probe.exists) OfflineRecordAction.DELETE_BYTES_AND_CLEAR else OfflineRecordAction.CLEAR_RECORD
        }
        if (!probe.exists) return OfflineRecordAction.CLEAR_RECORD
        if (!probe.readable) return OfflineRecordAction.CLEAR_RECORD
        return if (isUsableOffline(probe, expectedSizeBytes)) OfflineRecordAction.KEEP
        else OfflineRecordAction.DELETE_BYTES_AND_CLEAR
    }

    /** Combines a persisted offline record with the current transfer, if any. */
    fun stateFor(
        recordedPath: String?,
        expectedSizeBytes: Long?,
        probe: LocalFileProbe,
        activeTransfer: Transfer? = null,
    ): OfflineFileState {
        activeTransfer?.takeIf { it.direction == TransferDirection.DOWNLOAD && it.state.isActive }?.let { transfer ->
            val availability = when (transfer.state) {
                TransferState.QUEUED -> OfflineAvailability.QUEUED
                TransferState.PREPARING -> OfflineAvailability.PREPARING
                TransferState.RUNNING -> OfflineAvailability.DOWNLOADING
                TransferState.VERIFYING -> OfflineAvailability.VERIFYING
                else -> OfflineAvailability.DOWNLOADING
            }
            return OfflineFileState(availability, recordedPath)
        }
        if (isUsableOffline(probe, expectedSizeBytes)) {
            return OfflineFileState(OfflineAvailability.AVAILABLE, recordedPath)
        }
        if (!recordedPath.isNullOrBlank()) {
            return OfflineFileState(
                availability = OfflineAvailability.NEEDS_ATTENTION,
                localPath = recordedPath,
                message = AppErrorMessages.forCategory(ErrorCategory.LOCAL_FILE_MISSING),
                retryable = true,
            )
        }
        return when (activeTransfer?.state) {
            TransferState.FAILED -> OfflineFileState(
                availability = OfflineAvailability.FAILED,
                message = activeTransfer.errorMessage,
                retryable = activeTransfer.retryAtEpochSeconds != null || activeTransfer.attempt < DownloadLimits.Default.maxAutomaticAttempts,
            )
            TransferState.CANCELED -> OfflineFileState(OfflineAvailability.CANCELED, message = AppErrorMessages.forCategory(ErrorCategory.TRANSFER_CANCELED))
            else -> OfflineFileState(OfflineAvailability.NOT_DOWNLOADED)
        }
    }

    /** True when a stored availability flag claimed a copy that the disk does not have. */
    fun recordIsStale(declaredAvailable: Boolean, probe: LocalFileProbe, expectedSizeBytes: Long?): Boolean =
        declaredAvailable && !isUsableOffline(probe, expectedSizeBytes)

    fun canStartDownload(hasRemoteFile: Boolean, availability: OfflineAvailability): Boolean =
        hasRemoteFile && availability in setOf(
            OfflineAvailability.NOT_DOWNLOADED,
            OfflineAvailability.FAILED,
            OfflineAvailability.CANCELED,
            OfflineAvailability.NEEDS_ATTENTION,
        )

    fun totalOfflineBytes(files: Iterable<StoredFile>, probe: (StoredFile) -> LocalFileProbe): Long =
        files.sumOf { file ->
            if (file.localAvailability == LocalAvailability.AVAILABLE) {
                probe(file).takeIf { it.exists && it.readable }?.sizeBytes ?: 0L
            } else 0L
        }
}

/**
 * Immutable UI snapshot for one download. Produced by the download coordinator and
 * consumed by the file browser, viewers and the PDF reader.
 */
data class DownloadSnapshot(
    val stableId: String,
    /** Null means no transfer has ever been requested for this file. */
    val state: TransferState?,
    val bytesTransferred: Long = 0L,
    val totalBytes: Long? = null,
    val bytesPerSecond: Long? = null,
    val etaSeconds: Long? = null,
    val attempt: Int = 0,
    val errorMessage: String? = null,
    val retryable: Boolean = false,
    val isOfflineAvailable: Boolean = false,
) {
    val progressFraction: Float?
        get() = TransferProgressPolicy.progressPercent(bytesTransferred, totalBytes)?.let { it / 100f }

    val isActive: Boolean get() = state?.isActive == true
    val canCancel: Boolean get() = state?.isActive == true
    val canRetry: Boolean get() = state == TransferState.FAILED && retryable
    val canRemoveOfflineCopy: Boolean get() = isOfflineAvailable

    val statusLabel: String
        get() = when (state) {
            null -> "Not downloaded"
            TransferState.QUEUED -> "Waiting to download"
            TransferState.PREPARING -> "Preparing download"
            TransferState.RUNNING -> "Downloading"
            TransferState.VERIFYING -> "Checking the downloaded file"
            TransferState.COMPLETED -> if (isOfflineAvailable) "Available offline" else "Downloaded"
            TransferState.FAILED -> errorMessage ?: AppErrorMessages.forCategory(ErrorCategory.DOWNLOAD_FAILED)
            TransferState.CANCELED -> "Download canceled"
        }

    val speedLabel: String? get() = TransferProgressPolicy.humanizeSpeed(bytesPerSecond)
    val etaLabel: String? get() = TransferProgressPolicy.humanizeEta(etaSeconds)
    val transferredLabel: String get() = TransferProgressPolicy.humanizeBytes(bytesTransferred)

    fun withProgress(bytes: Long, totalBytes: Long?, speed: TransferSpeedSnapshot): DownloadSnapshot = copy(
        bytesTransferred = bytes,
        totalBytes = totalBytes ?: this.totalBytes,
        bytesPerSecond = speed.bytesPerSecond,
        etaSeconds = speed.etaSeconds,
    )

    fun asFailure(error: AppError): DownloadSnapshot = copy(
        state = TransferState.FAILED,
        errorMessage = error.userMessage,
        retryable = error.retryable,
        bytesPerSecond = null,
        etaSeconds = null,
    )

    fun asCompleted(sizeBytes: Long?): DownloadSnapshot = copy(
        state = TransferState.COMPLETED,
        bytesTransferred = sizeBytes ?: bytesTransferred,
        totalBytes = sizeBytes ?: totalBytes,
        bytesPerSecond = null,
        etaSeconds = null,
        errorMessage = null,
        isOfflineAvailable = true,
    )

    fun asCanceled(): DownloadSnapshot = copy(
        state = TransferState.CANCELED,
        bytesPerSecond = null,
        etaSeconds = null,
        errorMessage = null,
    )

    companion object {
        fun idle(stableId: String) = DownloadSnapshot(stableId = stableId, state = null)

        fun from(transfer: Transfer, offlineAvailable: Boolean = false): DownloadSnapshot = DownloadSnapshot(
            stableId = transfer.remote?.value.orEmpty(),
            state = transfer.state,
            bytesTransferred = transfer.bytesTransferred,
            totalBytes = transfer.totalBytes,
            attempt = transfer.attempt,
            errorMessage = transfer.errorMessage,
            retryable = transfer.state == TransferState.FAILED && (transfer.retryAtEpochSeconds != null || transfer.attempt < DownloadLimits.Default.maxAutomaticAttempts),
            isOfflineAvailable = offlineAvailable,
        )
    }
}
