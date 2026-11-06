package com.teledrive.playback

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import com.teledrive.playback.video.MediaTrackKind
import com.teledrive.playback.video.MediaTrackSnapshot
import com.teledrive.playback.video.VideoDecoderStats
import com.teledrive.playback.video.VideoDimensions
import com.teledrive.playback.video.stepPosition
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

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
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
    private var volumeJob: Job? = null
    private var pendingSeek: Long? = null
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    val queueState: StateFlow<PlaybackQueueState> = queue.state

    private val _trackSnapshot = MutableStateFlow(MediaTrackSnapshot())

    /** Audio/subtitle/video tracks and stream details of the current item, for the video workspace. */
    val trackSnapshot: StateFlow<MediaTrackSnapshot> = _trackSnapshot.asStateFlow()

    private val _audioSessionId = MutableStateFlow(0)

    /** Audio session the equalizer/amplifier attach to; 0 until the first audio track is created. */
    val audioSessionId: StateFlow<Int> = _audioSessionId.asStateFlow()

    private val _videoSize = MutableStateFlow(VideoDimensions())

    /** Decoded picture size of the current video, before any display rotation. */
    val videoSize: StateFlow<VideoDimensions> = _videoSize.asStateFlow()

    init {
        // Restore the last chosen output volume before any track is prepared.
        safeLaunch("volume_restore") {
            val saved = runCatching { progressStore.getVolume() }.getOrDefault(1f)
            _uiState.update { it.copy(volume = saved) }
            player?.volume = saved
        }
    }

    /**
     * Every fire-and-forget task started from this controller goes through here.
     *
     * These coroutines run on the main dispatcher with nobody to join them, so an
     * escaping exception (Room, DataStore, the file system, a decoder init) reaches the
     * thread's default handler and kills the process. Failures are logged and surfaced
     * through [reportFailure] instead, which is what the player UI renders.
     */
    private fun safeLaunch(tag: String, block: suspend () -> Unit): Job =
        controllerScope.launch {
            try {
                block()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                com.teledrive.core.model.TeleDriveLog.d(
                    "playback.$tag",
                    "error" to error.message,
                )
                reportFailure(error)
            }
        }

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

        override fun onTracksChanged(tracks: Tracks) {
            refreshTracks(tracks)
        }

        override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
            refreshTracks(player?.currentTracks)
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            _videoSize.value = VideoDimensions(videoSize.width, videoSize.height)
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) {
            _audioSessionId.value = audioSessionId
        }
    }

    private fun refreshTracks(tracks: Tracks?) {
        val exo = player
        _trackSnapshot.value = VideoTrackAdapter.snapshot(
            tracks = tracks,
            videoFormat = runCatching { exo?.videoFormat }.getOrNull(),
            audioFormat = runCatching { exo?.audioFormat }.getOrNull(),
        )
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
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
        newPlayer.volume = _uiState.value.volume
        // Video scrubbing and frame stepping need frame-accurate seeks, not keyframe snaps.
        newPlayer.setSeekParameters(SeekParameters.EXACT)
        newPlayer.repeatMode = when (queue.state.value.repeatMode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
        }
        player = newPlayer
        _videoSize.value = VideoDimensions()
        refreshTracks(newPlayer.currentTracks)
        ensureSession(newPlayer)
        return newPlayer
    }

    /**
     * The controller is the single owner of the [MediaSession]. Creating a second session for
     * the same player (the service used to build a fallback one) produces two notification
     * providers and duplicate controller registrations, which Media3 rejects at runtime.
     */
    private fun ensureSession(exo: ExoPlayer): MediaSession {
        mediaSession?.let { return it }
        val session = MediaSession.Builder(context, exo).build()
        mediaSession = session
        return session
    }

    /**
     * Session for [MediaPlaybackService.onGetSession]; builds the player lazily so the
     * service never constructs a player (and therefore a competing session) on its own.
     */
    fun getOrCreateSession(): MediaSession = ensureSession(ensurePlayer())

    fun getPlayer(): ExoPlayer = ensurePlayer()
    fun getSession(): MediaSession? = mediaSession

    /**
     * Surfaces a failure raised outside the ExoPlayer listener chain (queue building,
     * resolution, storage) so the player screen shows an actionable error instead of
     * crashing the UI coroutine.
     */
    fun reportFailure(error: Throwable) {
        runCatching {
            val mapped = if (error is PlaybackException) {
                MediaErrorMapper.fromPlaybackException(error)
            } else {
                MediaErrorMapper.fromDownloadError(error.message)
            }
            com.teledrive.core.model.TeleDriveLog.d("playback.failed", "error" to error.message)
            _uiState.update { it.copy(status = PlaybackStatus.ERROR, error = mapped) }
        }
    }

    private fun ensureService() {
        try {
            val intent = android.content.Intent(context, MediaPlaybackService::class.java)
            // startService rather than startForegroundService: the platform kills the process
            // when a service started with startForegroundService has not posted a foreground
            // notification within ~5s, which happens whenever playback never actually starts
            // (missing local file, unsupported codec, instant error). Media3 promotes the
            // running service to foreground itself as soon as a session is playing.
            context.startService(intent)
        } catch (error: Exception) {
            // The service is optional for in-process playback; backgrounded starts are
            // rejected by the platform on API 26+ and only cost the notification.
            com.teledrive.core.model.TeleDriveLog.d("playback.service_start_failed", "error" to error.message)
        }
    }

    /** Releases the notification/foreground state once nothing is playing. */
    private fun stopService() {
        runCatching { context.stopService(android.content.Intent(context, MediaPlaybackService::class.java)) }
            .onFailure { error ->
                com.teledrive.core.model.TeleDriveLog.d("playback.service_stop_failed", "error" to error.message)
            }
    }

    suspend fun playSingle(stableId: String, allIds: List<String> = emptyList()) {
        _uiState.update { it.copy(status = PlaybackStatus.RESOLVING, availability = MediaAvailability.REMOTE_ONLY, error = null, downloadProgress = null) }
        // Build the queue from the caller's id list when it is empty, or when the requested
        // track is not part of the current queue (e.g. "play this file" from another section).
        val queuedIds = queue.state.value.items.map { it.file.stableId }
        if (allIds.isNotEmpty() && (queuedIds.isEmpty() || stableId !in queuedIds)) {
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
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    // Leaving the player is not a playback failure.
                    _uiState.update { it.copy(status = PlaybackStatus.IDLE, error = null) }
                    throw cancelled
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
            _uiState.update { it.copy(status = PlaybackStatus.PREPARING, error = null) }
            if (item.localPath.isBlank()) {
                _uiState.update { it.copy(status = PlaybackStatus.ERROR, error = MediaErrorMapper.missingFile("The file has not been downloaded yet.")) }
                stopProgressPolling()
                return@withContext
            }
            val file = File(item.localPath)
            if (!file.exists()) {
                _uiState.update { it.copy(status = PlaybackStatus.ERROR, error = MediaErrorMapper.missingFile("Local file not found: ${item.file.originalFilename}")) }
                stopProgressPolling()
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
            try {
                exo.setMediaItem(mediaItem)
                exo.prepare()
                // restore position if we have saved progress
                pendingSeek = runCatching { progressStore.getPosition(item.file.stableId) }.getOrDefault(0L)
                if (pendingSeek == 0L) pendingSeek = null
                // Only now is there something the foreground service can advertise.
                ensureService()
                exo.playWhenReady = true
            } catch (error: Exception) {
                com.teledrive.core.model.TeleDriveLog.d("playback.prepare_failed", "error" to error.message)
                reportFailure(error)
            }
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

    /** Sets an explicit repeat mode (Off / One / All) instead of cycling. */
    fun setRepeat(mode: RepeatMode) {
        queue.setRepeatMode(mode)
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

    /** Applies output volume immediately and persists it shortly after the user stops dragging. */
    fun setVolume(volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        player?.volume = clamped
        _uiState.update { it.copy(volume = clamped) }
        volumeJob?.cancel()
        volumeJob = controllerScope.launch {
            delay(500)
            runCatching { progressStore.setVolume(clamped) }
        }
    }

    /** Timeline skip buttons: ±10 seconds without touching the queue order. */
    fun seekRelative(deltaMs: Long) {
        val current = _uiState.value.progress.positionMs.takeIf { it > 0L } ?: player?.currentPosition ?: 0L
        seekTo(current + deltaMs)
    }

    /**
     * Transport "previous" that always moves in the queue. The mini-player keeps the
     * platform convention of restarting the current track after a few seconds.
     */
    fun previousTrack() {
        safeLaunch("previous_track") {
            val prev = queue.previous()
            if (prev == null) {
                seekTo(0L)
            } else {
                resolveAndPlay(prev)
            }
        }
    }

    fun next() {
        safeLaunch("next") {
            val next = queue.next() ?: run {
                // if no next but repeat ALL, queue handling already done
                return@safeLaunch
            }
            resolveAndPlay(next)
        }
    }

    fun previous() {
        safeLaunch("previous") {
            // If position > 3 sec, seek to start instead of previous track (platform convention)
            val pos = player?.currentPosition ?: 0L
            if (pos > 3000L) {
                seekTo(0L)
                return@safeLaunch
            }
            val prev = queue.previous() ?: return@safeLaunch
            resolveAndPlay(prev)
        }
    }

    fun addToQueue(stableId: String, playNext: Boolean = false) {
        safeLaunch("add_to_queue") {
            val file = playbackRepo.getFile(stableId) ?: return@safeLaunch
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
        safeLaunch("add_all_to_queue") {
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
        safeLaunch("track_ended") {
            val repeat = queue.state.value.repeatMode
            if (repeat == RepeatMode.ONE) {
                player?.seekTo(0L)
                player?.play()
                return@safeLaunch
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
                    // Reading a released/stale player must end the polling loop, not the app.
                    val snapshot = runCatching {
                        Triple(p.currentPosition.coerceAtLeast(0L), p.duration, p.bufferedPosition.coerceAtLeast(0L))
                    }.getOrElse { error ->
                        com.teledrive.core.model.TeleDriveLog.d("playback.progress_poll_failed", "error" to error.message)
                        stopProgressPolling()
                        return@launch
                    }
                    val (pos, rawDuration, buf) = snapshot
                    val dur = rawDuration.takeIf { it > 0 } ?: queue.current()?.durationMs ?: _uiState.value.progress.durationMs
                    _uiState.update { it.copy(progress = PlaybackProgress(positionMs = pos, durationMs = dur.coerceAtLeast(0L), bufferedMs = buf, speed = runCatching { p.playbackParameters.speed }.getOrDefault(it.progress.speed))) }
                    // Throttle persistence: every 5s. A DataStore write failure (full disk,
                    // revoked storage) must only cost the resume position, never the process.
                    if (pos % 5000L < 550L) {
                        queue.current()?.let { cur ->
                            runCatching { progressStore.setPosition(cur.file.stableId, pos, dur) }
                                .onFailure { error ->
                                    com.teledrive.core.model.TeleDriveLog.d("playback.progress_save_failed", "error" to error.message)
                                }
                        }
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
        safeLaunch("progress_persist") {
            runCatching { progressStore.setPosition(cur.file.stableId, pos, dur.takeIf { it > 0 }) }
                .onFailure { error ->
                    com.teledrive.core.model.TeleDriveLog.d("playback.progress_save_failed", "error" to error.message)
                }
        }
    }

    // ---- video workspace ----------------------------------------------------------

    /** Selects one specific audio/subtitle/video track. */
    fun selectTrack(kind: MediaTrackKind, groupIndex: Int, trackIndex: Int) {
        val exo = player ?: return
        val parameters = VideoTrackAdapter.parametersForTrack(
            base = exo.trackSelectionParameters,
            tracks = exo.currentTracks,
            kind = kind,
            groupIndex = groupIndex,
            trackIndex = trackIndex,
        ) ?: return
        exo.trackSelectionParameters = parameters
        refreshTracks(exo.currentTracks)
    }

    /** Hands control of a track type back to DefaultTrackSelector. */
    fun selectTrackAutomatic(kind: MediaTrackKind) {
        val exo = player ?: return
        val parameters = VideoTrackAdapter.parametersAutomatic(exo.trackSelectionParameters, kind) ?: return
        exo.trackSelectionParameters = parameters
        refreshTracks(exo.currentTracks)
    }

    /** Turns a track type on or off, e.g. subtitles with the caption toggle. */
    fun setTrackTypeEnabled(kind: MediaTrackKind, enabled: Boolean) {
        val exo = player ?: return
        val parameters = VideoTrackAdapter.parametersWithTypeEnabled(exo.trackSelectionParameters, kind, enabled) ?: return
        exo.trackSelectionParameters = parameters
        refreshTracks(exo.currentTracks)
    }

    /**
     * Steps a single frame. ExoPlayer has no frame-step API, so this pauses, then seeks by one
     * frame duration with exact seek parameters — the standard way to reach the adjacent frame.
     */
    fun frameStep(forward: Boolean) {
        val exo = player ?: return
        exo.pause()
        val frameRate = runCatching { exo.videoFormat?.frameRate }.getOrNull()
        val duration = exo.duration.takeIf { it > 0L } ?: 0L
        val target = stepPosition(exo.currentPosition, duration, frameRate, frames = 1, forward = forward)
        seekTo(target)
    }

    /** Tightens seeks to the exact frame while a video surface is on screen. */
    fun setPreciseSeeking(enabled: Boolean) {
        player?.setSeekParameters(if (enabled) SeekParameters.EXACT else SeekParameters.DEFAULT)
    }

    /** Last stored position for the resume prompt; never throws. */
    suspend fun savedPositionMs(stableId: String): Long =
        runCatching { progressStore.getPosition(stableId) }.getOrDefault(0L)

    /** Reads ExoPlayer's video decoder counters (rendered/dropped frames). */
    fun decoderStats(): VideoDecoderStats {
        val exo = player ?: return VideoDecoderStats()
        val counters = runCatching { exo.videoDecoderCounters }.getOrNull()
        return VideoDecoderStats(
            renderedFrames = counters?.renderedOutputBufferCount?.toLong() ?: 0L,
            droppedFrames = counters?.droppedBufferCount?.toLong() ?: 0L,
            activeCodecs = runCatching { exo.videoFormat?.codecs }.getOrNull(),
        )
    }

    fun isActivePlayback(): Boolean = _uiState.value.status in setOf(PlaybackStatus.PLAYING, PlaybackStatus.PAUSED, PlaybackStatus.BUFFERING, PlaybackStatus.PREPARING) && queue.current() != null

    fun release() {
        persistProgress()
        stopProgressPolling()
        // The session references the player, so it must be released first; releasing the
        // player underneath a live session throws inside Media3's internal callbacks.
        runCatching { mediaSession?.release() }
            .onFailure { error -> com.teledrive.core.model.TeleDriveLog.d("playback.session_release_failed", "error" to error.message) }
        mediaSession = null
        runCatching { player?.removeListener(playerListener) }
        runCatching { player?.release() }
            .onFailure { error -> com.teledrive.core.model.TeleDriveLog.d("playback.player_release_failed", "error" to error.message) }
        player = null
        stopService()
        _uiState.update { PlayerUiState(volume = it.volume) }
    }

    // Called when app backgrounded; keep service alive if playing
    fun shouldKeepService(): Boolean = isActivePlayback()
}
