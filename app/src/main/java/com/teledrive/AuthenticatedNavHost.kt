package com.teledrive

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import android.net.Uri
import com.teledrive.files.viewer.ViewerScreen
import com.teledrive.playback.MediaPlayerViewModel
import com.teledrive.playback.PlaybackStatus
import com.teledrive.playback.ui.AudioPlayerScreen
import com.teledrive.playback.ui.MiniPlayer
import com.teledrive.playback.ui.VideoPlayerScreen
import com.teledrive.session.SessionViewModel
import com.teledrive.files.FileBrowserScreen
import com.teledrive.transfers.TransfersScreen
import com.teledrive.settings.SettingsViewModel

private data class TopLevelDestination(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun AuthenticatedNavHost(sessionViewModel: SessionViewModel, settingsViewModel: SettingsViewModel) {
    val navController = rememberNavController()
    val destinations = remember {
        listOf(
            TopLevelDestination("home", "Overview", Icons.Rounded.CloudQueue),
            TopLevelDestination("files", "Files", Icons.Rounded.Folder),
            TopLevelDestination("transfers", "Transfers", Icons.Rounded.SwapVert),
            TopLevelDestination("settings", "Settings", Icons.Rounded.Settings),
        )
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val hideBottomBar = currentRoute?.startsWith("viewer/") == true || currentRoute?.startsWith("video/") == true || currentRoute?.startsWith("audio") == true
    // Observe playback for smart mini-player expansion
    val playerVM: MediaPlayerViewModel = hiltViewModel()
    val playerUi by playerVM.uiState.collectAsStateWithLifecycle()
    Scaffold(
        bottomBar = {
            Column {
                MiniPlayer(
                    onExpand = {
                        val current = playerUi.queue.currentItem
                        if (current != null && current.file.kind == com.teledrive.core.model.FileKind.VIDEO) {
                            // Expand to video with queue context
                            val ids = playerUi.queue.items.map { it.file.stableId }
                            navController.navigate("video/${Uri.encode(current.file.stableId)}?all=${Uri.encode(ids.joinToString(","))}")
                        } else {
                            navController.navigate("audio")
                        }
                    }
                )
                if (!hideBottomBar) NavigationBar {
                    destinations.forEach { destination ->
                        NavigationBarItem(
                            selected = currentRoute == destination.route,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo("home") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(navController, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") { HomeScreen(onOpenFiles = { navController.navigate("files") }, onLogout = sessionViewModel::logout) }
            composable("files") {
                FileBrowserScreen(
                    onOpenViewer = { id, imageIds -> navController.navigate("viewer/${Uri.encode(id)}?images=${Uri.encode(imageIds.joinToString(","))}") },
                    onOpenVideo = { id, allIds -> navController.navigate("video/${Uri.encode(id)}?all=${Uri.encode(allIds.joinToString(","))}") },
                    onOpenAudio = { id, allIds -> navController.navigate("audio/${Uri.encode(id)}?all=${Uri.encode(allIds.joinToString(","))}") }
                )
            }
            composable("viewer/{stableId}?images={images}", arguments = listOf(navArgument("stableId") { type = NavType.StringType }, navArgument("images") { type = NavType.StringType; defaultValue = "" })) { entry ->
                val imageIds = Uri.decode(entry.arguments?.getString("images").orEmpty()).split(',').filter(String::isNotBlank)
                ViewerScreen(stableId = Uri.decode(entry.arguments?.getString("stableId").orEmpty()), imageIds = imageIds, onBack = { navController.popBackStack() })
            }
            composable("video/{stableId}?all={all}", arguments = listOf(navArgument("stableId") { type = NavType.StringType }, navArgument("all") { type = NavType.StringType; defaultValue = "" })) { entry ->
                val stableId = Uri.decode(entry.arguments?.getString("stableId").orEmpty())
                val allIds = Uri.decode(entry.arguments?.getString("all").orEmpty()).split(',').filter(String::isNotBlank)
                VideoPlayerScreen(stableId = stableId, allIds = allIds, onBack = { navController.popBackStack() })
            }
            composable("audio") {
                AudioPlayerScreen(onBack = { navController.popBackStack() })
            }
            composable("audio/{stableId}?all={all}", arguments = listOf(navArgument("stableId") { type = NavType.StringType }, navArgument("all") { type = NavType.StringType; defaultValue = "" })) { entry ->
                val stableId = Uri.decode(entry.arguments?.getString("stableId").orEmpty())
                val allIds = Uri.decode(entry.arguments?.getString("all").orEmpty()).split(',').filter(String::isNotBlank)
                AudioPlayerScreen(stableId = stableId, allIds = allIds, onBack = { navController.popBackStack() })
            }
            composable("transfers") { TransfersScreen() }
            composable("settings") { SettingsScreen(settingsViewModel = settingsViewModel, onLogout = sessionViewModel::logout) }
        }
    }
}
