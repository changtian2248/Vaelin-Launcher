package com.vaelin.launcher.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vaelin.launcher.ui.LaunchUiState
import com.vaelin.launcher.ui.rendererNameOf
import com.vaelin.launcher.ui.theme.ThemeMode
import com.vaelin.startup.profile.GameDirectoryMode
import com.vaelin.startup.profile.VersionProfile

/**
 * 实例列表页。
 *
 * 首页以最近启动实例作为快速启动卡，下方保留完整实例列表。
 * 长按实例仅从清单移除；游戏目录中的内容保留，不在此页面做破坏性文件操作。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VersionListScreen(
    profiles: List<VersionProfile>,
    themeMode: ThemeMode,
    gameDirectoryMode: GameDirectoryMode,
    launchState: LaunchUiState,
    onCycleTheme: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onQuickLaunch: (VersionProfile) -> Unit,
    onCreateProfile: () -> Unit,
    onDeleteProfile: (VersionProfile) -> Unit,
    onOpenDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 待确认删除的实例。用 id 而不是对象，避免列表刷新后弹窗里还是旧快照
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    val pendingDelete = pendingDeleteId?.let { id -> profiles.firstOrNull { it.id == id } }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Vaelin Launcher", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "Minecraft Java · Android",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onCycleTheme) {
                        Text("主题：${themeMode.displayName()}")
                    }
                },
            )
        },
        bottomBar = {
            LauncherBottomNavigation(
                selected = LauncherTopLevelTab.INSTANCES,
                onInstances = {},
                onResources = onOpenDownload,
                onSettings = onOpenSettings,
            )
        },
        floatingActionButton = {
            if (profiles.isEmpty()) {
                ExtendedFloatingActionButton(onClick = onCreateProfile) {
                    Text("新建实例")
                }
            }
        },
    ) { innerPadding ->
        if (profiles.isEmpty()) {
            EmptyState(modifier = Modifier.fillMaxSize().padding(innerPadding))
        } else {
            val featuredProfile = profiles.maxByOrNull { it.lastPlayedAt }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                featuredProfile?.let { featured ->
                    item(key = "featured-${featured.id}") {
                        FeaturedProfileCard(
                            profile = featured,
                            launchState = launchState,
                            onOpen = { onOpenProfile(featured.id) },
                            onLaunch = { onQuickLaunch(featured) },
                        )
                    }
                }
                item(key = "profiles-section-heading") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("我的实例", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("${profiles.size} 个配置 · ${gameDirectoryMode.displayName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = onCreateProfile) { Text("+ 新建") }
                    }
                }
                items(items = profiles, key = { it.id }) { profile ->
                    VersionCard(
                        profile = profile,
                        gameDirectoryMode = gameDirectoryMode,
                        onClick = { onOpenProfile(profile.id) },
                        onLongClick = { pendingDeleteId = profile.id },
                    )
                }
            }
        }
    }

    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("删除实例") },
            text = {
                Text("将从启动器列表移除「${pendingDelete.name}」及其实例配置，但不会删除游戏目录中的模组、存档或配置。隔离目录会保留；以后新建实例会使用新的目录 ID，避免旧内容串入。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteProfile(pendingDelete)
                        pendingDeleteId = null
                    },
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun FeaturedProfileCard(
    profile: VersionProfile,
    launchState: LaunchUiState,
    onOpen: () -> Unit,
    onLaunch: () -> Unit,
) {
    val profileLaunchState = if (launchState.profileId == profile.id) launchState else LaunchUiState()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                if (profile.lastPlayedAt > 0L) "最近游玩" else "快速启动",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(profile.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                "Minecraft ${profile.minecraftVersion} · ${profile.modLoader} · ${rendererNameOf(profile)}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                "${if (profile.lastPlayedAt > 0L) "最近游玩" else "尚未启动"} · 堆内存 ${profile.memoryMB} MB · ${formatProfileLastPlayed(profile.lastPlayedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f),
            )
            if (profileLaunchState.isPreparing) {
                Text("${profileLaunchState.stageLabel} · ${profileLaunchState.percent}%", style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.LinearProgressIndicator(
                    progress = { profileLaunchState.percent.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (profileLaunchState.error != null) {
                Text("启动失败：${profileLaunchState.error}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onLaunch, enabled = !profileLaunchState.isPreparing) {
                    Text(if (profileLaunchState.isPreparing) "准备中…" else "启动实例")
                }
                OutlinedButton(onClick = onOpen) { Text("实例详情") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VersionCard(
    profile: VersionProfile,
    gameDirectoryMode: GameDirectoryMode,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        // 纯黑主题下 tonal elevation 不产生视觉差异，必须靠描边把卡片分出来
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = profile.minecraftVersion,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = buildString {
                    append("加载器 "); append(profile.modLoader)
                    append(" · 渲染器 "); append(rendererNameOf(profile))
                    append(" · 内存 "); append(profile.memoryMB); append(" MB")
                    append(" · "); append(gameDirectoryMode.displayName)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun formatProfileLastPlayed(epochMillis: Long): String {
    if (epochMillis <= 0L) return "从未启动"
    return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(epochMillis))
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "还没有实例",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "点右下角「新建实例」创建第一个",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
