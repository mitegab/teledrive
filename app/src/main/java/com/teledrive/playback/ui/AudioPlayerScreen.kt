package com.teledrive.playback.ui

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import com.teledrive.playback.MediaPlayerViewModel
import com.teledrive.playback.ui.amplify.AmplifyAudioPlayerScreen

/**
 * Audio route entry point.
 *
 * The player itself lives in [AmplifyAudioPlayerScreen]: a sidebar-driven workspace with a
 * playlist, mood/genre filters and a waveform timeline. This wrapper keeps the navigation
 * contract (`onBack`, `stableId`, `allIds`) unchanged for the existing routes.
 */
@Composable
fun AudioPlayerScreen(
    onBack: () -> Unit,
    stableId: String? = null,
    allIds: List<String> = emptyList(),
    viewModel: MediaPlayerViewModel = hiltViewModel(),
) {
    AmplifyAudioPlayerScreen(
        viewModel = viewModel,
        onBack = onBack,
        stableId = stableId,
        allIds = allIds,
    )
}
