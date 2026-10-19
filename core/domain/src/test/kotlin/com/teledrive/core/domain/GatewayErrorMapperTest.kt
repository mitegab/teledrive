package com.teledrive.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GatewayErrorMapperTest {
    @Test
    fun mapsNetworkErrorsToRetryableSafeMessages() {
        val error = GatewayErrorMapper.fromTdlib(500, "NETWORK_ERROR\nsecret-looking data")
        assertEquals("NETWORK_ERROR", error.code)
        assertTrue(error.retryable)
        assertEquals("NETWORK_ERROR secret-looking data", error.message)
    }

    @Test
    fun truncatesAndCleansServerText() {
        val error = GatewayErrorMapper.fromTdlib(null, "\u0000" + "x".repeat(500))
        assertTrue(error.message.length <= 240)
    }

    @Test
    fun mapsChatNotFoundToAnExplicitNonRetryableCode() {
        val error = GatewayErrorMapper.fromTdlib(400, "Chat not found")
        assertEquals("CHAT_NOT_FOUND", error.code)
        assertEquals(false, error.retryable)
    }

    @Test
    fun mapsFloodWaitsAsRetryable() {
        val error = GatewayErrorMapper.fromTdlib(420, "FLOOD_WAIT_30")
        assertEquals("FLOOD_WAIT", error.code)
        assertTrue(error.retryable)
    }

    @Test
    fun unrelatedMessagesContainingCodeAuthOrPhoneAreNotAuthenticationErrors() {
        listOf(
            "Wrong type of input document; expected code 12",
            "Phone camera is unavailable for this attachment",
            "Authorization database is temporarily locked for maintenance",
        ).forEach { raw ->
            val error = GatewayErrorMapper.fromTdlib(400, raw)
            assertEquals("400", error.code, "classification for '$raw'")
            assertEquals(false, error.retryable, "retryability for '$raw'")
        }
    }

    @Test
    fun realAuthenticationFailuresMapToAuthenticationErrors() {
        listOf("PHONE_CODE_INVALID", "SESSION_PASSWORD_NEEDED", "AUTH_KEY_UNREGISTERED", "PASSWORD_HASH_INVALID").forEach { raw ->
            val error = GatewayErrorMapper.fromTdlib(401, raw)
            assertEquals("AUTHENTICATION_ERROR", error.code, "classification for '$raw'")
            assertEquals(false, error.retryable, "retryability for '$raw'")
        }
        assertEquals("AUTHENTICATION_ERROR", GatewayErrorMapper.fromTdlib(401, "Unauthorized").code)
    }

    @Test
    fun networkAndTimeoutMessagesRemainRetryable() {
        assertEquals(true, GatewayErrorMapper.fromTdlib(null, "Connection reset by peer").retryable)
        assertEquals(true, GatewayErrorMapper.fromTdlib(504, "Request timed out").retryable)
    }
}
