package com.vaelin.launcher

import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vaelin.launcher.ui.DownloadViewModel
import com.vaelin.launcher.ui.DownloadViewModel.ModpackImportState
import com.vaelin.launcher.ui.GameDirectorySettingsViewModel
import com.vaelin.launcher.ui.InstalledContentViewModel
import com.vaelin.launcher.ui.ManagedContentType
import com.vaelin.launcher.ui.LauncherViewModel
import com.vaelin.launcher.ui.VRoute
import com.vaelin.launcher.ui.VRouteSaver
import com.vaelin.launcher.ui.dialogs.CreateProfileDialog
import com.vaelin.launcher.ui.rendererOptions
import com.vaelin.launcher.ui.screens.SplashScreen
import com.vaelin.launcher.ui.screens.InstalledContentScreen
import com.vaelin.launcher.ui.screens.SettingsScreen
import com.vaelin.launcher.ui.screens.VersionDetailScreen
import com.vaelin.launcher.ui.screens.VersionListScreen
import com.vaelin.launcher.ui.screens.download.DownloadDetailScreen
import com.vaelin.launcher.ui.screens.download.DownloadScreen
import com.vaelin.launcher.ui.theme.VaelinTheme
import com.vaelin.startup.download.source.AddonKind
import com.vaelin.startup.plugin.PluginRenderersBootstrap
import com.vaelin.startup.renderer.VaelinRenderers
import com.vaelin.startup.util.VLog
import com.vaelin.startup.util.VPaths

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化顺序有依赖，不能换：
        //   VPaths 建目录 → VLog 才有地方写日志 → 渲染器注册时就会经 VLog 打点；
        //   ProfileStore 读写的 minecraft_versions.json 路径也来自 VPaths。
        VPaths.init(applicationContext)
        VLog.initialize(applicationContext)
        VaelinRenderers.init()
        // 插件渲染器扫描注册（T1.12 接线）：必须在 setContent 之前，首帧的
        // rendererOptions() 才能带上已装插件的渲染器；扫描失败只记日志不崩，
        // 插件环境是外部变量。进程内幂等，Activity 重建不会重复扫。
        PluginRenderersBootstrap.scanAndRegister(applicationContext)

        setContent {
            val launcherViewModel: LauncherViewModel = viewModel()
            val downloadViewModel: DownloadViewModel = viewModel()
            val installedContentViewModel: InstalledContentViewModel = viewModel()
            val gameDirectorySettingsViewModel: GameDirectorySettingsViewModel = viewModel()
            VaelinApp(launcherViewModel, downloadViewModel, installedContentViewModel, gameDirectorySettingsViewModel)
        }
    }
}

@Composable
private fun VaelinApp(
    viewModel: LauncherViewModel,
    downloadViewModel: DownloadViewModel,
    installedContentViewModel: InstalledContentViewModel,
    gameDirectorySettingsViewModel: GameDirectorySettingsViewModel,
) {
    // 导航状态：跨进程重建用字符串编码恢复（见 VRouteSaver）
    var route by rememberSaveable(stateSaver = VRouteSaver) {
        mutableStateOf<VRoute>(VRoute.Splash)
    }
    var settingsReturnRoute by rememberSaveable(stateSaver = VRouteSaver) {
        mutableStateOf<VRoute>(VRoute.VersionList)
    }
    val openSettings: () -> Unit = {
        settingsReturnRoute = route
        route = VRoute.Settings
    }
    var showCreateDialog by rememberSaveable { mutableStateOf(false) }

    // 渲染器注册表（内置 10 个 + 插件）在 onCreate 已 init/扫描完，这里取一次即可
    val options = remember { rendererOptions() }

    LaunchedEffect(viewModel.gameDirectoryMode, viewModel.profiles) {
        downloadViewModel.syncGlobalDirectoryMode(viewModel.gameDirectoryMode, viewModel.profiles)
    }

    // 组合层拿好 Context 供下面的 SAF 回调用（LocalContext 不能在非 Composable lambda 里读）
    val activityContext = androidx.compose.ui.platform.LocalContext.current

    // 整合包本地文件选择（SAF）。mime 给 zip 但用 */* 兜底：很多文件管理器
    // 把 .mrpack 报成 octet-stream，识别交给 ModpackDetect（认不出会显式报错）。
    val modpackPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            var displayName: String? = null
            runCatching {
                activityContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) displayName = cursor.getString(idx)
                    }
                }
            }
            downloadViewModel.onModpackPicked(uri, displayName ?: uri.lastPathSegment)
        }
    }

    // 整合包带 Forge：实例以 vanilla 建好后，触发启动核心侧安装链（一次性消费）
    LaunchedEffect(downloadViewModel.modpackImport.stage) {
        val imp = downloadViewModel.modpackImport
        if (imp.stage == ModpackImportState.Stage.DONE &&
            imp.triggerForgeInstall &&
            imp.createdProfileId != null
        ) {
            viewModel.installForge(imp.createdProfileId)
            downloadViewModel.consumeForgeInstallTrigger()
        }
    }

    VaelinTheme(mode = viewModel.themeMode) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            when (val current = route) {
                VRoute.Splash -> SplashScreen(
                    themeMode = viewModel.themeMode,
                    onCycleTheme = viewModel::cycleTheme,
                    onEnter = { route = VRoute.VersionList },
                )

                VRoute.VersionList -> VersionListScreen(
                    profiles = viewModel.profiles,
                    themeMode = viewModel.themeMode,
                    gameDirectoryMode = viewModel.gameDirectoryMode,
                    launchState = viewModel.launchState,
                    onCycleTheme = viewModel::cycleTheme,
                    onOpenSettings = openSettings,
                    onOpenProfile = { id -> route = VRoute.VersionDetail(id) },
                    onQuickLaunch = viewModel::launch,
                    onCreateProfile = { showCreateDialog = true },
                    onDeleteProfile = { profile -> viewModel.deleteProfile(profile.id) },
                    onOpenDownload = { route = VRoute.Download },
                )

                is VRoute.VersionDetail -> {
                    val profile = viewModel.findProfile(current.profileId)
                    if (profile == null) {
                        // 进程重建后实例可能已被别处删掉：回列表，不留白屏
                        LaunchedEffect(current.profileId) { route = VRoute.VersionList }
                    } else {
                        VersionDetailScreen(
                            profile = profile,
                            gameDirectoryMode = viewModel.gameDirectoryMode,
                            launchState = viewModel.launchState,
                            rendererOptions = options,
                            rendererUpdateError = viewModel.rendererUpdateError,
                            forgeInstallState = viewModel.forgeInstallState,
                            forgePreviewState = viewModel.forgePreviewState,
                            onUpdateRenderer = { uniqueId, rendererId ->
                                viewModel.updateRenderer(profile.id, uniqueId, rendererId)
                            },
                            onInstallForge = { viewModel.installForge(profile.id) },
                            onPrepareForgeInstall = { viewModel.prepareForgeInstall(profile.id) },
                            onManageContent = { route = VRoute.InstalledContent(profile.id) },
                            onLaunch = { viewModel.launch(profile) },
                            onBack = { route = VRoute.VersionList },
                        )
                    }
                }

                is VRoute.InstalledContent -> {
                    val profile = viewModel.findProfile(current.profileId)
                    if (profile == null) {
                        LaunchedEffect(current.profileId) { route = VRoute.VersionList }
                    } else {
                        LaunchedEffect(profile.id, viewModel.gameDirectoryMode) {
                            installedContentViewModel.load(profile)
                        }
                        InstalledContentScreen(
                            profile = profile,
                            gameDirectoryMode = viewModel.gameDirectoryMode,
                            state = installedContentViewModel.state,
                            visibleEntries = installedContentViewModel.visibleEntries,
                            onTypeChange = installedContentViewModel::onTypeChange,
                            onQueryChange = installedContentViewModel::onQueryChange,
                            onToggle = installedContentViewModel::toggle,
                            onDelete = installedContentViewModel::delete,
                            onFindReplacement = { name, type ->
                                val kind = when (type) {
                                    ManagedContentType.MODS -> AddonKind.MOD
                                    ManagedContentType.SHADERS -> AddonKind.SHADER
                                    ManagedContentType.RESOURCE_PACKS -> AddonKind.RESOURCE_PACK
                                }
                                downloadViewModel.searchForInstalledContent(profile.id, name, kind)
                                route = VRoute.Download
                            },
                            onRefresh = { installedContentViewModel.load(profile) },
                            onBack = { route = VRoute.VersionDetail(profile.id) },
                        )
                    }
                }

                VRoute.Settings -> SettingsScreen(
                    themeMode = viewModel.themeMode,
                    gameDirectoryMode = viewModel.gameDirectoryMode,
                    profiles = viewModel.profiles,
                    migrationState = gameDirectorySettingsViewModel.state,
                    onCycleTheme = viewModel::cycleTheme,
                    onPrepareIsolation = { gameDirectorySettingsViewModel.prepareIsolation(viewModel.profiles) },
                    onCategorySelectionChange = gameDirectorySettingsViewModel::setCategorySelected,
                    onTargetSelectionChange = gameDirectorySettingsViewModel::setTargetSelected,
                    onKeepUnselectedDataChange = gameDirectorySettingsViewModel::setLeaveUnselectedDataInSharedRoot,
                    onConfirmIsolation = {
                        gameDirectorySettingsViewModel.confirmIsolation(viewModel.profiles) { viewModel.refreshGameDirectoryMode() }
                    },
                    onCancelMigration = gameDirectorySettingsViewModel::cancelPreview,
                    onSwitchToShared = {
                        gameDirectorySettingsViewModel.switchToShared { viewModel.refreshGameDirectoryMode() }
                    },
                    onBack = { route = settingsReturnRoute },
                )

                VRoute.Download -> DownloadScreen(
                    searchState = downloadViewModel.searchState,
                    detailState = downloadViewModel.detailState,
                    downloadJob = downloadViewModel.downloadJob,
                    gateBlocked = downloadViewModel.gateBlocked,
                    simRegionLabel = downloadViewModel.simRegionLabel,
                    sources = downloadViewModel.sources,
                    selectedSource = downloadViewModel.sourceId,
                    profiles = viewModel.profiles,
                    globalDirectoryMode = viewModel.gameDirectoryMode,
                    targetProfileId = downloadViewModel.targetProfileId,
                    onTargetProfileChange = downloadViewModel::onTargetProfileChange,
                    onOpenSettings = openSettings,
                    onSourceChange = downloadViewModel::onSourceChange,
                    onQueryChange = downloadViewModel::onQueryChange,
                    onSearch = { downloadViewModel.search(reset = true) },
                    onKindChange = downloadViewModel::onKindChange,
                    onSortChange = downloadViewModel::onSortChange,
                    onLoadMore = { downloadViewModel.search(reset = false) },
                    onSelectItem = { id -> downloadViewModel.loadDetail(id) },
                    onOpenDetail = { id ->
                        downloadViewModel.loadDetail(id)
                        route = VRoute.DownloadDetail(downloadViewModel.sourceId, id)
                    },
                    onDownload = downloadViewModel::download,
                    onRetryDetail = {
                        downloadViewModel.detailState.project?.let { downloadViewModel.loadDetail(it.projectId) }
                    },
                    onCycleSimRegion = { downloadViewModel.cycleSimulatedRegion() },
                    modpackImport = downloadViewModel.modpackImport,
                    onImportModpackClick = { modpackPicker.launch("*/*") },
                    onConfirmModpackInstall = { downloadViewModel.confirmModpackInstall() },
                    onDismissModpackInstall = { downloadViewModel.resetModpackImport() },
                    onBack = { route = VRoute.VersionList },
                )

                is VRoute.DownloadDetail -> {
                    // 横屏双栏在 DownloadScreen 内嵌详情，不走这里；此分支仅竖屏全屏
                    if (downloadViewModel.detailState.project?.projectId != current.projectId &&
                        !downloadViewModel.detailState.loading
                    ) {
                        LaunchedEffect(current.projectId) {
                            downloadViewModel.loadDetail(current.projectId)
                        }
                    }
                    DownloadDetailScreen(
                        state = downloadViewModel.detailState,
                        downloadJob = downloadViewModel.downloadJob,
                        gateBlocked = downloadViewModel.gateBlocked,
                        onDownload = downloadViewModel::download,
                        onRetry = { downloadViewModel.loadDetail(current.projectId) },
                        onBack = { route = VRoute.Download },
                    )
                }
            }

            if (showCreateDialog) {
                CreateProfileDialog(
                    options = options,
                    heapMinMb = viewModel.heapMinMb,
                    heapMaxMb = viewModel.heapMaxMb,
                    defaultMemoryMb = viewModel.recommendedHeapMb,
                    onDismiss = { showCreateDialog = false },
                    onConfirm = { name, minecraftVersion, renderer, memoryMb ->
                        viewModel.createProfile(name, minecraftVersion, renderer, memoryMb)
                        showCreateDialog = false
                    },
                )
            }
        }
    }
}
