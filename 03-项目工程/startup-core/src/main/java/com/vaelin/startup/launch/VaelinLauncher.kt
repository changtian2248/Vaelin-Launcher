/*
 * Vaelin Launcher
 *
 * JVM 拉起主流程（R1 · T1.5）。
 *
 * 六步顺序是硬约束，任何一步都不能提前或省略：
 *   1. ZLBridge.setLdLibraryPath
 *   2. 设置环境变量（Os.setenv）
 *   3. dlopenJavaRuntime
 *   4. dlopenEngine
 *   5. 生成 JVM 参数
 *   6. VMLauncher.launchJVM 并返回 exit code
 *
 * 参数生成、LD_LIBRARY_PATH、环境变量、目录布局解析全部在 [VLaunchArgs] 里，
 * 那一层不引用 Android API，可以在普通 JVM 单测里断言。
 */
package com.vaelin.startup.launch

import android.content.Context
import android.os.Build
import android.system.Os
import com.movtery.zalithlauncher.bridge.LoggerBridge
import com.movtery.zalithlauncher.bridge.ZLBridge
import com.oracle.dalvik.VMLauncher
import com.vaelin.startup.components.VComponents
import com.vaelin.startup.plugin.PluginRenderersBootstrap
import com.vaelin.startup.multirt.VRuntime
import com.vaelin.startup.profile.VersionProfile
import com.vaelin.startup.renderer.RendererLibPathsProvider
import com.vaelin.startup.renderer.RendererLibraryLoader
import com.vaelin.startup.renderer.VaelinRenderers
import com.vaelin.startup.util.Architecture
import com.vaelin.startup.util.VLog
import com.vaelin.startup.util.VPaths
import com.vaelin.startup.util.getSystemDnsServerAddresses
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.TimeZone

object VaelinLauncher {

    private const val TAG = "VaelinLauncher"

    /**
     * 拉起 JVM 并阻塞到游戏进程结束。
     *
     * 这是整条启动链的核心：它不负责拼 classpath、不负责下载资源，只负责把
     * 已经准备好的运行时、参数和环境交给 JLI。
     *
     * 前置条件：`VPaths.init(context)` 已经执行；`runtimeHome` 是已解压的 JRE 根目录；
     * `classpath` 里的文件存在且是最终启动用的 classpath。
     *
     * @return JVM 的退出码（0 表示正常退出）。
     */
    suspend fun launch(
        context: Context,
        gameDir: File,
        runtimeHome: File,
        runtime: VRuntime,
        profile: VersionProfile,
        classpath: List<File>,
        mainClass: String,
        gameArgs: List<String>,
        extraJvmArgs: List<String>,
        lwjglComponentDir: String,
        /** 版本 JSON `arguments.jvm` 展开结果；安装器 JVM 等无版本 JSON 的场景留空。 */
        jsonJvmArgs: List<String> = emptyList(),
        screenWidth: Int,
        screenHeight: Int,
        useLocalLanguage: Boolean = true,
    ): Int = withContext(Dispatchers.IO) {
        require(runtimeHome.isDirectory) { "运行时目录不存在: ${runtimeHome.absolutePath}" }
        require(gameDir.isDirectory) { "游戏目录不存在: ${gameDir.absolutePath}" }
        require(classpath.isNotEmpty()) { "classpath 为空，无法启动" }
        require(mainClass.isNotBlank()) { "mainClass 为空" }
        require(lwjglComponentDir.isNotBlank()) { "lwjglComponentDir 为空，无法拼 LWJGL natives 路径" }
        require(screenWidth > 0 && screenHeight > 0) { "窗口尺寸非法: ${screenWidth}x$screenHeight" }

        val isJava8 = runtime.isJDK8
        val javaHome = VLaunchArgs.javaHome(runtimeHome, isJava8)
        val libDir = VLaunchArgs.resolveLibDir(javaHome, runtime.arch)
        val jvmLibDir = VLaunchArgs.resolveJvmLibDir(javaHome, libDir)
        val lwjglNativesDir = resolveLwjglNativesDir(lwjglComponentDir)

        // ---- 步骤 1.5：渲染器选择（必须在 applyEnv 之前：env 要合并 getRendererEnv）----
        // :game 进程的注册表是独立的，这里幂等 init + 按 profile 双键选择，都失败即显式抛错。
        VaelinRenderers.init()
        // 插件渲染器也要在 :game 进程补注册：主进程的注册表不会跨进程带过来，
        // 不扫的话 profile 选中的插件渲染器会在这里抛「渲染器不存在」。
        // 扫描失败只记日志（插件是外部变量，不能挡内置渲染器启动），见 PluginRenderersBootstrap。
        PluginRenderersBootstrap.scanAndRegister(context)
        selectRenderer(profile)
        val renderer = VaelinRenderers.getCurrentRenderer()

        val cacioComponent =
            if (isJava8) VComponents.Component.CACIOCAVALLO else VComponents.Component.CACIOCAVALLO17
        val cacioDir = VComponents.location(cacioComponent)
        val cacioAgent = if (isJava8) null else File(cacioDir, "cacio-agent.jar").takeIf { it.isFile }

        // ---- 步骤 0.9：补齐 JRE 缺失的启动器侧 native ----
        // 临时二分（2026-11-08）：禁用拷贝——XToolkit 的 loadLibrary("awt_xawt") 会按
        // sun.boot.library.path 顺序命中 nativeLibDir 里的同一份文件，拷贝理论上冗余。
        // 若禁用后静默 abort 消失 → 「拷进 lib 的文件」是触发物；若仍在 → 「带桩的假 xawt」。
        if (System.getenv("VAELIN_DISABLE_XAWT_COPY") == null) {
            ensureLauncherNatives(javaHome)
        }

        val resolvConfPath = ensureResolvConf(context, gameDir)
        val libraryPath = VLaunchArgs.buildLibraryPath(
            VLaunchArgs.LibraryPathInput(
                javaHome = javaHome,
                libDir = libDir,
                jvmLibDir = jvmLibDir,
                jnaDir = VPaths.jnaDir,
                runtimeModDir = VPaths.runtimeModDir,
                lwjglNativesDir = lwjglNativesDir,
                nativeLibDir = VPaths.nativeLibDir,
            )
        )

        VLog.info(
            TAG,
            "JVM 启动: runtime=${runtime.name} java8=$isJava8 " +
                "libDir=$libDir jvmLib=${jvmLibDir} arch=${runtime.arch} renderer=${renderer.getRendererName()}"
        )
        VLog.debug(TAG, "LD_LIBRARY_PATH=$libraryPath")

        // ---- 步骤 1：设置动态链接器搜索路径 ----
        ZLBridge.setLdLibraryPath(libraryPath)

        // ---- 步骤 2：环境变量（ZL2 GameLauncher.setRendererEnv 同款渲染器 env 面）----
        // POJAV_RENDERER 必须由启动器直接注入（ZL2 GameLauncher.kt:489）：pojavInitOpenGL /
        // gl_bridge / sdl_hook 都用它判定渲染器类型，缺失时 strncmp(NULL) 直接 SIGSEGV
        // （真机 hs_err 实证，MC 渲染线程 glfwInit → pojavInit）。
        // 2026-11-08 性能排查：此前只注入 POJAV_RENDERER + getRendererEnv，缺 gl4es 的
        // LIBGL_* 块（NOERROR/MIPMAP/NOINTOVLHACK），是渲染负载掉帧的主因之一。
        applyEnv(
            buildEnv(context, runtimeHome, javaHome, libraryPath, screenWidth, screenHeight) +
                VLaunchArgs.buildRendererEnv(
                    VLaunchArgs.RendererEnvInput(
                        rendererId = renderer.getRendererId(),
                        rendererEGL = renderer.getRendererEGL(),
                        rendererEnv = renderer.getRendererEnv().value,
                        rendererLibrary = renderer.getRendererLibrary(),
                        mesaBased = renderer.mesaBased,
                        needsZinkDriverOverride = renderer.needsZinkDriverOverride,
                        nativeLibDir = VPaths.nativeLibDir,
                        cacheDir = VPaths.cache,
                    ),
                ),
        )

        // ---- 步骤 3：dlopen Java 运行时 ----
        dlopenJavaRuntime(javaHome, libDir, jvmLibDir)

        // ---- 步骤 4：dlopen 引擎库（OpenAL）----
        dlopenEngine()

        // ---- 步骤 4.5：dlopen 渲染器库（显式失败，禁静默回退）----
        val loadedRenderer = RendererLibraryLoader(
            libPaths = RendererLibPathsProvider.fromVPaths(),
            dlopen = { ZLBridge.dlopen(it) },
        ).load(renderer)
        VLog.info(
            TAG,
            "渲染器库已加载: ${loadedRenderer.mainLibrary} (${loadedRenderer.mainLibraryPath}) " +
                "附加库 ${loadedRenderer.extraLibraries.size} 个",
        )

        // ---- 步骤 5：生成 JVM 参数 ----
        val jvmArgs = VLaunchArgs.buildJvmArgs(
            VLaunchArgs.JvmArgsInput(
                javaHome = javaHome,
                cacheDir = VPaths.cache,
                nativeLibDir = VPaths.nativeLibDir,
                gameDir = gameDir,
                filesPrivateDir = VPaths.filesPrivate,
                userHome = VPaths.filesExternal,
                lwjglNativesDir = lwjglNativesDir,
                resolvConfPath = resolvConfPath,
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                useLocalLanguage = useLocalLanguage,
                userLanguage = Locale.getDefault().language,
                userCountry = Locale.getDefault().country,
                userTimezone = TimeZone.getDefault().id,
                osVersion = "Android-${Build.VERSION.RELEASE}",
                socName = getSocName(),
                userArguments = VLaunchArgs.parseUserArguments(profile.extraJvmArgs + extraJvmArgs),
                memoryMB = profile.memoryMB,
                availableProcessors = Runtime.getRuntime().availableProcessors(),
                cacioDir = cacioDir,
                cacioAgentJar = cacioAgent,
                isJava8 = isJava8,
                classpath = classpath,
                mainClass = mainClass,
                gameArgs = gameArgs,
                jsonJvmArgs = jsonJvmArgs,
                rendererLibName = loadedRenderer.mainLibrary,
            )
        )
        logJvmArgs(jvmArgs)

        // ---- 步骤 5.5：接 JVM 日志 ----
        // stdio_is.c 把 JVM 的 stdout/stderr 重定向到 LoggerBridge.start 的文件里；
        // 不 start 的话 JVM（包括 exit() 钩子里的 jvmExit 日志）的输出全部落空，
        // 启动失败时没有任何可查的证据。每次启动覆盖上一份（与 ZL2 latestlog 语义一致）。
        startGameLog()

        // ---- 步骤 6：交给 JLI ----
        // setupExitMethod / initializeGameExitHook 必须在 launchJVM 之前绑定，
        // 否则 JVM 退出时收不到回调，进程会僵死。
        ZLBridge.setupExitMethod(context.applicationContext)
        ZLBridge.initializeGameExitHook()
        val chdirResult = ZLBridge.chdir(gameDir.absolutePath)
        if (chdirResult != 0) {
            VLog.error(TAG, "chdir 失败: ${gameDir.absolutePath} => $chdirResult")
        }

        // jre_launcher.c 把 argv[0] 当 progname，JVM 参数必须从 argv[1] 开始解析。
        val argv = VLaunchArgs.withJavaExecutable(runtimeHome, jvmArgs).toTypedArray()
        VLog.info(TAG, "launchJVM: ${argv.size} 条参数，mainClass=$mainClass")
        VMLauncher.launchJVM(argv)
    }

    /**
     * 补齐 JRE 缺失的启动器侧 native（当前只有假 xawt）。
     * 每次启动无条件覆盖：启动器升级后假 xawt 可能新增符号（如 Font_initIDs 桩），
     * 幂等跳过会让旧文件一直驻留，症状是「改了代码但行为不变」。
     * 源文件缺失即显式抛错：没有它 cacio 初始化必炸，静默继续只会把错误推到更难查的地方。
     */
    private fun ensureLauncherNatives(javaHome: File) {
        val target = File(javaHome, "lib/libawt_xawt.so")
        val source = File(VPaths.nativeLibDir, "libawt_xawt.so")
        require(source.isFile) { "启动器假 xawt 缺失: ${source.absolutePath}，cacio/AWT 初始化必然失败" }
        runCatching {
            if (target.exists() && !target.delete()) {
                error("无法覆盖旧的假 xawt: ${target.absolutePath}")
            }
            source.copyTo(target, overwrite = true)
        }.onSuccess {
            VLog.info(TAG, "已补齐 ${target.absolutePath}（来自 ${source.absolutePath}）")
        }.getOrThrow()
    }

    /**
     * 先按 uniqueIdentifier 选渲染器，再按 getRendererId() 兜底（两者都是用户配置的
     * 合法选择键）；都失败显式抛错，不做「回退到首个渲染器」的静默降级。
     */
    private fun selectRenderer(profile: VersionProfile) {
        runCatching { VaelinRenderers.setCurrentRenderer(profile.rendererUniqueId) }
            .recover { VaelinRenderers.setCurrentRendererById(profile.rendererId) }
            .getOrElse {
                throw IllegalStateException(
                    "渲染器解析失败: id=${profile.rendererId} uniqueId=${profile.rendererUniqueId}",
                    it,
                )
            }
    }

    /**
     * 打开 JVM 侧日志文件并交给 LoggerBridge（stdio_is 的重定向落点）。
     * 失败只记错误不抛——日志缺位不该阻断启动，但会让下次排查无据可查。
     */
    private fun startGameLog() {
        runCatching {
            val logsDir = File(VPaths.filesExternal, "logs")
            if (!logsDir.isDirectory) logsDir.mkdirs()
            val logFile = File(logsDir, "latestlog.txt")
            if (logFile.exists()) logFile.delete()
            if (!logFile.createNewFile()) error("无法创建游戏日志文件: ${logFile.absolutePath}")
            LoggerBridge.start(logFile.absolutePath)
            VLog.info(TAG, "游戏日志重定向到 ${logFile.absolutePath}")
        }.onFailure { VLog.error(TAG, "启动游戏日志失败（继续启动，但 JVM 输出不可查）", it) }
    }

    /**
     * 生成环境变量映射。DALVIK_JAVAVM / DALVIK_APPLICATION 是 JNI 桥的裸指针，
     * 取不到时跳过并告警（不阻断启动，但 AWT/剪贴板相关能力会失效）。
     */
    private fun buildEnv(
        context: Context,
        runtimeHome: File,
        javaHome: File,
        ldLibraryPath: String,
        screenWidth: Int,
        screenHeight: Int,
    ): Map<String, String> {
        val javaVmPointer = runCatching { ZLBridge.getJavaVMPointer().toString() }
            .onFailure { VLog.error(TAG, "获取 JVM 指针失败", it) }
            .getOrNull()
        val applicationPointer = runCatching { ZLBridge.jObjectToString(context.applicationContext) }
            .onFailure { VLog.error(TAG, "获取 Application 指针失败", it) }
            .getOrNull()

        return VLaunchArgs.buildEnvMap(
            VLaunchArgs.EnvInput(
                runtimeHome = runtimeHome,
                javaHome = javaHome,
                nativeLibDir = VPaths.nativeLibDir,
                filesExternalDir = VPaths.filesExternal,
                cacheDir = VPaths.cache,
                runtimeModDir = VPaths.runtimeModDir,
                ldLibraryPath = ldLibraryPath,
                existingPath = System.getenv("PATH"),
                screenWidth = screenWidth,
                screenHeight = screenHeight,
                javaVmPointer = javaVmPointer,
                applicationPointer = applicationPointer,
            )
        )
    }

    private fun applyEnv(env: Map<String, String>) {
        val failures = mutableListOf<String>()
        for ((key, value) in env) {
            runCatching { Os.setenv(key, value, true) }
                .onFailure { failures += "$key=$value (${it.message ?: it::class.java.simpleName})" }
        }
        // 这些变量是子进程 JVM 的运行时契约（JAVA_HOME / LD_LIBRARY_PATH / HOME 等），
        // 静默失败会让 JVM 读到错误环境，症状出现在完全无关的地方。
        if (failures.isNotEmpty()) {
            throw IllegalStateException(
                "环境变量设置失败（${failures.size}/${env.size}），子进程 JVM 会读到错误环境：\n" +
                    failures.joinToString("\n") { "  - $it" }
            )
        }
    }

    /**
     * 先按固定顺序 dlopen 10 个关键库，再递归 dlopen 运行时目录下其余 .so。
     *
     * 固定的 10 个不能省：libjli 是 JLI 入口，libjvm 是虚拟机本体，
     * 其余是 AWT/字体/网络/nio 的依赖，漏掉任何一个都会在 MC 启动阶段崩。
     */
    private fun dlopenJavaRuntime(javaHome: File, libDir: String, jvmLibDir: String) {
        val jliDir = VLaunchArgs.resolveJliDir(javaHome, libDir)
        val jvmDir = File(javaHome, "$libDir/$jvmLibDir".trimStart('/', '\\'))

        val required = listOf(
            File(jliDir, "libjli.so"),
            File(jvmDir, "libjvm.so"),
            VLaunchArgs.resolveLibFile(javaHome, libDir, "libfreetype.so"),
            VLaunchArgs.resolveLibFile(javaHome, libDir, "libverify.so"),
            VLaunchArgs.resolveLibFile(javaHome, libDir, "libjava.so"),
            VLaunchArgs.resolveLibFile(javaHome, libDir, "libnet.so"),
            VLaunchArgs.resolveLibFile(javaHome, libDir, "libnio.so"),
            VLaunchArgs.resolveLibFile(javaHome, libDir, "libawt.so"),
            VLaunchArgs.resolveLibFile(javaHome, libDir, "libawt_headless.so"),
            VLaunchArgs.resolveLibFile(javaHome, libDir, "libfontmanager.so"),
        )

        val loaded = LinkedHashSet<String>(required.size * 2)
        val failures = mutableListOf<String>()
        for (library in required) {
            if (!library.isFile) {
                failures += "缺失 ${library.absolutePath}"
                continue
            }
            val ok = runCatching { ZLBridge.dlopen(library.absolutePath) }.getOrDefault(false)
            if (ok) {
                loaded.add(library.absolutePath)
            } else {
                failures += "dlopen 失败 ${library.absolutePath}"
            }
        }
        // 这 10 个库缺任何一个，JVM 都会在后面某个远离此处的地方崩掉，
        // 现场信息全丢。这里显式失败并带完整清单。
        if (failures.isNotEmpty()) {
            throw IllegalStateException(
                "JRE 运行时库加载失败（${failures.size}/${required.size}），游戏必然无法启动：\n" +
                    failures.joinToString("\n") { "  - $it" }
            )
        }

        val remaining = VLaunchArgs.collectSharedLibraries(javaHome)
            .filter { it.absolutePath !in loaded }
        VLog.info(TAG, "递归 dlopen 其余 ${remaining.size} 个 .so")
        for (library in remaining) {
            val ok = runCatching { ZLBridge.dlopen(library.absolutePath) }.getOrDefault(false)
            if (!ok) VLog.debug(TAG, "递归 dlopen 失败: ${library.absolutePath}")
        }
    }

    /** 引擎库只开 OpenAL；渲染器库由渲染器初始化阶段自己 dlopen（R1 不在本函数范围）。 */
    private fun dlopenEngine() {
        val openal = File(VPaths.nativeLibDir, "libopenal.so")
        if (!openal.isFile) {
            throw IllegalStateException("音频引擎库缺失: ${openal.absolutePath}")
        }
        val ok = runCatching { ZLBridge.dlopen(openal.absolutePath) }.getOrDefault(false)
        // -Dorg.lwjgl.openal.libname 指向这个文件，LWJGL 在 SoundManager 初始化时会硬加载，
        // 提前失败比在游戏内崩掉更容易定位。
        if (!ok) {
            throw IllegalStateException("dlopen 失败: ${openal.absolutePath}")
        }
    }

    /**
     * LWJGL natives 目录：主进程 prepare 选定的组件目录（[LaunchPlan.lwjglComponentDir]，
     * 如 `lwjgl/3.3.3`）下按 ABI 分子目录，与 classpath 里的组件 jar 出自同一组件，保证版本一致。
     *
     * 目录本身可能还没落地（如 `:jvm` 安装器进程不装组件），这里只负责拼路径不校验存在性：
     * ld 搜索路径与 -Dorg.lwjgl.librarypath 都需要它。
     */
    private fun resolveLwjglNativesDir(lwjglComponentDir: String): File {
        val abi = Architecture.archAsStringAndroid(Architecture.getDeviceArchitecture())
        return File(VPaths.componentsDir, "$lwjglComponentDir/natives/$abi")
    }

    /**
     * 生成 resolv.conf。写入失败不阻断启动（ext.net.resolvPath 仍指向该路径，
     * JVM 侧解析失败时会有自己的报错），只记日志。
     */
    private fun ensureResolvConf(context: Context, gameDir: File): String {
        val file = File(gameDir, "resolv.conf")
        runCatching {
            val servers = getSystemDnsServerAddresses(context).orEmpty()
            val preferChina = Locale.getDefault().country.equals("CN", ignoreCase = true)
            file.parentFile?.mkdirs()
            file.writeText(VLaunchArgs.buildResolvConf(servers, preferChina))
        }.onFailure { VLog.error(TAG, "写入 resolv.conf 失败: ${file.absolutePath}", it) }
        return file.absolutePath
    }

    /** SoC 型号：先读系统属性，失败回退 Build.HARDWARE（与参考实现一致）。 */
    fun getSocName(): String {
        val fromProperty = runCatching {
            val process = ProcessBuilder("getprop", "ro.soc.model").start()
            process.inputStream.bufferedReader().use { it.readLine() }
        }.getOrNull()?.trim()
        return fromPropOrHardware(fromProperty)
    }

    private fun fromPropOrHardware(value: String?): String =
        if (!value.isNullOrBlank()) value else Build.HARDWARE

    private fun logJvmArgs(args: List<String>) {
        val masked = maskSensitive(args)
        VLog.debug(TAG, "JVM 参数 ${masked.size} 条:")
        masked.forEachIndexed { index, arg -> VLog.debug(TAG, "  [$index] $arg") }
        // 游戏日志文件（LoggerBridge）依赖 native 已加载，失败不影响启动。
        runCatching {
            LoggerBridge.appendTitle("Vaelin JVM arguments")
            masked.forEach { LoggerBridge.appendInfo(it) }
        }
    }

    /** accessToken 这类凭据不进日志。 */
    private fun maskSensitive(args: List<String>): List<String> {
        var maskNext = false
        return args.map { arg ->
            when {
                maskNext -> {
                    maskNext = false
                    "<hidden>"
                }
                arg == "--accessToken" || arg == "--access-token" -> {
                    maskNext = true
                    arg
                }
                arg.startsWith("--accessToken=") || arg.startsWith("--access-token=") -> "<hidden>"
                else -> arg
            }
        }
    }
}
