package com.teledrive.playback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MediaPlayerUiState(
    val player: PlayerUiState = PlayerUiState(),
    val queue: PlaybackQueueState = PlaybackQueueState(),
    val hasQueue: Boolean = false,
)

@HiltViewModel
class MediaPlayerViewModel @Inject constructor(
    val controller: PlaybackController,
) : ViewModel() {

    val playerState: StateFlow<PlayerUiState> = controller.uiState
    val queueState: StateFlow<PlaybackQueueState> = controller.queueState

    val uiState: StateFlow<MediaPlayerUiState> = combine(controller.uiState, controller.queueState) { player, queue ->
        MediaPlayerUiState(player = player, queue = queue, hasQueue = queue.items.isNotEmpty())
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), MediaPlayerUiState())

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    fun playFile(stableId: String, allIds: List<String> = emptyList()) {
        viewModelScope.launch {
            _loading.value = true
            try {
                controller.playSingle(stableId, allIds)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
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
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                controller.reportFailure(error)
            }
        }
    }

    fun togglePlayPause() {
        val status = controller.uiState.value.status
        if (status == PlaybackStatus.PLAYING) controller.pause() else controller.play()
    }

    fun play() = controller.play()
    fun pause() = controller.pause()
    fun stop() = controller.stop()
    fun seekTo(ms: Long) = controller.seekTo(ms)
    fun next() = controller.next()
    fun previous() = controller.previous()
    fun setSpeed(speed: Float) = controller.setSpeed(speed)
    fun toggleShuffle() = controller.toggleShuffle()
    fun toggleRepeat() = controller.toggleRepeat()
    fun addToQueue(stableId: String, playNext: Boolean = false) = controller.addToQueue(stableId, playNext)
    fun addAllToQueue(ids: List<String>) = controller.addAllToQueue(ids)
    fun removeFromQueue(index: Int) = controller.removeFromQueue(index)
    fun moveInQueue(from: Int, to: Int) = controller.moveInQueue(from, to)
    fun clearQueue() = controller.clearQueue()

    override fun onCleared() {
        // Do not release controller here; it's singleton and may be needed for background playback
        super.onCleared()
    }
}
