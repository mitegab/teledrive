package com.teledrive.core.domain

import com.teledrive.core.model.TransferRetryPolicy

object GatewayErrorMapper {
    fun fromTdlib(code: Int?, rawMessage: String?): StorageGatewayException {
        val safeMessage = safeMessage(rawMessage)
        val normalized = rawMessage.orEmpty().uppercase()
        val errorCode = when {
            normalized.contains("FLOOD_WAIT") -> "FLOOD_WAIT"
            normalized.contains("NETWORK") || normalized.contains("CONNECTION") -> "NETWORK_ERROR"
            normalized.contains("TIMEOUT") -> "TIMEOUT"
            normalized.contains("FILE_REFERENCE") -> "FILE_REFERENCE_EXPIRED"
            normalized.contains("AUTH") || normalized.contains("PHONE") || normalized.contains("CODE") -> "AUTHENTICATION_ERROR"
            else -> code?.toString()
        }
        val retryable = TransferRetryPolicy.isRetryable(errorCode, rawMessage)
        return StorageGatewayException(errorCode, safeMessage, retryable)
    }

    /** TDLib errors are untrusted server text; keep it displayable without echoing secrets or huge payloads. */
    fun safeMessage(rawMessage: String?): String {
        val value = rawMessage.orEmpty()
            .replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (value.isBlank()) return "Telegram returned an unspecified error"
        return value.take(240)
    }
}
