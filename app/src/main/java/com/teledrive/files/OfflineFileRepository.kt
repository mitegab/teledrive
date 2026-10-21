package com.teledrive.files

import com.teledrive.core.domain.FileIndexRepository
import com.teledrive.core.model.FileSort
import com.teledrive.core.model.LocalAvailability
import com.teledrive.core.model.OfflineAvailability
import com.teledrive.core.model.OfflineFileState
import com.teledrive.core.model.OfflinePolicy
import com.teledrive.core.model.OfflineRecordAction
import com.teledrive.core.model.SortDirection
import com.teledrive.core.model.SortField
import com.teledrive.core.model.StoredFile
import com.teledrive.core.model.TeleDriveLog
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Single source of truth for "is this file really on the device?".
 *
 * It deliberately owns no table of its own: offline records live on [StoredFile]
 * (Room) and the bytes live in [LocalFileStorage]. This class only resolves and
 * reconciles the two, so the offline library can never drift into a parallel copy
 * of the remote index.
 */
@Singleton
class OfflineFileRepository @Inject constructor(
    private val repository: FileIndexRepository,
    private val localStorage: LocalFileStorage,
) {
    fun observeOfflineFiles(): Flow<List<StoredFile>> = repository.observeFiles(
        query = com.teledrive.core.model.FileQuery(localOnly = true),
        sort = FileSort(SortField.DATE, SortDirection.DESCENDING),
    )

    /** Latest persisted row for a stable id; never contacts Telegram. */
    suspend fun current(file: StoredFile): StoredFile =
        repository.observeFile(file.stableId).first() ?: file

    /** Resolves local bytes only; callers must not require network access for this. */
    suspend fun state(file: StoredFile, activeTransfer: com.teledrive.core.model.Transfer? = null): OfflineFileState =
        withContext(Dispatchers.IO) {
            val current = current(file)
            val probe = localStorage.probe(current.localPath, current.sizeBytes)
            OfflinePolicy.stateFor(current.localPath, current.sizeBytes, probe, activeTransfer)
        }

    suspend fun isUsableOffline(file: StoredFile): Boolean =
        withContext(Dispatchers.IO) { localStorage.probe(file.localPath, file.sizeBytes).let { OfflinePolicy.isUsableOffline(it, file.sizeBytes) } }

    /** Returns the verified local path, or null when there are no usable bytes. */
    suspend fun localPathOrNull(file: StoredFile): String? = withContext(Dispatchers.IO) {
        val current = current(file)
        val probe = localStorage.probe(current.localPath, current.sizeBytes)
        if (OfflinePolicy.isUsableOffline(probe, current.sizeBytes)) current.localPath else null
    }

    /**
     * Removes ONLY the device copy. The Telegram message and its index row are kept,
     * and the file remains downloadable.
     */
    suspend fun removeOfflineCopy(file: StoredFile): Boolean = withContext(Dispatchers.IO) {
        val current = current(file)
        val removedBytes = localStorage.remove(current.localPath)
        repository.deleteLocalFile(current.stableId)
        TeleDriveLog.d("offline.remove", "stable_id" to stableIdTail(current.stableId), "had_bytes" to removedBytes)
        true
    }

    /**
     * Compares every persisted offline flag with the filesystem. Missing or damaged
     * bytes are cleared (and deleted when they are clearly the wrong file), so the UI
     * never advertises a copy that cannot be opened.
     */
    suspend fun reconcile(): ReconcileReport = withContext(Dispatchers.IO) {
        val files = repository.observeFiles().first()
        var checked = 0
        var cleared = 0
        var deleted = 0
        // Also drops stale DOWNLOADING/PARTIAL flags left by a killed process.
        files.filter {
            it.localPath != null || it.localAvailability == LocalAvailability.AVAILABLE ||
                it.localAvailability == LocalAvailability.DOWNLOADING ||
                it.localAvailability == LocalAvailability.PARTIAL ||
                it.localAvailability == LocalAvailability.FAILED
        }.forEach { file ->
            checked++
            val probe = localStorage.probe(file.localPath, file.sizeBytes)
            when (OfflinePolicy.reconcile(file.localPath, file.sizeBytes, probe)) {
                OfflineRecordAction.KEEP -> Unit
                OfflineRecordAction.CLEAR_RECORD -> {
                    repository.deleteLocalFile(file.stableId)
                    cleared++
                }
                OfflineRecordAction.DELETE_BYTES_AND_CLEAR -> {
                    localStorage.remove(file.localPath)
                    repository.deleteLocalFile(file.stableId)
                    deleted++
                }
            }
        }
        TeleDriveLog.d("offline.reconcile", "checked" to checked, "cleared" to cleared, "deleted" to deleted)
        ReconcileReport(checked = checked, cleared = cleared, deleted = deleted)
    }

    suspend fun offlineUsageBytes(): Long = withContext(Dispatchers.IO) { localStorage.storageUsageBytes() }

    suspend fun freeSpaceBytes(): Long = withContext(Dispatchers.IO) { localStorage.availableBytes() }

    suspend fun markAvailable(file: StoredFile, localPath: String, sizeBytes: Long?, nowEpochSeconds: Long) {
        repository.saveLocalCopy(
            remoteId = file.stableId,
            localPath = localPath,
            sizeBytes = sizeBytes,
            offlineSinceEpochSeconds = nowEpochSeconds,
        )
    }

    private fun stableIdTail(stableId: String): String = stableId.substringAfter(':', stableId).takeLast(6)

    data class ReconcileReport(val checked: Int, val cleared: Int, val deleted: Int)
}

/** Presentation labels shared by the offline library, file rows and viewers. */
fun OfflineAvailability.label(): String = when (this) {
    OfflineAvailability.NOT_DOWNLOADED -> "Not downloaded"
    OfflineAvailability.QUEUED -> "Queued"
    OfflineAvailability.PREPARING -> "Preparing"
    OfflineAvailability.DOWNLOADING -> "Downloading"
    OfflineAvailability.VERIFYING -> "Checking file"
    OfflineAvailability.AVAILABLE -> "Available offline"
    OfflineAvailability.FAILED -> "Download failed"
    OfflineAvailability.CANCELED -> "Download canceled"
    OfflineAvailability.NEEDS_ATTENTION -> "Saved copy missing"
}
