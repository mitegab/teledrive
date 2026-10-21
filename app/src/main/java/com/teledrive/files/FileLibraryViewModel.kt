package com.teledrive.files

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teledrive.core.domain.FileIndexRepository
import com.teledrive.core.domain.ObserveFilesUseCase
import com.teledrive.core.domain.SynchronizeFilesUseCase
import com.teledrive.core.domain.TelegramStorageGateway
import com.teledrive.core.domain.toAppError
import com.teledrive.core.model.DownloadSnapshot
import com.teledrive.core.model.ErrorOperation
import com.teledrive.core.model.FileKind
import com.teledrive.core.model.FileQuery
import com.teledrive.core.model.FileSort
import com.teledrive.core.model.Folder
import com.teledrive.core.model.LocalAvailability
import com.teledrive.core.model.MetadataOperation
import com.teledrive.core.model.RemoteAvailability
import com.teledrive.core.model.SortDirection
import com.teledrive.core.model.SortField
import com.teledrive.core.model.StoredFile
import com.teledrive.core.model.TransferState
import com.teledrive.metadata.MetadataSyncWriter
import com.teledrive.preferences.PreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One user-facing message. Local operations stay inline, short outcomes use a snackbar. */
data class UiNotice(val message: String, val isError: Boolean = false, val id: Long = System.nanoTime())

data class FileLibraryUiState(
    val files: List<StoredFile> = emptyList(),
    val folders: List<Folder> = emptyList(),
    /** Live download state, keyed by stable id; never fabricated for untouched files. */
    val downloads: Map<String, DownloadSnapshot> = emptyMap(),
    val query: FileQuery = FileQuery(),
    val sort: FileSort = FileSort(),
    val isGrid: Boolean = false,
    val isSyncing: Boolean = false,
    val hasSynced: Boolean = false,
    val syncIncomplete: Boolean = false,
    /** Inline problem in the list itself (sync/search/index). */
    val errorMessage: String? = null,
    /** Transient outcome shown in a snackbar. */
    val notice: UiNotice? = null,
    val offlineUsageBytes: Long = 0L,
    val selectionMode: Boolean = false,
    val selectedIds: Set<String> = emptySet(),
    val batchRunning: Boolean = false,
) {
    val selectedFiles: List<StoredFile> get() = files.filter { it.stableId in selectedIds }
    val activeDownloads: Int get() = downloads.values.count { it.isActive }
    val queuedDownloads: Int get() = downloads.values.count { it.state == TransferState.QUEUED }
    val offlineOnly: Boolean get() = query.localOnly
    val selectedCompleted: Int
        get() = selectedIds.count { id -> downloads[id]?.state == TransferState.COMPLETED }
    val batchFraction: Float?
        get() = selectedIds.size.takeIf { it > 0 }?.let { total ->
            (selectedCompleted.toFloat() / total).coerceIn(0f, 1f)
        }
}

@HiltViewModel
class FileLibraryViewModel @Inject constructor(
    private val observeFiles: ObserveFilesUseCase,
    private val synchronizeFiles: SynchronizeFilesUseCase,
    private val repository: FileIndexRepository,
    private val gateway: TelegramStorageGateway,
    private val uploadCoordinator: UploadCoordinator,
    private val downloadCoordinator: DownloadCoordinator,
    private val localStorage: LocalFileStorage,
    private val preferences: PreferencesRepository,
    private val metadataWriter: MetadataSyncWriter,
) : ViewModel() {
    private val query = MutableStateFlow(FileQuery())
    private val sort = MutableStateFlow(FileSort())
    private val _state = MutableStateFlow(FileLibraryUiState())
    val state: StateFlow<FileLibraryUiState> = _state.asStateFlow()
    private var batchJob: Job? = null

    init {
        viewModelScope.launch {
            combine(query, sort, preferences.gridView) { q, s, grid -> Triple(q, s, grid) }
                .collectLatest { (q, s, grid) ->
                    observeFiles(q, s).collect { files ->
                        _state.update { current ->
                            current.copy(
                                files = files,
                                query = q,
                                sort = s,
                                isGrid = grid,
                                selectedIds = current.selectedIds.intersect(files.map { it.stableId }.toSet()),
                            )
                        }
                    }
                }
        }
        viewModelScope.launch {
            repository.observeFolders().collect { folders -> _state.update { it.copy(folders = folders) } }
        }
        viewModelScope.launch {
            downloadCoordinator.statuses.collect { downloads -> _state.update { it.copy(downloads = downloads) } }
        }
        viewModelScope.launch {
            gateway.remoteMessageDeletions.collect { remote -> repository.markRemoteDeleted(remote) }
        }
        viewModelScope.launch {
            repository.observeFiles().collect { files ->
                val usage = files.filter { it.localAvailability == LocalAvailability.AVAILABLE }
                    .sumOf { it.sizeBytes ?: 0L }
                _state.update { it.copy(offlineUsageBytes = usage) }
            }
        }
    }

    fun synchronize() {
        if (_state.value.isSyncing) return
        viewModelScope.launch {
            _state.update { it.copy(isSyncing = true, errorMessage = null) }
            try {
                val summary = synchronizeFiles()
                if (preferences.syncMetadataEnabled.first()) {
                    applyMetadataSnapshot(metadataWriter.synchronize().snapshot)
                }
                _state.update { it.copy(isSyncing = false, hasSynced = true, syncIncomplete = !summary.complete) }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(isSyncing = false) }
                throw cancelled
            } catch (error: Exception) {
                val appError = error.toAppError(ErrorOperation.SYNC)
                _state.update { it.copy(isSyncing = false, errorMessage = appError.userMessage) }
            }
        }
    }

    fun upload(uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                uploadCoordinator.upload(uri)
                _state.update { it.copy(notice = UiNotice("Upload queued")) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(notice = UiNotice(error.toAppError(ErrorOperation.UPLOAD).userMessage, isError = true)) }
            }
        }
    }

    fun setSearchText(value: String) = query.update { it.copy(text = value) }
    fun clearKinds() = query.update { it.copy(kinds = emptySet()) }
    fun setFolder(folderId: String?) = query.update { it.copy(folderId = folderId) }
    fun setFavoritesOnly(enabled: Boolean) = query.update { it.copy(favoritesOnly = enabled) }

    /** Switches between the full index and the offline library, reusing the same query pipeline. */
    fun setOfflineOnly(enabled: Boolean) = query.update { it.copy(localOnly = enabled) }

    fun toggleKind(kind: FileKind) = query.update { current ->
        current.copy(kinds = if (kind in current.kinds) current.kinds - kind else current.kinds + kind)
    }

    fun setSort(field: SortField, direction: SortDirection = sort.value.direction) = sort.update { FileSort(field, direction) }
    fun toggleSortDirection() = sort.update { it.copy(direction = if (it.direction == SortDirection.ASCENDING) SortDirection.DESCENDING else SortDirection.ASCENDING) }
    fun toggleGridView() = viewModelScope.launch { preferences.setGridView(!_state.value.isGrid) }

    fun setFavorite(file: StoredFile, favorite: Boolean) {
        viewModelScope.launch {
            try {
                repository.setFavorite(file.stableId, favorite)
                appendMetadata(MetadataOperation.FavoriteFile(file.stableId, favorite))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(errorMessage = error.toAppError(ErrorOperation.LISTING).userMessage) }
            }
        }
    }

    fun createFolder(name: String, parentId: String? = null) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis() / 1000
                val folder = Folder(UUID.randomUUID().toString(), cleanName, parentId, now, now)
                repository.createFolder(folder)
                appendMetadata(MetadataOperation.CreateFolder(folder.id, folder.name, folder.parentId))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(errorMessage = error.toAppError(ErrorOperation.LISTING).userMessage) }
            }
        }
    }

    fun renameFolder(folder: Folder, name: String) {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return
        viewModelScope.launch {
            try {
                repository.renameFolder(folder.id, cleanName, System.currentTimeMillis() / 1000)
                appendMetadata(MetadataOperation.RenameFolder(folder.id, cleanName))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(errorMessage = error.toAppError(ErrorOperation.LISTING).userMessage) }
            }
        }
    }

    fun deleteFolder(folder: Folder) {
        viewModelScope.launch {
            try {
                repository.deleteFolder(folder.id)
                appendMetadata(MetadataOperation.DeleteFolder(folder.id))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(errorMessage = error.toAppError(ErrorOperation.LISTING).userMessage) }
            }
        }
    }

    fun moveFile(file: StoredFile, folderId: String?) {
        viewModelScope.launch {
            try {
                repository.setFolder(file.stableId, folderId)
                appendMetadata(MetadataOperation.MoveFile(file.stableId, folderId))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(errorMessage = error.toAppError(ErrorOperation.LISTING).userMessage) }
            }
        }
    }

    // ---- offline downloads -----------------------------------------------------

    /** Starts or reuses the one transfer for this file. Returns immediately. */
    fun download(file: StoredFile) {
        val request = downloadCoordinator.beginDownload(file)
        request.onCompletion(file)
    }

    fun cancelDownload(stableId: String) {
        downloadCoordinator.cancel(stableId)
        _state.update { it.copy(notice = UiNotice("Download canceled")) }
    }

    fun retryDownload(file: StoredFile) {
        // Reuses the persisted FAILED row so history and attempt count stay truthful.
        viewModelScope.launch {
            try {
                downloadCoordinator.retryDownload(file.stableId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(notice = UiNotice(error.toAppError(ErrorOperation.DOWNLOAD).userMessage, isError = true)) }
            }
        }
    }

    fun removeOffline(file: StoredFile) {
        viewModelScope.launch {
            try {
                downloadCoordinator.removeLocalCopy(file)
                _state.update { it.copy(notice = UiNotice("Removed the offline copy of ${file.originalFilename}")) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(notice = UiNotice(error.toAppError(ErrorOperation.STORAGE).userMessage, isError = true)) }
            }
        }
    }

    fun downloadSelected() = startBatch(_state.value.selectedFiles)

    private fun startBatch(files: List<StoredFile>) {
        if (files.isEmpty()) {
            _state.update { it.copy(notice = UiNotice("Select files first")) }
            return
        }
        if (batchJob?.isActive == true) {
            _state.update { it.copy(notice = UiNotice("A download batch is already running")) }
            return
        }
        batchJob = viewModelScope.launch {
            _state.update { it.copy(batchRunning = true) }
            try {
                val report = downloadCoordinator.downloadAll(files)
                _state.update {
                    it.copy(
                        batchRunning = false,
                        selectionMode = false,
                        selectedIds = emptySet(),
                        notice = UiNotice(
                            report.message,
                            isError = report.downloaded == 0 && (report.failed > 0 || report.skipped > 0),
                        ),
                    )
                }
            } catch (cancelled: CancellationException) {
                _state.update { it.copy(batchRunning = false) }
                throw cancelled
            } finally {
                _state.update { it.copy(batchRunning = false) }
            }
        }
    }

    fun cancelAllDownloads() {
        downloadCoordinator.cancelAll()
        batchJob?.cancel()
        _state.update { it.copy(notice = UiNotice("Download queue canceled")) }
    }

    fun toggleSelection(file: StoredFile) {
        _state.update { current ->
            val selected = if (file.stableId in current.selectedIds) {
                current.selectedIds - file.stableId
            } else {
                current.selectedIds + file.stableId
            }
            current.copy(selectionMode = true, selectedIds = selected)
        }
    }

    fun selectAllVisible() = _state.update { it.copy(selectionMode = true, selectedIds = it.files.map { file -> file.stableId }.toSet()) }

    fun clearSelection() = _state.update { it.copy(selectionMode = false, selectedIds = emptySet()) }

    // ---- open / export ---------------------------------------------------------

    fun export(localPath: String, destination: android.net.Uri) {
        viewModelScope.launch {
            try {
                downloadCoordinator.export(localPath, destination)
                _state.update { it.copy(notice = UiNotice("Copy saved")) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(notice = UiNotice(error.toAppError(ErrorOperation.STORAGE).userMessage, isError = true)) }
            }
        }
    }

    fun open(file: StoredFile, share: Boolean = false) {
        viewModelScope.launch {
            try {
                val available = downloadCoordinator.download(file)
                downloadCoordinator.launchLocalFile(available, share)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(notice = UiNotice(error.toAppError(ErrorOperation.DOWNLOAD).userMessage, isError = true)) }
            }
        }
    }

    /** Kept for callers that still use the old name; removal never deletes remote data. */
    fun deleteLocalCopy(file: StoredFile) = removeOffline(file)

    fun deleteRemote(file: StoredFile) {
        viewModelScope.launch {
            try {
                gateway.deleteRemoteMessage(file.remote, revoke = false)
                // Remote deletion is independent from the app-managed offline copy. Keep local bytes
                // usable until the user explicitly removes them.
                repository.updateFile(file.copy(remoteAvailability = RemoteAvailability.DELETED))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update { it.copy(errorMessage = error.toAppError(ErrorOperation.LISTING).userMessage) }
            }
        }
    }

    private fun kotlinx.coroutines.Deferred<StoredFile>.onCompletion(file: StoredFile) {
        // Never await inside viewModelScope: closing the screen must not cancel the transfer.
        invokeOnCompletion { cause ->
            when {
                cause == null -> _state.update {
                    it.copy(notice = UiNotice("Saved ${file.originalFilename} for offline use"))
                }
                cause is CancellationException -> Unit
                else -> {
                    val message = (cause as? DownloadException)?.error?.userMessage
                        ?: cause.toAppError(ErrorOperation.DOWNLOAD).userMessage
                    _state.update { it.copy(notice = UiNotice(message, isError = true)) }
                }
            }
        }
    }

    /** Called by the details dialog after a fresh status is needed. */
    fun refreshStatus(file: StoredFile) = downloadCoordinator.refreshStatus(file)

    fun dismissNotice(id: Long) = _state.update { current ->
        if (current.notice?.id == id) current.copy(notice = null) else current
    }

    fun clearError() = _state.update { it.copy(errorMessage = null) }

    private suspend fun appendMetadata(operation: MetadataOperation) {
        if (preferences.syncMetadataEnabled.first()) metadataWriter.append(operation)
    }

    private suspend fun applyMetadataSnapshot(snapshot: com.teledrive.core.model.MetadataSnapshot) {
        snapshot.deletedFolderIds.forEach { repository.deleteFolder(it) }
        snapshot.folders.values.forEach { repository.upsertFolder(it) }
        snapshot.fileFolderIds.forEach { (fileId, folderId) -> repository.setFolder(fileId, folderId) }
        snapshot.favoriteFileStates.forEach { (fileId, favorite) -> repository.setFavorite(fileId, favorite) }
    }
}
