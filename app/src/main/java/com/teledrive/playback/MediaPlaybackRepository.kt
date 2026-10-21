package com.teledrive.playback

import com.teledrive.core.domain.FileIndexRepository
import com.teledrive.core.model.FileKindDetector
import com.teledrive.core.model.LocalAvailability
import com.teledrive.core.model.StoredFile
import com.teledrive.files.DownloadCoordinator
import com.teledrive.files.LocalFileStorage
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

sealed interface MediaResolution {
    data class Available(val file: StoredFile, val localPath: String) : MediaResolution
    data class NeedsDownload(val file: StoredFile) : MediaResolution
    data class Unavailable(val file: StoredFile?, val reason: String) : MediaResolution
}

@Singleton
class MediaPlaybackRepository @Inject constructor(
    private val repository: FileIndexRepository,
    private val localStorage: LocalFileStorage,
    private val downloads: DownloadCoordinator,
    private val metadataExtractor: MediaMetadataExtractor,
) {
    suspend fun resolveForPlayback(stableId: String): MediaResolution {
        val file = repository.observeFile(stableId).first() ?: return MediaResolution.Unavailable(null, "File not found in library")
        // Check local availability
        val path = file.localPath
        val isLocal = file.localAvailability == LocalAvailability.AVAILABLE && localStorage.isAvailable(path, file.sizeBytes)
        return if (isLocal && path != null) MediaResolution.Available(file, path)
        else if (file.tdlibFileId != null) MediaResolution.NeedsDownload(file)
        else MediaResolution.Unavailable(file, "This file cannot be downloaded")
    }

    suspend fun ensureLocalFile(stableId: String): MediaResolution {
        val resolved = resolveForPlayback(stableId)
        if (resolved is MediaResolution.Available) return resolved
        if (resolved is MediaResolution.NeedsDownload) {
            return try {
                val downloaded = downloads.download(resolved.file)
                MediaResolution.Available(downloaded, downloaded.localPath ?: return MediaResolution.Unavailable(downloaded, "Download completed without a local path"))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                MediaResolution.Unavailable(resolved.file, com.teledrive.core.domain.toAppError(error, com.teledrive.core.model.ErrorOperation.DOWNLOAD).userMessage)
            }
        }
        return resolved
    }

    suspend fun toPlaybackItem(file: StoredFile, localPath: String): PlaybackItem {
        val metadata = metadataExtractor.extract(localPath, file.originalFilename)
        return PlaybackItem(
            file = file,
            localPath = localPath,
            title = metadata.title ?: FileKindDetector.detect(file.originalFilename, file.mimeType).let { file.originalFilename.substringBeforeLast('.') },
            artist = metadata.artist,
            album = metadata.album,
            durationMs = metadata.durationMs,
        )
    }

    suspend fun buildQueueFromIds(stableIds: List<String>, startId: String? = null): Pair<List<PlaybackItem>, Int> {
        val items = mutableListOf<PlaybackItem>()
        var startIndex = 0
        for (id in stableIds) {
            val res = resolveForPlayback(id)
            // Include remote-only items; they will trigger download on selection
            val file = when (res) {
                is MediaResolution.Available -> res.file
                is MediaResolution.NeedsDownload -> res.file
                is MediaResolution.Unavailable -> res.file ?: continue
            }
            // For queue building, we use placeholder localPath if not available; actual path resolved on play
            val path = (res as? MediaResolution.Available)?.localPath ?: file.localPath ?: ""
            val item = toPlaybackItem(file, path)
            if (id == startId) startIndex = items.size
            items.add(item)
        }
        return items to startIndex
    }

    suspend fun getFile(stableId: String): StoredFile? = repository.observeFile(stableId).first()
}
