package com.teledrive.transfers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.teledrive.core.domain.FileIndexRepository
import com.teledrive.core.domain.TelegramStorageGateway
import com.teledrive.core.model.AuthorizationState
import com.teledrive.core.model.TeleDriveLog
import com.teledrive.core.model.Transfer
import com.teledrive.core.model.TransferDirection
import com.teledrive.core.model.TransferState
import com.teledrive.files.DownloadCoordinator
import com.teledrive.files.LocalFileStorage
import com.teledrive.files.UploadCoordinator
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Resumes persisted work when Android grants a network window.
 *
 * This worker never returns [Result.retry]: an unbounded retry loop can keep a doomed
 * transfer looking active forever. Failed transfers carry their own `retryAt`, and the
 * coordinator schedules a delayed drain for the earliest one. A single run processes a
 * bounded number of rows.
 */
class TransferDrainWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val dependencies = EntryPointAccessors.fromApplication(
            applicationContext,
            TransferWorkerEntryPoint::class.java,
        )
        dependencies.localStorage.cleanupTemporaryFiles()
        runCatching { dependencies.gateway.start() }.onFailure { error ->
            TeleDriveLog.d("drain.start_failed", "error" to error.message)
            return Result.success()
        }
        val authorization = withTimeoutOrNull(15_000) {
            dependencies.gateway.authorizationState.first { state ->
                state is AuthorizationState.Ready || state is AuthorizationState.Error
            }
        }
        if (authorization !is AuthorizationState.Ready) {
            // Connecting/authenticating is not transfer work; do not spin WorkManager.
            TeleDriveLog.d("drain.not_ready", "state" to (authorization?.let { it::class.java.simpleName } ?: "timeout"))
            return Result.success()
        }

        val transfers = dependencies.repository.observeTransfers().first()
        val now = System.currentTimeMillis() / 1000
        val due = transfers
            .filter { transfer ->
                transfer.state == TransferState.QUEUED ||
                    transfer.state == TransferState.PREPARING ||
                    transfer.state == TransferState.RUNNING ||
                    transfer.state == TransferState.VERIFYING ||
                    (transfer.state == TransferState.FAILED && (transfer.retryAtEpochSeconds ?: Long.MAX_VALUE) <= now)
            }
            .take(MAX_TRANSFERS_PER_RUN)

        var handled = 0
        var uploads = 0
        due.forEach { transfer ->
            if (transfer.state.isActive && dependencies.activeTransfers.isActive(transfer.id.value)) {
                // Already owned by the in-process coordinator.
                return@forEach
            }
            if (transfer.direction == TransferDirection.UPLOAD && !dependencies.localStorage.isStagedSource(transfer.sourcePath)) {
                dependencies.repository.updateTransfer(
                    transfer.copy(
                        state = TransferState.FAILED,
                        errorMessage = "The staged upload file is no longer available; choose the file again",
                        retryAtEpochSeconds = null,
                    ),
                )
                return@forEach
            }
            if (transfer.direction == TransferDirection.UPLOAD && uploads >= MAX_UPLOADS_PER_RUN) return@forEach
            runCatching {
                if (transfer.direction == TransferDirection.UPLOAD) {
                    uploads++
                    dependencies.uploadCoordinator.retry(transfer)
                } else {
                    // Enqueues into the bounded pool and returns; the process (or the next
                    // worker run) keeps the persisted row truthful.
                    dependencies.downloadCoordinator.beginRetry(transfer)
                }
                handled++
            }.onFailure { error ->
                TeleDriveLog.d("drain.transfer_failed", "direction" to transfer.direction.name, "error" to error.message)
            }
        }

        // If a retry is scheduled for later, ask WorkManager to wake once at the earliest time.
        val nextRetry = dependencies.repository.observeTransfers().first()
            .mapNotNull { it.retryAtEpochSeconds }
            .filter { it > now }
            .minOrNull()
        if (nextRetry != null) {
            dependencies.workScheduler.enqueue((nextRetry - now).coerceAtLeast(1L))
        }
        TeleDriveLog.d("drain.done", "due" to due.size, "handled" to handled)
        return Result.success()
    }

    private companion object {
        const val MAX_TRANSFERS_PER_RUN = 20
        /** Uploads are awaited by this worker, so a run only takes on a small number. */
        const val MAX_UPLOADS_PER_RUN = 2
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TransferWorkerEntryPoint {
    val gateway: TelegramStorageGateway
    val repository: FileIndexRepository
    val activeTransfers: ActiveTransferRegistry
    val localStorage: LocalFileStorage
    val uploadCoordinator: UploadCoordinator
    val downloadCoordinator: DownloadCoordinator
    val workScheduler: TransferWorkScheduler
}
