package com.teledrive.playback

import com.teledrive.core.model.StoredFile

enum class PlaybackStatus {
    IDLE,
    RESOLVING,
    WAITING_FOR_DOWNLOAD,
    PREPARING,
    BUFFERING,
    PLAYING,
    PAUSED,
    ENDED,
    ERROR
}

enum class MediaAvailability {
    REMOTE_ONLY,
    DOWNLOADING,
    AVAILABLE,
    UNAVAILABLE
}

enum class PlaybackErrorType {
    MISSING_FILE,
    DOWNLOAD_FAILED,
    UNSUPPORTED_FORMAT,
    DECODER_ERROR,
    NETWORK_ERROR,
    UNKNOWN
}

data class PlaybackError(
    val type: PlaybackErrorType,
    val message: String,
    val retryable: Boolean = false,
)

enum class RepeatMode { OFF, ONE, ALL }
enum class ShuffleMode { OFF, ON }

data class PlaybackProgress(
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val speed: Float = 1f,
)

data class PlaybackItem(
    val file: StoredFile,
    val localPath: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val artworkPath: String? = null,
    val durationMs: Long? = null,
)

data class PlaybackQueueState(
    val items: List<PlaybackItem> = emptyList(),
    val currentIndex: Int = -1,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val shuffleMode: ShuffleMode = ShuffleMode.OFF,
    val shuffledOrder: List<Int> = emptyList(),
) {
    val currentItem: PlaybackItem? get() = items.getOrNull(currentIndex)
    val hasNext: Boolean get() = when (repeatMode) {
        RepeatMode.ALL -> items.isNotEmpty()
        else -> currentIndex >= 0 && currentIndex < items.size - 1
    }
    val hasPrevious: Boolean get() = when (repeatMode) {
        RepeatMode.ALL -> items.isNotEmpty()
        else -> currentIndex > 0
    }
    val queueSize: Int get() = items.size
}

data class PlayerUiState(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val availability: MediaAvailability = MediaAvailability.REMOTE_ONLY,
    val queue: PlaybackQueueState = PlaybackQueueState(),
    val progress: PlaybackProgress = PlaybackProgress(),
    val error: PlaybackError? = null,
    val isBuffering: Boolean = false,
    val isSeeking: Boolean = false,
    val downloadProgress: Float? = null,
)

fun formatTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
