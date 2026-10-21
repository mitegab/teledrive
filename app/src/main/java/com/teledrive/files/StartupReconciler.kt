package com.teledrive.files

import com.teledrive.core.domain.FileIndexRepository
import com.teledrive.core.model.LocalAvailability
import com.teledrive.core.model.TeleDriveLog
import com.teledrive.core.model.TransferState
import com.teledrive.transfers.ActiveTransferRegistry
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Runs once per process before any UI can trust persisted transfer/offline state.
 * It never contacts Telegram: only Room rows and local files are reconciled.
 */
@Singleton
class StartupReconciler @Inject constructor(
    private val repository: FileIndexRepository,
    private val offline: OfflineFileRepository,
    private val localStorage: LocalFileStorage,
    private val activeTransfers: ActiveTransferRegistry,
) {
    suspend fun reconcile(): Report = withContext(Dispatchers.IO) {
        // A process death leaves rows in QUEUED/RUNNING/VERIFYING that no coroutine owns.
        // Rows already owned by this process (a drain worker may start before the UI) are
        // left alone; the message is direction-neutral because it covers uploads too.
        val owned = activeTransfers.activeIds()
        val interruptedRows = repository.observeTransfers().first()
            .filter { it.state.isActive && it.id.value !in owned }
        interruptedRows.forEach { row ->
            repository.updateTransfer(
                row.copy(
                    state = TransferState.FAILED,
                    errorMessage = "The transfer was interrupted when the app closed. Try again.",
                    retryAtEpochSeconds = null,
                ),
            )
        }
        val interrupted = interruptedRows.size
        // Flags claiming an in-progress copy must not survive into the new process. The
        // filesystem sweep below clears them, this count is only for diagnostics.
        val staleFlags = repository.observeFiles().first().count {
            it.localAvailability == LocalAvailability.DOWNLOADING || it.localAvailability == LocalAvailability.PARTIAL
        }
        val report = offline.reconcile()
        localStorage.cleanupTemporaryFiles()
        TeleDriveLog.d(
            "startup.reconcile",
            "interrupted_transfers" to interrupted,
            "stale_flags" to staleFlags,
            "checked_offline" to report.checked,
            "cleared_records" to report.cleared,
            "deleted_damaged" to report.deleted,
        )
        Report(interrupted, staleFlags, report)
    }

    data class Report(
        val interruptedTransfers: Int,
        val staleAvailabilityFlags: Int,
        val offline: OfflineFileRepository.ReconcileReport,
    )
}
