package com.teledrive.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorModelsTest {

    @Test
    fun networkAndTimeoutCodesAreRetryableWithPlainLanguageMessages() {
        val network = AppErrorMapper.fromCode("NETWORK_ERROR", "Connection reset", ErrorOperation.DOWNLOAD)
        assertEquals(ErrorCategory.NETWORK_UNAVAILABLE, network.category)
        assertTrue(network.retryable)
        assertFalse(network.userMessage.contains("Connection reset"))

        val timeout = AppErrorMapper.fromCode("TIMEOUT", null, ErrorOperation.SYNC)
        assertEquals(ErrorCategory.REQUEST_TIMEOUT, timeout.category)
        assertTrue(timeout.retryable)
    }

    @Test
    fun floodWaitCarriesTheServerRequestedDelay() {
        val error = AppErrorMapper.fromCode("FLOOD_WAIT_30", "Too many requests", ErrorOperation.DOWNLOAD)
        assertEquals(ErrorCategory.RATE_LIMITED, error.category)
        assertTrue(error.retryable)
        assertEquals(30L, error.retryAfterSeconds)
        assertTrue(error.userMessage.contains("30"))
    }

    @Test
    fun authenticationFailuresAreNotNetworkRetries() {
        listOf("AUTHENTICATION_ERROR", "SESSION_REVOKED", "AUTH_KEY_UNREGISTERED").forEach { code ->
            val error = AppErrorMapper.fromCode(code, "Unauthorized", ErrorOperation.LISTING, retryableHint = true)
            assertTrue("auth category for $code", error.needsAuthentication)
            assertFalse("retryable for $code", error.retryable)
        }
    }

    @Test
    fun cancellationIsNotAnOrdinaryFailureAndIsNeverRetryable() {
        val error = AppErrorMapper.cancellation(ErrorOperation.DOWNLOAD)
        assertEquals(ErrorCategory.TRANSFER_CANCELED, error.category)
        assertTrue(error.isCancellation)
        assertFalse(error.retryable)
        assertNull(error.retryAfterSeconds)
    }

    @Test
    fun canceledCodeIsClassifiedAsCancellation() {
        val error = AppErrorMapper.fromCode("TRANSFER_CANCELED", null, ErrorOperation.UPLOAD)
        assertTrue(error.isCancellation)
        assertFalse(error.retryable)
    }

    @Test
    fun unknownFailuresAreSafeAndNotRetryableWithoutEvidence() {
        val error = AppErrorMapper.fromCode("SOMETHING_NEW", "stack trace fragment", ErrorOperation.LISTING)
        assertEquals(ErrorCategory.UNKNOWN, error.category)
        assertFalse(error.retryable)
        assertEquals(AppErrorMessages.forCategory(ErrorCategory.UNKNOWN), error.userMessage)
        assertFalse(error.userMessage.contains("stack trace fragment"))
    }

    @Test
    fun gatewayRetryableHintKeepsUploadAndDownloadCategoriesDistinct() {
        val download = AppErrorMapper.fromCode("WEIRD", null, ErrorOperation.DOWNLOAD, retryableHint = true)
        val upload = AppErrorMapper.fromCode("WEIRD", null, ErrorOperation.UPLOAD, retryableHint = true)
        assertEquals(ErrorCategory.DOWNLOAD_FAILED, download.category)
        assertEquals(ErrorCategory.UPLOAD_FAILED, upload.category)
        assertTrue(download.retryable)
        assertTrue(upload.retryable)
    }

    @Test
    fun serverErrorsAreRetryableButClientErrorsAreNot() {
        assertTrue(AppErrorMapper.fromCode("503", "boom", ErrorOperation.DOWNLOAD).retryable)
        assertFalse(AppErrorMapper.fromCode("400", "bad request", ErrorOperation.DOWNLOAD).retryable)
    }

    @Test
    fun randomNumbersAndMissingCodesDoNotBecomeRetryable() {
        assertFalse(AppErrorMapper.fromCode(null, "", ErrorOperation.DOWNLOAD).retryable)
        assertFalse(AppErrorMapper.fromCode("1234", "odd", ErrorOperation.DOWNLOAD).retryable)
    }

    @Test
    fun storageAndLocalFileFailuresUseActionableMessages() {
        val full = AppErrorMapper.storageFull(ErrorOperation.DOWNLOAD, requiredBytes = 500, freeBytes = 100)
        assertEquals(ErrorCategory.STORAGE_FULL, full.category)
        assertFalse(full.retryable)
        assertTrue(full.userMessage.contains("storage", ignoreCase = true))

        val missing = AppErrorMapper.localFileMissing(ErrorOperation.PDF, "/data/user/0/app/files/downloads/x/report.pdf")
        assertEquals(ErrorCategory.LOCAL_FILE_MISSING, missing.category)
        assertTrue(missing.diagnosticCause?.contains("/data") != true)
        assertTrue(missing.diagnosticCause?.contains("report.pdf") == true)
    }

    @Test
    fun pdfOpenFailuresExplainDamageWithoutTechnicalNoise() {
        val error = AppErrorMapper.pdfOpenFailure("java.io.IOException: broken xref")
        assertEquals(ErrorCategory.PDF_RENDERING_FAILED, error.category)
        assertTrue(error.userMessage.contains("PDF"))
        assertFalse(error.userMessage.contains("xref"))
    }

    @Test
    fun diagnosticCauseIsSanitizedAndTruncated() {
        val error = AppErrorMapper.fromThrowable(
            RuntimeException("bad\u0000value " + "x".repeat(400)),
            ErrorOperation.DOWNLOAD,
            ErrorCategory.DOWNLOAD_FAILED,
            retryable = true,
        )
        val cause = error.diagnosticCause ?: error("expected diagnostic cause")
        assertTrue(cause.length <= 200)
        assertFalse(cause.contains("\u0000"))
    }

    @Test
    fun logLineIsStructuredAndDoesNotExposeSecrets() {
        val error = AppError(
            category = ErrorCategory.AUTHENTICATION_REQUIRED,
            operation = ErrorOperation.AUTHENTICATION,
            userMessage = AppErrorMessages.forCategory(ErrorCategory.AUTHENTICATION_REQUIRED),
            retryable = false,
            diagnosticCode = "AUTHENTICATION_ERROR",
            diagnosticCause = "password=super-secret-1234",
        )
        val line = error.logLine()
        assertTrue(line.startsWith("error category="))
        assertTrue(line.contains("operation=AUTHENTICATION"))
        assertTrue(line.contains("retryable=false"))
        assertFalse(line.contains("super-secret-1234"))
    }

    @Test
    fun localIoFailuresMapToTheOperationCategoryAndStayRetryable() {
        val download = AppErrorMapper.fromThrowable(java.io.IOException("disk full"), ErrorOperation.DOWNLOAD)
        assertEquals(ErrorCategory.DOWNLOAD_FAILED, download.category)
        assertTrue(download.retryable)
        assertEquals("IOException", download.diagnosticCode)

        val database = AppErrorMapper.fromThrowable(IllegalStateException("row missing"), ErrorOperation.DATABASE)
        assertEquals(ErrorCategory.DATABASE_FAILURE, database.category)
        assertFalse(database.retryable)
    }

    @Test
    fun authenticationCodesBecomeSpecificActionableMessages() {
        val invalidPhone = AppErrorMapper.fromCode("PHONE_NUMBER_INVALID", "PHONE_NUMBER_INVALID", ErrorOperation.AUTHENTICATION)
        assertEquals(ErrorCategory.AUTHENTICATION_REQUIRED, invalidPhone.category)
        assertTrue(invalidPhone.userMessage.contains("phone number"))
        assertFalse(invalidPhone.retryable)

        val wrongPassword = AppErrorMapper.fromCode("PASSWORD_HASH_INVALID", "PASSWORD_HASH_INVALID", ErrorOperation.AUTHENTICATION)
        assertTrue(wrongPassword.userMessage.contains("two-step verification password"))
        assertFalse(wrongPassword.userMessage.contains("PASSWORD_HASH_INVALID"))
    }

    @Test
    fun authenticationFallbacksDoNotLeakCodesForOtherOperations() {
        val unknownAuth = AppErrorMapper.fromCode("SOME_UNKNOWN_AUTH_STATE", null, ErrorOperation.AUTHENTICATION)
        assertEquals(ErrorCategory.AUTHENTICATION_REQUIRED, unknownAuth.category)
        assertFalse(unknownAuth.userMessage.contains("SOME_UNKNOWN_AUTH_STATE"))

        // The same code outside authentication is not treated as an auth message.
        val listing = AppErrorMapper.fromCode("PHONE_NUMBER_INVALID", null, ErrorOperation.LISTING)
        assertFalse(listing.userMessage.contains("phone number"))
    }

    @Test
    fun everyCategoryHasADistinctUserFacingSentence() {
        val messages = ErrorCategory.entries.map { AppErrorMessages.forCategory(it) }
        assertEquals(ErrorCategory.entries.size, messages.toSet().size)
        assertTrue(messages.all { it.length > 20 && !it.contains("Exception") })
    }
}
