/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现。下载主页：搜索 + 分类页签 + 结果列表。
 *
 * 横屏适配（2340×1080 横屏约 850dp 宽）：BoxWithConstraints 断点 840dp——
 * 宽屏走双栏（左列表 42% + 右详情 58%，点列表即在右栏看详情，Axolotl 桌面端
 * 同款信息架构）；竖屏单栏，点项跳全屏详情。空间利用与触达尺寸按横屏拇指区校过。
 */

package com.vaelin.launcher.ui.screens.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vaelin.launcher.ui.DownloadJob
import com.vaelin.launcher.ui.DownloadSearchState
import com.vaelin.launcher.ui.DownloadDetailState
import com.vaelin.launcher.ui.DownloadViewModel.ModpackImportState
import com.vaelin.launcher.ui.screens.LauncherBottomNavigation
import com.vaelin.launcher.ui.screens.LauncherTopLevelTab
import com.vaelin.startup.download.source.AddonKind
import com.vaelin.startup.download.source.AddonSort
import com.vaelin.startup.download.source.AddonVersion
import com.vaelin.startup.profile.GameDirectoryMode
import com.vaelin.startup.profile.VersionProfile

private val KIND_TABS = listOf(
    AddonKind.MOD,
    AddonKind.SHADER,
    AddonKind.RESOURCE_PACK,
    AddonKind.MODPACK,
)

private fun sortName(sort: AddonSort): String = when (sort) {
    AddonSort.RELEVANCE -> "综合排序"
    AddonSort.DOWNLOADS -> "最多下载"
    AddonSort.FOLLOWS -> "最多关注"
    AddonSort.NEWEST -> "最新发布"
    AddonSort.UPDATED -> "最近更新"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    searchState: DownloadSearchState,
    detailState: DownloadDetailState,
    downloadJob: DownloadJob?,
    gateBlocked: Boolean,
    simRegionLabel: String,
    /** 可选源（id→显示名）与当前选中源 */
    sources: List<Pair<String, String>>,
    selectedSource: String,
    profiles: List<VersionProfile>,
    globalDirectoryMode: GameDirectoryMode,
    targetProfileId: String?,
    onTargetProfileChange: (String?) -> Unit,
    onOpenSettings: () -> Unit,
    onSourceChange: (String) -> Unit,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onKindChange: (AddonKind) -> Unit,
    onSortChange: (AddonSort) -> Unit,
    onLoadMore: () -> Unit,
    /** 宽屏双栏：点列表项只加载详情到右栏，不跳路由 */
    onSelectItem: (String) -> Unit,
    /** 竖屏：点列表项进全屏详情（调用方负责加载 + 导航） */
    onOpenDetail: (String) -> Unit,
    onDownload: (AddonVersion) -> Unit,
    onRetryDetail: () -> Unit,
    onCycleSimRegion: () -> Unit,
    /** 整合包导入（本地文件） */
    modpackImport: ModpackImportState,
    onImportModpackClick: () -> Unit,
    onConfirmModpackInstall: () -> Unit,
    onDismissModpackInstall: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 宽屏双栏的选中项；窄屏不用（跳转全屏详情）
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        Scaffold(
            bottomBar = {
                LauncherBottomNavigation(
                    selected = LauncherTopLevelTab.RESOURCES,
                    onInstances = onBack,
                    onResources = {},
                    onSettings = onOpenSettings,
                )
            },
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("资源下载", maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = sources.firstOrNull { it.first == selectedSource }?.second
                                    ?.plus(" · 模拟地区：$simRegionLabel")
                                    ?: simRegionLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    navigationIcon = {
                        TextButton(onClick = onBack) { Text("返回") }
                    },
                    actions = {
                        if (sources.size > 1) {
                            SourceMenu(sources = sources, selected = selectedSource, onSelect = onSourceChange)
                        }
                        SortMenu(current = searchState.sort, onSelect = onSortChange)
                    },
                )
            },
        ) { innerPadding ->
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                Column(modifier = Modifier.fillMaxSize()) {
                    TargetDirectoryRow(
                        profiles = profiles,
                        mode = globalDirectoryMode,
                        selectedProfileId = targetProfileId,
                        onSelect = onTargetProfileChange,
                    )
                    SearchBarRow(
                        query = searchState.query,
                        onQueryChange = onQueryChange,
                        onSearch = onSearch,
                    )
                    KindTabsRow(
                        selected = searchState.kind,
                        onSelect = onKindChange,
                    )
                    if (searchState.kind == AddonKind.MODPACK) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "新实例目录：${globalDirectoryMode.displayName}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            TextButton(onClick = onOpenSettings) { Text("更改") }
                            TextButton(onClick = onImportModpackClick) {
                                Text("导入整合包")
                            }
                        }
                    }
                    if (searchState.loading && searchState.items.isEmpty()) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    if (wide) {
                        WideContent(
                            searchState = searchState,
                            detailState = detailState,
                            downloadJob = downloadJob,
                            gateBlocked = gateBlocked,
                            onSelect = { id ->
                                selectedId = id
                                onSelectItem(id)
                            },
                            onLoadMore = onLoadMore,
                            onDownload = onDownload,
                            onRetryDetail = onRetryDetail,
                        )
                    } else {
                        NarrowList(
                            searchState = searchState,
                            onSelect = { id -> onOpenDetail(id) },
                            onLoadMore = onLoadMore,
                            bottomContent = {
                                DebugRegionRow(gateBlocked, onCycleSimRegion)
                            },
                        )
                    }
                }
                ModpackImportSheet(
                    state = modpackImport,
                    gameDirectoryMode = globalDirectoryMode,
                    gateBlocked = gateBlocked,
                    onConfirm = onConfirmModpackInstall,
                    onDismiss = onDismissModpackInstall,
                )
            }
        }
    }
}

@Composable
private fun TargetDirectoryRow(
    profiles: List<VersionProfile>,
    mode: GameDirectoryMode,
    selectedProfileId: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedProfile = profiles.firstOrNull { it.id == selectedProfileId }
    val selectedLabel = if (mode == GameDirectoryMode.SHARED) "共享游戏目录" else selectedProfile?.name ?: "请选择实例"
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "安装目标 · ${mode.displayName}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(selectedLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (mode == GameDirectoryMode.SHARED) {
                    DropdownMenuItem(
                        text = { Text("共享游戏目录（所有实例共用）") },
                        onClick = {
                            expanded = false
                            onSelect(null)
                        },
                    )
                }
                if (mode == GameDirectoryMode.ISOLATED) {
                    profiles.forEach { profile ->
                        DropdownMenuItem(
                            text = { Text(profile.name) },
                            onClick = {
                                expanded = false
                                onSelect(profile.id)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchBarRow(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("搜索 Modrinth 资源…") },
            singleLine = true,
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "清空")
                    }
                }
            },
        )
        Spacer(Modifier.width(8.dp))
        // 纯文本按钮不用图标：material-icons-core 无 Download 图标，扩展包为个图标不值当
        TextButton(onClick = onSearch) { Text("搜索") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KindTabsRow(
    selected: AddonKind,
    onSelect: (AddonKind) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        KIND_TABS.forEachIndexed { index, kind ->
            SegmentedButton(
                selected = selected == kind,
                onClick = { onSelect(kind) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = KIND_TABS.size),
            ) {
                Text(addonKindName(kind))
            }
        }
    }
}

@Composable
private fun SourceMenu(
    sources: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(sources.firstOrNull { it.first == selected }?.second ?: "源", maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            sources.forEach { (id, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        expanded = false
                        onSelect(id)
                    },
                )
            }
        }
    }
}

@Composable
private fun SortMenu(current: AddonSort, onSelect: (AddonSort) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(sortName(current), maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            AddonSort.entries.forEach { sort ->
                DropdownMenuItem(
                    text = { Text(sortName(sort)) },
                    onClick = {
                        expanded = false
                        onSelect(sort)
                    },
                )
            }
        }
    }
}

@Composable
private fun WideContent(
    searchState: DownloadSearchState,
    detailState: DownloadDetailState,
    downloadJob: DownloadJob?,
    gateBlocked: Boolean,
    onSelect: (String) -> Unit,
    onLoadMore: () -> Unit,
    onDownload: (AddonVersion) -> Unit,
    onRetryDetail: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        NarrowList(
            searchState = searchState,
            onSelect = onSelect,
            onLoadMore = onLoadMore,
            bottomContent = {
                DebugRegionRow(gateBlocked) {}
            },
            modifier = Modifier.weight(0.42f).fillMaxHeight(),
        )
        VerticalDivider()
        DownloadDetailContent(
            state = detailState,
            gateBlocked = gateBlocked,
            downloadJob = downloadJob,
            onDownload = onDownload,
            onRetry = onRetryDetail,
            modifier = Modifier.weight(0.58f).fillMaxHeight(),
        )
    }
}

@Composable
private fun NarrowList(
    searchState: DownloadSearchState,
    onSelect: (String) -> Unit,
    onLoadMore: () -> Unit,
    bottomContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        searchState.items.isEmpty() && !searchState.loading && searchState.error == null ->
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "输入关键词搜索，或直接点「搜索」浏览热门",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        searchState.items.isEmpty() && searchState.error != null ->
            ErrorPane(
                message = searchState.error,
                onRetry = onLoadMore,
                modifier = modifier.fillMaxSize(),
            )
        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(items = searchState.items, key = { it.projectId }) { project ->
                ProjectCard(project = project, onClick = { onSelect(project.projectId) })
            }
            if (searchState.error != null) {
                item(key = "error") {
                    Text(
                        text = "加载更多失败：${searchState.error}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                }
            }
            if (searchState.hasMore) {
                item(key = "more") {
                    TextButton(
                        onClick = onLoadMore,
                        enabled = !searchState.loading,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (searchState.loading) "加载中…" else "加载更多（已显示 ${searchState.items.size}/${searchState.totalHits}）")
                    }
                }
            }
            item(key = "debug") {
                bottomContent()
            }
        }
    }
}

/** Beta 调试行：地区闸状态 + 模拟切换。R8 发布前随开发者选项一起收编/隐藏。 */
@Composable
private fun DebugRegionRow(gateBlocked: Boolean, onCycleSimRegion: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        HorizontalDividerLite()
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (gateBlocked) "地区闸：已拦截下载动作" else "地区闸：放行",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "模拟切换仅供 Beta 测试，发布版将隐藏",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            TextButton(onClick = onCycleSimRegion) { Text("切换") }
        }
    }
}

@Composable
private fun HorizontalDividerLite() {
    androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
