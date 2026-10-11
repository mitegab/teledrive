package com.teledrive

import androidx.compose.runtime.Composable
import com.teledrive.session.SessionUiState
import com.teledrive.session.SessionViewModel
import com.teledrive.settings.SettingsViewModel
import com.teledrive.core.model.AuthorizationState

@Composable
fun TeleDriveRoot(session: SessionUiState, sessionViewModel: SessionViewModel, settingsViewModel: SettingsViewModel) {
    when (val state = session.authorizationState) {
        AuthorizationState.Uninitialized,
        AuthorizationState.Closed,
        is AuthorizationState.Error,
        -> OnboardingScreen(
            errorMessage = session.actionError ?: (state as? AuthorizationState.Error)?.message,
            isConnecting = session.isConnecting,
            onConnect = sessionViewModel::connect,
        )

        AuthorizationState.Ready -> AuthenticatedNavHost(sessionViewModel = sessionViewModel, settingsViewModel = settingsViewModel)
        else -> AuthenticationScreen(
            state = state,
            errorMessage = session.actionError,
            onPhoneNumber = sessionViewModel::submitPhoneNumber,
            onCode = sessionViewModel::submitCode,
            onPassword = sessionViewModel::submitPassword,
            onEmail = sessionViewModel::submitEmail,
            onEmailCode = sessionViewModel::submitEmailCode,
            onCancel = sessionViewModel::cancel,
            onClearError = sessionViewModel::clearError,
        )
    }
}
