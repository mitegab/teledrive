package com.teledrive.transfers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.teledrive.core.domain.FileIndexRepository
import com.teledrive.core.domain.TelegramStorageGateway
import com.teledrive.core.model.AuthorizationState
import com.teledrive.core.model.TransferState
import com.teledrive.files.DownloadCoordinator
import com.teledrive.files.LocalFileStorage
import com.teledrive.files.UploadCoordinator
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.EntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Resumes persisted, retryable work when Android grants a network window. */
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
        runCatching { dependencies.gateway.start() }.getOrElse { return Result.retry() }
        val authorization = withTimeoutOrNull(15_000) {
            dependencies.gateway.authorizationState.first { state -> state is AuthorizationState.Ready || state is AuthorizationState.Error }
        }
        if (authorization is AuthorizationState.Error) return Result.failure()
        if (authorization !is AuthorizationState.Ready) return Result.retry()
        val transfers = dependencies.repository.observeTransfers().first()
        var failed = false
        val now = System.currentTimeMillis() / 1000
        transfers.filter { transfer ->
            val retryAt = transfer.retryAtEpochSeconds
            transfer.state == TransferState.QUEUED ||
                transfer.state == TransferState.RUNNING ||
                (transfer.state == TransferState.FAILED && retryAt != null && retryAt <= now)
        }.forEach { transfer ->
            if (transfer.state == TransferState.RUNNING && dependencies.activeTransfers.isActive(transfer.id.value)) {
                failed = true
                return@forEach
            }
            val retryable = transfer.direction != com.teledrive.core.model.TransferDirection.UPLOAD || dependencies.localStorage.isStagedSource(transfer.sourcePath)
            if (!retryable) {
                dependencies.repository.updateTransfer(transfer.copy(state = TransferState.FAILED, errorMessage = "The staged upload file is no longer available", retryAtEpochSeconds = null))
                return@forEach
            }
            runCatching {
                if (transfer.direction == com.teledrive.core.model.TransferDirection.UPLOAD) dependencies.uploadCoordinator.retry(transfer)
                else dependencies.downloadCoordinator.retry(transfer)
            }.onFailure { failed = true }
        }
        return if (failed) Result.retry() else Result.success()
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
}
