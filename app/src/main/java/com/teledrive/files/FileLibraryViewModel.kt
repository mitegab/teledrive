package com.teledrive.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teledrive.core.domain.FileIndexRepository
import com.teledrive.core.domain.ObserveFilesUseCase
import com.teledrive.core.domain.StorageGatewayException
import com.teledrive.core.domain.SynchronizeFilesUseCase
import com.teledrive.core.domain.TelegramStorageGateway
import com.teledrive.core.model.FileKind
import com.teledrive.core.model.FileQuery
import com.teledrive.core.model.FileSort
import com.teledrive.core.model.Folder
import com.teledrive.core.model.LocalAvailability
import com.teledrive.core.model.RemoteAvailability
import com.teledrive.core.model.SortDirection
import com.teledrive.core.model.SortField
import com.teledrive.core.model.StoredFile
import com.teledrive.core.model.TransferProgress
import com.teledrive.preferences.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

 data class FileLibraryUiState(
    val files: List<StoredFile> = emptyList(),
    val folders: List<Folder> = emptyList(),
    val transfers: List<TransferProgress> = emptyList(),
    val query: FileQuery = FileQuery(),
    val sort: FileSort = FileSort(),
    val isGrid: Boolean = false,
    val isSyncing: Boolean = false,
    val hasSynced: Boolean = false,
    val errorMessage: String? = null,
)

@HiltViewModel
class FileLibraryViewModel @Inject constructor(
    private val observeFiles: ObserveFilesUseCase,
    private val synchronizeFiles: SynchronizeFilesUseCase,
    private val repository: FileIndexRepository,
    private val gateway: TelegramStorageGateway,
    private val uploadCoordinator: UploadCoordinator,
    private val downloadCoordinator: DownloadCoordinator,
    private val preferences: PreferencesRepository,
) : ViewModel() {
    private val query = MutableStateFlow(FileQuery())
    private val sort = MutableStateFlow(FileSort())
    private val _state = MutableStateFlow(FileLibraryUiState())
    val state: StateFlow<FileLibraryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(query, sort, preferences.gridView) { q, s, grid -> Triple(q, s, grid) }
                .collectLatest { (q, s, grid) ->
                    observeFiles(q, s).collect { files -> _state.update { it.copy(files = files, query = q, sort = s, isGrid = grid) } }
                }
        }
        viewModelScope.launch {
            repository.observeFolders().collect { folders -> _state.update { it.copy(folders = folders) } }
        }
        viewModelScope.launch {
            gateway.transferUpdates.collect { progress ->
                _state.update { current ->
                    val without = current.transfers.filterNot { it.transferId == progress.transferId }
                    current.copy(transfers = without + progress)
                }
            }
        }
    }

    fun synchronize() {
        if (_state.value.isSyncing) return
        viewModelScope.launch {
            _state.update { it.copy(isSyncing = true, errorMessage = null) }
            runCatching { synchronizeFiles() }
                .onSuccess { _state.update { it.copy(isSyncing = false, hasSynced = true) } }
                .onFailure { error -> _state.update { it.copy(isSyncing = false, errorMessage = error.safeMessage()) } }
        }
    }

    fun upload(uri: android.net.Uri) {
        viewModelScope.launch {
            runCatching { uploadCoordinator.upload(uri) }
                .onFailure { error -> _state.update { it.copy(errorMessage = error.safeMessage()) } }
        }
    }

    fun setSearchText(value: String) = query.update { it.copy(text = value) }
    fun clearKinds() = query.update { it.copy(kinds = emptySet()) }
    fun setFolder(folderId: String?) = query.update { it.copy(folderId = folderId) }
    fun setFavoritesOnly(enabled: Boolean) = query.update { it.copy(favoritesOnly = enabled) }
    fun toggleKind(kind: FileKind) = query.update { current ->
        current.copy(kinds = if (kind in current.kinds) current.kinds - kind else current.kinds + kind)
    }

    fun setSort(field: SortField, direction: SortDirection = sort.value.direction) = sort.update { FileSort(field, direction) }
    fun toggleSortDirection() = sort.update { it.copy(direction = if (it.direction == SortDirection.ASCENDING) SortDirection.DESCENDING else SortDirection.ASCENDING) }
    fun toggleGridView() = viewModelScope.launch { preferences.setGridView(!_state.value.isGrid) }

    fun setFavorite(file: StoredFile, favorite: Boolean) {
        viewModelScope.launch {
            runCatching { repository.setFavorite(file.stableId, favorite) }
                .onFailure { error -> _state.update { it.copy(errorMessage = error.safeMessage()) } }
        }
    }

    fun download(file: StoredFile) {
        viewModelScope.launch {
            runCatching { downloadCoordinator.download(file) }
                .onFailure { error -> _state.update { it.copy(errorMessage = error.safeMessage()) } }
        }
    }

    fun export(localPath: String, destination: android.net.Uri) {
        viewModelScope.launch {
            runCatching { downloadCoordinator.export(localPath, destination) }
                .onFailure { error -> _state.update { it.copy(errorMessage = error.safeMessage()) } }
        }
    }

    fun deleteLocalCopy(file: StoredFile) {
        viewModelScope.launch {
            runCatching { repository.deleteLocalFile(file.stableId) }
                .onFailure { error -> _state.update { it.copy(errorMessage = error.safeMessage()) } }
        }
    }

    fun deleteRemote(file: StoredFile) {
        viewModelScope.launch {
            runCatching {
                gateway.deleteRemoteMessage(file.remote, revoke = false)
                repository.updateFile(file.copy(remoteAvailability = RemoteAvailability.DELETED, localAvailability = LocalAvailability.NOT_DOWNLOADED, localPath = null))
            }.onFailure { error -> _state.update { it.copy(errorMessage = error.safeMessage()) } }
        }
    }

    fun clearError() = _state.update { it.copy(errorMessage = null) }
}

private fun Throwable.safeMessage(): String = when (this) {
    is StorageGatewayException -> message
    else -> message ?: "The operation could not be completed"
}
