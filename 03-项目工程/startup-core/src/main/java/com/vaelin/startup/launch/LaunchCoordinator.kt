package com.vaelin.startup.launch

import android.content.Context
import com.vaelin.startup.components.VComponents
import com.vaelin.startup.download.AssetDownloadPlanner
import com.vaelin.startup.download.AssetIndexParser
import com.vaelin.startup.download.BatchDownloader
import com.vaelin.startup.download.DownloadPlan
import com.vaelin.startup.download.DownloadRequest
import com.vaelin.startup.download.DownloadResult
import com.vaelin.startup.download.FileDownloader
import com.vaelin.startup.download.HttpTransport
import com.vaelin.startup.download.Json
import com.vaelin.startup.download.JsonParseException
import com.vaelin.startup.download.MojangVersionSource
import com.vaelin.startup.download.NativeClassifiers
import com.vaelin.startup.download.RuleEnvironment
import com.vaelin.startup.download.UrlConnectionTransport
import com.vaelin.startup.download.VersionDownloadPlanner
import com.vaelin.startup.download.VersionJson
import com.vaelin.startup.download.VersionJsonMerger
import com.vaelin.startup.multirt.JreInstaller
import com.vaelin.startup.multirt.VRuntime
import com.vaelin.startup.profile.GameDirectoryMode
import com.vaelin.startup.profile.GameDirectoryResolver
import com.vaelin.startup.profile.VersionProfile
import com.vaelin.startup.util.GameDirectoryOperationLock
import com.vaelin.startup.util.VPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * T1.11 启动编排器：Compose 点「启动」→ 真的把 JVM 拉起来。
 *
 * 进程边界（关键设计）：VMLauncher.launchJVM 依赖 :game 进程里已加载的 native 库与 EGL/Surface 窗口，
 * 因此 JVM 必须在 :game 进程拉起；而下载/安装/拼参数的进度要显示在主进程 UI 上。两者用一份落盘
 * [LaunchPlan] 解耦：
 *   1. 主进程 [prepare]（[prepareAsync] / [prepareBlocking]）：拉清单 → 下载 → 装 JRE/组件 → 拼 classpath
 *      → 展开 gameArgs → 写 `<gameDir>/launch_plans/<versionId>.plan`。
 *   2. 主进程 GameLauncher.start 把 profile 送进 :game。
 *   3. :game 进程 VMActivity.execute（Surface 就绪后）另起后台线程调 [launchFromGameProcess]，
 *      读盘 → VaelinLauncher.launch（阻塞到游戏结束）→ 返回退出码。
 *
 * 下载与规划部分 [fetchAndInstallDownloads] 不依赖 Android Context，纯 JVM 可单测（注入 FakeHttpTransport）。
 * 任何失败显式抛 [LaunchException] 中止，禁止静默回退。
 */
object LaunchCoordinator {
    /** 本构建只打包 arm64-v8a native 库（app/build.gradle.kts abiFilters），故 ABI 是显式常量而非运行时猜测。 */
    const val DEFAULT_ABI = "arm64-v8a"
    const val LAUNCH_PLANS_DIR = "launch_plans"

    /** 把 Android 侧的安装动作抽象出来，让 [prepare] 能在纯 JVM 单测里注入 Fake 实现。 */
    interface LaunchEnvironmentProvider {
        suspend fun ensureJre(name: String, onProgress: (String) -> Unit): File
        suspend fun ensureComponents(onProgress: (String) -> Unit)

        /** 按版本探测 LWJGL 组件并拼出组件 jar（排在 Mojang 库之前，见 [LwjglSelection]）。 */
        fun lwjglComponent(version: VersionJson): LwjglSelection.Choice
    }

    class AndroidLaunchEnvironmentProvider(private val context: Context) : LaunchEnvironmentProvider {
        override suspend fun ensureJre(name: String, onProgress: (String) -> Unit): File =
            JreInstaller.ensureInstalled(context, name, onProgress)

        override suspend fun ensureComponents(onProgress: (String) -> Unit) {
            val components = VComponents.Component.entries
            components.forEachIndexed { index, component ->
                onProgress("${component.installName} (${index + 1}/${components.size})")
                VComponents.ensureInstalled(context, component) { onProgress(it) }
            }
        }

        override fun lwjglComponent(version: VersionJson): LwjglSelection.Choice =
            LwjglSelection.choose(version)
    }

    /** 下载阶段的产物，纯数据，供后续拼参与单测断言。 */
    data class DownloadArtifacts(
        val version: VersionJson,
        val versionJsonText: String,
        val plan: DownloadPlan,
        /**
         * 参与 classpath 的客户端 jar。清单路径 = [DownloadPlan.clientJarFile]；
         * 本地版本路径 = `<versions>/<jar|id>/<jar|id>.jar`（`jar` 字段决定文件名，
         * 见 [ensureLocalClientJar]），可能与 [DownloadPlan.clientJarFile] 不同。
         */
        val clientJarFile: File?,
    )

    fun planFile(gameDir: File, versionId: String): File =
        File(File(gameDir, LAUNCH_PLANS_DIR), "$versionId.plan")

    /**
     * 纯 JVM 下载阶段：拉清单 → 定位版本 → 写 version json → 规划并下载 client/libraries/asset index
     * → 读已落盘的 asset index → 规划并下载 asset objects。已有且校验通过的文件由
     * FileDownloader / AssetDownloadPlanner 直接跳过（零网络）。
     *
     * 本地版本分支：`<gameDir>/versions/<profile.minecraftVersion>/<同名>.json` 已存在时
     * （Forge/Fabric 等安装产物，目录名即版本串），跳过清单与版本 JSON 下载，读本地文件并
     * 按 `inheritsFrom` 递归合并父版本（[VersionJsonMerger]，父文件缺失显式报错）；
     * assets/index 仍按合并后 JSON 的 assetIndex 保证已下载（vanilla 1.20.1 的 index 5）。
     */
    suspend fun fetchAndInstallDownloads(
        transport: HttpTransport,
        gameDir: File,
        abi: String,
        environment: RuleEnvironment,
        profile: VersionProfile,
        reporter: ProgressReporter,
    ): DownloadArtifacts {
        val versionsDir = File(gameDir, VersionDownloadPlanner.VERSIONS_DIR)
        val localJsonFile = File(File(versionsDir, profile.minecraftVersion), "${profile.minecraftVersion}.json")
        if (localJsonFile.isFile) {
            return fetchLocalVersionDownloads(transport, gameDir, versionsDir, localJsonFile, abi, environment, profile, reporter)
        }
        return fetchRemoteVersionDownloads(transport, gameDir, abi, environment, profile, reporter)
    }

    /** Mojang 清单路径（vanilla 原行为）：拉清单 → 定位版本 → 读 version json。 */
    private suspend fun fetchRemoteVersionDownloads(
        transport: HttpTransport,
        gameDir: File,
        abi: String,
        environment: RuleEnvironment,
        profile: VersionProfile,
        reporter: ProgressReporter,
    ): DownloadArtifacts {
        val source = MojangVersionSource(transport)
        reporter.stage(LaunchStage.FETCH_MANIFEST, "拉取版本清单")
        val manifest = source.fetchManifest()
        val entry = manifest.find(profile.minecraftVersion)
            ?: throw LaunchException(
                "版本清单里找不到 Minecraft ${profile.minecraftVersion}（禁止回退到 latest）",
            )
        val versionJsonText = source.readText(entry.url)
        val version = VersionJson.parse(versionJsonText)
        if (version.mainClass == null) {
            // parse 只在「无 mainClass 但有 inheritsFrom」时放行到这里：清单路径不做继承合并
            // （父 JSON 只保证存在于本地安装目录），显式中止而不是拉起一个必然起不来的版本。
            throw LaunchException(
                "远程 version json（${version.id}）缺少 mainClass 且声明 inheritsFrom='${version.inheritsFrom}'，" +
                    "清单路径不支持继承合并；请使用本地已安装的版本启动",
            )
        }
        reporter.stage(LaunchStage.FETCH_MANIFEST, "版本 ${version.id}", 100)
        return planAndDownload(transport, gameDir, abi, environment, reporter, version, versionJsonText, writeVersionJson = true)
    }

    /**
     * 本地版本路径：读安装产物 JSON → `inheritsFrom` 递归合并 → id 强制以目录名为准
     * （对照 ZL2 GameJsonMerger.kt:134 `outputJson.addProperty("id", outputFolder.name)`）→
     * 库/资源照常规划下载（安装器已放好的文件由 sha1/大小校验直接跳过）。
     * 版本 JSON 本身不回写：合并产物只在内存里，覆盖会毁掉安装器留下的 inheritsFrom 引用。
     */
    private suspend fun fetchLocalVersionDownloads(
        transport: HttpTransport,
        gameDir: File,
        versionsDir: File,
        localJsonFile: File,
        abi: String,
        environment: RuleEnvironment,
        profile: VersionProfile,
        reporter: ProgressReporter,
    ): DownloadArtifacts {
        reporter.stage(LaunchStage.FETCH_MANIFEST, "发现本地版本 JSON：$localJsonFile（跳过清单拉取）")
        val mergedText = try {
            VersionJsonMerger.merge(localJsonFile.readText(Charsets.UTF_8)) { inherits ->
                val parentFile = File(File(versionsDir, inherits), "$inherits.json")
                if (!parentFile.isFile) {
                    throw LaunchException(
                        "父版本 JSON 不存在：$parentFile（'${localJsonFile.name}' 声明 inheritsFrom='$inherits'，禁止静默跳过继承）",
                    )
                }
                // 在这里先解析一遍：父 JSON 坏掉时报错能带父文件的路径，而不是笼统算到子文件头上
                val parentText = parentFile.readText(Charsets.UTF_8)
                try {
                    Json.parse(parentText)
                } catch (e: JsonParseException) {
                    throw LaunchException("父版本 JSON 解析失败：$parentFile（${e.message}）", e)
                }
                parentText
            }
        } catch (e: JsonParseException) {
            throw LaunchException("本地版本 JSON 解析失败：$localJsonFile（${e.message}）", e)
        }
        val parsed = try {
            VersionJson.parse(mergedText)
        } catch (e: JsonParseException) {
            throw LaunchException("合并后的版本 JSON 解析失败：$localJsonFile（${e.message}）", e)
        } catch (e: IllegalArgumentException) {
            throw LaunchException("合并后的版本 JSON 缺少必需字段：$localJsonFile（${e.message}）", e)
        }
        // Forge/Fabric 安装产物目录名 = 版本串，JSON 内 id 可能不同；本地分支强制以目录名为准。
        val idNote = if (parsed.id != profile.minecraftVersion) {
            "，JSON id='${parsed.id}' 按目录名 '${profile.minecraftVersion}' 为准"
        } else {
            ""
        }
        val version = parsed.copy(id = profile.minecraftVersion)
        reporter.stage(LaunchStage.FETCH_MANIFEST, "本地版本 ${version.id}（继承合并完成$idNote）", 100)

        val artifacts = planAndDownload(transport, gameDir, abi, environment, reporter, version, mergedText, writeVersionJson = false)
        return artifacts.copy(clientJarFile = ensureLocalClientJar(transport, gameDir, version))
    }

    /** 清单/本地两条路径共用的「规划 + 下载」尾巴（库 → asset index → asset objects）。 */
    private suspend fun planAndDownload(
        transport: HttpTransport,
        gameDir: File,
        abi: String,
        environment: RuleEnvironment,
        reporter: ProgressReporter,
        version: VersionJson,
        versionJsonText: String,
        writeVersionJson: Boolean,
    ): DownloadArtifacts {
        val downloadPlan = VersionDownloadPlanner(gameDir, abi, environment).plan(version)
        // version json 不在 DownloadPlan.requests 里，由调用方自己写盘（见 VersionDownloadPlanner 契约）；
        // 本地分支不写：磁盘上那份是安装产物（含 inheritsFrom），覆盖会破坏后续启动的合并输入。
        if (writeVersionJson) {
            downloadPlan.versionJsonFile.parentFile?.mkdirs()
                ?: throw LaunchException("无法创建版本目录：${downloadPlan.versionJsonFile.parentFile}")
            downloadPlan.versionJsonFile.writeText(versionJsonText, Charsets.UTF_8)
        }

        val downloader = FileDownloader(transport)
        val batch = BatchDownloader(downloader)
        reporter.stage(LaunchStage.DOWNLOAD_VERSION, "规划出 ${downloadPlan.requests.size} 个下载项")
        val versionResult = batch.downloadAll(downloadPlan.requests) { p ->
            reporter.stage(
                LaunchStage.DOWNLOAD_VERSION,
                p.currentUrl,
                if (p.total > 0) p.completed * 100 / p.total else 100,
            )
        }
        if (!versionResult.isFullSuccess) {
            throw LaunchException(
                "版本文件下载失败（${versionResult.failed.size} 项）：" +
                    versionResult.failed.joinToString("; ") { "${it.request.url} → ${it.reason}" },
            )
        }
        reporter.stage(LaunchStage.DOWNLOAD_VERSION, "版本文件就绪", 100)

        val assetObjects = if (version.assetIndex != null) {
            val indexFile = downloadPlan.assetIndexFile
                ?: throw LaunchException("version json 有 assetIndex 但规划器未产出索引文件")
            if (!indexFile.isFile) {
                throw LaunchException("资源索引下载后仍不存在：$indexFile")
            }
            AssetIndexParser.parse(indexFile.readText(Charsets.UTF_8))
        } else {
            emptyList()
        }
        val assetPlan = AssetDownloadPlanner(gameDir).plan(assetObjects)
        reporter.stage(LaunchStage.DOWNLOAD_ASSETS, "资源对象 ${assetObjects.size} 个 / 需下载 ${assetPlan.requests.size} 个")
        val assetResult = batch.downloadAll(assetPlan.requests) { p ->
            reporter.stage(
                LaunchStage.DOWNLOAD_ASSETS,
                p.currentUrl,
                if (p.total > 0) p.completed * 100 / p.total else 100,
            )
        }
        if (!assetResult.isFullSuccess) {
            throw LaunchException(
                "资源文件下载失败（${assetResult.failed.size} 项）：" +
                    assetResult.failed.joinToString("; ") { "${it.request.url} → ${it.reason}" },
            )
        }
        reporter.stage(LaunchStage.DOWNLOAD_ASSETS, "资源文件就绪", 100)
        return DownloadArtifacts(version, versionJsonText, downloadPlan, downloadPlan.clientJarFile)
    }

    /**
     * 本地版本的 client jar：`<versions>/<jar|id>/<jar|id>.jar`（`jar` 字段决定文件名，
     * 无则用合并后 JSON 的 id；Forge 的 `<id>.jar` 是安装器写的桩 jar，语义同 ZL2
     * LaunchArgs 的 clientJar=版本自身 jar）。已存在直接信任（安装器产物，不做 sha1
     * 校验——ZL2 同款把版本自身 jar 原样送进 classpath）；缺失且有 downloads.client.url
     * 才下载，两者都没有显式报错，不静默回退。
     */
    private fun ensureLocalClientJar(transport: HttpTransport, gameDir: File, version: VersionJson): File {
        val jarName = version.jar ?: version.id
        val clientJarFile = File(File(File(gameDir, VersionDownloadPlanner.VERSIONS_DIR), jarName), "$jarName.jar")
        if (clientJarFile.isFile) return clientJarFile
        val client = version.client
        val url = client?.url
        if (url.isNullOrBlank()) {
            throw LaunchException(
                "客户端 jar 不存在且 version json 未提供 downloads.client.url：$clientJarFile（禁止静默回退）",
            )
        }
        val result = FileDownloader(transport).download(
            DownloadRequest(
                url = url,
                target = clientJarFile,
                expectedSha1 = client?.sha1,
                expectedSize = client?.size ?: -1L,
            ),
        )
        if (result is DownloadResult.Failure) {
            throw LaunchException("客户端 jar 下载失败：$url → ${result.reason}", result.cause)
        }
        return clientJarFile
    }

    /** JRE 选择：profile 显式指定优先，否则按 version.json 的 javaVersion.majorVersion；缺失即显式报错。 */
    fun selectJre(profile: VersionProfile, version: VersionJson): String {
        val override = profile.javaRuntime
        if (!override.isNullOrBlank()) return override
        val major = version.javaVersion?.majorVersion
            ?: throw LaunchException(
                "version json 缺少 javaVersion.majorVersion 且 profile 未指定 javaRuntime，无法确定 Java 运行时（禁止静默回退）",
            )
        return when {
            major >= 21 -> "jre-21"
            major >= 17 -> "jre-17"
            else -> throw LaunchException(
                "Java $major 不受支持：assets 只提供 jre-17 / jre-21（禁止静默回退到默认运行时）",
            )
        }
    }

    private fun runtimeJavaVersion(jreName: String, version: VersionJson): Int =
        version.javaVersion?.majorVersion
            ?: Regex("(\\d+)").find(jreName)?.value?.toIntOrNull()
            ?: throw LaunchException("无法确定运行时 $jreName 的 Java 版本")

    /**
     * 主进程挂起入口：完成全部准备并落盘 LaunchPlan。
     * [environmentProvider] 可注入，便于纯 JVM 单测跑完整阶段序列。
     */
    suspend fun prepare(
        context: Context?,
        profile: VersionProfile,
        transport: HttpTransport = UrlConnectionTransport(),
        /** 共享的 gameHome，持有 versions JSON/JAR、libraries、assets 等可复用核心文件。 */
        gameDir: File = VPaths.gameDir,
        /** Minecraft 的 mutable working directory；null 时按全局 ZL2 目录模式解析。 */
        contentDir: File? = null,
        /** JVM 启动计划记录的全局模式快照；纯 JVM 测试可显式注入，app流程读VPaths。 */
        gameDirectoryMode: GameDirectoryMode? = null,
        /** 版本 JSON `${natives_directory}` 的展开目录（ZL2 用其缓存目录：可读可写）。 */
        nativesDir: File = VPaths.cache,
        /** 版本 JSON `${user_home}` 的展开目录。 */
        userHome: File = VPaths.filesExternal,
        abi: String = DEFAULT_ABI,
        environmentProvider: LaunchEnvironmentProvider = AndroidLaunchEnvironmentProvider(
            checkNotNull(context) { "prepare 需要 Context 或显式注入 environmentProvider" },
        ),
        onProgress: (LaunchProgress) -> Unit = {},
    ): LaunchPlan {
        val reporter = ProgressReporter(onProgress)
        val resolvedMode = gameDirectoryMode ?: if (context == null) GameDirectoryMode.SHARED else VPaths.gameDirectoryMode
        val resolvedGameDir = contentDir ?: if (context == null) {
            gameDir
        } else {
            GameDirectoryResolver.resolve(gameDir, profile, resolvedMode)
        }
        if (!gameDir.mkdirs() && !gameDir.isDirectory) {
            throw LaunchException("无法创建共享游戏元数据目录：$gameDir")
        }
        if (!resolvedGameDir.mkdirs() && !resolvedGameDir.isDirectory) {
            throw LaunchException("无法创建实例游戏工作目录：$resolvedGameDir")
        }
        val environment = RuleEnvironment.android(abi)
        val artifacts = fetchAndInstallDownloads(transport, gameDir, abi, environment, profile, reporter)
        val version = artifacts.version

        reporter.stage(LaunchStage.INSTALL_JRE, "选择运行时")
        val jreName = selectJre(profile, version)
        val runtimeHome = environmentProvider.ensureJre(jreName) { reporter.stage(LaunchStage.INSTALL_JRE, it) }
        reporter.stage(LaunchStage.INSTALL_JRE, "运行时 $jreName 就绪", 100)

        reporter.stage(LaunchStage.INSTALL_COMPONENTS, "准备六件启动组件")
        environmentProvider.ensureComponents { reporter.stage(LaunchStage.INSTALL_COMPONENTS, it) }
        reporter.stage(LaunchStage.INSTALL_COMPONENTS, "组件就绪", 100)

        reporter.stage(LaunchStage.BUILD_CLASSPATH, "过滤规则并去重")
        val classpath = ClasspathBuilder.build(
            version,
            artifacts.plan.librariesDir,
            artifacts.clientJarFile,
            environment,
        )
        if (classpath.isEmpty()) {
            throw LaunchException("拼出的 classpath 为空，拒绝拉起 JVM")
        }
        // LWJGL 组件 jar 必须排在 Mojang 库之前：补丁版 org.lwjgl 类要赢过原版同名类（ZL2 同款顺序）。
        val lwjgl = environmentProvider.lwjglComponent(version)
        if (lwjgl.classpathJars.isEmpty()) {
            throw LaunchException("LWJGL 组件没有产出 classpath jar（${lwjgl.componentDir}），拒绝无 LWJGL 启动")
        }
        reporter.stage(
            LaunchStage.BUILD_CLASSPATH,
            "LWJGL ${lwjgl.classpathJars.size} 个 jar（${lwjgl.componentDir}）+ 库 ${classpath.size} 个",
            100,
        )

        reporter.stage(LaunchStage.BUILD_ARGUMENTS, "展开离线账号占位符")
        val argumentContext = ArgumentContext(
            versionId = version.id,
            versionType = version.type ?: "release",
            gameDir = resolvedGameDir,
            assetsDir = artifacts.plan.assetsDir,
            assetIndexId = version.assetIndex?.id ?: version.assets ?: version.id,
            librariesDir = File(gameDir, "libraries"),
        )
        val gameArgs = GameArgumentExpander.build(versionJsonText = artifacts.versionJsonText, context = argumentContext, environment = environment)
        // 版本 JSON 的 JVM 参数段（ZL2 getMinecraftJVMArgs 同款展开；classpath/cp token
        // 丢弃，buildJvmArgs 末尾统一追加）。纯 vanilla 也带 -Djava.library.path 等四项。
        val jsonJvmArgs = GameArgumentExpander.buildJvmArgs(
            versionJsonText = artifacts.versionJsonText,
            context = argumentContext,
            environment = environment,
            classpathPaths = (lwjgl.classpathJars + classpath).map { it.absolutePath },
            nativesDir = nativesDir,
            userHome = userHome,
            launcherName = VLaunchArgs.LAUNCHER_NAME,
            launcherVersion = "1.0",
        )
        reporter.stage(LaunchStage.BUILD_ARGUMENTS, "${gameArgs.size} 个参数 + jvm 段 ${jsonJvmArgs.size} 条", 100)

        val runtime = VRuntime(
            name = jreName,
            versionString = null,
            arch = NativeClassifiers.mojangArch(abi),
            javaVersion = runtimeJavaVersion(jreName, version),
            isProvidedByLauncher = true,
            isJDK8 = false,
        )
        // 模组版 JSON 的 mainClass 应在继承合并后由父版本提供；整条链都拿不到就显式中止。
        val mainClass = version.mainClass
            ?: throw LaunchException("version json（${version.id}）合并继承链后仍缺少 mainClass，无法启动（禁止静默回退）")
        val plan = LaunchPlan.build(
            profile = profile,
            gameDirectory = resolvedGameDir,
            gameDirectoryMode = resolvedMode,
            runtime = runtime,
            runtimeHome = runtimeHome,
            classpath = lwjgl.classpathJars + classpath,
            mainClass = mainClass,
            gameArgs = gameArgs,
            lwjglComponentDir = lwjgl.componentDir,
            jsonJvmArgs = jsonJvmArgs,
        )

        reporter.stage(LaunchStage.WRITE_PLAN, "写入跨进程启动计划")
        val file = planFile(gameDir, profile.id)
        file.parentFile?.mkdirs()
            ?: throw LaunchException("无法创建启动计划目录：${file.parentFile}")
        file.writeText(LaunchPlanCodec.encode(plan), Charsets.UTF_8)
        reporter.stage(LaunchStage.READY, "准备完成，交棒 :game 进程", 100)
        return plan
    }

    /** 同步阻塞版，供 app 侧用普通 Thread 调用（app 不直接依赖协程 API）。 */
    fun prepareBlocking(
        context: Context,
        profile: VersionProfile,
        transport: HttpTransport = UrlConnectionTransport(),
        onProgress: (LaunchProgress) -> Unit = {},
    ): LaunchPlan = GameDirectoryOperationLock.withLock(VPaths.gameDirectoryLockFile) {
        runBlocking(Dispatchers.IO) {
            prepare(context, profile, transport, onProgress = onProgress)
        }
    }

    /**
     * 回调版：内部起普通 Thread，进度/成功/失败都回调出去，调用方自行切换 UI 线程。
     * 返回该 Thread 便于调用方持有/命名，不吞异常。
     */
    fun prepareAsync(
        context: Context,
        profile: VersionProfile,
        transport: HttpTransport = UrlConnectionTransport(),
        onProgress: (LaunchProgress) -> Unit = {},
        onSuccess: (LaunchPlan) -> Unit = {},
        onError: (Throwable) -> Unit = {},
    ): Thread = Thread {
        try {
            onSuccess(prepareBlocking(context, profile, transport, onProgress))
        } catch (t: Throwable) {
            onError(t)
        }
    }.apply {
        name = "vaelin-prepare-${profile.id}"
        start()
    }

    /**
     * :game 进程入口：读落盘的 LaunchPlan → 调 VaelinLauncher.launch（阻塞到游戏结束）。
     * extraJvmArgs 传 emptyList()：VaelinLauncher 内部会把 profile.extraJvmArgs 与它相加，
     * 而用户参数已经在 LaunchPlan.toProfile() 里，重复传会导致参数出现两遍。
     */
    fun launchFromGameProcess(
        context: Context,
        versionId: String,
        screenWidth: Int,
        screenHeight: Int,
    ): Int = GameDirectoryOperationLock.withLock(VPaths.gameDirectoryLockFile) {
        launchFromGameProcessLocked(context, versionId, screenWidth, screenHeight)
    }

    private fun launchFromGameProcessLocked(
        context: Context,
        versionId: String,
        screenWidth: Int,
        screenHeight: Int,
    ): Int {
        val gameDirectoryMode = VPaths.gameDirectoryMode
        val gameDirectory = VPaths.resolveGameDir(versionId)
        val file = planFile(VPaths.gameDir, versionId)
        if (!file.isFile) {
            throw LaunchException("启动计划不存在：$file（主进程 prepare 未成功或已完成？拒绝静默回退）")
        }
        val plan = LaunchPlanCodec.decode(file.readText(Charsets.UTF_8))
        if (plan.profileId != versionId || plan.gameDirectoryMode != gameDirectoryMode) {
            throw LaunchException("启动计划实例/目录模式与 :game Intent 不一致：${plan.profileId}/${plan.gameDirectoryMode}")
        }
        if (File(plan.gameDirectory).canonicalFile != gameDirectory.canonicalFile) {
            throw LaunchException("启动计划游戏目录与当前实例目录不一致：${plan.gameDirectory} != $gameDirectory")
        }
        return runBlocking(Dispatchers.IO) {
            VaelinLauncher.launch(
                context = context,
                gameDir = gameDirectory,
                runtimeHome = File(plan.runtimeHome),
                runtime = plan.toVRuntime(),
                profile = plan.toProfile(),
                classpath = plan.classpathFiles(),
                mainClass = plan.mainClass,
                gameArgs = plan.gameArgs,
                extraJvmArgs = emptyList(),
                lwjglComponentDir = plan.lwjglComponentDir,
                jsonJvmArgs = plan.jsonJvmArgs,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
            )
        }
    }
}
