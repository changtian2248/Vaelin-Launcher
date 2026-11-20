package com.vaelin.launcher.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vaelin.launcher.ui.GameDirectorySettingsState
import com.vaelin.launcher.ui.MigrationCategory
import com.vaelin.launcher.ui.theme.ThemeMode
import com.vaelin.startup.profile.GameDirectoryMode
import com.vaelin.startup.profile.VersionProfile

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    gameDirectoryMode: GameDirectoryMode,
    profiles: List<VersionProfile>,
    migrationState: GameDirectorySettingsState,
    onCycleTheme: () -> Unit,
    onPrepareIsolation: () -> Unit,
    onCategorySelectionChange: (MigrationCategory, Boolean) -> Unit,
    onTargetSelectionChange: (MigrationCategory, String, Boolean) -> Unit,
    onKeepUnselectedDataChange: (Boolean) -> Unit,
    onConfirmIsolation: () -> Unit,
    onCancelMigration: () -> Unit,
    onSwitchToShared: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSharedWarning by remember { mutableStateOf(false) }
    var showFinalMigrationConfirmation by remember { mutableStateOf(false) }
    val preview = migrationState.preview

    BackHandler(enabled = migrationState.migrating) { }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    TextButton(onClick = onBack, enabled = !migrationState.migrating) { Text("返回") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingsCard(title = "外观") {
                Text("当前主题：${themeMode.displayName()}", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Vaelin 保留浅色、深色与纯黑三态。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onCycleTheme) { Text("切换主题") }
            }

            SettingsCard(title = "游戏目录 · 全局") {
                Text(
                    text = if (gameDirectoryMode == GameDirectoryMode.SHARED) "共享游戏目录" else "全局版本隔离",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = if (gameDirectoryMode == GameDirectoryMode.SHARED) {
                        "所有版本共用游戏工作目录，模组、配置和存档互通。此模式保留当前旧版目录布局。"
                    } else {
                        "所有版本分别使用 versions/<实例ID> 作为 Minecraft 工作目录；versions 元数据、libraries、assets 与运行时仍复用 gameHome。"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("当前实例：${profiles.size}", style = MaterialTheme.typography.labelLarge)
                if (migrationState.migrating && preview == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator()
                        Text(migrationState.progressDetail.ifBlank { "正在更新全局设置…" })
                    }
                }
                if (gameDirectoryMode == GameDirectoryMode.SHARED) {
                    Button(
                        onClick = onPrepareIsolation,
                        enabled = !migrationState.loadingPreview && !migrationState.migrating,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (migrationState.loadingPreview) "正在盘点共享数据…" else "预览并开启全局版本隔离")
                    }
                } else {
                    TextButton(
                        onClick = { showSharedWarning = true },
                        enabled = !migrationState.migrating,
                    ) { Text("切回共享目录") }
                }
                migrationState.message?.let { message ->
                    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                migrationState.error?.let { error ->
                    Text("操作未完成：$error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (preview != null) {
        AlertDialog(
            onDismissRequest = { if (!migrationState.migrating) onCancelMigration() },
            title = { Text(if (migrationState.migrating) "正在迁移并验证" else "迁移到全局版本隔离") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("来源：${preview.sourceRoot.absolutePath}", style = MaterialTheme.typography.bodySmall)
                    if (preview.files.isEmpty()) {
                        Text("未发现共享根目录中的用户文件。本次只会启用全局版本隔离，不复制数据。")
                    } else {
                        Text("数据类别和目标实例均需逐项勾选。未选数据仍保留在共享源中，但开启隔离后不会自动出现在实例目录。存档会变为互不联动的副本；模组/配置可能与目标版本或加载器不兼容。")
                    }
                    preview.categories.filter { it.fileCount > 0 }.forEach { summary ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(
                                checked = summary.category in preview.selection.categories,
                                onCheckedChange = { onCategorySelectionChange(summary.category, it) },
                                enabled = !migrationState.migrating,
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${summary.category.title} · ${summary.fileCount} 个 · ${formatBytes(summary.bytes)}")
                                Text(summary.category.riskNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                preview.files.filter { it.category == summary.category }.take(3).forEach { file ->
                                    Text("源：${file.relativePath}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (summary.fileCount > 3) {
                                    Text("另有 ${summary.fileCount - 3} 个源文件", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (summary.category in preview.selection.categories) {
                            if (preview.selection.targetsByCategory[summary.category].orEmpty().isEmpty()) {
                                Text(
                                    "尚未选择目标实例：${summary.category.title} 会继续留在共享根，不会复制进入任何隔离实例。",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            preview.targets.forEach { target ->
                                val selected = target.profileId in preview.selection.targetsByCategory[summary.category].orEmpty()
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = selected,
                                        onCheckedChange = { onTargetSelectionChange(summary.category, target.profileId, it) },
                                        enabled = !migrationState.migrating,
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        val existingFiles = target.existingUserFiles.filter { it.category == summary.category }
                                        val existingBytes = existingFiles.fold(0L) { total, file -> Math.addExact(total, file.sizeBytes) }
                                        Text("${target.profileName} · 现有 ${existingFiles.size} 个文件 / ${formatBytes(existingBytes)}", style = MaterialTheme.typography.bodySmall)
                                        Text(target.gameDirectory.absolutePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        existingFiles.take(3).forEach { file ->
                                            Text("已有：${file.relativePath}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        if (existingFiles.size > 3) {
                                            Text("另有 ${existingFiles.size - 3} 个现有文件", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                                preview.conflicts.filter {
                                    it.profileId == target.profileId && it.category == summary.category
                                }.forEach { conflict ->
                                    Text(
                                        "冲突（不会覆盖）：${conflict.sourceFile.absolutePath} → ${conflict.targetFile.absolutePath}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                    if (preview.files.isNotEmpty() && preview.selection.categories.isEmpty()) {
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(
                                checked = preview.selection.keepUnselectedDataInSharedRoot,
                                onCheckedChange = onKeepUnselectedDataChange,
                                enabled = !migrationState.migrating,
                            )
                            Text("不复制旧共享文件；开启隔离后实例看不到这些文件（共享源仍保留）")
                        }
                    }
                    Text(
                        "已选类别：${preview.selection.categories.size} · 已选目标副本：${preview.selectedCopies.size} · 源文件：${preview.selectedFiles.size} 个 / ${formatBytes(preview.selectedSourceBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        if (preview.selectedCopies.isEmpty()) "无共享用户文件需要复制；共享数据源仍保留。"
                        else "备份位置：${preview.backupRoot.absolutePath}（源文件和备份都会保留）",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text("预计备份 ${formatBytes(preview.estimatedBackupBytes)} + 目标副本 ${formatBytes(preview.estimatedTargetBytes)} + 临时文件峰值 ${formatBytes(preview.estimatedTransientBytes)} = ${formatBytes(preview.estimatedRequiredBytes)}；存储${if (preview.backupAndTargetShareFileStore) "相同" else "不同"}，可用备份 ${formatBytes(preview.availableBackupBytes)} / 目标 ${formatBytes(preview.availableTargetBytes)}")
                    Text(
                        "每个目标副本使用 SHA-256 校验；校验全部通过后才提交全局模式。失败不会覆盖已有文件或删除源数据；可重新预览后重试。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (preview.selectedConflicts.isNotEmpty()) {
                        Text("当前选择有 ${preview.selectedConflicts.size} 个目标冲突。取消冲突类别/目标勾选，或先手动备份处理后重新盘点。", color = MaterialTheme.colorScheme.error)
                    }
                    if (!preview.requiredSpaceAvailable) {
                        Text("备份或目标可用空间不足，迁移未开始。", color = MaterialTheme.colorScheme.error)
                    }
                    if (!preview.hasRequiredSelection) {
                        Text("先勾选至少一个有数据的类别，并为每个已选类别选择一个目标实例。", color = MaterialTheme.colorScheme.error)
                    }
                    migrationState.error?.let { error ->
                        Text("迁移未提交，仍使用共享目录：$error", color = MaterialTheme.colorScheme.error)
                    }
                    if (migrationState.migrating) {
                        val progress = migrationState.progress
                        if (progress != null && progress.second > 0) {
                            LinearProgressIndicator(
                                progress = { progress.first.toFloat() / progress.second.coerceAtLeast(1) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Text(migrationState.progressDetail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showFinalMigrationConfirmation = true },
                    enabled = preview.canRun && !migrationState.migrating,
                ) { Text(if (migrationState.migrating) "正在复制" else "下一步：复核并确认") }
            },
            dismissButton = {
                TextButton(onClick = onCancelMigration, enabled = !migrationState.migrating) {
                    Text(if (migrationState.migrating) "迁移中" else "取消")
                }
            },
        )
    }

    if (migrationState.loadingPreview && preview == null) {
        AlertDialog(
            onDismissRequest = onCancelMigration,
            title = { Text("盘点共享数据") },
            text = { Row { CircularProgressIndicator(); Spacer(Modifier.height(8.dp)); Text("正在扫描可迁移文件…") } },
            confirmButton = {},
            dismissButton = { TextButton(onClick = onCancelMigration) { Text("取消") } },
        )
    }

    if (showFinalMigrationConfirmation && preview != null) {
        AlertDialog(
            onDismissRequest = { showFinalMigrationConfirmation = false },
            title = { Text("最后确认：开启全局版本隔离？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (preview.selectedCopies.isEmpty()) {
                            "不会复制用户文件；共享目录中的数据保持原样，但启用隔离后不会自动出现在实例中。预计新增空间为 0。"
                        } else {
                            "将把 ${preview.selectedFiles.size} 个源文件复制到 ${preview.selectedCopies.size} 个类别-实例目标；预计占用 ${formatBytes(preview.estimatedRequiredBytes)}（备份 ${formatBytes(preview.estimatedBackupBytes)}、目标 ${formatBytes(preview.estimatedTargetBytes)}、临时空间峰值 ${formatBytes(preview.estimatedTransientBytes)}）。"
                        },
                    )
                    preview.selection.categories.forEach { category ->
                        val destinations = preview.selection.targetsByCategory[category].orEmpty()
                            .mapNotNull { id -> preview.targets.firstOrNull { it.profileId == id }?.profileName }
                        Text("${category.title} → ${destinations.joinToString("、").ifBlank { "无目标" }}")
                    }
                    Text(
                        if (preview.selectedCopies.isEmpty()) {
                            "共享源会保留，但隔离实例不会自动读取它们。不会创建备份副本。确认后只提交全局模式。"
                        } else {
                            "共享源与SHA-256备份都会保留。已复制的存档互不联动；模组/配置可能不兼容。选中目标若有异内容冲突、空间不足或中断，保持共享模式且不覆盖既有文件。"
                        },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showFinalMigrationConfirmation = false
                        onConfirmIsolation()
                    },
                    enabled = preview.canRun && !migrationState.migrating,
                ) { Text("确认复制并切换") }
            },
            dismissButton = {
                TextButton(onClick = { showFinalMigrationConfirmation = false }) { Text("返回修改") }
            },
        )
    }

    if (showSharedWarning) {
        AlertDialog(
            onDismissRequest = { showSharedWarning = false },
            title = { Text("切回共享目录？") },
            text = {
                Text("所有版本将改用共享工作目录。现有隔离目录及其中的模组/配置/存档会原样保留，不会自动合并或删除；如需回到这些内容，可重新开启隔离模式。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showSharedWarning = false
                    onSwitchToShared()
                }) { Text("切回共享") }
            },
            dismissButton = { TextButton(onClick = { showSharedWarning = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024L -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
