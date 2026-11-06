package com.teledrive.playback.ui.video

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.util.Rational
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teledrive.playback.MediaPlayerViewModel
import com.teledrive.playback.PlaybackStatus
import com.teledrive.playback.RepeatMode
import com.teledrive.playback.ShuffleMode
import com.teledrive.playback.ui.amplify.AmplifyColors
import com.teledrive.playback.ui.amplify.AmplifyPill
import com.teledrive.playback.ui.amplify.AmplifySpeedSheet
import com.teledrive.playback.ui.amplify.AmplifyType
import com.teledrive.playback.ui.amplify.amplifyClickable
import com.teledrive.playback.video.CaptionPalette
import com.teledrive.playback.video.CaptionSize
import com.teledrive.playback.video.MediaTrackOption
import com.teledrive.playback.video.ScreenOrientation
import com.teledrive.playback.video.VideoAspect
import com.teledrive.playback.video.VideoPlaybackOptions
import com.teledrive.playback.video.VideoPlaylist
import com.teledrive.playback.video.VideoLibraryEntry
import com.teledrive.playback.video.VideoLibraryViewModel
import com.teledrive.playback.video.VideoSortField
import com.teledrive.playback.video.codecLabel
import com.teledrive.playback.video.formatClock
import com.teledrive.playback.video.playbackRateLabel
import com.teledrive.playback.video.resumePosition
import com.teledrive.playback.video.resolutionTier
import com.teledrive.playback.video.normalizeRotation
import kotlin.math.abs
import kotlinx.coroutines.delay

private val videoOptionsSaver = listSaver<VideoPlaybackOptions, Any>(
    save = { options ->
        listOf(
            options.aspect.name,
            options.rotationDegrees,
            options.orientation.name,
            options.skipIntervalMs,
            options.captionsEnabled,
            options.captionSize.name,
            options.captionPalette.name,
            options.showRemainingTime,
        )
    },
    restore = { values ->
        VideoPlaybackOptions(
            aspect = VideoAspect.entries.firstOrNull { it.name == values[0] } ?: VideoAspect.FIT,
            rotationDegrees = (values[1] as? Int) ?: 0,
            orientation = ScreenOrientation.entries.firstOrNull { it.name == values[2] } ?: ScreenOrientation.AUTO,
            skipIntervalMs = (values[3] as? Long) ?: 10_000L,
            captionsEnabled = (values[4] as? Boolean) ?: true,
            captionSize = CaptionSize.entries.firstOrNull { it.name == values[5] } ?: CaptionSize.MEDIUM,
            captionPalette = CaptionPalette.entries.firstOrNull { it.name == values[6] } ?: CaptionPalette.WHITE_SHADOW,
            showRemainingTime = (values[7] as? Boolean) ?: false,
        )
    },
)

/**
 * The video workspace: a full-window Media3 surface with an Amplify-styled transport,
 * gesture layer, settings sheet and a library/playlist browser.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun AmplifyVideoPlayerScreen(
    stableId: String,
    allIds: List<String> = emptyList(),
    onBack: () -> Unit,
    playerViewModel: MediaPlayerViewModel = hiltViewModel(),
    libraryViewModel: VideoLibraryViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val window = activity?.window
    val view = LocalView.current

    val uiState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val tracks by playerViewModel.trackSnapshot.collectAsStateWithLifecycle()
    val videoSize by playerViewModel.videoSize.collectAsStateWithLifecycle()
    val audioSessionId by playerViewModel.audioSessionId.collectAsStateWithLifecycle()
    val effects by playerViewModel.audioEffectsState.collectAsStateWithLifecycle()
    val library by libraryViewModel.uiState.collectAsStateWithLifecycle()

    var options by rememberSaveable(stateSaver = videoOptionsSaver) { mutableStateOf(VideoPlaybackOptions()) }
    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showLibrary by remember { mutableStateOf(false) }
    var showSpeed by remember { mutableStateOf(false) }
    var brightness by remember { mutableFloatStateOf(-1f) }
    var resumeChip by remember { mutableStateOf<Long?>(null) }
    var savedPositionMs by remember { mutableStateOf(0L) }
    var resumeDismissed by remember { mutableStateOf(false) }

    val player = remember { playerViewModel.controller.getPlayer() }
    val currentItem = uiState.queue.currentItem
    val currentStableId = currentItem?.file?.stableId
    val durationMs = uiState.player.progress.durationMs.takeIf { it > 0L }
        ?: currentItem?.durationMs ?: 0L
    val positionMs = uiState.player.progress.positionMs
    val playing = uiState.player.status == PlaybackStatus.PLAYING

    // Start (or keep) playback for this file.
    LaunchedEffect(stableId) {
        val queued = playerViewModel.controller.queueState.value.currentItem?.file?.stableId
        if (queued != stableId) playerViewModel.playFile(stableId, allIds)
        libraryViewModel.recordRecent(stableId)
    }

    // Frame-accurate seeks while the workspace is open; release decoder helpers on exit.
    DisposableEffect(Unit) {
        playerViewModel.setPreciseSeeking(true)
        onDispose {
            playerViewModel.setPreciseSeeking(false)
            VideoFrameLoader.release()
        }
    }

    // Keep the panel awake for as long as the video surface is mounted.
    DisposableEffect(Unit) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Caption visibility follows the user's switch; the player keeps the track selection.
    LaunchedEffect(options.captionsEnabled) {
        playerViewModel.setCaptionsEnabled(options.captionsEnabled)
    }

    // Equalizer / loudness effects need the player's audio session.
    LaunchedEffect(audioSessionId) {
        if (audioSessionId > 0) playerViewModel.attachAudioEffects(audioSessionId)
    }

    // Forced orientation (Auto restores the system default).
    LaunchedEffect(options.orientation) {
        activity ?: return@LaunchedEffect
        activity.requestedOrientation = when (options.orientation) {
            ScreenOrientation.AUTO -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            ScreenOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            ScreenOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            window?.let { win ->
                win.attributes = win.attributes.apply { screenBrightness = -1f }
            }
        }
    }

    // Immersive bars only while nothing is on screen.
    DisposableEffect(controlsVisible, locked, uiState.player.status) {
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        val hideBars = !controlsVisible && !locked && uiState.player.status == PlaybackStatus.PLAYING
        if (hideBars) {
            controller?.hide(WindowInsetsCompat.Type.systemBars())
            controller?.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // Auto-hide the chrome while playing.
    LaunchedEffect(controlsVisible, locked, uiState.player.status) {
        if (controlsVisible && !locked && uiState.player.status == PlaybackStatus.PLAYING) {
            delay(4000)
            controlsVisible = false
        }
    }

    // Resume hint: the controller restores the saved position itself, so this only offers a restart.
    LaunchedEffect(currentStableId) {
        val id = currentStableId ?: return@LaunchedEffect
        resumeDismissed = false
        val saved = playerViewModel.savedPosition(id)
        savedPositionMs = saved
        resumeChip = resumePosition(saved, durationMs, minMs = 30_000L)
    }
    LaunchedEffect(resumeChip) {
        if (resumeChip != null) {
            delay(7000)
            resumeChip = null
        }
    }

    BackHandler(enabled = true) {
        when {
            locked -> locked = false
            showSettings -> showSettings = false
            showLibrary -> showLibrary = false
            showSpeed -> showSpeed = false
            else -> onBack()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VideoSurface(
            player = player,
            aspect = options.aspect,
            rotationDegrees = options.rotationDegrees,
            captionSize = options.captionSize,
            captionPalette = options.captionPalette,
            captionsVisible = options.captionsEnabled,
            modifier = Modifier.fillMaxSize(),
        )

        VideoGestureLayer(
            enabled = !locked && !showSettings && !showLibrary && !showSpeed,
            durationMs = durationMs,
            positionMs = positionMs,
            skipByMs = options.skipIntervalMs,
            localPath = currentItem?.localPath,
            volume = uiState.player.volume,
            brightness = brightness,
            onToggleControls = { controlsVisible = !controlsVisible },
            onSeekBy = { delta -> playerViewModel.seekRelative(delta) },
            onSeekTo = { target -> playerViewModel.seekTo(target) },
            onVolume = { value -> playerViewModel.setVolume(value) },
            onBrightness = { value ->
                brightness = value
                window?.let { win ->
                    win.attributes = win.attributes.apply { screenBrightness = value.coerceIn(0.02f, 1f) }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Buffering / loading indicator
        if (uiState.player.status in setOf(PlaybackStatus.BUFFERING, PlaybackStatus.PREPARING, PlaybackStatus.RESOLVING)) {
            Box(Modifier.align(Alignment.Center).size(46.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    color = AmplifyColors.Accent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(32.dp),
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible && !locked,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            VideoTopBar(
                title = currentItem?.title ?: currentItem?.file?.originalFilename ?: "Video",
                subtitle = librarySubtitle(currentItem?.file?.mimeType, durationMs),
                badge = tracks.videoStream.takeIf { it.known }?.let { stream ->
                    "${resolutionTier(stream.width, stream.height)} · ${codecLabel(stream.sampleMimeType, stream.codecs)}"
                },
                queueLabel = if (uiState.queue.items.size > 1) {
                    "${uiState.queue.currentIndex + 1} / ${uiState.queue.items.size}"
                } else {
                    null
                },
                captionsOn = tracks.captionsEnabled || !tracks.hasText,
                onBack = onBack,
                onLibrary = { showLibrary = true },
                onLock = {
                    locked = true
                    controlsVisible = false
                },
                onPip = { activity?.enterVideoPip(videoSize.width to videoSize.height) },
                onSettings = { showSettings = true },
            )
        }

        AnimatedVisibility(
            visible = controlsVisible && !locked,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center),
        ) {
            VideoCenterControls(
                playing = playing,
                hasPrevious = uiState.queue.hasPrevious,
                hasNext = uiState.queue.hasNext,
                skipSeconds = (options.skipIntervalMs / 1000L).toInt(),
                onPrevious = { playerViewModel.previous() },
                onSkipBack = { playerViewModel.seekRelative(-options.skipIntervalMs) },
                onPlayPause = { playerViewModel.togglePlayPause() },
                onSkipForward = { playerViewModel.seekRelative(options.skipIntervalMs) },
                onNext = { playerViewModel.next() },
                onFrameBack = { playerViewModel.frameStep(false) },
                onFrameForward = { playerViewModel.frameStep(true) },
            )
        }

        AnimatedVisibility(
            visible = controlsVisible && !locked,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            VideoBottomBar(
                positionMs = positionMs,
                bufferedMs = uiState.player.progress.bufferedMs,
                durationMs = durationMs,
                localPath = currentItem?.localPath,
                playing = playing,
                speed = uiState.player.progress.speed,
                volume = uiState.player.volume,
                captionsOn = options.captionsEnabled && tracks.hasText,
                captionsAvailable = tracks.hasText,
                showRemaining = options.showRemainingTime,
                orientationLabel = options.orientation.label,
                onSeek = { target -> playerViewModel.seekTo(target) },
                onScrubStart = { controlsVisible = true },
                onScrubEnd = {},
                onToggleTimeMode = { options = options.copy(showRemainingTime = !options.showRemainingTime) },
                onPlayPause = { playerViewModel.togglePlayPause() },
                onVolumeChange = { value -> playerViewModel.setVolume(value) },
                onToggleMute = {
                    playerViewModel.setVolume(if (uiState.player.volume > 0.01f) 0f else 1f)
                },
                onSpeedClick = { showSpeed = true },
                onCaptionsClick = {
                    options = options.copy(captionsEnabled = !options.captionsEnabled)
                },
                onOrientationClick = {
                    options = options.copy(
                        orientation = when (options.orientation) {
                            ScreenOrientation.AUTO -> ScreenOrientation.LANDSCAPE
                            ScreenOrientation.LANDSCAPE -> ScreenOrientation.PORTRAIT
                            ScreenOrientation.PORTRAIT -> ScreenOrientation.AUTO
                        }
                    )
                },
                onPip = { activity?.enterVideoPip(videoSize.width to videoSize.height) },
                onLibrary = { showLibrary = true },
                onSettings = { showSettings = true },
            )
        }

        if (locked) {
            LockedChrome(modifier = Modifier.align(Alignment.BottomCenter), onUnlock = { locked = false })
        }

        // Resume / restart chip
        val chip = resumeChip
        if (chip != null && !resumeDismissed && !locked) {
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 64.dp, end = 14.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.68f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Resumed at ${formatClock(chip)}",
                    style = AmplifyType.meta,
                    color = AmplifyColors.TextSecondary,
                )
                AmplifyPill(
                    text = "Start over",
                    icon = Icons.Rounded.Replay,
                    compact = true,
                    onClick = {
                        playerViewModel.seekTo(0L)
                        resumeChip = null
                        resumeDismissed = true
                    },
                )
            }
        }

        // Errors
        uiState.player.error?.let { error ->
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.82f))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    error.message,
                    style = AmplifyType.label,
                    color = AmplifyColors.Danger,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AmplifyPill(
                        text = "Retry",
                        icon = Icons.Rounded.Replay,
                        compact = true,
                        onClick = { playerViewModel.playFile(stableId, allIds) },
                    )
                    AmplifyPill(text = "Back", compact = true, onClick = onBack)
                }
            }
        }

        // Ended state hint
        if (uiState.player.status == PlaybackStatus.ENDED && !controlsVisible) {
            Box(Modifier.align(Alignment.Center), contentAlignment = Alignment.Center) {
                AmplifyPill(
                    text = "Replay",
                    icon = Icons.Rounded.Replay,
                    onClick = { playerViewModel.seekTo(0L); playerViewModel.play() },
                )
            }
        }
    }

    if (showSettings) {
        VideoSettingsSheet(
            state = VideoSettingsState(
                options = options,
                speed = uiState.player.progress.speed,
                volume = uiState.player.volume,
                tracks = tracks,
                dimensions = videoSize,
                decoder = playerViewModel.decoderStats(),
                durationMs = durationMs,
                fileSizeBytes = currentItem?.file?.sizeBytes,
                filename = currentItem?.file?.originalFilename.orEmpty(),
                mimeType = currentItem?.file?.mimeType,
                repeatMode = uiState.queue.repeatMode,
                shuffleEnabled = uiState.queue.shuffleMode == ShuffleMode.ON,
                audioEffects = effects,
                resumeMs = resumeChip ?: savedPositionMs,
            ),
            onDismiss = { showSettings = false },
            onSpeed = { playerViewModel.setSpeed(it) },
            onVolume = { playerViewModel.setVolume(it) },
            onToggleMute = { playerViewModel.setVolume(if (uiState.player.volume > 0.01f) 0f else 1f) },
            onSkipInterval = { options = options.copy(skipIntervalMs = it) },
            onRepeatMode = { mode: RepeatMode -> playerViewModel.setRepeatMode(mode) },
            onToggleShuffle = { playerViewModel.toggleShuffle() },
            onRestart = { playerViewModel.seekTo(0L) },
            onResume = {
                // Re-resolve this item so the controller restores the saved position.
                playerViewModel.playFile(stableId, allIds)
            },
            onCaptionsEnabled = { options = options.copy(captionsEnabled = it) },
            onCaptionSize = { options = options.copy(captionSize = it) },
            onCaptionPalette = { options = options.copy(captionPalette = it) },
            onSelectTextTrack = { option: MediaTrackOption ->
                playerViewModel.selectTrack(option.kind, option.groupIndex, option.trackIndex)
                playerViewModel.setCaptionsEnabled(true)
                options = options.copy(captionsEnabled = true)
            },
            onAutomaticText = { playerViewModel.selectTrackAutomatic(com.teledrive.playback.video.MediaTrackKind.TEXT) },
            onSelectAudioTrack = { option: MediaTrackOption ->
                playerViewModel.selectTrack(option.kind, option.groupIndex, option.trackIndex)
            },
            onAutomaticAudio = { playerViewModel.selectTrackAutomatic(com.teledrive.playback.video.MediaTrackKind.AUDIO) },
            onSelectVideoTrack = { option: MediaTrackOption ->
                playerViewModel.selectTrack(option.kind, option.groupIndex, option.trackIndex)
            },
            onAspect = { options = options.copy(aspect = it) },
            onRotate = { options = options.copy(rotationDegrees = normalizeRotation(options.rotationDegrees + 90)) },
            onResetRotation = { options = options.copy(rotationDegrees = 0) },
            onOrientation = { options = options.copy(orientation = it) },
            onEqualizerEnabled = { playerViewModel.setEqualizerEnabled(it) },
            onEqualizerPreset = { playerViewModel.selectEqualizerPreset(it) },
            onEqualizerBand = { index, level -> playerViewModel.setEqualizerBand(index, level) },
            onLoudnessGain = { playerViewModel.setLoudnessGain(it) },
            onResetAudioEffects = { playerViewModel.resetAudioEffects() },
        )
    }

    if (showSpeed) {
        AmplifySpeedSheet(
            current = uiState.player.progress.speed,
            onSelect = { playerViewModel.setSpeed(it) },
            onDismiss = { showSpeed = false },
        )
    }

    if (showLibrary) {
        VideoLibrarySheet(
            library = library,
            queue = uiState.queue,
            currentStableId = currentStableId,
            onDismiss = { showLibrary = false },
            onQuery = libraryViewModel::setQuery,
            onSortField = { field: VideoSortField -> libraryViewModel.setSortField(field) },
            onToggleSortDirection = { libraryViewModel.toggleSortDirection() },
            onToggleFavorites = { libraryViewModel.toggleFavoritesOnly() },
            onToggleInProgress = { libraryViewModel.toggleInProgressOnly() },
            onPlay = { entry: VideoLibraryEntry ->
                libraryViewModel.play(entry, library.visible.map { it.stableId })
                showLibrary = false
            },
            onAddToQueue = { entry: VideoLibraryEntry, playNext: Boolean ->
                libraryViewModel.addToQueue(entry.stableId, playNext)
            },
            onToggleFavorite = { entry -> libraryViewModel.toggleFavorite(entry) },
            onCreatePlaylist = { name -> libraryViewModel.createPlaylist(name) },
            onRenamePlaylist = { playlist: VideoPlaylist, name: String ->
                libraryViewModel.renamePlaylist(playlist.id, name)
            },
            onDeletePlaylist = { playlist -> libraryViewModel.deletePlaylist(playlist.id) },
            onPlayPlaylist = { playlist: VideoPlaylist ->
                libraryViewModel.playPlaylist(playlist)
                showLibrary = false
            },
            onMoveInPlaylist = { playlist: VideoPlaylist, from: Int, to: Int ->
                libraryViewModel.moveInPlaylist(playlist.id, from, to)
            },
            onToggleInPlaylist = { playlist: VideoPlaylist, entry: VideoLibraryEntry ->
                if (playlist.stableIds.contains(entry.stableId)) {
                    libraryViewModel.removeFromPlaylist(playlist.id, entry.stableId)
                } else {
                    libraryViewModel.addToPlaylist(playlist.id, entry.stableId)
                }
            },
            onClearRecents = { libraryViewModel.clearRecents() },
            onPlayQueueIndex = { index -> playerViewModel.playAt(index) },
            onRemoveQueueIndex = { index -> playerViewModel.removeFromQueue(index) },
            onMoveQueue = { from, to -> playerViewModel.moveInQueue(from, to) },
            onClearQueue = { playerViewModel.clearQueue() },
            onToggleShuffle = { playerViewModel.toggleShuffle() },
            onRepeatMode = { mode: RepeatMode -> playerViewModel.setRepeatMode(mode) },
        )
    }
}

/**
 * Full-surface gesture layer:
 * tap toggles chrome, double-tap seeks, horizontal drag scrubs (with a live preview),
 * vertical drag on the left changes brightness and on the right the player volume.
 */
@Composable
private fun VideoGestureLayer(
    enabled: Boolean,
    durationMs: Long,
    positionMs: Long,
    skipByMs: Long,
    localPath: String?,
    volume: Float,
    brightness: Float,
    onToggleControls: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onVolume: (Float) -> Unit,
    onBrightness: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val liveDuration by rememberUpdatedState(durationMs)
    val livePosition by rememberUpdatedState(positionMs)
    val liveVolume by rememberUpdatedState(volume)
    val liveBrightness by rememberUpdatedState(brightness)

    var hudText by remember { mutableStateOf<String?>(null) }
    var hudIcon by remember { mutableStateOf<androidx.compose.ui.graphics.vector.ImageVector?>(null) }
    var seekPreview by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(hudText) {
        if (hudText != null) {
            delay(900)
            hudText = null
            hudIcon = null
        }
    }

    Box(
        modifier
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onTap = { onToggleControls() },
                    onDoubleTap = { offset ->
                        val forward = offset.x > size.width / 2f
                        onSeekBy(if (forward) skipByMs else -skipByMs)
                        hudIcon = null
                        hudText = (if (forward) "+" else "−") + "${skipByMs / 1000}s"
                    },
                )
            }
            .pointerInput(enabled, liveDuration) {
                if (!enabled) return@pointerInput
                var mode = 0 // 0 none, 1 seek, 2 brightness, 3 volume
                var accumulated = Offset.Zero
                var startPosition = 0L
                var startVolume = 1f
                var startBrightness = 0.5f
                var startX = 0f
                detectDragGestures(
                    onDragStart = { offset ->
                        mode = 0
                        accumulated = Offset.Zero
                        startPosition = livePosition
                        startVolume = liveVolume
                        startBrightness = if (liveBrightness < 0f) 0.5f else liveBrightness
                        startX = offset.x
                    },
                    onDragEnd = {
                        if (mode == 1) {
                            seekPreview?.let { onSeekTo(it) }
                        }
                        mode = 0
                        seekPreview = null
                        hudText = null
                        hudIcon = null
                    },
                    onDragCancel = {
                        mode = 0
                        seekPreview = null
                        hudText = null
                        hudIcon = null
                    },
                    onDrag = { change, dragAmount ->
                        accumulated += dragAmount
                        if (mode == 0) {
                            val slop = viewConfiguration.touchSlop
                            mode = when {
                                abs(accumulated.x) > slop && abs(accumulated.x) > abs(accumulated.y) -> 1
                                abs(accumulated.y) > slop -> if (startX < size.width / 2f) 2 else 3
                                else -> 0
                            }
                        }
                        when (mode) {
                            1 -> {
                                if (liveDuration > 0L) {
                                    val delta = (accumulated.x / size.width) * liveDuration
                                    val target = (startPosition + delta).toLong().coerceIn(0L, liveDuration)
                                    seekPreview = target
                                    hudText = "${formatClock(target)} / ${formatClock(liveDuration)}"
                                    hudIcon = null
                                }
                            }
                            2 -> {
                                val next = (startBrightness - accumulated.y / size.height).coerceIn(0.02f, 1f)
                                onBrightness(next)
                                hudIcon = Icons.Rounded.WbSunny
                                hudText = "Brightness ${(next * 100).toInt()}%"
                            }
                            3 -> {
                                val next = (startVolume - accumulated.y / size.height).coerceIn(0f, 1f)
                                onVolume(next)
                                hudIcon = Icons.Rounded.VolumeUp
                                hudText = "Volume ${(next * 100).toInt()}%"
                            }
                        }
                        change.consume()
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        seekPreview?.let { preview ->
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                VideoFramePreview(
                    localPath = localPath,
                    timeMs = preview,
                    modifier = Modifier.width(132.dp).height(74.dp),
                )
                Text(formatClock(preview), style = AmplifyType.numeric, color = AmplifyColors.TextPrimary)
            }
        }
        hudText?.let { text ->
            GestureHud(icon = hudIcon, text = text, modifier = Modifier.align(Alignment.Center))
        }
    }
}

private fun librarySubtitle(mimeType: String?, durationMs: Long): String? {
    val clock = if (durationMs > 0L) formatClock(durationMs) else null
    val kind = mimeType?.substringAfter('/')?.uppercase()
    return listOfNotNull(kind, clock).joinToString(" · ").ifBlank { null }
}

private fun Activity.enterVideoPip(size: Pair<Int, Int>?) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val (width, height) = size ?: (16 to 9)
    val (w, h) = if (width > 0 && height > 0) width to height else 16 to 9
    val ratio = (w.toFloat() / h.toFloat()).coerceIn(0.45f, 2.35f)
    val params = PictureInPictureParams.Builder()
        .setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
        .build()
    runCatching { enterPictureInPictureMode(params) }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return current as? Activity
}
