package com.teledrive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teledrive.session.SessionViewModel

@Composable
fun TeleDriveApp(sessionViewModel: SessionViewModel = hiltViewModel()) {
    val session by sessionViewModel.uiState.collectAsStateWithLifecycle()
    TeleDriveTheme {
        TeleDriveRoot(session = session, sessionViewModel = sessionViewModel)
    }
}
