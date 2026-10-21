package com.teledrive.core.model

/**
 * Typed, user-presentable failures with a diagnostic side channel.
 *
 * Rules enforced here:
 *  - [userMessage] never contains raw exception text, server payloads, or paths.
 *  - [diagnosticCause] is for logs only and is sanitized/truncated at construction.
 *  - cancellation is its own category and is never retryable.
 *  - unknown failures are not retryable without evidence.
 */
enum class ErrorCategory {
    NETWORK_UNAVAILABLE,
    REQUEST_TIMEOUT,
    AUTHENTICATION_REQUIRED,
    SESSION_EXPIRED,
    /** The Telegram session exists but is not usable yet (starting, reconnecting, closing). */
    SESSION_NOT_READY,
    RATE_LIMITED,
    ACCESS_DENIED,
    REMOTE_FILE_MISSING,
    DOWNLOAD_FAILED,
    UPLOAD_FAILED,
    TRANSFER_CANCELED,
    STORAGE_FULL,
    LOCAL_FILE_MISSING,
    LOCAL_FILE_INACCESSIBLE,
    CORRUPT_FILE,
    UNSUPPORTED_FORMAT,
    PDF_RENDERING_FAILED,
    MEDIA_PLAYBACK_FAILED,
    DATABASE_FAILURE,
    SYNC_FAILED,
    INVALID_INPUT,
    CONFIGURATION_FAILURE,
    NATIVE_INITIALIZATION_FAILED,
    SAVED_MESSAGES_UNAVAILABLE,
    UNKNOWN,
}

/** The user-visible feature that was running when the failure happened. */
enum class ErrorOperation {
    AUTHENTICATION,
    LISTING,
    SEARCH,
    DOWNLOAD,
    UPLOAD,
    SYNC,
    PDF,
    MEDIA,
    STORAGE,
    DATABASE,
    STARTUP,
    SETTINGS,
    UNKNOWN,
}

data class AppError(
    val category: ErrorCategory,
    val operation: ErrorOperation = ErrorOperation.UNKNOWN,
    val userMessage: String,
    val retryable: Boolean = false,
    /** Server-requested delay (Telegram flood wait) that callers must respect. */
    val retryAfterSeconds: Long? = null,
    val diagnosticCode: String? = null,
    /** Sanitized technical detail; logs only, never rendered in UI. */
    val diagnosticCause: String? = null,
) {
    val isCancellation: Boolean get() = category == ErrorCategory.TRANSFER_CANCELED
    val needsAuthentication: Boolean
        get() = category == ErrorCategory.AUTHENTICATION_REQUIRED || category == ErrorCategory.SESSION_EXPIRED

    /** Structured, redacted log line (path/phone/code never reach this value). */
    fun logLine(): String = buildString {
        append("error category=").append(category.name)
        append(" operation=").append(operation.name)
        append(" retryable=").append(retryable)
        // Not "code=": that key is redacted for authentication codes, which used to hide the
        // diagnostic classification of every failure in the log.
        diagnosticCode?.let { append(" error_code=").append(TeleDriveLog.scrub("error_code", it)) }
        retryAfterSeconds?.let { append(" retry_after=").append(it) }
        diagnosticCause?.let { append(" cause=").append(TeleDriveLog.scrub("cause", redactSecrets(it))) }
    }
}

/** Masks key=value secrets that could be embedded in lower-level server or I/O text. */
internal fun redactSecrets(raw: String): String = raw.replace(
    Regex("(?i)\\b(password|passcode|token|secret|api_hash|auth_code|verification_code|session_key)\\s*[=:]\\s*\\S+"),
    "$1=[redacted]",
)

object AppErrorMessages {
    fun forCategory(category: ErrorCategory, retryAfterSeconds: Long? = null): String = when (category) {
        ErrorCategory.NETWORK_UNAVAILABLE -> "TeleDrive can't reach Telegram right now. Check your connection and try again."
        ErrorCategory.REQUEST_TIMEOUT -> "Telegram took too long to respond. Try again in a moment."
        ErrorCategory.AUTHENTICATION_REQUIRED -> "Your Telegram session needs attention before remote files can be accessed."
        ErrorCategory.SESSION_EXPIRED -> "Your Telegram session has expired. Connect again to continue."
        ErrorCategory.SESSION_NOT_READY -> "Telegram is still getting ready in TeleDrive. Try again in a moment."
        ErrorCategory.RATE_LIMITED -> retryAfterSeconds?.takeIf { it > 0 }
            ?.let { "Telegram asked TeleDrive to wait $it seconds before retrying." }
            ?: "Telegram asked TeleDrive to slow down. Try again shortly."
        ErrorCategory.ACCESS_DENIED -> "Telegram didn't allow this action for your account."
        ErrorCategory.REMOTE_FILE_MISSING -> "This file is no longer available in Telegram. A copy saved on this device stays usable."
        // A transfer can fail for storage, verification, session or server reasons. Blaming
        // the connection here made every local problem look like a network outage.
        ErrorCategory.DOWNLOAD_FAILED -> "The download didn't finish. Try again."
        ErrorCategory.UPLOAD_FAILED -> "The upload didn't finish. Try again."
        ErrorCategory.TRANSFER_CANCELED -> "You canceled this transfer."
        ErrorCategory.STORAGE_FULL -> "Not enough storage to save this file. Free up space and retry."
        ErrorCategory.LOCAL_FILE_MISSING -> "The saved copy is no longer on this device. Download it again to open it offline."
        ErrorCategory.LOCAL_FILE_INACCESSIBLE -> "TeleDrive can't read the saved copy on this device."
        ErrorCategory.CORRUPT_FILE -> "This file appears to be damaged or incomplete."
        ErrorCategory.UNSUPPORTED_FORMAT -> "TeleDrive can't display this file format. You can export it or open it in another app."
        ErrorCategory.PDF_RENDERING_FAILED -> "This PDF could not be opened. The document may be damaged or use unsupported encryption."
        ErrorCategory.MEDIA_PLAYBACK_FAILED -> "This media file can't be played on this device."
        ErrorCategory.DATABASE_FAILURE -> "TeleDrive couldn't update its local library. Try again."
        ErrorCategory.SYNC_FAILED -> "Sync stopped before it finished. Your saved files are unchanged; try again."
        ErrorCategory.INVALID_INPUT -> "Check the information you entered and try again."
        ErrorCategory.CONFIGURATION_FAILURE -> "TeleDrive isn't configured for Telegram yet. Add your API credentials and rebuild."
        ErrorCategory.NATIVE_INITIALIZATION_FAILED -> "Telegram couldn't start on this device. Check the app build and try again."
        ErrorCategory.SAVED_MESSAGES_UNAVAILABLE -> "TeleDrive couldn't open Saved Messages in Telegram. Try again in a moment."
        ErrorCategory.UNKNOWN -> "Something went wrong. TeleDrive recorded the details for diagnostics."
    }

    /**
     * Telegram authentication failures are actionable on their own; a generic
     * "session needs attention" sentence would hide which input was rejected.
     */
    fun forAuthenticationCode(code: String?): String? {
        val normalized = code.orEmpty().uppercase().replace(Regex("[\\s-]+"), "_")
        return when {
            normalized.contains("PHONE_NUMBER_INVALID") || normalized.contains("PHONE_NUMBER_EMPTY") ->
                "That phone number doesn't look valid. Enter it in international format."
            normalized.contains("PHONE_CODE_EXPIRED") -> "The verification code expired. Request a new one."
            normalized.contains("PHONE_CODE_INVALID") || normalized.contains("PHONE_CODE_EMPTY") ->
                "That verification code wasn't accepted. Check the digits and try again."
            normalized.contains("PASSWORD_HASH_INVALID") -> "The two-step verification password was incorrect."
            normalized.contains("SESSION_PASSWORD_NEEDED") || normalized.contains("PASSWORD_EMPTY") ->
                "Enter your two-step verification password to continue."
            normalized.contains("EMAIL_ADDRESS_INVALID") || normalized.contains("EMAIL_CODE_INVALID") ->
                "That email or email code wasn't accepted. Check it and try again."
            normalized.contains("USER_DEACTIVATED") -> "This Telegram account can't be used right now."
            normalized.contains("FLOOD_WAIT") -> null
            else -> null
        }
    }
}

object AppErrorMapper {
    private const val MAX_CAUSE_LENGTH = 200
    // TDLib reports "Too Many Requests: retry after 30"; codes elsewhere use
    // FLOOD_WAIT_30 / RETRY_AFTER_30. Accept separators in either form.
    private val floodWait = Regex("FLOOD[_ ]WAIT[ _:]?[ ]?(\\d+)", RegexOption.IGNORE_CASE)
    private val retryAfter = Regex("RETRY[ _]AFTER[ _:]?[ ]?(\\d+)", RegexOption.IGNORE_CASE)

    fun cancellation(operation: ErrorOperation = ErrorOperation.UNKNOWN): AppError = AppError(
        category = ErrorCategory.TRANSFER_CANCELED,
        operation = operation,
        userMessage = AppErrorMessages.forCategory(ErrorCategory.TRANSFER_CANCELED),
        retryable = false,
    )

    /**
     * Maps Telegram/TDLib-style error codes. [retryableHint] is the gateway's own
     * assessment; it is honored for unknown codes but never overrides safety rules
     * such as "authentication failures are not network retries".
     */
    fun fromCode(
        code: String?,
        message: String? = null,
        operation: ErrorOperation = ErrorOperation.UNKNOWN,
        retryableHint: Boolean = false,
    ): AppError {
        val normalized = code.orEmpty().uppercase()
        return when {
            normalized.contains("NETWORK") || normalized.contains("CONNECTION") ->
                category(ErrorCategory.NETWORK_UNAVAILABLE, operation, retryable = true, code = code, message = message)

            normalized.contains("TIMEOUT") ->
                category(ErrorCategory.REQUEST_TIMEOUT, operation, retryable = true, code = code, message = message)

            normalized.contains("FLOOD_WAIT") || normalized.contains("RATE_LIMIT") || normalized == "429" ->
                category(
                    ErrorCategory.RATE_LIMITED,
                    operation,
                    retryable = true,
                    code = code,
                    message = message,
                    retryAfterSeconds = requestedDelaySeconds(code, message),
                )

            operation == ErrorOperation.AUTHENTICATION && AppErrorMessages.forAuthenticationCode(normalized) != null ->
                AppError(
                    category = ErrorCategory.AUTHENTICATION_REQUIRED,
                    operation = operation,
                    userMessage = AppErrorMessages.forAuthenticationCode(normalized)!!,
                    retryable = false,
                    diagnosticCode = code,
                    diagnosticCause = sanitizeCause(message),
                )

            normalized == "AUTHENTICATION_ERROR" || normalized.contains("SESSION_") || normalized.startsWith("AUTH_KEY") ->
                category(ErrorCategory.AUTHENTICATION_REQUIRED, operation, retryable = false, code = code, message = message)

            normalized == "SESSION_EXPIRED" || normalized == "AUTHORIZATION_REVOKED" ->
                category(ErrorCategory.SESSION_EXPIRED, operation, retryable = false, code = code, message = message)

            normalized == "ACCESS_DENIED" || normalized == "PERMISSION_DENIED" || normalized == "CHAT_FORBIDDEN" ->
                category(ErrorCategory.ACCESS_DENIED, operation, retryable = false, code = code, message = message)

            normalized == "TRANSFER_CANCELED" || normalized == "CANCELED" ->
                cancellation(operation).copy(diagnosticCode = code)

            normalized == "REMOTE_FILE_MISSING" || normalized == "MESSAGE_NOT_FOUND" || normalized == "FILE_NOT_FOUND" ->
                category(ErrorCategory.REMOTE_FILE_MISSING, operation, retryable = false, code = code, message = message)

            normalized == "STORAGE_FULL" || normalized == "ENOSPC" ->
                category(ErrorCategory.STORAGE_FULL, operation, retryable = false, code = code, message = message)

            normalized == "LOCAL_FILE_MISSING" ->
                category(ErrorCategory.LOCAL_FILE_MISSING, operation, retryable = true, code = code, message = message)

            // A session that is not ready is not a network outage and not a failed download:
            // waiting or reconnecting must be described as such so the retry is meaningful.
            normalized == "NOT_READY" || normalized == "TDLIB_NOT_STARTED" ->
                category(ErrorCategory.SESSION_NOT_READY, operation, retryable = true, code = code, message = message)

            normalized == "NOT_AUTHENTICATED" ->
                AppError(
                    category = ErrorCategory.AUTHENTICATION_REQUIRED,
                    operation = operation,
                    userMessage = "Log in to Telegram to continue.",
                    retryable = false,
                    diagnosticCode = code,
                    diagnosticCause = sanitizeCause(message),
                )

            // Telegram's file reference is intentionally short-lived; re-reading the message
            // makes the next attempt work, so the message must promise exactly that.
            normalized == "FILE_REFERENCE_EXPIRED" ->
                transferFailure(
                    operation,
                    userMessage = "Telegram's link to this file expired. Try again to fetch it fresh.",
                    code = code,
                    message = message,
                )

            // TDLib reported a completed download but could not point at the bytes. That is a
            // failed hand-off on this device, not a connectivity problem.
            normalized == "DOWNLOAD_PATH_MISSING" ->
                transferFailure(
                    operation,
                    userMessage = "Telegram finished downloading but didn't provide the file on this device. Try again.",
                    code = code,
                    message = message,
                )

            // Two index rows can point at the same Telegram file (the same photo forwarded
            // twice) and TDLib allows one active download per file id.
            normalized == "TRANSFER_ALREADY_ACTIVE" ->
                transferFailure(
                    operation,
                    userMessage = "This file is already being downloaded. Try again when it finishes.",
                    code = code,
                    message = message,
                )

            normalized == "CHAT_NOT_FOUND" || normalized == "SAVED_MESSAGES_NOT_FOUND" ->
                category(ErrorCategory.SAVED_MESSAGES_UNAVAILABLE, operation, retryable = true, code = code, message = message)

            normalized == "CONFIGURATION_ERROR" || normalized == "NATIVE_LIBRARY_MISSING" ->
                category(ErrorCategory.CONFIGURATION_FAILURE, operation, retryable = false, code = code, message = message)

            normalized.toIntOrNull()?.let { numeric -> numeric == 429 || numeric in 500..599 } == true ->
                category(transferCategory(operation), operation, retryable = true, code = code, message = message)

            // Anything unrecognized inside the authentication flow still means the
            // session is not usable; reporting UNKNOWN would hide the recovery action.
            operation == ErrorOperation.AUTHENTICATION ->
                category(ErrorCategory.AUTHENTICATION_REQUIRED, operation, retryable = false, code = code, message = message)

            retryableHint ->
                category(transferCategory(operation), operation, retryable = true, code = code, message = message)

            else -> AppError(
                category = ErrorCategory.UNKNOWN,
                operation = operation,
                userMessage = AppErrorMessages.forCategory(ErrorCategory.UNKNOWN),
                retryable = false,
                diagnosticCode = code,
                diagnosticCause = sanitizeCause(message),
            )
        }
    }

    /** Maps local exceptions (I/O, database, rendering) without leaking their text to the UI. */
    fun fromThrowable(
        error: Throwable,
        operation: ErrorOperation = ErrorOperation.UNKNOWN,
        category: ErrorCategory = operation.defaultCategory(),
        retryable: Boolean = operation.defaultRetryable(),
    ): AppError = AppError(
        category = category,
        operation = operation,
        userMessage = AppErrorMessages.forCategory(category),
        retryable = retryable,
        diagnosticCode = error::class.java.simpleName,
        diagnosticCause = sanitizeCause(error.message ?: error::class.java.simpleName),
    )

    fun storageFull(operation: ErrorOperation, requiredBytes: Long, freeBytes: Long): AppError = AppError(
        category = ErrorCategory.STORAGE_FULL,
        operation = operation,
        userMessage = AppErrorMessages.forCategory(ErrorCategory.STORAGE_FULL),
        retryable = false,
        diagnosticCode = "INSUFFICIENT_STORAGE",
        diagnosticCause = "required=$requiredBytes free=$freeBytes",
    )

    fun localFileMissing(operation: ErrorOperation, path: String? = null): AppError = AppError(
        category = ErrorCategory.LOCAL_FILE_MISSING,
        operation = operation,
        userMessage = AppErrorMessages.forCategory(ErrorCategory.LOCAL_FILE_MISSING),
        retryable = true,
        diagnosticCode = "LOCAL_FILE_MISSING",
        diagnosticCause = path?.let { "file=${it.substringAfterLast('/')}" },
    )

    fun pdfOpenFailure(diagnostic: String?): AppError = AppError(
        category = ErrorCategory.PDF_RENDERING_FAILED,
        operation = ErrorOperation.PDF,
        userMessage = AppErrorMessages.forCategory(ErrorCategory.PDF_RENDERING_FAILED),
        retryable = false,
        diagnosticCode = "PDF_OPEN_FAILED",
        diagnosticCause = sanitizeCause(diagnostic),
    )

    fun requestedDelaySeconds(code: String?, message: String? = null): Long? {
        val haystack = listOfNotNull(code, message).joinToString(" ").uppercase()
        return floodWait.find(haystack)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?: retryAfter.find(haystack)?.groupValues?.getOrNull(1)?.toLongOrNull()
    }

    fun sanitizeCause(raw: String?): String? {
        val cleaned = redactSecrets(raw.orEmpty())
            .replace(Regex("[\\u0000-\\u001F\\u007F]"), " ")
            // Absolute paths are reduced to their last segment so logs never expose
            // the app's private directory layout or user file names in full.
            .replace(Regex("(?<=^|\\s)/(?:[^\\s/]+/)+([^\\s/]+)")) { match -> match.groupValues[1] }
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_CAUSE_LENGTH)
        return cleaned.ifBlank { null }
    }

    private fun transferCategory(operation: ErrorOperation): ErrorCategory =
        if (operation == ErrorOperation.UPLOAD) ErrorCategory.UPLOAD_FAILED else ErrorCategory.DOWNLOAD_FAILED

    /**
     * A transfer failure with a cause-specific sentence. The category still says whether the
     * upload or the download failed; only [userMessage] carries the concrete reason, so the UI
     * never falls back to a generic "try again" when the app already knows what went wrong.
     */
    private fun transferFailure(
        operation: ErrorOperation,
        userMessage: String,
        code: String?,
        message: String?,
    ): AppError = AppError(
        category = transferCategory(operation),
        operation = operation,
        userMessage = userMessage,
        retryable = true,
        diagnosticCode = code,
        diagnosticCause = sanitizeCause(message),
    )

    private fun category(
        category: ErrorCategory,
        operation: ErrorOperation,
        retryable: Boolean,
        code: String?,
        message: String?,
        retryAfterSeconds: Long? = null,
    ): AppError = AppError(
        category = category,
        operation = operation,
        userMessage = AppErrorMessages.forCategory(category, retryAfterSeconds),
        retryable = retryable,
        retryAfterSeconds = retryAfterSeconds,
        diagnosticCode = code,
        diagnosticCause = sanitizeCause(message),
    )
}

private fun ErrorOperation.defaultCategory(): ErrorCategory = when (this) {
    ErrorOperation.DOWNLOAD -> ErrorCategory.DOWNLOAD_FAILED
    ErrorOperation.UPLOAD -> ErrorCategory.UPLOAD_FAILED
    ErrorOperation.PDF -> ErrorCategory.PDF_RENDERING_FAILED
    ErrorOperation.MEDIA -> ErrorCategory.MEDIA_PLAYBACK_FAILED
    ErrorOperation.SYNC -> ErrorCategory.SYNC_FAILED
    ErrorOperation.DATABASE -> ErrorCategory.DATABASE_FAILURE
    ErrorOperation.STORAGE -> ErrorCategory.LOCAL_FILE_INACCESSIBLE
    ErrorOperation.AUTHENTICATION -> ErrorCategory.AUTHENTICATION_REQUIRED
    else -> ErrorCategory.UNKNOWN
}

private fun ErrorOperation.defaultRetryable(): Boolean = when (this) {
    ErrorOperation.DOWNLOAD, ErrorOperation.UPLOAD, ErrorOperation.SYNC, ErrorOperation.MEDIA -> true
    else -> false
}
