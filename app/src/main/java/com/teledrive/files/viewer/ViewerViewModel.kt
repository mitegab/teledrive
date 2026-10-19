package com.teledrive.files.viewer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teledrive.core.domain.FileIndexRepository
import com.teledrive.core.domain.TelegramStorageGateway
import com.teledrive.core.model.StoredFile
import com.teledrive.files.DownloadCoordinator
import com.teledrive.files.FileOpenRouter
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltViewModel
class ViewerViewModel @Inject constructor(
    private val repository: FileIndexRepository,
    private val gateway: TelegramStorageGateway,
    private val downloads: DownloadCoordinator,
    private val external: FileOpenRouter,
) : ViewModel() {
    private val _state = MutableStateFlow(ViewerState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            gateway.transferUpdates.collect { update ->
                _state.value.file?.takeIf { it.remote == update.remote }?.let { current ->
                    _state.value = _state.value.copy(progress = update.totalBytes?.takeIf { it > 0 }?.let { (update.bytesTransferred.toFloat() / it).coerceIn(0f, 1f) }, transferredBytes = update.bytesTransferred, totalBytes = update.totalBytes)
                }
            }
        }
    }

    fun load(stableId: String) {
        if (_state.value.file?.stableId == stableId || _state.value.loading) return
        viewModelScope.launch {
            _state.value = ViewerState(loading = true)
            val file = repository.observeFile(stableId).first()
            if (file == null) _state.value = ViewerState(error = "This file is no longer in the library.")
            else {
                _state.value = ViewerState(file = file, loading = true)
                runCatching { downloads.download(file) }
                    .onSuccess { _state.value = ViewerState(file = it) }
                    .onFailure { error -> _state.value = ViewerState(file = file, error = error.message ?: "Download failed.") }
            }
        }
    }

    fun cancelDownload() { _state.value.file?.let { downloads.cancel(it.stableId) } }
    fun openExternal() {
        val file = _state.value.file ?: return
        runCatching { external.open(file, false) }
            .onFailure { _state.value = _state.value.copy(error = it.message ?: "Unable to open in another app.") }
    }
    fun export(destination: android.net.Uri) {
        val path = _state.value.file?.localPath ?: return
        viewModelScope.launch {
            runCatching { downloads.export(path, destination) }
                .onFailure { _state.value = _state.value.copy(error = it.message ?: "Export failed.") }
        }
    }
}

data class ViewerState(val file: StoredFile? = null, val loading: Boolean = false, val error: String? = null, val progress: Float? = null, val transferredBytes: Long = 0, val totalBytes: Long? = null)
