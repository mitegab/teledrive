package com.teledrive.playback.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teledrive.core.domain.FileIndexRepository
import com.teledrive.core.model.FileKind
import com.teledrive.core.model.FileQuery
import com.teledrive.core.model.FileSort
import com.teledrive.core.model.SortDirection
import com.teledrive.core.model.SortField
import com.teledrive.core.model.StoredFile
import com.teledrive.playback.PlaybackController
import com.teledrive.playback.PlaybackProgressStore
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class VideoFilters(
    val query: String = "",
    val sort: VideoSort = VideoSort(),
    val favoritesOnly: Boolean = false,
    val inProgressOnly: Boolean = false,
)

/**
 * Backs the video workspace's library tabs: search, sort, favourites, continue-watching,
 * recently played and named playlists. Playback itself stays in [PlaybackController].
 */
@HiltViewModel
class VideoLibraryViewModel @Inject constructor(
    private val repository: FileIndexRepository,
    private val progressStore: PlaybackProgressStore,
    private val playlistStore: VideoPlaylistStore,
    private val controller: PlaybackController,
) : ViewModel() {

    private val filters = MutableStateFlow(VideoFilters())
    private val positions = MutableStateFlow<Map<String, Long>>(emptyMap())
    private val durations = MutableStateFlow<Map<String, Long>>(emptyMap())

    private val files: StateFlow<List<StoredFile>> = repository.observeFiles(
        query = FileQuery(kinds = setOf(FileKind.VIDEO)),
        sort = FileSort(field = SortField.DATE, direction = SortDirection.DESCENDING),
    ).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val entries: StateFlow<List<VideoLibraryEntry>> =
        combine(files, positions, durations) { fileList, pos, dur ->
            fileList.map { file ->
                VideoLibraryEntry(
                    stableId = file.stableId,
                    filename = file.originalFilename,
                    mimeType = file.mimeType,
                    sizeBytes = file.sizeBytes,
                    savedAtEpochSeconds = file.savedAtEpochSeconds,
                    durationMs = dur[file.stableId],
                    localPath = file.localPath,
                    favorite = file.favorite,
                    downloaded = file.localAvailability == com.teledrive.core.model.LocalAvailability.AVAILABLE,
                    resumeMs = pos[file.stableId] ?: 0L,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val playlistState = playlistStore.playlists.stateIn(
        viewModelScope, SharingStarted.Eagerly, emptyList(),
    )
    private val recentIds = playlistStore.recents.stateIn(
        viewModelScope, SharingStarted.Eagerly, emptyList(),
    )

    val uiState: StateFlow<VideoLibraryUiState> = combine(
        entries, filters, playlistState, recentIds,
    ) { all, filter, playlists, recents ->
        val byId = all.associateBy { it.stableId }
        VideoLibraryUiState(
            all = all,
            visible = filterAndSortVideos(
                entries = all,
                query = filter.query,
                sort = filter.sort,
                favoritesOnly = filter.favoritesOnly,
                inProgressOnly = filter.inProgressOnly,
            ),
            continueWatching = recentlyPlayed(all),
            recentlyPlayed = recents.mapNotNull { byId[it] },
            playlists = playlists,
            query = filter.query,
            sort = filter.sort,
            favoritesOnly = filter.favoritesOnly,
            inProgressOnly = filter.inProgressOnly,
            loading = false,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, VideoLibraryUiState())

    init {
        viewModelScope.launch {
            files.collect { list -> refreshPositions(list.map { it.stableId }) }
        }
        viewModelScope.launch {
            controller.queueState.collect { queue ->
                val learned = queue.items.mapNotNull { item ->
                    item.durationMs?.takeIf { it > 0L }?.let { item.file.stableId to it }
                }.toMap()
                if (learned.isNotEmpty()) durations.update { it + learned }
            }
        }
    }

    private suspend fun refreshPositions(ids: List<String>) {
        if (ids.isEmpty()) return
        val loaded = withContext(Dispatchers.IO) {
            val map = mutableMapOf<String, Long>()
            for (id in ids) {
                val position = runCatching { progressStore.getPosition(id) }.getOrDefault(0L)
                if (position > 0L) map[id] = position
            }
            map
        }
        positions.value = loaded
    }

    /** Re-reads saved positions; call when the workspace becomes visible again. */
    fun refresh() {
        viewModelScope.launch { refreshPositions(files.value.map { it.stableId }) }
    }

    fun setQuery(value: String) = filters.update { it.copy(query = value) }

    fun setSortField(field: VideoSortField) = filters.update { it.copy(sort = it.sort.withField(field)) }

    fun toggleSortDirection() = filters.update { it.copy(sort = it.sort.toggled()) }

    fun toggleFavoritesOnly() = filters.update { it.copy(favoritesOnly = !it.favoritesOnly) }

    fun toggleInProgressOnly() = filters.update { it.copy(inProgressOnly = !it.inProgressOnly) }

    fun clearFilters() = filters.update { VideoFilters(sort = it.sort) }

    fun toggleFavorite(entry: VideoLibraryEntry) {
        viewModelScope.launch {
            runCatching { repository.setFavorite(entry.stableId, !entry.favorite) }
        }
    }

    fun recordRecent(stableId: String) {
        viewModelScope.launch { runCatching { playlistStore.recordRecent(stableId) } }
    }

    fun clearRecents() {
        viewModelScope.launch { runCatching { playlistStore.clearRecents() } }
    }

    fun play(entry: VideoLibraryEntry, allIds: List<String> = emptyList()) {
        viewModelScope.launch { controller.playSingle(entry.stableId, allIds) }
    }

    fun playPlaylist(playlist: VideoPlaylist) {
        val first = playlist.stableIds.firstOrNull() ?: return
        viewModelScope.launch { controller.playSingle(first, playlist.stableIds) }
    }

    fun addToQueue(stableId: String, playNext: Boolean = false) = controller.addToQueue(stableId, playNext)

    // ---- playlists ---------------------------------------------------------------

    fun createPlaylist(name: String? = null) {
        val now = System.currentTimeMillis()
        mutatePlaylists { existing ->
            val id = "pl_${now}_${existing.size}"
            val label = name?.let(::sanitizePlaylistName)?.takeIf { it.isNotBlank() }
                ?: defaultPlaylistName(existing, now / 1000L)
            existing + VideoPlaylist(id = id, name = label, createdAtEpochSeconds = now / 1000L)
        }
    }

    fun renamePlaylist(playlistId: String, name: String) {
        val clean = sanitizePlaylistName(name)
        if (clean.isBlank()) return
        mutatePlaylists { existing ->
            existing.map { if (it.id == playlistId) it.copy(name = clean) else it }
        }
    }

    fun deletePlaylist(playlistId: String) {
        mutatePlaylists { existing -> existing.filterNot { it.id == playlistId } }
    }

    fun addToPlaylist(playlistId: String, stableId: String) {
        mutatePlaylists { existing ->
            existing.map { playlist ->
                if (playlist.id == playlistId) playlist.copy(stableIds = addEntry(playlist.stableIds, stableId)) else playlist
            }
        }
    }

    fun removeFromPlaylist(playlistId: String, stableId: String) {
        mutatePlaylists { existing ->
            existing.map { playlist ->
                if (playlist.id == playlistId) playlist.copy(stableIds = removeEntry(playlist.stableIds, stableId)) else playlist
            }
        }
    }

    fun moveInPlaylist(playlistId: String, from: Int, to: Int) {
        mutatePlaylists { existing ->
            existing.map { playlist ->
                if (playlist.id == playlistId) {
                    playlist.copy(stableIds = moveEntry(playlist.stableIds, from, to))
                } else {
                    playlist
                }
            }
        }
    }

    private fun mutatePlaylists(transform: (List<VideoPlaylist>) -> List<VideoPlaylist>) {
        viewModelScope.launch {
            val current = runCatching { playlistStore.snapshotPlaylists() }.getOrDefault(emptyList())
            runCatching { playlistStore.savePlaylists(transform(current)) }
        }
    }
}
