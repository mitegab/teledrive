package com.teledrive.core.telegram

import com.teledrive.core.domain.StorageGatewayException
import com.teledrive.core.model.AuthorizationState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Blocks until the TDLib session is usable, or fails fast with a clear error.
 *
 * `start()` returns long before `authorizationStateReady`; requests issued too
 * early fail with `TDLIB_NOT_STARTED`, 401, or "Chat not found". Every method
 * that needs a live session waits here first.
 */
internal suspend fun awaitReadySession(
    authorizationState: StateFlow<AuthorizationState>,
    timeoutMillis: Long = READY_TIMEOUT_MILLIS,
) {
    val settled = withTimeoutOrNull(timeoutMillis) {
        authorizationState.first { state ->
            state is AuthorizationState.Ready ||
                state is AuthorizationState.Error ||
                state is AuthorizationState.Closing ||
                state is AuthorizationState.Closed ||
                state is AuthorizationState.LoggingOut ||
                state is AuthorizationState.WaitingForPhoneNumber ||
                state is AuthorizationState.WaitingForCode ||
                state is AuthorizationState.WaitingForPassword ||
                state is AuthorizationState.WaitingForEmailAddress ||
                state is AuthorizationState.WaitingForEmailCode ||
                state is AuthorizationState.WaitingForOtherDeviceConfirmation ||
                state is AuthorizationState.WaitingForRegistration ||
                state is AuthorizationState.WaitingForPremiumPurchase
        }
    } ?: authorizationState.value
    when (settled) {
        is AuthorizationState.Ready -> return
        is AuthorizationState.Error -> throw StorageGatewayException("NOT_READY", settled.message, false)
        is AuthorizationState.Closing,
        is AuthorizationState.Closed,
        is AuthorizationState.LoggingOut,
        -> throw StorageGatewayException(
            "NOT_READY",
            "The Telegram session is closing; reconnect and try again",
            true,
        )
        is AuthorizationState.WaitingForPhoneNumber,
        is AuthorizationState.WaitingForCode,
        is AuthorizationState.WaitingForPassword,
        is AuthorizationState.WaitingForEmailAddress,
        is AuthorizationState.WaitingForEmailCode,
        is AuthorizationState.WaitingForOtherDeviceConfirmation,
        is AuthorizationState.WaitingForRegistration,
        is AuthorizationState.WaitingForPremiumPurchase,
        -> throw StorageGatewayException(
            "NOT_READY",
            "Log in to Telegram to continue",
            false,
        )
        else -> throw StorageGatewayException(
            "NOT_READY",
            "Telegram is still starting up; try again in a moment",
            true,
        )
    }
}

internal const val READY_TIMEOUT_MILLIS: Long = 30_000L

/**
 * Guards one-shot `setTdlibParameters` submission per TDLib client instance.
 * The flag must reset when the client closes or the session turns over, or a
 * later session never answers `authorizationStateWaitTdlibParameters` again.
 */
internal class TdLibParametersGate {
    @Volatile
    private var sent = false

    fun shouldSend(): Boolean {
        if (sent) return false
        sent = true
        return true
    }

    fun markUnsent() {
        sent = false
    }
}
