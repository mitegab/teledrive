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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teledrive.core.model.FileKind
import com.teledrive.core.model.FileSort
import com.teledrive.core.model.Folder
import com.teledrive.core.model.LocalAvailability
import com.teledrive.core.model.RemoteAvailability
import com.teledrive.core.model.SortDirection
import com.teledrive.core.model.SortField
import com.teledrive.core.model.StoredFile
import com.teledrive.playback.MediaPlayerViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileBrowserScreen(
    onOpenViewer: (String, List<String>) -> Unit = { _, _ -> },
    onOpenVideo: (String, List<String>) -> Unit = { id, ids -> onOpenViewer(id, ids) },
    onOpenAudio: (String, List<String>) -> Unit = { id, ids -> onOpenViewer(id, ids) },
    viewModel: FileLibraryViewModel = hiltViewModel(),
    playerViewModel: MediaPlayerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val playerState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var selectedFile by remember { mutableStateOf<StoredFile?>(null) }
    var showFilterMenu by rememberSaveable { mutableStateOf(false) }
    var showSortMenu by rememberSaveable { mutableStateOf(false) }
    var showCreateFolder by rememberSaveable { mutableStateOf(false) }
    var newFolderName by rememberSaveable { mutableStateOf("") }
    var folderForActions by remember { mutableStateOf<Folder?>(null) }
    var folderBeingRenamed by remember { mutableStateOf<Folder?>(null) }
    var showRenameFolder by rememberSaveable { mutableStateOf(false) }
    var renameFolderName by rememberSaveable { mutableStateOf("") }
    var confirmFolderDelete by remember { mutableStateOf<Folder?>(null) }
    var confirmRemoteDelete by remember { mutableStateOf<StoredFile?>(null) }
    var exportFile by remember { mutableStateOf<StoredFile?>(null) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { destination ->
        val file = exportFile
        val path = file?.localPath
        if (destination != null && path != null) viewModel.export(path, destination)
        exportFile = null
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            viewModel.upload(uri)
        }
    }

    fun handleOpen(file: StoredFile) {
        val router = com.teledrive.files.viewer.DefaultViewerRouter().route(file)
        val mediaIds = state.files.filter { it.kind == FileKind.VIDEO || it.kind == FileKind.AUDIO }.map { it.stableId }
        val imageIds = state.files.filter { com.teledrive.files.viewer.DefaultViewerRouter().route(it) == com.teledrive.files.viewer.ViewerTarget.InAppImage }.map { it.stableId }
        when (router) {
            is com.teledrive.files.viewer.ViewerTarget.InAppImage -> onOpenViewer(file.stableId, imageIds)
            is com.teledrive.files.viewer.ViewerTarget.InAppVideo -> onOpenVideo(file.stableId, mediaIds.ifEmpty { listOf(file.stableId) })
            is com.teledrive.files.viewer.ViewerTarget.InAppAudio -> {
                // Audio uses same VideoPlayerScreen route but AudioPlayerScreen can also be opened via queue
                // Use video route for unified queue handling, but also allow audio-specific screen after playback starts
                onOpenAudio(file.stableId, mediaIds.ifEmpty { listOf(file.stableId) })
            }
            else -> selectedFile = file
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
                        when {
                            state.syncIncomplete -> "Sync stopped early; tap refresh to continue"
                            state.hasSynced -> "Indexed from Saved Messages"
                            else -> "Local index not synchronized yet"
                        },
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
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = state.query.folderId == null, onClick = { viewModel.setFolder(null) }, label = { Text("All folders") })
                state.folders.forEach { folder ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = state.query.folderId == folder.id, onClick = { viewModel.setFolder(folder.id) }, label = { Text(folder.name) })
                        IconButton(onClick = { folderForActions = folder }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "Manage ${folder.name}")
                        }
                    }
                }
                AssistChip(onClick = { showCreateFolder = true }, label = { Text("New folder") }, leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, contentDescription = null) })
            }
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.query.kinds.isEmpty(), onClick = viewModel::clearKinds, label = { Text("All types") })
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
                        val isPlaying = playerState.queue.currentItem?.file?.stableId == file.stableId
                        FileGridCard(file, isPlaying = isPlaying, onClick = { handleOpen(file) }, onFavorite = { viewModel.setFavorite(file, !file.favorite) })
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.files, key = { it.stableId }) { file ->
                        val isPlaying = playerState.queue.currentItem?.file?.stableId == file.stableId
                        FileListRow(file, isPlaying = isPlaying, onClick = { handleOpen(file) }, onFavorite = { viewModel.setFavorite(file, !file.favorite) })
                    }
                }
            }
        }
    }

    if (showCreateFolder) {
        AlertDialog(
            onDismissRequest = { showCreateFolder = false },
            title = { Text("Create virtual folder") },
            text = {
                OutlinedTextField(
                    value = newFolderName,
                    onValueChange = { newFolderName = it },
                    singleLine = true,
                    label = { Text("Folder name") },
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.createFolder(newFolderName)
                        newFolderName = ""
                        showCreateFolder = false
                    },
                    enabled = newFolderName.trim().isNotEmpty(),
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showCreateFolder = false }) { Text("Cancel") } },
        )
    }

    folderForActions?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderForActions = null },
            title = { Text(folder.name) },
            text = { Text("Manage this virtual folder. File assignments stay local unless metadata sync is enabled.") },
            confirmButton = {
                TextButton(onClick = {
                    renameFolderName = folder.name
                    folderBeingRenamed = folder
                    showRenameFolder = true
                    folderForActions = null
                }) { Text("Rename") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmFolderDelete = folder; folderForActions = null }) { Text("Delete") }
                    TextButton(onClick = { folderForActions = null }) { Text("Close") }
                }
            },
        )
    }
    if (showRenameFolder) {
        AlertDialog(
            onDismissRequest = { showRenameFolder = false },
            title = { Text("Rename folder") },
            text = { OutlinedTextField(value = renameFolderName, onValueChange = { renameFolderName = it }, singleLine = true, label = { Text("Folder name") }) },
            confirmButton = {
                Button(onClick = {
                    folderBeingRenamed?.let { viewModel.renameFolder(it, renameFolderName) }
                    folderBeingRenamed = null
                    showRenameFolder = false
                }, enabled = renameFolderName.trim().isNotEmpty()) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { showRenameFolder = false }) { Text("Cancel") } },
        )
    }

    confirmFolderDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { confirmFolderDelete = null },
            title = { Text("Delete ${folder.name}?") },
            text = { Text("Files will remain in Saved Messages and will be unassigned from this virtual folder. This does not delete remote files.") },
            confirmButton = {
                Button(onClick = { if (state.query.folderId == folder.id) viewModel.setFolder(null); viewModel.deleteFolder(folder); confirmFolderDelete = null }) { Text("Delete folder") }
            },
            dismissButton = { TextButton(onClick = { confirmFolderDelete = null }) { Text("Cancel") } },
        )
    }
    selectedFile?.let { file ->
        FileDetailsDialog(
            file = file,
            onDismiss = { selectedFile = null },
            folders = state.folders,
            onFavorite = { viewModel.setFavorite(file, !file.favorite); selectedFile = null },
            onMove = { folderId -> viewModel.moveFile(file, folderId); selectedFile = null },
            onPreview = {
                val router = com.teledrive.files.viewer.DefaultViewerRouter().route(file)
                when (router) {
                    is com.teledrive.files.viewer.ViewerTarget.InAppImage -> onOpenViewer(file.stableId, state.files.filter { com.teledrive.files.viewer.DefaultViewerRouter().route(it) == com.teledrive.files.viewer.ViewerTarget.InAppImage }.map { it.stableId })
                    is com.teledrive.files.viewer.ViewerTarget.InAppVideo -> onOpenVideo(file.stableId, state.files.filter { it.kind == FileKind.VIDEO || it.kind == FileKind.AUDIO }.map { it.stableId })
                    is com.teledrive.files.viewer.ViewerTarget.InAppAudio -> onOpenAudio(file.stableId, state.files.filter { it.kind == FileKind.VIDEO || it.kind == FileKind.AUDIO }.map { it.stableId })
                    else -> {}
                }
                selectedFile = null
            },
            onOpen = { viewModel.open(file); selectedFile = null },
            onShare = { viewModel.open(file, share = true); selectedFile = null },
            onDownload = { viewModel.download(file); selectedFile = null },
            onExport = { exportFile = file; selectedFile = null; exportPicker.launch(file.originalFilename) },
            onRemoveLocal = { viewModel.deleteLocalCopy(file); selectedFile = null },
            onDeleteRemote = { confirmRemoteDelete = file; selectedFile = null },
            onPlayNow = {
                val mediaIds = state.files.filter { it.kind == FileKind.VIDEO || it.kind == FileKind.AUDIO }.map { it.stableId }
                if (file.kind == FileKind.VIDEO) onOpenVideo(file.stableId, mediaIds) else onOpenAudio(file.stableId, mediaIds)
                selectedFile = null
            },
            onAddToQueue = {
                playerViewModel.addToQueue(file.stableId, false)
                selectedFile = null
            },
            onPlayNext = {
                playerViewModel.addToQueue(file.stableId, true)
                selectedFile = null
            },
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
private fun FileListRow(file: StoredFile, isPlaying: Boolean = false, onClick: () -> Unit, onFavorite: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = if (isPlaying) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
        Row(modifier = Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FileKindIcon(file.kind, Modifier.size(38.dp), isPlaying)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(file.originalFilename, maxLines = 1, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f, fill = false))
                    if (isPlaying) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "Now playing", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
                Text("${file.kind.label()} · ${file.sizeBytes.formatBytes()} · ${file.savedAtEpochSeconds.formatDate()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (file.localAvailability == LocalAvailability.AVAILABLE) Text("Available offline", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    else if (file.localAvailability == LocalAvailability.DOWNLOADING) Text("Downloading…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    if (file.remoteAvailability == RemoteAvailability.DELETED) Text("Remote file unavailable", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    if (file.tdlibFileId == null) Text("Not downloadable", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
            IconButton(onClick = onFavorite) { Icon(if (file.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, contentDescription = if (file.favorite) "Remove favorite" else "Add favorite") }
        }
    }
}

@Composable
private fun FileGridCard(file: StoredFile, isPlaying: Boolean = false, onClick: () -> Unit, onFavorite: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = if (isPlaying) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                FileKindIcon(file.kind, Modifier.size(34.dp), isPlaying)
                IconButton(onClick = onFavorite, modifier = Modifier.size(32.dp)) { Icon(if (file.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, contentDescription = "Favorite") }
            }
            Text(file.originalFilename, maxLines = 2, minLines = 2, style = MaterialTheme.typography.titleSmall)
            Text(file.sizeBytes.formatBytes(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (isPlaying) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Now playing", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun FileKindIcon(kind: FileKind, modifier: Modifier = Modifier, isPlaying: Boolean = false) {
    val (icon, tint) = when (kind) {
        FileKind.IMAGE -> Icons.Rounded.Image to MaterialTheme.colorScheme.primary
        FileKind.VIDEO -> Icons.Rounded.Movie to MaterialTheme.colorScheme.tertiary
        FileKind.AUDIO -> Icons.Rounded.AudioFile to MaterialTheme.colorScheme.secondary
        FileKind.ARCHIVE -> Icons.Rounded.Archive to MaterialTheme.colorScheme.error
        FileKind.TEXT -> Icons.Rounded.Description to MaterialTheme.colorScheme.primary
        else -> Icons.Rounded.Description to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val bg = if (isPlaying) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else tint.copy(alpha = .14f)
    Surface(modifier = modifier, shape = RoundedCornerShape(12.dp), color = bg) { Icon(icon, contentDescription = null, modifier = Modifier.padding(8.dp), tint = tint) }
}

@Composable
private fun FileDetailsDialog(
    file: StoredFile,
    folders: List<Folder>,
    onDismiss: () -> Unit,
    onFavorite: () -> Unit,
    onMove: (String?) -> Unit,
    onPreview: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDownload: () -> Unit,
    onExport: () -> Unit,
    onRemoveLocal: () -> Unit,
    onDeleteRemote: () -> Unit,
    onPlayNow: () -> Unit = {},
    onAddToQueue: () -> Unit = {},
    onPlayNext: () -> Unit = {},
) {
    var moveMenuExpanded by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(file.originalFilename) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${file.kind.label()} · ${file.sizeBytes.formatBytes()}")
                Text("Saved ${file.savedAtEpochSeconds.formatDate()}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (file.localAvailability == LocalAvailability.AVAILABLE) "A local cached copy is available." else "No local cached copy.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Remote message: ${file.remote.value}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (file.kind == FileKind.VIDEO || file.kind == FileKind.AUDIO) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onPlayNow) { Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Play") }
                        TextButton(onClick = onAddToQueue) { Icon(Icons.Rounded.PlaylistAdd, contentDescription = null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Add to queue") }
                        TextButton(onClick = onPlayNext) { Text("Play next") }
                    }
                }
                Box {
                    TextButton(onClick = { moveMenuExpanded = true }) { Text("Move to folder") }
                    DropdownMenu(expanded = moveMenuExpanded, onDismissRequest = { moveMenuExpanded = false }) {
                        DropdownMenuItem(text = { Text("No folder") }, onClick = { moveMenuExpanded = false; onMove(null) })
                        folders.forEach { folder ->
                            DropdownMenuItem(text = { Text(folder.name) }, onClick = { moveMenuExpanded = false; onMove(folder.id) })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (file.localAvailability == LocalAvailability.AVAILABLE && (file.kind in setOf(FileKind.IMAGE, FileKind.VIDEO, FileKind.AUDIO) || file.mimeType?.startsWith("text/") == true || file.mimeType == "application/pdf")) TextButton(onClick = onPreview) { Text("Open in app") }
                    TextButton(onClick = onOpen) { Text(if (file.localAvailability == LocalAvailability.AVAILABLE) "Open with another app" else "Download and open with another app") }
                    TextButton(onClick = onShare) { Text(if (file.localAvailability == LocalAvailability.AVAILABLE) "Share" else "Download and share") }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onFavorite) { Text(if (file.favorite) "Unfavorite" else "Favorite") }
                if (file.localAvailability == LocalAvailability.AVAILABLE) {
                    Button(onClick = onExport) { Icon(Icons.Rounded.CloudUpload, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Export") }
                } else {
                    Button(onClick = onDownload) { Icon(Icons.Rounded.CloudDownload, contentDescription = null); Spacer(Modifier.width(6.dp)); Text("Download") }
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (file.localAvailability == LocalAvailability.AVAILABLE) IconButton(onClick = onRemoveLocal) { Icon(Icons.Rounded.Description, contentDescription = "Remove local copy") }
                IconButton(onClick = onDeleteRemote) { Icon(Icons.Rounded.Archive, contentDescription = "Delete remote message") }
                TextButton(onClick = onDismiss) { Text("Close") }
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
