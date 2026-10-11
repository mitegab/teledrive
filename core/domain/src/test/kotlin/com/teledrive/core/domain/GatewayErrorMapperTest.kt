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
}
