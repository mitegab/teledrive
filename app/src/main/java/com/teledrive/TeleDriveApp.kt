package com.teledrive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teledrive.session.SessionViewModel
import com.teledrive.settings.SettingsViewModel

@Composable
fun TeleDriveApp(
    sessionViewModel: SessionViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    val session by sessionViewModel.uiState.collectAsStateWithLifecycle()
    val settings by settingsViewModel.state.collectAsStateWithLifecycle()
    TeleDriveTheme(theme = settings.theme) {
        TeleDriveRoot(session = session, sessionViewModel = sessionViewModel, settingsViewModel = settingsViewModel)
    }
}
