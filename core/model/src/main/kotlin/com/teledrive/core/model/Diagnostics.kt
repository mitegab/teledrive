package com.teledrive.core.model

/**
 * Small structured debug logger (tag `TeleDrive`).
 *
 * Events are formatted as `event key=value key=value` and handed to the
 * installed [sink] (wired to logcat by the app). Values are sanitized before
 * they are emitted: control characters are stripped, long text is truncated,
 * filesystem paths are reduced to their last segment, and secrets (passwords,
 * auth codes, API hash, database keys, phone numbers) are redacted defensively —
 * call sites must never pass them in the first place.
 */
object TeleDriveLog {
    const val TAG = "TeleDrive"

    @Volatile
    var sink: ((String) -> Unit)? = null

    fun d(event: String, vararg details: Pair<String, Any?>) {
        val target = sink ?: return
        target(format(event, details))
    }

    fun format(event: String, details: Array<out Pair<String, Any?>>): String = buildString {
        append(event)
        details.forEach { (key, value) ->
            append(' ').append(key).append('=').append(scrub(key, value))
        }
    }

    fun scrub(key: String, value: Any?): String {
        val normalizedKey = key.lowercase()
        if (normalizedKey in REDACTED_KEYS || normalizedKey.endsWith("_hash") || normalizedKey.endsWith("_key")) {
            return "[redacted]"
        }
        val cleaned = value?.toString().orEmpty()
            .replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifEmpty { return "null" }
        val shaped = if (normalizedKey.endsWith("path")) {
            cleaned.substringAfterLast('/').substringAfterLast('\\')
        } else {
            cleaned
        }
        return shaped.take(MAX_VALUE_LENGTH)
    }

    private val REDACTED_KEYS = setOf(
        "password", "api_hash", "database_key", "database_key_base64", "key",
        "phone", "phone_number", "code", "auth_code", "email_code", "api_id",
    )

    private const val MAX_VALUE_LENGTH = 120
}
