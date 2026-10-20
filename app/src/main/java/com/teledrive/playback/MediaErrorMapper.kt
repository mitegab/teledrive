package com.teledrive.playback

import androidx.media3.common.PlaybackException

object MediaErrorMapper {
    fun fromDownloadError(message: String?, code: String? = null): PlaybackError = when {
        message?.contains("no longer exposes", ignoreCase = true) == true -> PlaybackError(PlaybackErrorType.MISSING_FILE, "Telegram no longer has this file. It may have been deleted.", false)
        message?.contains("not a readable", ignoreCase = true) == true -> PlaybackError(PlaybackErrorType.MISSING_FILE, "The downloaded file is missing.", false)
        code == "NETWORK_ERROR" || message?.contains("network", true) == true -> PlaybackError(PlaybackErrorType.NETWORK_ERROR, "Download failed due to a network problem. Try again.", true)
        code == "TIMEOUT" -> PlaybackError(PlaybackErrorType.NETWORK_ERROR, "The download timed out. Check your connection and retry.", true)
        else -> PlaybackError(PlaybackErrorType.DOWNLOAD_FAILED, message ?: "Download failed. Try again.", retryable = code != null)
    }

    fun fromPlaybackException(error: PlaybackException): PlaybackError {
        val code = error.errorCode
        val name = error.errorCodeName
        return when (code) {
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> PlaybackError(PlaybackErrorType.UNSUPPORTED_FORMAT, "This file format is not supported on this device.", false)
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> PlaybackError(PlaybackErrorType.DECODER_ERROR, "Unable to decode this file. The codec may not be supported.", false)
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> PlaybackError(PlaybackErrorType.MISSING_FILE, "The local file is no longer available. Try downloading again.", true)
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> PlaybackError(PlaybackErrorType.NETWORK_ERROR, "Network error while loading media.", true)
            else -> {
                val msg = error.message ?: name ?: "Unknown playback error"
                if (msg.contains("unsupported", true) || msg.contains("codec", true)) {
                    PlaybackError(PlaybackErrorType.UNSUPPORTED_FORMAT, "This file uses an unsupported codec.", false)
                } else PlaybackError(PlaybackErrorType.UNKNOWN, "Playback failed: $msg", false)
            }
        }
    }

    fun unsupportedFormat(message: String = "This file format is not supported on this device."): PlaybackError =
        PlaybackError(PlaybackErrorType.UNSUPPORTED_FORMAT, message, false)

    fun missingFile(message: String = "The file is not available locally."): PlaybackError =
        PlaybackError(PlaybackErrorType.MISSING_FILE, message, true)
}
