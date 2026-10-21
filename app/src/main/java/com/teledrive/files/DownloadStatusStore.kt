package com.teledrive.files

import com.teledrive.core.model.DownloadSnapshot
import com.teledrive.core.model.LocalAvailability
import com.teledrive.core.model.StoredFile
import com.teledrive.core.model.Transfer
import com.teledrive.core.model.TransferDirection
import com.teledrive.core.model.TransferState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Observable, in-memory download state for the UI. The database remains the durable
 * record; this store only adds transient details (speed, ETA, live progress) so
 * Compose does not have to re-read Room on every TDLib update.
 */
@Singleton
class DownloadStatusStore @Inject constructor() {
    private val _states = MutableStateFlow<Map<String, DownloadSnapshot>>(emptyMap())
    val states: StateFlow<Map<String, DownloadSnapshot>> = _states.asStateFlow()

    fun snapshot(stableId: String): DownloadSnapshot? = _states.value[stableId]

    fun put(snapshot: DownloadSnapshot) {
        _states.update { current -> current + (snapshot.stableId to snapshot) }
    }

    fun update(stableId: String, transform: (DownloadSnapshot?) -> DownloadSnapshot?) {
        _states.update { current ->
            val next = transform(current[stableId]) ?: return@update current - stableId
            current + (stableId to next)
        }
    }

    fun remove(stableId: String) {
        _states.update { it - stableId }
    }

    fun clearTerminal() {
        _states.update { current -> current.filterValues { it.state?.isActive == true } }
    }

    /** Reflects transfers started by the background worker (another process lifetime). */
    fun mergePersisted(transfers: List<Transfer>) {
        val downloads = transfers.filter { it.direction == TransferDirection.DOWNLOAD && it.remote != null }
        _states.update { current ->
            val next = current.toMutableMap()
            downloads.forEach { transfer ->
                val stableId = transfer.remote?.value ?: return@forEach
                val existing = next[stableId]
                if (existing?.isActive == true) return@forEach
                if (transfer.state.isActive) {
                    next[stableId] = DownloadSnapshot(
                        stableId = stableId,
                        state = transfer.state,
                        bytesTransferred = transfer.bytesTransferred,
                        totalBytes = transfer.totalBytes,
                        attempt = transfer.attempt,
                        errorMessage = transfer.errorMessage,
                        isOfflineAvailable = existing?.isOfflineAvailable ?: false,
                    )
                } else if (existing == null && transfer.state == TransferState.FAILED) {
                    next[stableId] = DownloadSnapshot(
                        stableId = stableId,
                        state = TransferState.FAILED,
                        errorMessage = transfer.errorMessage,
                        // After a restart only the durable row is left; the user can always retry.
                        retryable = true,
                    )
                }
            }
            next
        }
    }

    /** Seeds a terminal snapshot so rows can show "Available offline" immediately. */
    fun seedFromFile(file: StoredFile) {
        if (file.localAvailability != LocalAvailability.AVAILABLE) return
        val stableId = file.stableId
        val existing = _states.value[stableId]
        if (existing?.isActive == true) return
        put(
            DownloadSnapshot(
                stableId = stableId,
                state = TransferState.COMPLETED,
                bytesTransferred = file.sizeBytes ?: 0L,
                totalBytes = file.sizeBytes,
                isOfflineAvailable = true,
            ),
        )
    }
}
