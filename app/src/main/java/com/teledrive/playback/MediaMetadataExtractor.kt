package com.teledrive.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.teledrive.core.model.TeleDriveLog
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class ExtractedMetadata(
    val title: String?,
    val artist: String?,
    val album: String?,
    val durationMs: Long?,
    val artworkBytes: ByteArray?,
    val trackNumber: String?,
    val genre: String? = null,
)

@Singleton
class MediaMetadataExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Bounded LRU cache. [extract] runs on Dispatchers.IO from several coroutines at once
     * (player artwork, playlist rows, the video library), so an unsynchronised HashMap here
     * loses entries and corrupts its buckets — surfacing as a ConcurrentModificationException
     * or an infinite loop inside the map during eviction.
     */
    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<String, ExtractedMetadata>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ExtractedMetadata>?): Boolean =
            size > MAX_CACHE_ENTRIES
    }

    suspend fun extract(localPath: String?, fallbackTitle: String): ExtractedMetadata = withContext(Dispatchers.IO) {
        if (localPath == null) return@withContext ExtractedMetadata(fallbackTitle, null, null, null, null, null)
        synchronized(cacheLock) { cache[localPath] }?.let { return@withContext it }
        val retriever = MediaMetadataRetriever()
        val result = try {
            retriever.setDataSource(localPath)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: fallbackTitle
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val track = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS)
            // Genre tags drive the playlist mood/genre filters; absence is fine.
            val genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            // Do not keep huge artwork in memory permanently; cap at 2MB
            val rawArt = runCatching { retriever.embeddedPicture }
                .onFailure { error -> TeleDriveLog.d("media.artwork_missing", "error" to error.message) }
                .getOrNull()
            val art = rawArt?.let { bytes ->
                if (bytes.size > 4 * 1024 * 1024) {
                    // Downsample large artwork
                    runCatching {
                        val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                        val bmp: Bitmap? = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                        bmp?.let {
                            val out = File(context.cacheDir, "art_tmp_${localPath.hashCode()}.jpg")
                            FileOutputStream(out).use { fos -> it.compress(Bitmap.CompressFormat.JPEG, 80, fos) }
                            it.recycle()
                            out.readBytes()
                        } ?: bytes
                    }.getOrDefault(bytes)
                } else bytes
            }
            ExtractedMetadata(title, artist, album, duration, art, track?.takeIf { it.isNotBlank() }, genre)
        } catch (error: Exception) {
            // Metadata is optional; the file name remains a usable title.
            TeleDriveLog.d("media.metadata_failed", "error" to error.message)
            ExtractedMetadata(fallbackTitle, null, null, null, null, null)
        } finally {
            runCatching { retriever.release() }
        }
        synchronized(cacheLock) { cache[localPath] = result }
        result
    }

    fun clearCache() {
        synchronized(cacheLock) { cache.clear() }
    }

    private companion object {
        const val MAX_CACHE_ENTRIES = 80
    }
}
