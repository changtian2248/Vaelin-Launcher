package com.vaelin.launcher.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vaelin.launcher.ui.ForgeInstallPhase
import com.vaelin.launcher.ui.ForgeInstallState
import com.vaelin.launcher.ui.ForgePreviewState
import com.vaelin.launcher.ui.LaunchUiState
import com.vaelin.launcher.ui.RendererOption
import com.vaelin.launcher.ui.rendererNameOf
import com.vaelin.startup.profile.GameDirectoryMode
import com.vaelin.startup.profile.VersionProfile
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 实例详情页。
 *
 * T1.11：「启动」按钮真调 LauncherViewModel.launch，主进程跑 prepare 时显示阶段进度，
 * 准备完成后交棒 :game 进程拉起 JVM（进度来自 LaunchCoordinator，不伪造启动结果）。
 *
 * 渲染器行可点击：弹出选择对话框（选项 = 内置 10 个 + 已注册插件渲染器），
 * 确认后经 [onUpdateRenderer] → LauncherViewModel.updateRenderer 校验并落盘。
 *
 * T1.5.5：模组加载器行在 vanilla 时给出「安装 Forge」入口——确认对话框先预解析
 * recommended 版本号（[onPrepareForgeInstall]），确认后 [onInstallForge] 起
 * ForgeInstaller 安装链；进度文本来自 UDP PROGRESS 与 installer 日志，失败显式展示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionDetailScreen(
    profile: VersionProfile,
    gameDirectoryMode: GameDirectoryMode,
    launchState: LaunchUiState,
    rendererOptions: List<RendererOption>,
    rendererUpdateError: String?,
    forgeInstallState: ForgeInstallState,
    forgePreviewState: ForgePreviewState,
    onUpdateRenderer: (rendererUniqueId: String, rendererId: String) -> Unit,
    onInstallForge: () -> Unit,
    onPrepareForgeInstall: () -> Unit,
    onManageContent: () -> Unit,
    onLaunch: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showRendererDialog by rememberSaveable { mutableStateOf(false) }
    var showForgeDialog by rememberSaveable { mutableStateOf(false) }
    val profileLaunchState = if (launchState.profileId == profile.id) launchState else LaunchUiState()
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("实例详情") },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 2.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(onClick = onManageContent, modifier = Modifier.weight(1f)) {
                        Text("管理内容")
                    }
                    Button(
                        onClick = onLaunch,
                        enabled = !profileLaunchState.isPreparing,
                        modifier = Modifier.weight(1.4f),
                    ) {
                        Text(if (profileLaunchState.isPreparing) "准备中…" else "启动实例")
                    }
                }
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("实例概览", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        "Minecraft ${profile.minecraftVersion} · ${profile.modLoader} · ${gameDirectoryMode.displayName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    DetailRow("实例 ID", profile.id)
                    DetailRow("全局游戏目录模式", gameDirectoryMode.displayName)
                    DetailRow("Minecraft 版本", profile.minecraftVersion)
                    ModLoaderRow(
                        modLoader = profile.modLoader,
                        modLoaderVersion = profile.modLoaderVersion,
                        installEnabled = forgeInstallState.phase != ForgeInstallPhase.DOWNLOADING &&
                            forgeInstallState.phase != ForgeInstallPhase.INSTALLING,
                        onInstallClick = { showForgeDialog = true },
                    )
                    DetailRow("渲染器", rendererNameOf(profile), onClick = { showRendererDialog = true })
                    DetailRow("渲染器短 ID", profile.rendererId)
                    DetailRow("堆内存", "${profile.memoryMB} MB")
                    DetailRow("Java 运行时", profile.javaRuntime ?: "内置默认")
                    DetailRow("上次启动", formatLastPlayed(profile.lastPlayedAt))
                    if (profile.extraJvmArgs.isNotEmpty()) {
                        DetailRow("额外 JVM 参数", profile.extraJvmArgs.joinToString(" "))
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            rendererUpdateError?.let { message ->
                Text(
                    text = "渲染器切换失败：$message",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
            }

            if (profileLaunchState.isPreparing) {
                Text(
                    text = "${profileLaunchState.stageLabel}  ${profileLaunchState.percent}%",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (profileLaunchState.detail.isNotBlank()) {
                    Text(
                        text = profileLaunchState.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { profileLaunchState.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            when {
                profileLaunchState.error != null -> Text(
                    text = "启动失败：${profileLaunchState.error}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                profileLaunchState.launched -> Text(
                    text = "已交棒给游戏进程，JVM 在 :game 进程窗口就绪后拉起。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Spacer(Modifier.height(8.dp))
            ForgeInstallStatusSection(forgeInstallState)
        }
    }

    if (showRendererDialog) {
        RendererPickerDialog(
            options = rendererOptions,
            currentUniqueId = profile.rendererUniqueId,
            currentRendererId = profile.rendererId,
            onDismiss = { showRendererDialog = false },
            onConfirm = { option ->
                showRendererDialog = false
                onUpdateRenderer(option.uniqueId, option.rendererId)
            },
        )
    }

    if (showForgeDialog) {
        // 每次打开对话框都重新预解析：promotions 是小请求，刷新成本低且能暴露网络问题
        LaunchedEffect(Unit) { onPrepareForgeInstall() }
        ForgeInstallConfirmDialog(
            mcVersion = profile.minecraftVersion,
            previewState = forgePreviewState,
            installRunning = forgeInstallState.phase == ForgeInstallPhase.DOWNLOADING ||
                forgeInstallState.phase == ForgeInstallPhase.INSTALLING,
            onConfirm = {
                showForgeDialog = false
                onInstallForge()
            },
            onDismiss = { showForgeDialog = false },
        )
    }
}

/**
 * 模组加载器行。vanilla：显示 "vanilla（未安装）" + 「安装 Forge」按钮；
 * 已装 forge：显示 forge 版本，不给按钮（不支持覆盖安装，详见 LauncherViewModel.installForge）。
 */
@Composable
private fun ModLoaderRow(
    modLoader: String,
    modLoaderVersion: String?,
    installEnabled: Boolean,
    onInstallClick: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "模组加载器",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildString {
                        when (modLoader) {
                            "vanilla" -> append("vanilla（未安装）")
                            "forge" -> {
                                append("forge")
                                modLoaderVersion?.let { append(" ").append(it) }
                            }
                            else -> {
                                append(modLoader)
                                modLoaderVersion?.let { append(" ").append(it) }
                            }
                        }
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (modLoader == "vanilla") {
                TextButton(
                    onClick = onInstallClick,
                    enabled = installEnabled,
                ) {
                    Text("安装 Forge")
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

/**
 * Forge 安装链的进度/结果展示（用法对齐上方启动进度条）。
 * 进行中用不确定进度条（installer 的 UDP PROGRESS 只有里程碑事件，无法给出真实百分比）。
 */
@Composable
private fun ForgeInstallStatusSection(state: ForgeInstallState) {
    when (state.phase) {
        ForgeInstallPhase.DOWNLOADING, ForgeInstallPhase.INSTALLING -> {
            Text(
                text = if (state.phase == ForgeInstallPhase.DOWNLOADING) "下载 Forge…" else "安装 Forge…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (state.detail.isNotBlank()) {
                Text(
                    text = state.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        ForgeInstallPhase.SUCCESS -> Text(
            text = state.detail.ifBlank { "Forge 安装成功" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        ForgeInstallPhase.ERROR -> Text(
            text = "Forge 安装失败：${state.error}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 10,
        )
        ForgeInstallPhase.IDLE -> {}
    }
}

/**
 * 安装确认对话框：显示将要安装的 Forge 版本号（打开时经 onPrepareForgeInstall 预解析）。
 * 解析失败时显式给出原因，确认按钮保持禁用——不假装知道版本号就放行安装。
 */
@Composable
private fun ForgeInstallConfirmDialog(
    mcVersion: String,
    previewState: ForgePreviewState,
    installRunning: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("安装 Forge") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "将为 Minecraft $mcVersion 安装 Forge：",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                when {
                    previewState.loading -> Text(
                        text = "正在从 Forge 官方源解析推荐版本…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    previewState.error != null -> Text(
                        text = "版本解析失败：${previewState.error}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    previewState.forgeVersion != null -> Text(
                        text = "Forge ${previewState.forgeVersion}（${previewState.channelLabel}）",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "安装会下载 installer 并在独立进程运行，可能需要数分钟；" +
                        "完成后此实例将切换为 Forge 版本启动，原版版本号由继承关系保留。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = previewState.forgeVersion != null && !installRunning,
            ) {
                Text("开始安装")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

/**
 * 渲染器选择对话框。下拉菜单实现仿 ui/dialogs/CreateProfileDialog.kt：
 * OutlinedButton 触发 DropdownMenu，选项两行（名称 + 短 id，副行带 summary）。
 *
 * 「当前」标记 = 对话框内当前选中项：初始按 profile 两级回退定位
 * （先 uniqueId、再短 id，与 rendererNameOf 的回退顺序一致），选中后跟随用户点选，
 * 确认后由 ViewModel 落盘并刷新详情页。
 */
@Composable
private fun RendererPickerDialog(
    options: List<RendererOption>,
    currentUniqueId: String,
    currentRendererId: String,
    onDismiss: () -> Unit,
    onConfirm: (RendererOption) -> Unit,
) {
    val initial = options.firstOrNull { it.uniqueId == currentUniqueId }
        ?: options.firstOrNull { it.rendererId == currentRendererId }
    var selectedUniqueId by rememberSaveable { mutableStateOf(initial?.uniqueId.orEmpty()) }
    var menuExpanded by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.uniqueId == selectedUniqueId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择渲染器") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "内置渲染器与已注册的插件渲染器都在列表中，切换立即对当前实例生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = options.isNotEmpty(),
                    ) {
                        Text(
                            text = selected?.let { "${it.name} (${it.rendererId})" }
                                ?: if (options.isEmpty()) "渲染器注册表为空" else "请选择",
                            fontWeight = FontWeight.Normal,
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        options.forEach { option ->
                            val isSelected = option.uniqueId == selectedUniqueId
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(if (isSelected) "${option.name}（当前）" else option.name)
                                        Text(
                                            text = option.rendererId,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        option.summary?.let {
                                            Text(
                                                text = it,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    selectedUniqueId = option.uniqueId
                                    menuExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selected?.let(onConfirm) },
                enabled = selected != null,
            ) {
                Text("确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

@Composable
private fun DetailRow(label: String, value: String, onClick: (() -> Unit)? = null) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val rowModifier = if (onClick != null) {
            Modifier.fillMaxWidth().clickable(onClick = onClick)
        } else {
            Modifier.fillMaxWidth()
        }
        Column(
            modifier = rowModifier.padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
            )
            if (onClick != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "点击修改",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}

private val lastPlayedFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

private fun formatLastPlayed(epochMillis: Long): String {
    if (epochMillis <= 0L) return "从未启动"
    return Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(lastPlayedFormatter)
}
