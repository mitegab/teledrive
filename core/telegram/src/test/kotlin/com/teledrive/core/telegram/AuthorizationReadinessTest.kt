package com.teledrive.core.telegram

import com.teledrive.core.domain.StorageGatewayException
import com.teledrive.core.model.AuthorizationState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AuthorizationReadinessTest {

    @Test
    fun waitsUntilTheSessionBecomesReady() = runBlocking {
        val state = MutableStateFlow<AuthorizationState>(AuthorizationState.Uninitialized)
        val ready = launch {
            delay(50)
            state.value = AuthorizationState.Ready
        }

        awaitReadySession(state, timeoutMillis = 5_000)

        ready.join()
    }

    @Test
    fun timesOutWithAClearRetryableErrorWhenReadyNeverArrives() = runBlocking {
        val state = MutableStateFlow<AuthorizationState>(AuthorizationState.WaitingForTdlibParameters)

        try {
            awaitReadySession(state, timeoutMillis = 100)
            fail("expected NOT_READY")
        } catch (error: StorageGatewayException) {
            assertEquals("NOT_READY", error.code)
            assertTrue(error.retryable)
            assertTrue("message should mention startup: ${error.message}", error.message!!.contains("starting up"))
        }
    }

    @Test
    fun failsFastWhenTheUserMustLogInFirst() = runBlocking {
        val state = MutableStateFlow<AuthorizationState>(AuthorizationState.WaitingForPhoneNumber)

        try {
            awaitReadySession(state, timeoutMillis = 5_000)
            fail("expected NOT_READY")
        } catch (error: StorageGatewayException) {
            assertEquals("NOT_READY", error.code)
            assertEquals(false, error.retryable)
            assertTrue("message should ask for login: ${error.message}", error.message!!.contains("Log in"))
        }
    }

    @Test
    fun surfacesAuthorizationErrorsWithoutHanging() = runBlocking {
        val state = MutableStateFlow<AuthorizationState>(
            AuthorizationState.Error(message = "Telegram API credentials are missing"),
        )

        try {
            awaitReadySession(state, timeoutMillis = 5_000)
            fail("expected NOT_READY")
        } catch (error: StorageGatewayException) {
            assertEquals("NOT_READY", error.code)
            assertEquals(false, error.retryable)
            assertEquals("Telegram API credentials are missing", error.message)
        }
    }

    @Test
    fun closingSessionsReportAnActionableError() = runBlocking {
        val state = MutableStateFlow<AuthorizationState>(AuthorizationState.Closed)

        try {
            awaitReadySession(state, timeoutMillis = 5_000)
            fail("expected NOT_READY")
        } catch (error: StorageGatewayException) {
            assertEquals("NOT_READY", error.code)
            assertTrue(error.retryable)
            assertTrue(error.message!!.contains("reconnect"))
        }
    }
}
