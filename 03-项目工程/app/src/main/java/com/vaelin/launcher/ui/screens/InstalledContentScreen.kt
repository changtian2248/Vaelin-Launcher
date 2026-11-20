package com.vaelin.launcher.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import com.vaelin.launcher.ui.InstalledContentState
import com.vaelin.launcher.ui.ManagedContentEntry
import com.vaelin.launcher.ui.ManagedContentType
import com.vaelin.startup.profile.GameDirectoryMode
import com.vaelin.startup.profile.VersionProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstalledContentScreen(
    profile: VersionProfile,
    gameDirectoryMode: GameDirectoryMode,
    state: InstalledContentState,
    visibleEntries: List<ManagedContentEntry>,
    onTypeChange: (ManagedContentType) -> Unit,
    onQueryChange: (String) -> Unit,
    onToggle: (ManagedContentEntry) -> Unit,
    onDelete: (ManagedContentEntry, (Result<Unit>) -> Unit) -> Unit,
    onFindReplacement: (String, ManagedContentType) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDelete by remember { mutableStateOf<ManagedContentEntry?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("内容管理", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${profile.name} · ${gameDirectoryMode.displayName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = { TextButton(onClick = onRefresh) { Text("刷新") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (gameDirectoryMode == GameDirectoryMode.SHARED) "共享内容范围" else "实例内容范围",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (gameDirectoryMode == GameDirectoryMode.SHARED) {
                            "此实例使用共享目录；在这里启用、禁用或删除的文件会影响所有实例。"
                        } else {
                            "此实例使用独立目录；这里的内容不会影响其他实例。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "${state.entries.size} 个文件 · ${state.entries.count { it.enabled }} 个启用",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                ManagedContentType.entries.forEachIndexed { index, type ->
                    SegmentedButton(
                        selected = state.type == type,
                        onClick = { onTypeChange(type) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ManagedContentType.entries.size,
                        ),
                    ) {
                        Text(type.label, maxLines = 1)
                    }
                }
            }
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                label = { Text("搜索已安装文件") },
                singleLine = true,
            )
            when {
                state.loading && state.entries.isEmpty() ->
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally).padding(24.dp))
                state.error != null && state.entries.isEmpty() ->
                    Text(
                        text = "读取失败：${state.error}",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                visibleEntries.isEmpty() ->
                    Text(
                        text = if (state.query.isBlank()) "此分类目录中没有已安装内容。" else "没有匹配项。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(visibleEntries, key = { it.file.absolutePath }) { entry ->
                        InstalledContentCard(
                            entry = entry,
                            type = state.type,
                            loading = state.loading,
                            onToggle = { onToggle(entry) },
                            onDelete = { pendingDelete = entry },
                            onFindReplacement = { onFindReplacement(entry.displayName, state.type) },
                        )
                    }
                }
            }
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除文件？") },
            text = {
                Text("将从实例目录永久删除「${entry.file.name}」。此操作不可撤销；如需保留可先禁用文件。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(entry) { pendingDelete = null }
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun InstalledContentCard(
    entry: ManagedContentEntry,
    type: ManagedContentType,
    loading: Boolean,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onFindReplacement: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${if (entry.enabled) "已启用" else "已禁用"} · ${formatBytes(entry.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (entry.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onToggle, enabled = !loading) {
                    Text(if (entry.enabled) "禁用" else "启用")
                }
                if (type == ManagedContentType.MODS) {
                    TextButton(onClick = onFindReplacement, enabled = !loading) { Text("查找更新") }
                }
                TextButton(onClick = onDelete, enabled = !loading) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

private fun formatBytes(size: Long): String = when {
    size < 1024L -> "$size B"
    size < 1024L * 1024L -> "%.1f KB".format(size / 1024.0)
    else -> "%.1f MB".format(size / (1024.0 * 1024.0))
}
