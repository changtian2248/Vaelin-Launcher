/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现。下载页 UI 的状态源：搜索/详情/下载编排。
 *
 * 线程模型：SourceRegistry 的源接口是同步阻塞契约（与 FileDownloader 同），
 * 统一走单线程 executor 串行执行——既满足 Modrinth 250ms 限速（IntervalRateLimiter
 * 在 ModrinthApi 内），又天然避免搜索翻页并发打爆；Compose state 一律 mainHandler
 * 切回主线程写（照 LauncherViewModel 的模式）。
 */

package com.vaelin.launcher.ui

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.vaelin.startup.download.DownloadRequest
import com.vaelin.startup.download.DownloadResult
import com.vaelin.startup.download.FileDownloader
import com.vaelin.startup.download.UrlConnectionTransport
import com.vaelin.startup.download.curseforge.CurseForgeMcimApi
import com.vaelin.startup.download.curseforge.CurseForgeMcimSource
import com.vaelin.startup.download.curseforge.CurseForgeParser
import com.vaelin.startup.download.modrinth.ModrinthApi
import com.vaelin.startup.download.modrinth.ModrinthParser
import com.vaelin.startup.download.modrinth.ModrinthSource
import com.vaelin.startup.download.modpack.InspectedPack
import com.vaelin.startup.download.modpack.ModpackInstallResult
import com.vaelin.startup.download.modpack.ModpackInstaller
import com.vaelin.startup.download.source.AddonKind
import com.vaelin.startup.download.source.AddonProject
import com.vaelin.startup.download.source.AddonSearchQuery
import com.vaelin.startup.download.source.AddonSort
import com.vaelin.startup.download.source.AddonVersion
import com.vaelin.startup.download.source.RegionDetector
import com.vaelin.startup.download.source.SourceGate
import com.vaelin.startup.download.source.SourceGateException
import com.vaelin.startup.download.source.SourceRegistry
import com.vaelin.startup.profile.GameDirectoryMode
import com.vaelin.startup.profile.ProfileStore
import com.vaelin.startup.profile.VersionProfile
import com.vaelin.startup.util.GameDirectoryOperationLock
import com.vaelin.startup.util.VLog
import com.vaelin.startup.util.VPaths
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

private const val TAG = "DownloadViewModel"

/** 搜索页状态。 */
data class DownloadSearchState(
    val query: String = "",
    val kind: AddonKind = AddonKind.MOD,
    val sort: AddonSort = AddonSort.RELEVANCE,
    val items: List<AddonProject> = emptyList(),
    val totalHits: Int = 0,
    val loading: Boolean = false,
    /** 非空 = 上一次搜索失败的原因（网络错误/解析失败），UI 显示错误卡片 */
    val error: String? = null,
    val hasMore: Boolean = false,
)

/** 详情页状态。 */
data class DownloadDetailState(
    val project: AddonProject? = null,
    val versions: List<AddonVersion> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

/** 单个下载任务状态。 */
data class DownloadJob(
    val fileName: String,
    val targetDir: String,
    val status: Status,
    val message: String = "",
) {
    enum class Status { RUNNING, DONE, FAILED }
}

class DownloadViewModel(application: Application) : AndroidViewModel(application) {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 单线程执行器：串行所有源调用（搜索/详情/下载互不争抢限速窗口） */
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    private val searchRequests = DownloadSearchRequestTracker()

    private val simStore = RegionSimStore(application)

    private val regionSignals = AndroidRegionSignals(application)

    private val curseforgeApi = CurseForgeMcimApi(UrlConnectionTransport())

    private val registry: SourceRegistry = SourceRegistry(
        gate = SourceGate(RegionDetector(regionSignals)),
        sources = listOf(
            ModrinthSource(ModrinthApi(UrlConnectionTransport())),
            // 用户裁定：无 CF 官方 key 期间用 MCIM 镜像端代持凭据顶位（备线性质，
            // 文件下载 302 回官方 CDN；拿到 key 后换官方 Api 实现，框架层零改动）
            CurseForgeMcimSource(curseforgeApi),
        ),
    )

    /** 可选源列表（id→显示名），给源切换 UI */
    val sources: List<Pair<String, String>>
        get() = registry.all().map { it.sourceId to it.displayName }

    /** 当前选中的源 */
    var sourceId by mutableStateOf(ModrinthParser.SOURCE_ID)
        private set

    /** 资源下载目标实例；null 表示用户明确选择 legacy 共享目录。 */
    var targetProfileId by mutableStateOf<String?>(null)
        private set

    fun onTargetProfileChange(profileId: String?) {
        val mode = VPaths.gameDirectoryMode
        if (mode == GameDirectoryMode.SHARED) {
            if (profileId == null) targetProfileId = null
            return
        }
        if (profileId == null || ProfileStore.find(profileId) == null) return
        targetProfileId = profileId
    }

    fun syncGlobalDirectoryMode(mode: GameDirectoryMode, profiles: List<VersionProfile>) {
        if (mode == GameDirectoryMode.SHARED) {
            targetProfileId = null
            return
        }
        if (targetProfileId == null || profiles.none { it.id == targetProfileId }) {
            targetProfileId = profiles.firstOrNull()?.id
        }
    }

    fun searchForInstalledContent(profileId: String, query: String, kind: AddonKind) {
        if (ProfileStore.find(profileId) == null) return
        targetProfileId = if (VPaths.gameDirectoryMode == GameDirectoryMode.ISOLATED) profileId else null
        searchState = DownloadSearchState(query = query, kind = kind)
        detailState = DownloadDetailState()
        search(reset = true)
    }

    val gameDirectoryMode: GameDirectoryMode
        get() = VPaths.gameDirectoryMode

    private fun targetGameDir(profileId: String? = targetProfileId): File {
        if (profileId == null) {
            if (VPaths.gameDirectoryMode == GameDirectoryMode.ISOLATED) {
                throw IllegalStateException("全局版本隔离已开启，请先选择一个下载目标实例")
            }
            return VPaths.gameDir
        }
        val profile = ProfileStore.find(profileId)
            ?: throw IllegalStateException("下载目标实例不存在：$profileId")
        return VPaths.resolveGameDir(profile)
    }

    /** 切源：清空搜索与详情状态并按新源重新搜索 */
    fun onSourceChange(id: String) {
        if (id == sourceId || registry.get(id) == null) return
        sourceId = id
        searchState = DownloadSearchState()
        detailState = DownloadDetailState()
        clearDownloadJob()
        search(reset = true)
    }

    private val downloader = FileDownloader(UrlConnectionTransport())

    var searchState by mutableStateOf(DownloadSearchState())
        private set

    var detailState by mutableStateOf(DownloadDetailState())
        private set

    /** 进行中的/最近一次的下载任务（按文件名 key 只保留最近一条，简化 UI） */
    var downloadJob by mutableStateOf<DownloadJob?>(null)
        private set

    /** 地区闸状态；UI 用它隐藏下载按钮。模拟切换后自动刷新。 */
    var gateBlocked by mutableStateOf(registry.isDistributionBlocked())
        private set

    /** 模拟地区档位显示名（标题栏展示）。 */
    var simRegionLabel by mutableStateOf(labelOf(simStore.load()))
        private set

    init {
        refreshGate()
    }

    // ---- 地区闸 ----

    fun refreshGate() {
        gateBlocked = registry.isDistributionBlocked()
    }

    /** Beta 调试：循环切换模拟地区（auto→overseas→mainland），返回新档位的显示名 */
    fun cycleSimulatedRegion(): String {
        val next = simStore.cycle()
        refreshGate()
        simRegionLabel = labelOf(next)
        VLog.info(TAG, "模拟地区切换为 $next")
        return simRegionLabel
    }

    private fun labelOf(region: SimulatedRegion): String = when (region) {
        SimulatedRegion.AUTO -> "自动（真实信号）"
        SimulatedRegion.OVERSEAS -> "模拟海外"
        SimulatedRegion.MAINLAND -> "模拟大陆"
    }

    // ---- 搜索 ----

    fun onQueryChange(text: String) {
        searchState = searchState.copy(query = text)
    }

    fun onKindChange(kind: AddonKind) {
        if (searchState.kind == kind) return
        searchState = searchState.copy(kind = kind)
        search(reset = true)
    }

    fun onSortChange(sort: AddonSort) {
        if (searchState.sort == sort) return
        searchState = searchState.copy(sort = sort)
        search(reset = true)
    }

    /** 搜索。reset=true 从头搜；false 追加下一页。 */
    fun search(reset: Boolean = true) {
        val current = searchState
        val ticket = searchRequests.begin(reset, current.loading) ?: return
        val requestSourceId = sourceId
        val requestQuery = current.query.trim()
        val requestKind = current.kind
        val requestSort = current.sort
        val nextOffset = if (reset) 0 else current.items.size
        searchState = current.copy(
            items = if (reset) emptyList() else current.items,
            totalHits = if (reset) 0 else current.totalHits,
            loading = true,
            error = null,
            hasMore = if (reset) false else current.hasMore,
        )
        worker.execute {
            runCatching {
                registry.search(
                    requestSourceId,
                    AddonSearchQuery(
                        text = requestQuery,
                        kind = requestKind,
                        sort = requestSort,
                        offset = nextOffset,
                        limit = PAGE_SIZE,
                    ),
                )
            }.onSuccess { page ->
                mainHandler.post {
                    if (!searchRequests.isCurrent(ticket)) return@post
                    val merged = if (reset) page.items else searchState.items + page.items
                    searchState = searchState.copy(
                        items = merged,
                        totalHits = page.totalHits,
                        loading = false,
                        error = null,
                        hasMore = page.hasMore(),
                    )
                }
            }.onFailure { t ->
                mainHandler.post {
                    if (!searchRequests.isCurrent(ticket)) return@post
                    searchState = searchState.copy(loading = false, error = describe(t))
                }
            }
        }
    }

    // ---- 详情 ----

    fun loadDetail(projectId: String) {
        detailState = DownloadDetailState(loading = true)
        worker.execute {
            runCatching {
                val project = registry.project(sourceId, projectId)
                val versions = registry.versions(sourceId, projectId)
                project to versions
            }.onSuccess { (project, versions) ->
                mainHandler.post {
                    detailState = DownloadDetailState(project = project, versions = versions, loading = false)
                }
            }.onFailure { t ->
                mainHandler.post {
                    detailState = DownloadDetailState(loading = false, error = describe(t))
                }
            }
        }
    }

    fun clearDetail() {
        detailState = DownloadDetailState()
    }

    // ---- 下载 ----

    /**
     * 下载指定版本的主文件到对应资源目录。
     *
     * 落盘位置与启动链的 game_directory 共用所选实例根目录；null 目标保留旧共享根：
     * MOD→mods/、SHADER→shaderpacks/、RESOURCE_PACK→resourcepacks/（R1.5 手动放
     * 模组的同一目录，MC 侧零改动）。
     *
     * 过闸：SourceGateException 显式转成 FAILED 状态展示，不静默。
     */
    fun download(version: AddonVersion) {
        val file = version.primaryFile()
        if (file == null) {
            downloadJob = DownloadJob("未知文件", "", DownloadJob.Status.FAILED, "该版本没有可下载文件")
            return
        }
        val project = detailState.project
        val kind = project?.kind ?: AddonKind.MOD
        val dirName = when (kind) {
            AddonKind.MOD -> "mods"
            AddonKind.SHADER -> "shaderpacks"
            AddonKind.RESOURCE_PACK -> "resourcepacks"
            // 整合包 zip 先落暂存目录，安装走「导入本地整合包」流程
            AddonKind.MODPACK -> "modpacks"
            else -> "mods"
        }
        val requestSourceId = sourceId
        val requestTargetProfileId = targetProfileId
        downloadJob = DownloadJob(file.filename, "正在确认目标目录…", DownloadJob.Status.RUNNING)
        worker.execute {
            val outcome = GameDirectoryOperationLock.withLock(VPaths.gameDirectoryLockFile) {
                runCatching {
                    val targetRoot = targetGameDir(requestTargetProfileId)
                    val targetDir = File(targetRoot, dirName)
                    val target = File(targetDir, file.filename)
                    // CF 少量项目禁第三方下载、downloadUrl 为空：走 download-url 端点补直链
                    val resolvedUrl = if (file.downloadUrl.isBlank() && requestSourceId == CurseForgeParser.SOURCE_ID) {
                        curseforgeApi.resolveDownloadUrl(version.projectId, version.versionId)
                    } else {
                        file.downloadUrl
                    }
                    val url = registry.prepareDownload(
                        requestSourceId,
                        if (resolvedUrl == file.downloadUrl) file else file.copy(downloadUrl = resolvedUrl),
                    )
                    targetDir to (target to downloader.download(
                        DownloadRequest(
                            url = url,
                            target = target,
                            expectedSha1 = file.sha1,
                            resume = false,
                        ),
                    ))
                }
            }
            outcome.onSuccess { (targetDir, pair) ->
                val (target, result) = pair
                mainHandler.post {
                    downloadJob = when (result) {
                        is DownloadResult.Success -> DownloadJob(
                            file.filename, targetDir.absolutePath, DownloadJob.Status.DONE,
                            "已保存到 ${target.absolutePath}",
                        )
                        is DownloadResult.Failure -> DownloadJob(
                            file.filename, targetDir.absolutePath, DownloadJob.Status.FAILED, result.reason,
                        )
                    }
                }
            }.onFailure { error ->
                mainHandler.post {
                    val message = if (error is SourceGateException) error.message ?: "地区闸拦截" else describe(error)
                    downloadJob = DownloadJob(file.filename, "", DownloadJob.Status.FAILED, message)
                }
            }
        }
    }

    fun clearDownloadJob() {
        downloadJob = null
    }

    // ---- 整合包导入 ----

    /** 整合包导入状态机。 */
    data class ModpackImportState(
        val stage: Stage,
        val fileName: String = "",
        val inspected: InspectedPack? = null,
        val progress: Pair<Int, Int>? = null,
        val result: ModpackInstallResult? = null,
        /** 安装成功后创建的实例 id */
        val createdProfileId: String? = null,
        /** true = 该整合包需要 Forge，实例已按 vanilla 建好，调用方应触发 Forge 安装链 */
        val triggerForgeInstall: Boolean = false,
        /** 加载器联动提示，null = 无需提示 */
        val loaderNote: String? = null,
        val error: String? = null,
    ) {
        enum class Stage { IDLE, READING, READY, INSTALLING, DONE, FAILED }
    }

    var modpackImport by mutableStateOf(ModpackImportState(ModpackImportState.Stage.IDLE))
        private set

    private val modpackInstaller: ModpackInstaller =
        ModpackInstaller(UrlConnectionTransport(), SourceGate(RegionDetector(regionSignals)))

    /** SAF 选中文件后调用：把 content 流复制成临时 zip → 识别格式 + 解析清单。 */
    fun onModpackPicked(uri: Uri, displayName: String?) {
        modpackImport = ModpackImportState(
            ModpackImportState.Stage.READING,
            fileName = displayName.orEmpty(),
        )
        worker.execute {
            val copied = runCatching {
                val target = File(getApplication<Application>().cacheDir, "modpack-import.zip")
                getApplication<Application>().contentResolver.openInputStream(uri)!!.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                target
            }
            when {
                copied.isFailure -> postModpackFailed("无法读取所选文件: ${describe(copied.exceptionOrNull()!!)}")
                else -> {
                    val inspected = runCatching { modpackInstaller.inspect(copied.getOrThrow()) }
                    mainHandler.post {
                        modpackImport = if (inspected.isSuccess) {
                            ModpackImportState(
                                stage = ModpackImportState.Stage.READY,
                                fileName = modpackImport.fileName,
                                inspected = inspected.getOrThrow(),
                            )
                        } else {
                            ModpackImportState(
                                stage = ModpackImportState.Stage.FAILED,
                                fileName = modpackImport.fileName,
                                error = describe(inspected.exceptionOrNull()!!),
                            )
                        }
                    }
                }
            }
        }
    }

    /** 确认安装：以当前全局目录策略，把整合包落盘到将创建的实例工作目录。 */
    fun confirmModpackInstall() {
        val current = modpackImport
        val inspected = current.inspected ?: return
        if (current.stage != ModpackImportState.Stage.READY) return
        val info = inspected.info
        val taken = ProfileStore.load().mapTo(mutableSetOf()) { it.id }
        val profileId = uniqueProfileId(taken, slugifyProfileId("${info.gameVersion}-${info.name}")) { candidate ->
            File(VPaths.gameDir, "versions/$candidate").exists()
        }
        modpackImport = current.copy(stage = ModpackImportState.Stage.INSTALLING, progress = 0 to info.files.size)
        worker.execute {
            val installation = GameDirectoryOperationLock.withLock(VPaths.gameDirectoryLockFile) {
                val targetGameDir = VPaths.resolveGameDir(profileId)
                val result = runCatching {
                    if (!targetGameDir.mkdirs() && !targetGameDir.isDirectory) {
                        throw IllegalStateException("无法创建整合包实例目录：$targetGameDir")
                    }
                    modpackInstaller.install(
                        inspected,
                        File(getApplication<Application>().cacheDir, "modpack-import.zip"),
                        targetGameDir,
                    ) { done, total ->
                        mainHandler.post { modpackImport = modpackImport.copy(progress = done to total) }
                    }
                }
                val createdProfile = result.getOrNull()?.takeIf { it.usable }?.let {
                    val isForge = info.loader == "forge"
                    VersionProfile(
                        id = profileId,
                        name = info.name,
                        minecraftVersion = info.gameVersion,
                        modLoader = if (isForge) "vanilla" else (info.loader ?: "vanilla"),
                        modLoaderVersion = if (isForge) null else info.loaderVersion,
                    ).also {
                        ProfileStore.upsert(it)
                        check(ProfileStore.find(profileId) != null) { "无法保存新建的整合包实例清单" }
                    }
                }
                result to createdProfile
            }
            mainHandler.post {
                val result = installation.first
                val r = result.getOrNull()
                when {
                    result.isFailure -> postModpackFailed(describe(result.exceptionOrNull()!!))
                    r != null && !r.usable -> modpackImport = ModpackImportState(
                        stage = ModpackImportState.Stage.FAILED,
                        fileName = current.fileName,
                        inspected = inspected,
                        result = r,
                        error = "${r.failedRequired.size} 个必需文件下载失败（如 ${r.failedRequired.firstOrNull()?.request?.url}），整合包不完整",
                    )
                    r != null -> {
                        val isForge = info.loader == "forge"
                        targetProfileId = profileId
                        modpackImport = ModpackImportState(
                            stage = ModpackImportState.Stage.DONE,
                            fileName = current.fileName,
                            inspected = inspected,
                            result = r,
                            createdProfileId = profileId,
                            triggerForgeInstall = isForge,
                            loaderNote = when {
                                isForge -> "Forge 安装链已排队（安装 recommended 版）；进度见实例详情页"
                                info.loader == "neoforge" ->
                                    "该整合包需要 NeoForge ${info.loaderVersion}：安装链暂只支持 Forge，请等待后续版本"
                                info.loader == "fabric" || info.loader == "quilt" ->
                                    "实例已记录加载器 ${info.loader} ${info.loaderVersion}；加载器安装链未接通，启动将按原版运行"
                                else -> null
                            },
                        )
                    }
                }
            }
        }
    }

    fun resetModpackImport() {
        modpackImport = ModpackImportState(ModpackImportState.Stage.IDLE)
        File(getApplication<Application>().cacheDir, "modpack-import.zip").delete()
    }

    /** Forge 安装触发器的一次性消费（MainActivity 触发 LauncherViewModel.installForge 后清掉，防重组重发）。 */
    fun consumeForgeInstallTrigger() {
        if (modpackImport.triggerForgeInstall) {
            modpackImport = modpackImport.copy(triggerForgeInstall = false)
        }
    }

    private fun postModpackFailed(message: String) {
        mainHandler.post {
            modpackImport = modpackImport.copy(stage = ModpackImportState.Stage.FAILED, error = message)
        }
    }

    // ---- 生命周期 ----

    override fun onCleared() {
        worker.shutdown()
    }

    private fun describe(t: Throwable): String = when (t) {
        is SourceGateException -> t.message ?: "地区闸拦截"
        else -> "${t.javaClass.simpleName}: ${t.message}"
    }

    companion object {
        const val PAGE_SIZE = 20
    }
}
