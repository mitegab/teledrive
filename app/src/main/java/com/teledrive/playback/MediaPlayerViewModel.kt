package com.teledrive.playback

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teledrive.core.domain.FileIndexRepository
import com.teledrive.core.model.FileKind
import com.teledrive.core.model.FileQuery
import com.teledrive.core.model.FileSort
import com.teledrive.core.model.SortDirection
import com.teledrive.core.model.SortField
import com.teledrive.core.model.StoredFile
import com.teledrive.core.model.TeleDriveLog
import com.teledrive.playback.video.MediaTrackKind
import com.teledrive.playback.video.MediaTrackSnapshot
import com.teledrive.playback.video.VideoDecoderStats
import com.teledrive.playback.video.VideoDimensions
import com.teledrive.support.guarded
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Collections
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MediaPlayerUiState(
    val player: PlayerUiState = PlayerUiState(),
    val queue: PlaybackQueueState = PlaybackQueueState(),
    val hasQueue: Boolean = false,
    /** Every audio file in the index, used by the player's "Add Tracks" browser. */
    val audioLibrary: List<StoredFile> = emptyList(),
)

@HiltViewModel
class MediaPlayerViewModel @Inject constructor(
    val controller: PlaybackController,
    private val repository: FileIndexRepository,
    private val metadataExtractor: MediaMetadataExtractor,
    private val audioEffects: PlaybackAudioEffects,
) : ViewModel() {

    val playerState: StateFlow<PlayerUiState> = controller.uiState
    val queueState: StateFlow<PlaybackQueueState> = controller.queueState

    /**
     * Artwork is decoded from the local file's embedded picture. Decoded bitmaps are cached
     * so scrolling the playlist does not re-run MediaMetadataRetriever for every row.
     */
    private val artworkCache = object : LruCache<String, ImageBitmap>(48) {}
    private val artworkMisses: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    val audioLibrary: StateFlow<List<StoredFile>> = repository.observeFiles(
        query = FileQuery(kinds = setOf(FileKind.AUDIO)),
        sort = FileSort(field = SortField.NAME, direction = SortDirection.ASCENDING),
        // A failing index query must empty the browser, not take the process down with it.
    ).guarded("audio_library").stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val uiState: StateFlow<MediaPlayerUiState> = combine(
        controller.uiState,
        controller.queueState,
        audioLibrary,
    ) { player, queue, library ->
        MediaPlayerUiState(
            player = player,
            queue = queue,
            hasQueue = queue.items.isNotEmpty(),
            audioLibrary = library,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MediaPlayerUiState())

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    fun playFile(stableId: String, allIds: List<String> = emptyList()) {
        viewModelScope.launch {
            _loading.value = true
            try {
                controller.playSingle(stableId, allIds)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                controller.reportFailure(error)
            } finally {
                _loading.value = false
            }
        }
    }

    fun playAt(index: Int) {
        viewModelScope.launch {
            try {
                controller.playQueueItem(index)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                controller.reportFailure(error)
            }
        }
    }

    fun togglePlayPause() {
        val queue = controller.queueState.value
        when {
            controller.uiState.value.status == PlaybackStatus.PLAYING -> controller.pause()
            // Ended: restart the track the user is looking at instead of a dead play() call.
            controller.uiState.value.status == PlaybackStatus.ENDED -> {
                controller.seekTo(0L)
                controller.play()
            }
            // Idle with a selected item means nothing is prepared yet; resolve it again.
            controller.uiState.value.status == PlaybackStatus.IDLE && queue.currentItem != null -> {
                if (queue.currentIndex >= 0) playAt(queue.currentIndex) else controller.play()
            }
            else -> controller.play()
        }
    }

    fun play() = controller.play()
    fun pause() = controller.pause()
    fun stop() = controller.stop()
    fun seekTo(ms: Long) = controller.seekTo(ms)
    fun seekRelative(deltaMs: Long) = controller.seekRelative(deltaMs)
    fun next() = controller.next()
    fun previous() = controller.previous()
    fun previousTrack() = controller.previousTrack()
    fun setSpeed(speed: Float) = controller.setSpeed(speed)
    fun setVolume(volume: Float) = controller.setVolume(volume)
    fun toggleShuffle() = controller.toggleShuffle()
    fun toggleRepeat() = controller.toggleRepeat()
    fun setRepeatMode(mode: RepeatMode) = controller.setRepeat(mode)
    fun addToQueue(stableId: String, playNext: Boolean = false) = controller.addToQueue(stableId, playNext)
    fun addAllToQueue(ids: List<String>) = controller.addAllToQueue(ids)
    fun removeFromQueue(index: Int) = controller.removeFromQueue(index)
    fun moveInQueue(from: Int, to: Int) = controller.moveInQueue(from, to)
    fun clearQueue() = controller.clearQueue()

    // ---- video workspace ----------------------------------------------------------

    val trackSnapshot: StateFlow<MediaTrackSnapshot> = controller.trackSnapshot
    val videoSize: StateFlow<VideoDimensions> = controller.videoSize
    val audioSessionId: StateFlow<Int> = controller.audioSessionId

    fun selectTrack(kind: MediaTrackKind, groupIndex: Int, trackIndex: Int) =
        controller.selectTrack(kind, groupIndex, trackIndex)

    fun selectTrackAutomatic(kind: MediaTrackKind) = controller.selectTrackAutomatic(kind)

    fun setTrackTypeEnabled(kind: MediaTrackKind, enabled: Boolean) =
        controller.setTrackTypeEnabled(kind, enabled)

    fun setCaptionsEnabled(enabled: Boolean) =
        controller.setTrackTypeEnabled(MediaTrackKind.TEXT, enabled)

    /** Pauses and moves one frame; [forward] steps toward the end. */
    fun frameStep(forward: Boolean) = controller.frameStep(forward)

    fun setPreciseSeeking(enabled: Boolean) = controller.setPreciseSeeking(enabled)

    suspend fun savedPosition(stableId: String): Long = controller.savedPositionMs(stableId)

    fun decoderStats(): VideoDecoderStats = controller.decoderStats()

    // ---- audio effects -------------------------------------------------------------

    val audioEffectsState: StateFlow<AudioEffectsState> = audioEffects.state

    /** Binds the equalizer/loudness effects to the player's current audio session. */
    fun attachAudioEffects(audioSessionId: Int) = audioEffects.attach(audioSessionId)

    fun setEqualizerEnabled(enabled: Boolean) = audioEffects.setEnabled(enabled)

    fun selectEqualizerPreset(index: Int) = audioEffects.usePreset(index)

    fun setEqualizerBand(index: Int, levelMb: Int) = audioEffects.setBandLevel(index, levelMb)

    fun setLoudnessGain(gainMb: Int) = audioEffects.setGainMb(gainMb)

    fun resetAudioEffects() = audioEffects.reset()

    /** Embedded cover for a local audio file, cached per path; null when there is none. */
    suspend fun artwork(localPath: String): ImageBitmap? {
        if (localPath.isBlank()) return null
        artworkCache.get(localPath)?.let { return it }
        if (localPath in artworkMisses) return null
        return withContext(Dispatchers.IO) {
            try {
                val metadata = metadataExtractor.extract(localPath, localPath.substringAfterLast('/'))
                val bytes = metadata.artworkBytes
                val bitmap = bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
                if (bitmap == null) {
                    artworkMisses.add(localPath)
                    null
                } else {
                    bitmap.asImageBitmap().also { artworkCache.put(localPath, it) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                TeleDriveLog.d("playback.artwork_failed", "error" to error.message)
                artworkMisses.add(localPath)
                null
            }
        }
    }

    /** True when the queue already holds a file, used by the Add Tracks browser. */
    fun isQueued(stableId: String, queue: PlaybackQueueState = controller.queueState.value): Boolean =
        queue.items.any { it.file.stableId == stableId }

    override fun onCleared() {
        // Do not release controller here; it's singleton and may be needed for background playback
        super.onCleared()
    }
}
