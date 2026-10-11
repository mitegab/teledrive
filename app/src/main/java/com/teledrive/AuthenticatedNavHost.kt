package com.teledrive

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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.teledrive.session.SessionViewModel
import com.teledrive.files.FileBrowserScreen

private data class TopLevelDestination(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

@Composable
fun AuthenticatedNavHost(sessionViewModel: SessionViewModel) {
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
    Scaffold(
        bottomBar = {
            NavigationBar {
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
        },
    ) { padding ->
        NavHost(navController, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") { HomeScreen(onOpenFiles = { navController.navigate("files") }, onLogout = sessionViewModel::logout) }
            composable("files") { FileBrowserScreen() }
            composable("transfers") { TransfersScreen() }
            composable("settings") { SettingsScreen(onLogout = sessionViewModel::logout) }
        }
    }
}
