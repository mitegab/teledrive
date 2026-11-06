package com.teledrive.playback.ui.video

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.teledrive.playback.ui.amplify.AmplifyColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Frame extraction for the scrubber preview and library cards.
 *
 * A single [MediaMetadataRetriever] is reused behind a mutex (it is not thread safe), results
 * are cached per second so dragging across the timeline does not re-decode the same frame, and
 * frames are downscaled before they reach Compose to keep the bitmap budget small.
 */
object VideoFrameLoader {

    private const val MAX_CACHED_FRAMES = 24
    private const val TARGET_WIDTH_PX = 480

    private val mutex = Mutex()
    private val cache = object : LruCache<String, ImageBitmap>(MAX_CACHED_FRAMES) {}
    private val failures = mutableSetOf<String>()
    private var retriever: MediaMetadataRetriever? = null
    private var retrieverPath: String? = null

    suspend fun frame(path: String?, timeMs: Long): ImageBitmap? {
        if (path.isNullOrBlank()) return null
        val cacheKey = "$path@${(timeMs / 1000L).coerceAtLeast(0L)}"
        cache.get(cacheKey)?.let { return it }
        if (failures.contains(cacheKey)) return null
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                cache.get(cacheKey)?.let { return@withLock it }
                val frames = retrieverFor(path) ?: run {
                    failures.add(cacheKey)
                    return@withLock null
                }
                val bitmap = runCatching {
                    frames.getFrameAtTime(
                        timeMs.coerceAtLeast(0L) * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    )
                }.getOrNull()
                val scaled = bitmap?.let(::downscale)
                if (scaled == null) {
                    failures.add(cacheKey)
                    null
                } else {
                    val image = scaled.asImageBitmap()
                    cache.put(cacheKey, image)
                    image
                }
            }
        }
    }

    fun release() {
        runCatching { retriever?.release() }
        retriever = null
        retrieverPath = null
        cache.evictAll()
        failures.clear()
    }

    private fun retrieverFor(path: String): MediaMetadataRetriever? {
        val existing = retriever
        if (existing != null && retrieverPath == path) return existing
        runCatching { existing?.release() }
        retriever = null
        retrieverPath = null
        val created = runCatching {
            MediaMetadataRetriever().apply { setDataSource(path) }
        }.getOrNull() ?: return null
        retriever = created
        retrieverPath = path
        return created
    }

    private fun downscale(source: Bitmap): Bitmap {
        if (source.width <= TARGET_WIDTH_PX) return source
        val ratio = TARGET_WIDTH_PX.toFloat() / source.width.toFloat()
        val height = (source.height * ratio).toInt().coerceAtLeast(1)
        return runCatching { Bitmap.createScaledBitmap(source, TARGET_WIDTH_PX, height, true) }
            .getOrDefault(source)
    }
}

/** 16:9 frame preview used by the scrubber bubble and the library cards. */
@Composable
fun VideoFramePreview(
    localPath: String?,
    timeMs: Long,
    modifier: Modifier = Modifier,
    settleMs: Long = 90L,
) {
    val key = "${localPath.orEmpty()}@${timeMs / 1000L}"
    var image by remember(key) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(key) {
        // Small debounce: while the thumb is dragged, skip intermediate positions.
        delay(settleMs)
        image = VideoFrameLoader.frame(localPath, timeMs)
    }
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val frame = image
        if (frame != null) {
            Image(
                bitmap = frame,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Rounded.Movie,
                contentDescription = null,
                tint = AmplifyColors.TextMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
