package com.teledrive.files

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sort
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teledrive.core.model.FileKind
import com.teledrive.core.model.FileSort
import com.teledrive.core.model.LocalAvailability
import com.teledrive.core.model.RemoteAvailability
import com.teledrive.core.model.SortDirection
import com.teledrive.core.model.SortField
import com.teledrive.core.model.StoredFile

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileBrowserScreen(viewModel: FileLibraryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var selectedFile by remember { mutableStateOf<StoredFile?>(null) }
    var showFilterMenu by rememberSaveable { mutableStateOf(false) }
    var showSortMenu by rememberSaveable { mutableStateOf(false) }
    var confirmRemoteDelete by remember { mutableStateOf<StoredFile?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            viewModel.upload(uri)
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { picker.launch(arrayOf("*/*")) },
                modifier = Modifier.semantics { contentDescription = "Upload files" },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) { Icon(Icons.Rounded.CloudUpload, contentDescription = null) }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp)) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Files", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        if (state.hasSynced) "Indexed from Saved Messages" else "Local index not synchronized yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = viewModel::synchronize, enabled = !state.isSyncing) { Icon(Icons.Rounded.Refresh, contentDescription = "Sync files") }
                IconButton(onClick = viewModel::toggleGridView) { Icon(if (state.isGrid) Icons.Rounded.ViewList else Icons.Rounded.GridView, contentDescription = "Change file view") }
            }
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = state.query.text,
                onValueChange = viewModel::setSearchText,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                placeholder = { Text("Search your local index") },
                shape = RoundedCornerShape(18.dp),
            )
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.query.kinds.isEmpty(), onClick = viewModel::clearKinds, label = { Text("All") })
                listOf(FileKind.DOCUMENT, FileKind.IMAGE, FileKind.VIDEO, FileKind.AUDIO, FileKind.ARCHIVE).forEach { kind ->
                    FilterChip(selected = kind in state.query.kinds, onClick = { viewModel.toggleKind(kind) }, label = { Text(kind.label()) })
                }
                AssistChip(onClick = { showFilterMenu = true }, label = { Text("Filters") }, leadingIcon = { Icon(Icons.Rounded.Tune, contentDescription = null) })
                AssistChip(onClick = { showSortMenu = true }, label = { Text("Sort") }, leadingIcon = { Icon(Icons.Rounded.Sort, contentDescription = null) })
            }
            Box {
                DropdownMenu(expanded = showFilterMenu, onDismissRequest = { showFilterMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Favorites only") },
                        leadingIcon = { Checkbox(checked = state.query.favoritesOnly, onCheckedChange = null) },
                        onClick = { viewModel.setFavoritesOnly(!state.query.favoritesOnly); showFilterMenu = false },
                    )
                }
                DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                    SortField.entries.forEach { field ->
                        DropdownMenuItem(text = { Text(field.label()) }, onClick = { viewModel.setSort(field); showSortMenu = false })
                    }
                    DropdownMenuItem(text = { Text(if (state.sort.direction == SortDirection.ASCENDING) "Descending" else "Ascending") }, onClick = { viewModel.toggleSortDirection(); showSortMenu = false })
                }
            }
            if (state.isSyncing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (state.errorMessage != null) {
                Card(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(state.errorMessage ?: "", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
                        IconButton(onClick = { viewModel.clearError(); viewModel.synchronize() }) { Icon(Icons.Rounded.Refresh, contentDescription = "Retry") }
                    }
                }
            }
            if (!state.hasSynced && state.files.isEmpty() && !state.isSyncing) {
                LibraryEmptyState(onSync = viewModel::synchronize, onUpload = { picker.launch(arrayOf("*/*")) })
            } else if (state.files.isEmpty() && !state.isSyncing) {
                EmptySearchState()
            } else if (state.isGrid) {
                LazyVerticalGrid(columns = GridCells.Adaptive(150.dp), contentPadding = PaddingValues(bottom = 100.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(state.files, key = { it.stableId }) { file ->
                        FileGridCard(file, onClick = { selectedFile = file }, onFavorite = { viewModel.setFavorite(file, !file.favorite) })
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.files, key = { it.stableId }) { file ->
                        FileListRow(file, onClick = { selectedFile = file }, onFavorite = { viewModel.setFavorite(file, !file.favorite) })
                    }
                }
            }
        }
    }

    selectedFile?.let { file ->
        FileDetailsDialog(
            file = file,
            onDismiss = { selectedFile = null },
            onFavorite = { viewModel.setFavorite(file, !file.favorite); selectedFile = null },
            onRemoveLocal = { viewModel.deleteLocalCopy(file); selectedFile = null },
            onDeleteRemote = { confirmRemoteDelete = file; selectedFile = null },
        )
    }
    confirmRemoteDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { confirmRemoteDelete = null },
            title = { Text("Delete from Saved Messages?") },
            text = { Text("This removes ${file.originalFilename} from your actual Telegram Saved Messages. Removing a local copy is a separate, safer action.") },
            confirmButton = { Button(onClick = { viewModel.deleteRemote(file); confirmRemoteDelete = null }) { Text("Delete remotely") } },
            dismissButton = { Button(onClick = { confirmRemoteDelete = null }) { Text("Keep file") } },
        )
    }
}

@Composable
private fun LibraryEmptyState(onSync: () -> Unit, onUpload: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 42.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Rounded.CloudUpload, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Build your library", style = MaterialTheme.typography.titleLarge)
        Text("Sync existing Saved Messages files or upload a new document.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onSync) { Text("Sync") }
            AssistChip(onClick = onUpload, label = { Text("Upload") })
        }
    }
}

@Composable
private fun EmptySearchState() {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 42.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Rounded.Search, contentDescription = null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
        Text("Nothing matches this view", style = MaterialTheme.typography.titleMedium)
        Text("Search covers the synchronized local index, not every message on Telegram.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FileListRow(file: StoredFile, onClick: () -> Unit, onFavorite: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(18.dp)) {
        Row(modifier = Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FileKindIcon(file.kind, Modifier.size(38.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(file.originalFilename, maxLines = 1, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text("${file.kind.label()} · ${file.sizeBytes.formatBytes()} · ${file.savedAtEpochSeconds.formatDate()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (file.localAvailability == LocalAvailability.AVAILABLE) Text("Available offline", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                if (file.remoteAvailability == RemoteAvailability.DELETED) Text("Remote file unavailable", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            }
            IconButton(onClick = onFavorite) { Icon(if (file.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, contentDescription = if (file.favorite) "Remove favorite" else "Add favorite") }
        }
    }
}

@Composable
private fun FileGridCard(file: StoredFile, onClick: () -> Unit, onFavorite: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(20.dp)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                FileKindIcon(file.kind, Modifier.size(34.dp))
                IconButton(onClick = onFavorite, modifier = Modifier.size(32.dp)) { Icon(if (file.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, contentDescription = "Favorite") }
            }
            Text(file.originalFilename, maxLines = 2, minLines = 2, style = MaterialTheme.typography.titleSmall)
            Text(file.sizeBytes.formatBytes(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FileKindIcon(kind: FileKind, modifier: Modifier = Modifier) {
    val (icon, tint) = when (kind) {
        FileKind.IMAGE -> Icons.Rounded.Image to MaterialTheme.colorScheme.primary
        FileKind.VIDEO -> Icons.Rounded.Movie to MaterialTheme.colorScheme.tertiary
        FileKind.AUDIO -> Icons.Rounded.AudioFile to MaterialTheme.colorScheme.secondary
        FileKind.ARCHIVE -> Icons.Rounded.Archive to MaterialTheme.colorScheme.error
        FileKind.TEXT -> Icons.Rounded.Description to MaterialTheme.colorScheme.primary
        else -> Icons.Rounded.Description to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp), color = tint.copy(alpha = .14f)) { Icon(icon, contentDescription = null, modifier = Modifier.padding(8.dp), tint = tint) }
}

@Composable
private fun FileDetailsDialog(file: StoredFile, onDismiss: () -> Unit, onFavorite: () -> Unit, onRemoveLocal: () -> Unit, onDeleteRemote: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.originalFilename) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${file.kind.label()} · ${file.sizeBytes.formatBytes()}")
                Text("Saved ${file.savedAtEpochSeconds.formatDate()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (file.localAvailability == LocalAvailability.AVAILABLE) "A local cached copy is available." else "No local cached copy.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Remote message: ${file.remote.value}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = onFavorite) { Text(if (file.favorite) "Unfavorite" else "Favorite") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (file.localAvailability == LocalAvailability.AVAILABLE) IconButton(onClick = onRemoveLocal) { Icon(Icons.Rounded.Description, contentDescription = "Remove local copy") }
                IconButton(onClick = onDeleteRemote) { Icon(Icons.Rounded.Archive, contentDescription = "Delete remote message") }
            }
        },
    )
}

private fun FileKind.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }
private fun SortField.label(): String = name.lowercase().replaceFirstChar { it.uppercase() }
private fun Long?.formatBytes(): String = when {
    this == null -> "Size unknown"
    this < 1024 -> "$this B"
    this < 1024 * 1024 -> "${this / 1024} KB"
    this < 1024 * 1024 * 1024 -> "${this / (1024 * 1024)} MB"
    else -> "${this / (1024 * 1024 * 1024)} GB"
}
private fun Long.formatDate(): String = if (this <= 0) "Date unknown" else java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(this * 1000))
