package com.teledrive.core.telegram

/** API credentials are injected from local build configuration; no production values belong in source. */
data class TelegramCredentials(
    val apiId: Int,
    val apiHash: String,
) {
    val isConfigured: Boolean get() = apiId > 0 && apiHash.isNotBlank()
}
