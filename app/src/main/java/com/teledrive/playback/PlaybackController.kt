package com.teledrive.playback

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Singleton
class PlaybackController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val queue: PlaybackQueue,
    private val playbackRepo: MediaPlaybackRepository,
    private val progressStore: PlaybackProgressStore,
) {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var progressJob: Job? = null
    private var pendingSeek: Long? = null
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    val queueState: StateFlow<PlaybackQueueState> = queue.state

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            val isBuffering = state == Player.STATE_BUFFERING
            val status = when (state) {
                Player.STATE_IDLE -> PlaybackStatus.IDLE
                Player.STATE_BUFFERING -> PlaybackStatus.BUFFERING
                Player.STATE_READY -> if (player?.playWhenReady == true) PlaybackStatus.PLAYING else PlaybackStatus.PAUSED
                Player.STATE_ENDED -> PlaybackStatus.ENDED
                else -> _uiState.value.status
            }
            _uiState.update { it.copy(status = status, isBuffering = isBuffering) }
            if (state == Player.STATE_READY) {
                maybeRestorePosition()
            }
            if (state == Player.STATE_ENDED) {
                handleTrackEnded()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(status = if (isPlaying) PlaybackStatus.PLAYING else if (it.status == PlaybackStatus.BUFFERING) PlaybackStatus.BUFFERING else PlaybackStatus.PAUSED) }
            if (isPlaying) startProgressPolling() else stopProgressPolling()
        }

        override fun onPlayerError(error: PlaybackException) {
            val mapped = MediaErrorMapper.fromPlaybackException(error)
            _uiState.update { it.copy(status = PlaybackStatus.ERROR, error = mapped) }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            // keep progress in sync
        }

        override fun onPlaybackParametersChanged(params: androidx.media3.common.PlaybackParameters) {
            _uiState.update { it.copy(progress = it.progress.copy(speed = params.speed)) }
        }
    }

    private fun ensurePlayer(): ExoPlayer {
        if (player != null) return player!!
        val dataSourceFactory = DefaultDataSource.Factory(context, FileDataSource.Factory())
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val newPlayer = ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        newPlayer.addListener(playerListener)
        newPlayer.repeatMode = when (queue.state.value.repeatMode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        }
        player = newPlayer
        // Create media session for notifications and background controls
        if (mediaSession == null) {
            mediaSession = MediaSession.Builder(context, newPlayer).build()
        }
        return newPlayer
    }

    fun getPlayer(): ExoPlayer = ensurePlayer()
    fun getSession(): MediaSession? = mediaSession

    private fun ensureService() {
        try {
            val intent = android.content.Intent(context, MediaPlaybackService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (_: Exception) { /* service may not be needed if player handles it */ }
    }

    suspend fun playSingle(stableId: String, allIds: List<String> = emptyList()) {
        _uiState.update { it.copy(status = PlaybackStatus.RESOLVING, availability = MediaAvailability.REMOTE_ONLY, error = null, downloadProgress = null) }
        // Build queue if needed
        if (allIds.isNotEmpty() && queue.state.value.items.isEmpty()) {
            val (items, startIdx) = playbackRepo.buildQueueFromIds(allIds, stableId)
            if (items.isNotEmpty()) queue.setQueue(items, startIdx)
        }
        val resolution = playbackRepo.resolveForPlayback(stableId)
        when (resolution) {
            is MediaResolution.Available -> {
                _uiState.update { it.copy(availability = MediaAvailability.AVAILABLE) }
                val item = playbackRepo.toPlaybackItem(resolution.file, resolution.localPath)
                // If queue empty or not containing this id, set single-item queue
                if (queue.state.value.items.none { it.file.stableId == stableId }) {
                    queue.setQueue(listOf(item), 0)
                } else {
                    // select correct index
                    val idx = queue.state.value.items.indexOfFirst { it.file.stableId == stableId }
                    if (idx >= 0) queue.select(idx)
                }
                prepareAndPlay(item)
            }
            is MediaResolution.NeedsDownload -> {
                _uiState.update { it.copy(availability = MediaAvailability.DOWNLOADING, status = PlaybackStatus.WAITING_FOR_DOWNLOAD) }
                try {
                    val ensured = playbackRepo.ensureLocalFile(stableId)
                    if (ensured is MediaResolution.Available) {
                        _uiState.update { it.copy(availability = MediaAvailability.AVAILABLE) }
                        val item = playbackRepo.toPlaybackItem(ensured.file, ensured.localPath)
                        if (queue.state.value.items.none { it.file.stableId == stableId }) {
                            queue.setQueue(listOf(item), 0)
                        } else {
                            val idx = queue.state.value.items.indexOfFirst { it.file.stableId == stableId }
                            if (idx >= 0) queue.select(idx)
                        }
                        prepareAndPlay(item)
                    } else {
                        _uiState.update { it.copy(status = PlaybackStatus.ERROR, error = MediaErrorMapper.fromDownloadError((ensured as? MediaResolution.Unavailable)?.reason)) }
                    }
                } catch (e: Exception) {
                    _uiState.update { it.copy(status = PlaybackStatus.ERROR, error = MediaErrorMapper.fromDownloadError(e.message)) }
                }
            }
            is MediaResolution.Unavailable -> {
                _uiState.update { it.copy(status = PlaybackStatus.ERROR, availability = MediaAvailability.UNAVAILABLE, error = PlaybackError(PlaybackErrorType.MISSING_FILE, resolution.reason, false)) }
            }
        }
    }

    suspend fun playQueueItem(index: Int) {
        val item = queue.select(index) ?: return
        resolveAndPlay(item)
    }

    private suspend fun resolveAndPlay(queueItem: PlaybackItem) {
        val file = queueItem.file
        val resolution = playbackRepo.resolveForPlayback(file.stableId)
        when (resolution) {
            is MediaResolution.Available -> {
                val actualItem = playbackRepo.toPlaybackItem(resolution.file, resolution.localPath)
                // update queue entry with real path
                queue.updateItem(actualItem)
                prepareAndPlay(actualItem)
            }
            is MediaResolution.NeedsDownload -> {
                _uiState.update { it.copy(status = PlaybackStatus.WAITING_FOR_DOWNLOAD, availability = MediaAvailability.DOWNLOADING) }
                val ensured = playbackRepo.ensureLocalFile(file.stableId)
                if (ensured is MediaResolution.Available) {
                    val actual = playbackRepo.toPlaybackItem(ensured.file, ensured.localPath)
                    queue.updateItem(actual)
                    prepareAndPlay(actual)
                } else {
                    _uiState.update { it.copy(status = PlaybackStatus.ERROR, error = MediaErrorMapper.fromDownloadError((ensured as? MediaResolution.Unavailable)?.reason)) }
                }
            }
            is MediaResolution.Unavailable -> {
                _uiState.update { it.copy(status = PlaybackStatus.ERROR, error = PlaybackError(PlaybackErrorType.MISSING_FILE, resolution.reason)) }
                // Auto-skip to next if possible
                handleTrackEnded()
            }
        }
    }

    private suspend fun prepareAndPlay(item: PlaybackItem) {
        withContext(Dispatchers.Main) {
            val exo = ensurePlayer()
            ensureService()
            _uiState.update { it.copy(status = PlaybackStatus.PREPARING, error = null) }
            val file = File(item.localPath)
            if (!file.exists()) {
                _uiState.update { it.copy(status = PlaybackStatus.ERROR, error = MediaErrorMapper.missingFile("Local file not found: ${item.file.originalFilename}")) }
                return@withContext
            }
            val mediaMetadata = MediaMetadata.Builder()
                .setTitle(item.title)
                .setArtist(item.artist)
                .setAlbumTitle(item.album)
                .build()
            val mediaItem = MediaItem.Builder()
                .setUri(Uri.fromFile(file))
                .setMediaMetadata(mediaMetadata)
                .setMediaId(item.file.stableId)
                .build()
            exo.setMediaItem(mediaItem)
            exo.prepare()
            // restore position if we have saved progress
            pendingSeek = progressStore.getPosition(item.file.stableId)
            if (pendingSeek == 0L) pendingSeek = null
            exo.playWhenReady = true
        }
    }

    private fun maybeRestorePosition() {
        pendingSeek?.let { pos ->
            if (pos > 0L) {
                val duration = player?.duration ?: 0L
                // Don't restore if very close to end
                val shouldRestore = when {
                    duration <= 0L -> pos > 1000L
                    pos >= duration - 5000L -> false
                    pos.toDouble() / duration.toDouble() >= 0.95 -> false
                    else -> pos > 2000L
                }
                if (shouldRestore) player?.seekTo(pos)
            }
            pendingSeek = null
        }
        startProgressPolling()
    }

    fun play() {
        player?.play()
        player?.playWhenReady = true
    }

    fun pause() {
        player?.pause()
        persistProgress()
    }

    fun stop() {
        player?.stop()
        stopProgressPolling()
        _uiState.update { it.copy(status = PlaybackStatus.IDLE, progress = PlaybackProgress()) }
    }

    fun seekTo(positionMs: Long) {
        val duration = player?.duration ?: 0L
        val clamped = positionMs.coerceIn(0L, if (duration > 0) duration else positionMs)
        player?.seekTo(clamped)
        _uiState.update { it.copy(progress = it.progress.copy(positionMs = clamped), isSeeking = false) }
    }

    fun setShuffle(enabled: Boolean) {
        queue.setShuffle(enabled)
    }

    fun toggleShuffle() = queue.toggleShuffle()
    fun toggleRepeat() = queue.toggleRepeat().also { mode ->
        player?.repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        }
    }

    fun setSpeed(speed: Float) {
        player?.setPlaybackSpeed(speed)
        _uiState.update { it.copy(progress = it.progress.copy(speed = speed)) }
    }

    fun next() {
        controllerScope.launch {
            val next = queue.next() ?: run {
                // if no next but repeat ALL, queue handling already done
                return@launch
            }
            resolveAndPlay(next)
        }
    }

    fun previous() {
        controllerScope.launch {
            // If position > 3 sec, seek to start instead of previous track (platform convention)
            val pos = player?.currentPosition ?: 0L
            if (pos > 3000L) {
                seekTo(0L)
                return@launch
            }
            val prev = queue.previous() ?: return@launch
            resolveAndPlay(prev)
        }
    }

    fun addToQueue(stableId: String, playNext: Boolean = false) {
        controllerScope.launch {
            val file = playbackRepo.getFile(stableId) ?: return@launch
            val res = playbackRepo.resolveForPlayback(stableId)
            val path = when (res) {
                is MediaResolution.Available -> res.localPath
                else -> file.localPath ?: ""
            }
            val item = playbackRepo.toPlaybackItem(file, path)
            queue.addToQueue(item, playNext)
        }
    }

    fun addAllToQueue(stableIds: List<String>) {
        controllerScope.launch {
            val items = mutableListOf<PlaybackItem>()
            for (id in stableIds) {
                val f = playbackRepo.getFile(id) ?: continue
                val r = playbackRepo.resolveForPlayback(id)
                val p = when (r) {
                    is MediaResolution.Available -> r.localPath
                    else -> f.localPath ?: ""
                }
                items.add(playbackRepo.toPlaybackItem(f, p))
            }
            queue.addAll(items)
        }
    }

    fun removeFromQueue(index: Int) = queue.removeAt(index)
    fun moveInQueue(from: Int, to: Int) = queue.move(from, to)

    fun clearQueue() {
        queue.clear()
        stop()
    }

    private fun handleTrackEnded() {
        controllerScope.launch {
            val repeat = queue.state.value.repeatMode
            if (repeat == RepeatMode.ONE) {
                player?.seekTo(0L)
                player?.play()
                return@launch
            }
            val next = queue.next()
            if (next != null) {
                resolveAndPlay(next)
            } else {
                _uiState.update { it.copy(status = PlaybackStatus.ENDED) }
            }
        }
    }

    private fun startProgressPolling() {
        progressJob?.cancel()
        progressJob = controllerScope.launch {
            while (isActive) {
                val p = player
                if (p != null) {
                    val pos = p.currentPosition.coerceAtLeast(0L)
                    val dur = p.duration.takeIf { it > 0 } ?: queue.current()?.durationMs ?: _uiState.value.progress.durationMs
                    val buf = p.bufferedPosition.coerceAtLeast(0L)
                    _uiState.update { it.copy(progress = PlaybackProgress(positionMs = pos, durationMs = dur.coerceAtLeast(0L), bufferedMs = buf, speed = p.playbackParameters.speed)) }
                    // Throttle persistence: every 5s
                    if (pos % 5000L < 550L) {
                        queue.current()?.let { cur -> progressStore.setPosition(cur.file.stableId, pos, dur) }
                    }
                }
                delay(500L)
            }
        }
    }

    private fun stopProgressPolling() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun persistProgress() {
        val cur = queue.current() ?: return
        val pos = player?.currentPosition ?: _uiState.value.progress.positionMs
        val dur = player?.duration ?: _uiState.value.progress.durationMs
        controllerScope.launch { progressStore.setPosition(cur.file.stableId, pos, dur.takeIf { it > 0 }) }
    }

    fun isActivePlayback(): Boolean = _uiState.value.status in setOf(PlaybackStatus.PLAYING, PlaybackStatus.PAUSED, PlaybackStatus.BUFFERING, PlaybackStatus.PREPARING) && queue.current() != null

    fun release() {
        persistProgress()
        progressJob?.cancel()
        player?.removeListener(playerListener)
        player?.release()
        player = null
        mediaSession?.release()
        mediaSession = null
        _uiState.update { PlayerUiState() }
    }

    // Called when app backgrounded; keep service alive if playing
    fun shouldKeepService(): Boolean = isActivePlayback()
}
