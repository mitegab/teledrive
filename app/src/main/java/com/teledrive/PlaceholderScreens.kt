package com.teledrive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(onLogout: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text("Telegram connection", style = MaterialTheme.typography.titleMedium)
        Text("Connected through official TDLib. Local cache, folder metadata, and remote deletion remain separate actions.", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onLogout) { Text("Log out of Telegram") }
    }
}
