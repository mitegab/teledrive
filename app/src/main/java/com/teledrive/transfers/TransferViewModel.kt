package com.teledrive.transfers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teledrive.core.domain.FileIndexRepository
import com.teledrive.core.domain.StorageGatewayException
import com.teledrive.core.domain.TelegramStorageGateway
import com.teledrive.core.model.Transfer
import com.teledrive.core.model.TransferProgress
import com.teledrive.core.model.TransferState
import com.teledrive.files.DownloadCoordinator
import com.teledrive.files.UploadCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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
            runCatching {
                gateway.cancelTransfer(transfer.id, transfer.direction, transfer.tdlibFileId)
                if (transfer.direction == com.teledrive.core.model.TransferDirection.UPLOAD) uploadCoordinator.discardStagedSource(transfer)
                repository.updateTransfer(com.teledrive.core.model.TransferStateMachine.reduce(transfer.copy(sourcePath = null), com.teledrive.core.model.TransferEvent.Cancel))
            }.onFailure { error -> _state.update { it.copy(errorMessage = error.safeMessage()) } }
        }
    }

    fun retry(transfer: Transfer) {
        if (transfer.state != TransferState.FAILED) return
        viewModelScope.launch {
            runCatching {
                if (transfer.direction == com.teledrive.core.model.TransferDirection.UPLOAD) uploadCoordinator.retry(transfer)
                else downloadCoordinator.retry(transfer)
            }.onFailure { error -> _state.update { it.copy(errorMessage = error.safeMessage()) } }
        }
    }

    fun clearError() = _state.update { it.copy(errorMessage = null) }

    private suspend fun applyProgress(progress: TransferProgress) {
        val current = repository.getTransfer(progress.transferId.value) ?: return
        val next = current.copy(
            bytesTransferred = progress.bytesTransferred,
            totalBytes = progress.totalBytes ?: current.totalBytes,
            state = progress.state,
            errorMessage = progress.errorMessage,
            remote = progress.remote ?: current.remote,
            tdlibFileId = progress.tdlibFileId ?: current.tdlibFileId,
        )
        repository.updateTransfer(next)
    }
}

private fun Throwable.safeMessage(): String = when (this) {
    is StorageGatewayException -> message
    else -> message ?: "The transfer could not be changed"
}
