package com.teledrive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teledrive.preferences.AppTheme
import com.teledrive.settings.SettingsViewModel

@Composable
fun SettingsScreen(settingsViewModel: SettingsViewModel, onLogout: () -> Unit) {
    val state by settingsViewModel.state.collectAsStateWithLifecycle()
    var confirmClearCache by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Text("Telegram connection", style = MaterialTheme.typography.titleMedium)
        Text(
            "TeleDrive uses official TDLib and your Saved Messages. Authentication data stays in TDLib's app-private encrypted database.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = onLogout) { Text("Log out of Telegram") }

        HorizontalDivider()
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            AppTheme.entries.forEach { theme ->
                if (state.theme == theme) Button(onClick = { settingsViewModel.setTheme(theme) }) { Text(theme.label()) }
                else OutlinedButton(onClick = { settingsViewModel.setTheme(theme) }) { Text(theme.label()) }
            }
        }

        HorizontalDivider()
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Sync folders and favorites", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Uses reserved metadata messages in Saved Messages. This is eventual and best-effort, not a Telegram filesystem.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = state.syncMetadata, onCheckedChange = settingsViewModel::setMetadataSync)
        }

        HorizontalDivider()
        Text("Local data", style = MaterialTheme.typography.titleMedium)
        Text("Clear downloaded/cache copies tracked by TeleDrive. Remote Saved Messages are not changed.", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { confirmClearCache = true }, enabled = !state.isClearingCache) {
            if (state.isClearingCache) CircularProgressIndicator(strokeWidth = 2.dp)
            else Text("Clear local cache")
        }
        if (state.cacheCleared) Text("Local cache cleared.", color = MaterialTheme.colorScheme.primary)
        state.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    if (confirmClearCache) {
        AlertDialog(
            onDismissRequest = { confirmClearCache = false },
            title = { Text("Clear local cache?") },
            text = { Text("This removes cached local copies from this device only. Your Telegram messages remain untouched and can be downloaded again.") },
            confirmButton = {
                Button(onClick = { confirmClearCache = false; settingsViewModel.clearLocalCache() }) { Text("Clear cache") }
            },
            dismissButton = { TextButton(onClick = { confirmClearCache = false }) { Text("Cancel") } },
        )
    }
}

private fun AppTheme.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }
