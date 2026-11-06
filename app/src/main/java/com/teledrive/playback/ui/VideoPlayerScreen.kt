package com.teledrive.playback.ui

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import com.teledrive.playback.MediaPlayerViewModel
import com.teledrive.playback.ui.video.AmplifyVideoPlayerScreen

/**
 * Destination entry point for `video/{stableId}`.
 *
 * The rebuilt workspace lives in [AmplifyVideoPlayerScreen]; this wrapper keeps the route and
 * its existing signature so navigation and deep links do not change.
 */
@Composable
fun VideoPlayerScreen(
    stableId: String,
    allIds: List<String> = emptyList(),
    onBack: () -> Unit,
    viewModel: MediaPlayerViewModel = hiltViewModel(),
) {
    AmplifyVideoPlayerScreen(
        stableId = stableId,
        allIds = allIds,
        onBack = onBack,
        playerViewModel = viewModel,
    )
}
