package com.teledrive.playback

import androidx.media3.common.PlaybackException
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaErrorMapperTest {
    @Test
    fun mapsDownloadMissingFile() {
        val err = MediaErrorMapper.fromDownloadError("Telegram no longer exposes this file", "NOT_FOUND")
        assertEquals(PlaybackErrorType.MISSING_FILE, err.type)
        assertEquals(false, err.retryable)
    }

    @Test
    fun mapsDownloadNetworkRetryable() {
        val err = MediaErrorMapper.fromDownloadError("network error", "NETWORK_ERROR")
        assertEquals(PlaybackErrorType.NETWORK_ERROR, err.type)
        assertEquals(true, err.retryable)
    }

    @Test
    fun mapsUnsupportedFormat() {
        val ex = PlaybackException("unsupported", null, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)
        val mapped = MediaErrorMapper.fromPlaybackException(ex)
        assertEquals(PlaybackErrorType.DECODER_ERROR, mapped.type)
    }

    @Test
    fun mapsFileNotFound() {
        val ex = PlaybackException("not found", null, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        val mapped = MediaErrorMapper.fromPlaybackException(ex)
        assertEquals(PlaybackErrorType.MISSING_FILE, mapped.type)
    }
}
