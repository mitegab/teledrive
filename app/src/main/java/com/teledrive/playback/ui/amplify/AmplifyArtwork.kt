package com.teledrive.playback.ui.amplify

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text

private val artworkGradients = listOf(
    listOf(Color(0xFF7C5BEF), Color(0xFF2E2352)),
    listOf(Color(0xFFE96BB0), Color(0xFF4A2145)),
    listOf(Color(0xFF35C2C1), Color(0xFF173A4A)),
    listOf(Color(0xFFF2A65A), Color(0xFF4C2A22)),
    listOf(Color(0xFF9C9BFF), Color(0xFF23234F)),
    listOf(Color(0xFF6BE3A2), Color(0xFF1B3C31)),
    listOf(Color(0xFFFF9ED2), Color(0xFF3B1F35)),
)

/** Stable gradient per track so placeholder covers stay recognisable between sessions. */
fun artworkBrush(seed: String): Brush {
    val index = (seed.hashCode() and Int.MAX_VALUE) % artworkGradients.size
    return Brush.linearGradient(artworkGradients[index])
}

fun artworkInitials(title: String): String {
    val words = title.split(' ', '-', '_', '/', '.')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    return when {
        words.isEmpty() -> "♪"
        words.size == 1 -> words.first().take(2).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}

/**
 * Album art with a graceful fallback. Artwork is decoded from the local file's embedded
 * picture; files that are still remote-only (or carry no cover) get a deterministic gradient
 * tile with the track initials, so the list never shows empty holes.
 */
@Composable
fun AmplifyArtwork(
    title: String,
    seed: String,
    localPath: String?,
    loadArtwork: suspend (String) -> ImageBitmap?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    iconSize: Dp = 18.dp,
    textSize: Int = 12,
) {
    val image by produceState<ImageBitmap?>(initialValue = null, key1 = localPath) {
        value = localPath?.takeIf { it.isNotBlank() }?.let { path ->
            runCatching { loadArtwork(path) }.getOrNull()
        }
    }
    Box(
        modifier
            .clip(shape)
            .background(artworkBrush(seed)),
        contentAlignment = Alignment.Center,
    ) {
        val artwork = image
        if (artwork != null) {
            Image(
                bitmap = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (title.isNotBlank()) {
            if (iconSize == 0.dp) {
                Text(
                    artworkInitials(title),
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = textSize.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            } else {
                Icon(
                    Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }
}
