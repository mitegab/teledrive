package com.teledrive.transfers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teledrive.core.domain.FileIndexRepository
import com.teledrive.core.domain.TelegramStorageGateway
import com.teledrive.core.domain.toAppError
import com.teledrive.core.model.ErrorOperation
import com.teledrive.core.model.TeleDriveLog
import com.teledrive.core.model.Transfer
import com.teledrive.core.model.TransferDirection
import com.teledrive.core.model.TransferProgress
import com.teledrive.core.model.TransferState
import com.teledrive.files.DownloadCoordinator
import com.teledrive.files.UploadCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

 data class TransfersUiState(
    val transfers: List<Transfer> = emptyList(),
    val errorMessage: String? = null,
)

@HiltViewModel
class TransferViewModel @Inject constructor(
    private val repository: FileIndexRepository,
    private val gateway: TelegramStorageGateway,
    private val uploadCoordinator: UploadCoordinator,
    private val downloadCoordinator: DownloadCoordinator,
) : ViewModel() {
    private val _state = MutableStateFlow(TransfersUiState())
    val state: StateFlow<TransfersUiState> = _state.asStateFlow()

    /** Coordinates the last progress write per upload so Room is not written per TDLib update. */
    private data class LastWrite(val atMillis: Long, val bytes: Long)
    private val lastWrites = ConcurrentHashMap<String, LastWrite>()

    init {
        viewModelScope.launch {
            repository.observeTransfers().collect { transfers -> _state.update { it.copy(transfers = transfers) } }
        }
        viewModelScope.launch {
            gateway.transferUpdates.collect { progress -> applyProgress(progress) }
        }
    }

    fun cancel(transfer: Transfer) {
        viewModelScope.launch {
            val current = repository.getTransfer(transfer.id.value) ?: transfer
            if (current.state.isTerminal) return@launch
            try {
                if (current.direction == TransferDirection.DOWNLOAD) {
                    // The coordinator owns download cancellation, including persisted rows
                    // that no longer have an in-process job.
                    downloadCoordinator.cancel(current.remote?.value)
                    return@launch
                }
                if (current.state == TransferState.RUNNING) {
                    runCatching { gateway.cancelTransfer(current.id, current.direction, current.tdlibFileId) }
                        .onFailure { error -> TeleDriveLog.d("transfer.cancel_gateway_failed", "error" to error.message) }
                }
                uploadCoordinator.cancel(current.id)
                val settled = repository.getTransfer(current.id.value) ?: current
                uploadCoordinator.discardStagedSource(settled)
                val latest = repository.getTransfer(current.id.value) ?: settled
                repository.updateTransfer(com.teledrive.core.model.TransferStateMachine.reduce(latest.copy(sourcePath = null), com.teledrive.core.model.TransferEvent.Cancel))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.update { it.copy(errorMessage = error.toAppError(ErrorOperation.UNKNOWN).userMessage) }
            }
        }
    }

    fun retry(transfer: Transfer) {
        if (transfer.state != TransferState.FAILED && transfer.state != TransferState.CANCELED) return
        viewModelScope.launch {
            try {
                if (transfer.direction == TransferDirection.UPLOAD) uploadCoordinator.retry(transfer)
                else downloadCoordinator.retry(transfer)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                _state.update { it.copy(errorMessage = error.toAppError(ErrorOperation.UNKNOWN).userMessage) }
            }
        }
    }

    fun clearError() = _state.update { it.copy(errorMessage = null) }

    private suspend fun applyProgress(progress: TransferProgress) {
        val current = repository.getTransfer(progress.transferId.value) ?: return
        // Downloads persist their own throttled progress in DownloadCoordinator. Writing here
        // as well would put Room on the per-update path and could overwrite VERIFYING.
        if (current.direction != TransferDirection.UPLOAD) return
        if (current.state.isTerminal) return
        val now = System.currentTimeMillis()
        val last = lastWrites[progress.transferId.value]
        val state = if (progress.state.isTerminal) current.state else progress.state
        val meaningful = state != current.state ||
            last == null ||
            now - last.atMillis >= 1_000L ||
            progress.bytesTransferred - last.bytes >= 512L * 1024L
        if (!meaningful) return
        lastWrites[progress.transferId.value] = LastWrite(now, progress.bytesTransferred)
        val next = current.copy(
            bytesTransferred = progress.bytesTransferred,
            totalBytes = progress.totalBytes ?: current.totalBytes,
            state = state,
            errorMessage = progress.errorMessage ?: current.errorMessage,
            remote = progress.remote ?: current.remote,
            tdlibFileId = progress.tdlibFileId ?: current.tdlibFileId,
        )
        repository.updateTransfer(next)
        if (state.isTerminal) lastWrites.remove(progress.transferId.value)
    }
}
