package com.teledrive.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TeleDriveLogTest {

    @Test
    fun formatsStructuredEventsAndTruncatesLongValues() {
        val line = TeleDriveLog.format(
            "tdlib.request",
            arrayOf<Pair<String, Any?>>("type" to "getChatHistory", "error" to "x".repeat(500)),
        )

        assertTrue(line.startsWith("tdlib.request type=getChatHistory error="))
        assertTrue(line.length < 200, "should truncate long text: ${line.length}")
    }

    @Test
    fun redactsSecretsEvenIfACallSitePassesThem() {
        assertEquals("[redacted]", TeleDriveLog.scrub("password", "hunter2"))
        assertEquals("[redacted]", TeleDriveLog.scrub("api_hash", "abc"))
        assertEquals("[redacted]", TeleDriveLog.scrub("database_key_base64", "a2V5"))
        assertEquals("[redacted]", TeleDriveLog.scrub("phone_number", "+15550001111"))
        assertEquals("[redacted]", TeleDriveLog.scrub("code", "12345"))
        assertEquals("[redacted]", TeleDriveLog.scrub("api_id", "1234"))
    }

    @Test
    fun reducesPathsToFileNamesOnly() {
        assertEquals("report.pdf", TeleDriveLog.scrub("local_path", "/data/user/0/com.teledrive/files/staging/2f6c/report.pdf"))
        assertEquals("report.pdf", TeleDriveLog.scrub("source_path", "/data/user/0/files/staging/2f6c/report.pdf"))
    }

    @Test
    fun stripsControlCharactersAndKeepsErrorCodesReadable() {
        assertEquals("400", TeleDriveLog.scrub("error_code", "400"))
        assertEquals("Chat not found", TeleDriveLog.scrub("error", "Chat\u0000\nnot\tfound"))
        assertEquals("null", TeleDriveLog.scrub("size", null))
    }

    @Test
    fun sinkReceivesFormattedLines() {
        val lines = mutableListOf<String>()
        TeleDriveLog.sink = { lines += it }
        try {
            TeleDriveLog.d("sync.done", "pages" to 2, "files" to 3)
            TeleDriveLog.d("sync.done", "pages" to 2, "files" to 3)
        } finally {
            TeleDriveLog.sink = null
        }

        assertEquals(listOf("sync.done pages=2 files=3", "sync.done pages=2 files=3"), lines)
    }
}
