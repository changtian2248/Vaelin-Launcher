package com.vaelin.launcher.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

/** Bottom navigation for the currently implemented top-level launcher destinations. */
enum class LauncherTopLevelTab { INSTANCES, RESOURCES }

@Composable
fun LauncherBottomNavigation(
    selected: LauncherTopLevelTab,
    onInstances: () -> Unit,
    onResources: () -> Unit,
    onSettings: () -> Unit,
) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        NavigationBarItem(
            selected = selected == LauncherTopLevelTab.INSTANCES,
            onClick = onInstances,
            icon = { Icon(Icons.Filled.Home, contentDescription = null) },
            label = { Text("实例") },
        )
        NavigationBarItem(
            selected = selected == LauncherTopLevelTab.RESOURCES,
            onClick = onResources,
            icon = { Icon(Icons.Filled.Search, contentDescription = null) },
            label = { Text("资源") },
        )
        NavigationBarItem(
            selected = false,
            onClick = onSettings,
            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
            label = { Text("设置") },
        )
    }
}
