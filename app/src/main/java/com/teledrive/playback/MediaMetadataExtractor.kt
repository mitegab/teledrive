package com.teledrive.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
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
)

@Singleton
class MediaMetadataExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cache = mutableMapOf<String, ExtractedMetadata>()

    suspend fun extract(localPath: String?, fallbackTitle: String): ExtractedMetadata = withContext(Dispatchers.IO) {
        if (localPath == null) return@withContext ExtractedMetadata(fallbackTitle, null, null, null, null, null)
        cache[localPath]?.let { return@withContext it }
        val retriever = MediaMetadataRetriever()
        val result = try {
            retriever.setDataSource(localPath)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf { it.isNotBlank() } ?: fallbackTitle
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf { it.isNotBlank() }
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.takeIf { it.isNotBlank() }
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val track = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS)
            // Do not keep huge artwork in memory permanently; cap at 2MB
            val rawArt = runCatching { retriever.embeddedPicture }.getOrNull()
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
            ExtractedMetadata(title, artist, album, duration, art, track?.takeIf { it.isNotBlank() })
        } catch (error: Exception) {
            // Metadata is optional; the file name remains a usable title.
            com.teledrive.core.model.TeleDriveLog.d("media.metadata_failed", "error" to error.message)
            ExtractedMetadata(fallbackTitle, null, null, null, null, null)
        } finally {
            runCatching { retriever.release() }
        }
        cache[localPath] = result
        // Bound cache size
        if (cache.size > 80) {
            val toRemove = cache.keys.take(20)
            toRemove.forEach { cache.remove(it) }
        }
        result
    }

    fun clearCache() { cache.clear() }
}
