package com.teledrive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun TransfersScreen() {
    EmptyFeatureScreen(
        icon = Icons.Rounded.SwapVert,
        title = "No active transfers",
        body = "Uploads and downloads will appear here with progress from TDLib, not simulated progress.",
    )
}

@Composable
fun SettingsScreen(onLogout: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text("Telegram connection", style = MaterialTheme.typography.titleMedium)
        Text("Connected through official TDLib. Local cache, folder metadata, and remote deletion remain separate actions.", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = onLogout) { Text("Log out of Telegram") }
    }
}

@Composable
private fun EmptyFeatureScreen(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, modifier = Modifier.padding(top = 80.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
