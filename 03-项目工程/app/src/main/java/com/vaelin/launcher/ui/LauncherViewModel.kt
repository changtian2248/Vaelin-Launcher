package com.vaelin.launcher.ui

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.vaelin.launcher.game.GameLauncher
import com.vaelin.launcher.ui.theme.ThemeMode
import com.vaelin.launcher.ui.theme.ThemeModeStore
import com.vaelin.startup.install.ForgeInstallStage
import com.vaelin.startup.install.ForgeInstaller
import com.vaelin.startup.launch.LaunchCoordinator
import com.vaelin.startup.profile.MemoryTiers
import com.vaelin.startup.profile.ProfileStore
import com.vaelin.startup.profile.VersionProfile
import com.vaelin.startup.renderer.VaelinRenderers
import com.vaelin.startup.util.VLog
import com.vaelin.startup.util.VPaths
import java.io.File

private const val TAG = "LauncherViewModel"

/**
 * 启动器 UI 的唯一状态源。
 *
 * 所有界面可变状态都挂在这里（实例列表、主题），Composable 只读不写；
 * 导航状态是唯一例外，因为它要跟随返回键与进程重建，放在 MainActivity 的
 * rememberSaveable 里更合适。
 *
 * 这里不做全局单例：ProfileStore 本身是 object（startup-core 的既定设计，只读不改），
 * 但 UI 侧没有任何 `object VaelinState { var ... }` 之类的可变全局。
 */
class LauncherViewModel(application: Application) : AndroidViewModel(application) {

    private val themeStore = ThemeModeStore(application)

    /** 当前主题；切换后立即落盘 */
    var themeMode by mutableStateOf(themeStore.load())
        private set

    /** 唯一全局游戏目录策略，只能从设置页修改。 */
    var gameDirectoryMode by mutableStateOf(VPaths.gameDirectoryMode)
        private set

    /** 目录切换迁移完成后，持久化全局模式并刷新 Compose 状态。 */
    fun refreshGameDirectoryMode() {
        gameDirectoryMode = VPaths.gameDirectoryMode
    }

    /** 磁盘上的实例列表，已按 ProfileStore 的原始顺序保存 */
    var profiles by mutableStateOf<List<VersionProfile>>(emptyList())
        private set

    /**
     * 内存滑块的上下界。刻意从 MemoryTiers 自己推出来，而不是在这里另写 512/4096：
     * 档位表将来改了（比如上探 6144），滑块范围跟着变，不会两边对不上。
     */
    val heapMinMb: Int = MemoryTiers.defaultHeapMb(0L)
    val heapMaxMb: Int = MemoryTiers.defaultHeapMb(Long.MAX_VALUE)

    /** 本机内存对应的推荐档，用作新建实例的默认值 */
    val recommendedHeapMb: Int = MemoryTiers.defaultHeapMb(deviceTotalRamMb(application))

    /** prepareAsync 的回调在后台线程，Compose state 必须切回主线程写。 */
    private val mainHandler = Handler(Looper.getMainLooper())

    /** T1.11 启动链在 UI 上的可见状态（只读暴露给 Composable）。 */
    var launchState by mutableStateOf(LaunchUiState())
        private set

    /**
     * 最近一次渲染器切换失败的可见原因（null = 无错误或最近一次切换成功）。
     * 与 launchState.error 分开：那是 prepare/启动链路的错误，混在一起会把
     * 「渲染器不存在」误标成「启动失败」。
     */
    var rendererUpdateError by mutableStateOf<String?>(null)
        private set

    /** T1.5.5 Forge 安装链在 UI 上的可见状态（详情页模组加载器行只读显示）。 */
    var forgeInstallState by mutableStateOf(ForgeInstallState())
        private set

    /** 详情页「安装 Forge」确认对话框的版本预解析状态。 */
    var forgePreviewState by mutableStateOf(ForgePreviewState())
        private set

    /** 防止连点重复启动；prepareAsync 自己也会起线程，这里只挡 UI 入口。 */
    @Volatile
    private var preparing = false

    /** 防止连点重复安装 / 重复预解析（ForgeInstaller 起线程前先在 UI 入口挡一道）。 */
    @Volatile
    private var installingForge = false

    @Volatile
    private var previewingForge = false

    init {
        reload()
    }

    fun reload() {
        profiles = ProfileStore.load()
    }

    fun findProfile(id: String): VersionProfile? = profiles.firstOrNull { it.id == id }

    fun setTheme(mode: ThemeMode) {
        if (themeMode == mode) return
        themeMode = mode
        themeStore.save(mode)
    }

    fun cycleTheme() = setTheme(themeMode.next())

    /**
     * 新建实例。id 是游戏实例目录名，所以做 slug 化并保证在现有清单内唯一。
     * @return 新建的实例；写盘失败时也返回对象（ProfileStore 内部已记日志），列表以落盘结果为准
     */
    fun createProfile(
        name: String,
        minecraftVersion: String,
        renderer: RendererOption,
        memoryMb: Int,
    ): VersionProfile {
        val trimmedName = name.trim().ifEmpty { minecraftVersion.trim() }
        val profile = VersionProfile(
            id = uniqueIdFor(trimmedName, minecraftVersion),
            name = trimmedName,
            minecraftVersion = minecraftVersion.trim(),
            rendererId = renderer.rendererId,
            rendererUniqueId = renderer.uniqueId,
            memoryMB = memoryMb,
        )
        profiles = ProfileStore.upsert(profile)
        return profile
    }

    fun deleteProfile(id: String) {
        profiles = ProfileStore.delete(id)
    }

    /**
     * 修改实例的渲染器（实例详情页选择对话框的确认回调）。
     *
     * 校验先于写盘：rendererUniqueId 必须在注册表里存在，否则不动 profile、
     * 把原因写进 [rendererUpdateError] 供详情页显示——绝不把一个 :game 进程
     * 解析不到的渲染器写进清单（那会让启动在 selectRenderer 处显式炸掉）。
     *
     * 落盘用 ProfileStore.upsert 整条覆盖，清单格式不变（rendererId /
     * rendererUniqueId 字段本就存在）。
     *
     * @param rendererUniqueId 渲染器唯一标识（RendererOption.uniqueId）
     * @param rendererId 渲染器短 id（RendererOption.rendererId）；与注册表不一致时
     *   以注册表为准并记日志——注册表才是启动链解析渲染器的依据，避免 UI 快照过期
     *   时把一对自相矛盾的键写进 profile（:game 的短 id 兜底会选错渲染器）。
     */
    fun updateRenderer(profileId: String, rendererUniqueId: String, rendererId: String) {
        val profile = findProfile(profileId)
        if (profile == null) {
            rendererUpdateError = "实例不存在或已被删除: $profileId"
            return
        }
        val found = VaelinRenderers.getRenderers()
            .firstOrNull { it.getUniqueIdentifier() == rendererUniqueId }
        if (found == null) {
            val message = "渲染器不存在: $rendererUniqueId" +
                "（插件可能已卸载，重启启动器重新扫描后再试）"
            rendererUpdateError = message
            VLog.warning(TAG, "拒绝更新渲染器: $message")
            return
        }
        val resolvedRendererId = found.getRendererId()
        if (rendererId.isNotBlank() && rendererId != resolvedRendererId) {
            VLog.warning(
                TAG,
                "rendererId 与注册表不一致（以注册表为准）: 传入 $rendererId，" +
                    "注册表 $resolvedRendererId (${found.getRendererName()})",
            )
        }
        profiles = ProfileStore.upsert(
            profile.copy(
                rendererId = resolvedRendererId,
                rendererUniqueId = rendererUniqueId,
            )
        )
        rendererUpdateError = null
    }

    /**
     * T1.5.5：详情页确认对话框打开时预解析 Forge recommended 版本号。
     * 只读一次 promotions_slim.json，不落盘不下载；失败原因显示在对话框里。
     */
    fun prepareForgeInstall(profileId: String) {
        if (previewingForge || installingForge) return
        val profile = findProfile(profileId)
        if (profile == null) {
            forgePreviewState = ForgePreviewState(error = "实例不存在或已被删除: $profileId")
            return
        }
        previewingForge = true
        forgePreviewState = ForgePreviewState(loading = true)
        Thread {
            val next = try {
                val build = ForgeInstaller.resolveRecommended(profile.minecraftVersion)
                ForgePreviewState(
                    loading = false,
                    forgeVersion = build.forgeVersion,
                    channel = build.channel,
                )
            } catch (t: Throwable) {
                VLog.error(TAG, "Forge 版本预解析失败: ${profile.minecraftVersion}", t)
                ForgePreviewState(loading = false, error = "${t.javaClass.simpleName}: ${t.message}")
            }
            mainHandler.post {
                previewingForge = false
                forgePreviewState = next
            }
        }.apply { name = "vaelin-forge-preview-$profileId"; start() }
    }

    /**
     * T1.5.5：安装 Forge 到当前实例（vanilla 专用入口）。
     *
     * 状态机：DOWNLOADING（promotions + installer 下载）→ INSTALLING（:jvm 跑 installer，
     * 进度文本来自 UDP PROGRESS 与 installer 日志滚动行）→ SUCCESS / ERROR。
     * 成功后 ProfileStore 已把 minecraftVersion 改成 forge 版本 id（如 1.20.1-forge-47.2.0），
     * 这里 reload 让详情页自动切到 Forge 版本语义；启动链不用动，prepare 会走本地版本分支。
     * 失败显式展示（含 installer 日志尾部），不静默回退。
     */
    fun installForge(profileId: String) {
        if (installingForge) return
        val profile = findProfile(profileId)
        if (profile == null) {
            forgeInstallState = ForgeInstallState(
                phase = ForgeInstallPhase.ERROR,
                error = "实例不存在或已被删除: $profileId",
            )
            return
        }
        if (profile.modLoader != "vanilla") {
            // UI 只在 vanilla 时给按钮；这里兜底拒绝覆盖安装，避免清掉已有加载器配置
            forgeInstallState = ForgeInstallState(
                phase = ForgeInstallPhase.ERROR,
                error = "实例已有加载器 ${profile.modLoader}，拒绝覆盖安装 Forge",
            )
            return
        }
        installingForge = true
        forgeInstallState = ForgeInstallState(phase = ForgeInstallPhase.DOWNLOADING, detail = "准备安装 Forge")
        val app = getApplication<Application>()
        ForgeInstaller.installAsync(
            context = app,
            profile = profile,
            onProgress = { stage, detail ->
                val phase = when (stage) {
                    ForgeInstallStage.RESOLVE, ForgeInstallStage.DOWNLOAD -> ForgeInstallPhase.DOWNLOADING
                    ForgeInstallStage.PREPARE, ForgeInstallStage.RUN, ForgeInstallStage.VERIFY ->
                        ForgeInstallPhase.INSTALLING
                }
                mainHandler.post {
                    // 成功/失败可能晚于最后一次进度回调到达，终态不被进度覆盖
                    if (forgeInstallState.phase != ForgeInstallPhase.SUCCESS &&
                        forgeInstallState.phase != ForgeInstallPhase.ERROR
                    ) {
                        forgeInstallState = ForgeInstallState(phase = phase, detail = detail)
                    }
                }
            },
            onSuccess = { updated ->
                mainHandler.post {
                    installingForge = false
                    profiles = ProfileStore.load()
                    forgeInstallState = ForgeInstallState(
                        phase = ForgeInstallPhase.SUCCESS,
                        detail = "Forge ${updated.modLoaderVersion} 安装完成，实例已切换为 ${updated.minecraftVersion}",
                    )
                }
            },
            onError = { t ->
                mainHandler.post {
                    installingForge = false
                    VLog.error(TAG, "Forge 安装失败: ${profile.id}", t)
                    forgeInstallState = ForgeInstallState(
                        phase = ForgeInstallPhase.ERROR,
                        error = "${t.javaClass.simpleName}: ${t.message}",
                    )
                }
            },
        )
    }

    /**
     * T1.11：点「启动」真的把 JVM 拉起来。
     *
     * 主进程跑 LaunchCoordinator.prepareAsync（下载/装 JRE/组件/拼参/落盘 LaunchPlan），
     * 成功后用 GameLauncher.start 把 profile 送进 :game 进程；VMActivity 在 Surface 就绪后
     * 读盘调 VaelinLauncher.launch。进度用 mainHandler 切回主线程写 Compose state。
     * 失败显式中止并把错误显示到 UI，不静默回退。
     */
    fun launch(profile: VersionProfile) {
        if (preparing) return
        preparing = true
        launchState = LaunchUiState(profileId = profile.id, isPreparing = true, stageLabel = "准备启动", percent = 0)
        val app = getApplication<Application>()
        LaunchCoordinator.prepareAsync(
            context = app,
            profile = profile,
            onProgress = { progress ->
                mainHandler.post {
                    launchState = LaunchUiState(
                        profileId = profile.id,
                        isPreparing = true,
                        stageLabel = progress.stage.label,
                        percent = progress.percent,
                        detail = progress.detail,
                    )
                }
            },
            onSuccess = {
                mainHandler.post {
                    preparing = false
                    val launchedProfile = profile.copy(lastPlayedAt = System.currentTimeMillis())
                    profiles = ProfileStore.upsert(launchedProfile)
                    launchState = LaunchUiState(
                        profileId = profile.id,
                        isPreparing = false,
                        stageLabel = "已交棒给游戏进程",
                        percent = 100,
                        launched = true,
                    )
                    GameLauncher.start(app, launchedProfile)
                }
            },
            onError = { t ->
                mainHandler.post {
                    preparing = false
                    launchState = LaunchUiState(
                        profileId = profile.id,
                        isPreparing = false,
                        error = "${t.javaClass.simpleName}: ${t.message}",
                    )
                }
            },
        )
    }

    private fun uniqueIdFor(name: String, minecraftVersion: String): String {
        val base = slugify("$minecraftVersion-$name")
        val taken = profiles.mapTo(mutableSetOf()) { it.id }
        fun available(candidate: String) = candidate !in taken && !File(VPaths.gameDir, "versions/$candidate").exists()
        if (available(base)) return base
        var suffix = 2
        while (!available("$base-$suffix")) suffix++
        return "$base-$suffix"
    }

    /** 只保留 [a-z0-9._-]，其余压成 '-'：id 会直接当目录名用 */
    private fun slugify(raw: String): String {
        val builder = StringBuilder(raw.length)
        raw.lowercase().forEach { ch ->
            builder.append(
                when {
                    ch in 'a'..'z' -> ch
                    ch in '0'..'9' -> ch
                    ch == '.' || ch == '_' || ch == '-' -> ch
                    else -> '-'
                }
            )
        }
        return builder.toString().trim('-', '.', '_').ifEmpty { "instance" }
    }

    private fun deviceTotalRamMb(context: Context): Long {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        manager?.getMemoryInfo(info)
        val totalBytes = info.totalMem
        // 拿不到时按 4 GB 估：对应 MemoryTiers 的 1024 MB 中位档，比给 512 更不容易 OOM
        return if (totalBytes > 0L) totalBytes / (1024L * 1024L) else 4096L
    }
}
