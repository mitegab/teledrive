package com.teledrive.core.domain

import com.teledrive.core.model.TransferRetryPolicy

object GatewayErrorMapper {
    /**
     * TDLib authentication failures carry specific machine-readable messages.
     * Loose substring matching on "AUTH"/"PHONE"/"CODE" mislabels unrelated
     * errors, so only these explicit markers map to AUTHENTICATION_ERROR.
     */
    private val authErrorMarkers = listOf(
        Regex("PHONE_(NUMBER|CODE)_(INVALID|EMPTY|EXPIRED|BANNED|FLOOD)"),
        Regex("PASSWORD_(HASH_INVALID|EMPTY)"),
        Regex("SESSION_(PASSWORD_NEEDED|REVOKED|EXPIRED)"),
        Regex("AUTH_KEY_"),
        Regex("USER_DEACTIVATED"),
        Regex("EMAIL_(ADDRESS|CODE)_(INVALID|UNCONFIRMED|EXPIRED|EMPTY)"),
        Regex("\\bUNAUTHORIZED\\b"),
    )

    fun fromTdlib(code: Int?, rawMessage: String?): StorageGatewayException {
        val safeMessage = safeMessage(rawMessage)
        // TDLib messages use spaces ("Chat not found"); normalize separators so
        // both that form and underscored codes ("FLOOD_WAIT_30") match.
        val normalized = rawMessage.orEmpty().uppercase().replace(Regex("[\\s-]+"), "_")
        val errorCode = when {
            normalized.contains("FLOOD_WAIT") -> "FLOOD_WAIT"
            normalized.contains("CHAT_NOT_FOUND") -> "CHAT_NOT_FOUND"
            normalized.contains("NETWORK") || normalized.contains("CONNECTION") -> "NETWORK_ERROR"
            normalized.contains("TIMEOUT") -> "TIMEOUT"
            normalized.contains("FILE_REFERENCE") -> "FILE_REFERENCE_EXPIRED"
            code == 401 -> "AUTHENTICATION_ERROR"
            authErrorMarkers.any { it.containsMatchIn(normalized) } -> "AUTHENTICATION_ERROR"
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
