package com.teledrive.files

import com.teledrive.core.domain.FileIndexRepository
import com.teledrive.core.model.AppErrorMessages
import com.teledrive.core.model.ErrorCategory
import com.teledrive.core.model.LocalAvailability
import com.teledrive.core.model.TeleDriveLog
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
) {
    suspend fun reconcile(): Report = withContext(Dispatchers.IO) {
        // A process death leaves rows in QUEUED/RUNNING/VERIFYING that no coroutine owns.
        val interrupted = repository.failInterruptedTransfers(AppErrorMessages.forCategory(ErrorCategory.DOWNLOAD_FAILED))
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
