package com.teledrive.transfers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teledrive.core.model.Transfer
import com.teledrive.core.model.TransferDirection
import com.teledrive.core.model.TransferState

@Composable
fun TransfersScreen(viewModel: TransferViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Transfers", style = MaterialTheme.typography.headlineMedium)
        Text("Progress is reported by TDLib. Android may still stop background work under current platform rules.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.errorMessage != null) {
            Text(state.errorMessage ?: "", color = MaterialTheme.colorScheme.error)
            IconButton(onClick = viewModel::clearError) { Icon(Icons.Rounded.Refresh, contentDescription = "Dismiss error") }
        }
        if (state.transfers.isEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
                Text("No transfers yet", style = MaterialTheme.typography.titleLarge)
                Text("Uploads and downloads will stay visible here after they are queued.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(state.transfers, key = { it.id.value }) { transfer -> TransferRow(transfer, viewModel::cancel, viewModel::retry) }
            }
        }
    }
}

@Composable
private fun TransferRow(transfer: Transfer, onCancel: (Transfer) -> Unit, onRetry: (Transfer) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .45f))) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(if (transfer.direction == TransferDirection.UPLOAD) Icons.Rounded.CloudUpload else Icons.Rounded.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(modifier = Modifier.weight(1f)) {
                    Text(transfer.filename, style = MaterialTheme.typography.titleMedium)
                    Text("${transfer.direction.name.lowercase().replaceFirstChar { it.uppercase() }} · ${transfer.state.label()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (transfer.state == TransferState.QUEUED || transfer.state == TransferState.RUNNING) {
                    IconButton(onClick = { onCancel(transfer) }) { Icon(Icons.Rounded.Cancel, contentDescription = "Cancel transfer") }
                }
            }
            if (transfer.state == TransferState.RUNNING) {
                LinearProgressIndicator(progress = { transfer.progress ?: 0f }, modifier = Modifier.fillMaxWidth())
                Text("${transfer.bytesTransferred.formatBytes()} of ${transfer.totalBytes.formatBytes()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (transfer.state == TransferState.FAILED) {
                val errorMessage = transfer.errorMessage
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (errorMessage != null) Text(errorMessage, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    IconButton(onClick = { onRetry(transfer) }) { Icon(Icons.Rounded.Refresh, contentDescription = "Retry transfer") }
                }
            }
        }
    }
}

private fun TransferState.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }
private fun Long?.formatBytes(): String = when {
    this == null -> "unknown size"
    this < 1024 -> "$this B"
    this < 1024 * 1024 -> "${this / 1024} KB"
    this < 1024 * 1024 * 1024 -> "${this / (1024 * 1024)} MB"
    else -> "${this / (1024 * 1024 * 1024)} GB"
}
