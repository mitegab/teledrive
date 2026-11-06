package com.teledrive.playback.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teledrive.playback.MediaPlayerViewModel
import com.teledrive.playback.PlaybackStatus
import com.teledrive.playback.ui.amplify.AmplifyArtwork
import com.teledrive.playback.ui.amplify.AmplifyColors
import com.teledrive.playback.ui.amplify.AmplifyEqualizer
import com.teledrive.playback.ui.amplify.AmplifyIconButton
import com.teledrive.playback.ui.amplify.AmplifyType

/**
 * Compact playback bar shown above the app navigation while something is loaded.
 *
 * It mirrors the player's timeline language (accent progress line, cover tile, accent
 * transport button) so the transition into the full player feels continuous.
 */
@Composable
fun MiniPlayer(
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MediaPlayerViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val queue = uiState.queue
    val player = uiState.player
    val current = queue.currentItem

    val visible = current != null && player.status in setOf(
        PlaybackStatus.PLAYING,
        PlaybackStatus.PAUSED,
        PlaybackStatus.BUFFERING,
        PlaybackStatus.PREPARING,
        PlaybackStatus.WAITING_FOR_DOWNLOAD,
        PlaybackStatus.IDLE,
    ) && queue.items.isNotEmpty()

    val artworkFor: suspend (String) -> ImageBitmap? = remember(viewModel) { viewModel::artwork }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = modifier,
    ) {
        val progress = player.progress
        val fraction = if (progress.durationMs > 0L) {
            (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f)
        } else {
            0f
        }
        val isPlaying = player.status == PlaybackStatus.PLAYING
        val preparing = player.status in setOf(
            PlaybackStatus.BUFFERING,
            PlaybackStatus.PREPARING,
            PlaybackStatus.WAITING_FOR_DOWNLOAD,
        )

        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0xFF231830), Color(0xFF130D1C)),
                    )
                )
                .clickable(onClick = onExpand),
        ) {
            // Progress line
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Color.White.copy(alpha = 0.08f))
            ) {
                Spacer(
                    Modifier
                        .fillMaxWidth(fraction)
                        .fillMaxHeight()
                        .background(Brush.horizontalGradient(listOf(AmplifyColors.Accent, AmplifyColors.Blush)))
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AmplifyArtwork(
                    title = current?.title ?: "Audio",
                    seed = current?.file?.stableId ?: "mini",
                    localPath = current?.localPath,
                    loadArtwork = artworkFor,
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(9.dp),
                    iconSize = 16.dp,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        current?.title ?: "Unknown track",
                        style = AmplifyType.trackTitle.copy(color = AmplifyColors.TextPrimary),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AmplifyEqualizer(
                            playing = isPlaying,
                            modifier = Modifier.size(11.dp),
                            color = AmplifyColors.Accent,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            current?.artist
                                ?: "${queue.currentIndex + 1} of ${queue.items.size} · ${player.status.name.lowercase().replaceFirstChar { it.uppercase() }}",
                            style = AmplifyType.meta.copy(color = AmplifyColors.TextMuted),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                AmplifyIconButton(
                    icon = Icons.Rounded.SkipPrevious,
                    contentDescription = "Previous",
                    onClick = { viewModel.previous() },
                    size = 32.dp,
                    iconSize = 17.dp,
                )
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(AmplifyColors.Accent, AmplifyColors.AccentDeep)))
                        .clickable { viewModel.togglePlayPause() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (preparing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = AmplifyColors.BackgroundBottom,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = AmplifyColors.BackgroundBottom,
                            modifier = Modifier.size(19.dp),
                        )
                    }
                }
                AmplifyIconButton(
                    icon = Icons.Rounded.SkipNext,
                    contentDescription = "Next",
                    onClick = { viewModel.next() },
                    size = 32.dp,
                    iconSize = 17.dp,
                )
                AmplifyIconButton(
                    icon = Icons.Rounded.Close,
                    contentDescription = "Close player",
                    onClick = {
                        viewModel.stop()
                        viewModel.clearQueue()
                    },
                    size = 28.dp,
                    iconSize = 15.dp,
                )
            }
        }
    }
}
