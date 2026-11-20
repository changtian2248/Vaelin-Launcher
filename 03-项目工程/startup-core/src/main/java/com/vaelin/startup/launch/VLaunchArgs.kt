/*
 * Vaelin Launcher
 *
 * 启动参数生成层：全部是纯 JVM 逻辑（File / String / List），不引用任何 Android API，
 * 因此可以在 src/test 的普通 JVM 单测里直接断言，不需要 Robolectric 或 mock。
 *
 * 唯一的外部依赖是 VLog，而 VLog 内部把 android.util.Log 包在 runCatching 里，
 * 未初始化时直接返回，所以单测调用是安全的。
 */
package com.vaelin.startup.launch

import com.vaelin.startup.util.VLog
import com.vaelin.startup.util.splitPreservingQuotes
import java.io.File

/**
 * JVM 参数、LD_LIBRARY_PATH、环境变量的纯生成逻辑。
 *
 * 与参考实现（ZalithLauncher2 的 Launcher.kt）的对应关系：
 *  - 布局解析对应 getJavaLibDir / getJvmLibDir / locateLibs
 *  - [buildLibraryPath] 对应 getRuntimeLibraryPath（去掉 Vaelin R1 不引入的插件段）
 *  - [buildJvmArgs] 对应 getJavaArgs + progressFinalUserArgs + getCacioJavaArgs
 */
object VLaunchArgs {

    private const val TAG = "VLaunchArgs"

    /** 写进 -Dnet.minecraft.clientmodname 的启动器名，任务书固定值。 */
    const val LAUNCHER_NAME = "Vaelin"

    /**
     * 允许用户参数覆盖的 -D 键。顺序即生成顺序（与任务书 §4 的清单一致）。
     * 用户参数里出现同键时跳过并记日志。
     */
    val OVERRIDABLE_D_KEYS: List<String> = listOf(
        "java.home",
        "java.io.tmpdir",
        "jna.boot.library.path",
        "user.home",
        "user.language",
        "user.country",
        "user.timezone",
        "os.name",
        "os.version",
        "pojav.path.minecraft",
        "pojav.path.private.account",
        "org.lwjgl.vulkan.libname",
        "org.lwjgl.librarypath",
        "glfwstub.windowWidth",
        "glfwstub.windowHeight",
        "glfwstub.initEgl",
        "ext.net.resolvPath",
        "log4j2.formatMsgNoLookups",
        "java.rmi.server.useCodebaseOnly",
        "com.sun.jndi.rmi.object.trustURLCodebase",
        "com.sun.jndi.cosnaming.object.trustURLCodebase",
        "net.minecraft.clientmodname",
        "fml.earlyprogresswindow",
        "fml.ignoreInvalidMinecraftCertificates",
        "fml.ignorePatchDiscrepancies",
        "loader.disable_forked_guis",
        "jdk.lang.Process.launchMechanism",
        "sodium.checks.issue2561",
        "file.encoding",
        "sun.stdout.encoding",
        "sun.stderr.encoding",
        "cpu.name",
    )

    /** 追加内存/库参数前必须从用户参数里清掉的项（去掉 -D 前缀后避免误伤，前缀匹配）。 */
    val PURGED_PREFIXES: List<String> = listOf(
        "-Xms",
        "-Xmx",
        "-d32",
        "-d64",
        "-Xint",
        "-XX:+UseTransparentHugePages",
        "-XX:+UseLargePagesInMetaspace",
        "-XX:+UseLargePages",
        "-Dorg.lwjgl.opengl.libname",
        "-Dorg.lwjgl.freetype.libname",
        "-XX:ActiveProcessorCount",
    )

    /** JDK17+ cacio 需要的 --add-exports（10 条）。 */
    val CACIOCAVALLO17_ADD_EXPORTS: List<String> = listOf(
        "--add-exports=java.desktop/java.awt=ALL-UNNAMED",
        "--add-exports=java.desktop/java.awt.peer=ALL-UNNAMED",
        "--add-exports=java.desktop/sun.awt.image=ALL-UNNAMED",
        "--add-exports=java.desktop/sun.java2d=ALL-UNNAMED",
        "--add-exports=java.desktop/java.awt.dnd.peer=ALL-UNNAMED",
        "--add-exports=java.desktop/sun.awt=ALL-UNNAMED",
        "--add-exports=java.desktop/sun.awt.event=ALL-UNNAMED",
        "--add-exports=java.desktop/sun.awt.datatransfer=ALL-UNNAMED",
        "--add-exports=java.desktop/sun.font=ALL-UNNAMED",
        "--add-exports=java.base/sun.security.action=ALL-UNNAMED",
    )

    /** JDK17+ cacio 需要的 --add-opens（6 条）。 */
    val CACIOCAVALLO17_ADD_OPENS: List<String> = listOf(
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.desktop/java.awt=ALL-UNNAMED",
        "--add-opens=java.desktop/sun.font=ALL-UNNAMED",
        "--add-opens=java.desktop/sun.java2d=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.net=ALL-UNNAMED",
    )

    // ---------------------------------------------------------------------
    // 运行时目录布局解析
    // ---------------------------------------------------------------------

    /** JDK8 的 java.home 是 JRE 子目录，JDK17+ 就是运行时根目录。 */
    fun javaHome(runtimeHome: File, isJDK8: Boolean): File =
        if (isJDK8) File(runtimeHome, "jre") else runtimeHome

    /**
     * 解析 JVM 库目录（相对 java.home）：优先 `lib/<arch>`，不存在则退回 `lib`。
     *
     * arch 来自 VRuntime.arch，可能是 "arm64" / "aarch64" / "i386/i486/i586" 这类写法，
     * 因此逐段尝试并补上同类别名；都没有时扫描 lib 下带 server|client 的目录。
     */
    fun resolveLibDir(javaHome: File, arch: String?): String {
        val libRoot = File(javaHome, "lib")
        if (!libRoot.isDirectory) return "/lib"

        val candidates = archCandidates(arch)
        for (candidate in candidates) {
            if (File(libRoot, candidate).isDirectory) return "/lib/$candidate"
        }

        val scanned = libRoot.listFiles()
            ?.filter { it.isDirectory && it.name != "jli" }
            ?.firstOrNull { dir ->
                File(dir, "server/libjvm.so").isFile || File(dir, "client/libjvm.so").isFile
            }
        return if (scanned != null) "/lib/${scanned.name}" else "/lib"
    }

    /** 有 libjvm.so 的 server 子目录优先，否则 client。 */
    fun resolveJvmLibDir(javaHome: File, libDir: String): String =
        if (under(javaHome, "$libDir/server/libjvm.so").isFile) "server" else "client"

    /**
     * libjli.so 的目录：优先 `lib/<arch>/jli`，退回 `lib/jli`，最后退回 `lib/` 本身。
     * 最后一级对齐 ZL2 Launcher.kt:513——ZL2 发行包的 bin-<arch>.tar.xz 把 libjli.so
     * 平铺在 lib/ 下（无 jli 子目录），缺这层兜底会报「缺失 lib/jli/libjli.so」。
     */
    fun resolveJliDir(javaHome: File, libDir: String): File {
        val primary = under(javaHome, "$libDir/jli")
        if (File(primary, "libjli.so").isFile) return primary
        val fallback = under(javaHome, "lib/jli")
        if (File(fallback, "libjli.so").isFile) return fallback
        val flat = under(javaHome, libDir)
        if (File(flat, "libjli.so").isFile) return flat
        return primary
    }

    /** 找 JRE 的单个 .so：优先 lib/<arch>，退回 lib。 */
    fun resolveLibFile(javaHome: File, libDir: String, name: String): File {
        val primary = under(javaHome, "$libDir/$name")
        if (primary.isFile) return primary
        val fallback = under(javaHome, "lib/$name")
        return if (fallback.isFile) fallback else primary
    }

    /** 递归收集运行时目录下所有 .so（参考实现的 locateLibs），排序保证结果稳定。 */
    fun collectSharedLibraries(root: File): List<File> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .maxDepth(12)
            .onEnter { it.canRead() }
            .filter { it.isFile && it.name.endsWith(".so") }
            .toList()
            .sortedBy { it.absolutePath }
    }

    // ---------------------------------------------------------------------
    // LD_LIBRARY_PATH
    // ---------------------------------------------------------------------

    data class LibraryPathInput(
        val javaHome: File,
        val libDir: String,
        val jvmLibDir: String,
        val jnaDir: File,
        val runtimeModDir: File,
        val lwjglNativesDir: File,
        val nativeLibDir: String,
    )

    /**
     * LD_LIBRARY_PATH 的段序（01-文档/05-启动基底复用清单.md §10.6 的系统段之后顺序不变）。
     *
     * **首段必须是 `<javaHome>/lib/server`（jvm 目录）**：上游 JDK launcher 的
     * RequiresSetenv（java_md.c:276-281）用「LD_LIBRARY_PATH 是否以 jvmpath 目录为前缀」
     * 判定是否需要 re-exec 自身；re-exec 模板（java_md.c:366-372）也正是把 jvm 目录
     * 放第一段（`LD_LIBRARY_PATH=%s:%s/lib:%s/../lib`）。首段不是 jvm 目录时
     * ContainsLibJVM 命中 lib/server 段 → execv `<home>/bin/java` → Android 10+
     * W^X 拒绝执行应用私有目录文件 → "Error: trying to exec" → exit(1)（真机实测）。
     *
     * `lib/jli` 字面量段保留在第二位（JLI 目录契约），`lib` 段是 launcher 模板的
     * 第二、三项（jre/lib 与 jre/../lib），一并前置避免后续 dedupe。
     */
    fun buildLibraryPath(input: LibraryPathInput, libName: String = "lib64"): String {
        val jvmDir = under(input.javaHome, "${input.libDir}/${input.jvmLibDir}")
        val segments = listOf(
            jvmDir.absolutePath,
            under(input.javaHome, "lib/jli").absolutePath,
            under(input.javaHome, "lib").absolutePath,
            "/system/$libName",
            "/vendor/$libName",
            "/vendor/$libName/hw",
            "/system_ext/$libName",
            File(input.jnaDir, "jna").absolutePath,
            input.runtimeModDir.absolutePath,
            input.lwjglNativesDir.absolutePath,
            input.nativeLibDir,
        )
        return segments.joinToString(":")
    }

    // ---------------------------------------------------------------------
    // JVM 参数
    // ---------------------------------------------------------------------

    data class JvmArgsInput(
        val javaHome: File,
        val cacheDir: File,
        val nativeLibDir: String,
        val gameDir: File,
        val filesPrivateDir: File,
        val userHome: File,
        val lwjglNativesDir: File,
        val resolvConfPath: String,
        val screenWidth: Int,
        val screenHeight: Int,
        val useLocalLanguage: Boolean,
        val userLanguage: String,
        val userCountry: String,
        val userTimezone: String,
        val osVersion: String,
        val socName: String,
        val userArguments: List<String>,
        val memoryMB: Int,
        val availableProcessors: Int,
        val cacioDir: File,
        val cacioAgentJar: File?,
        val isJava8: Boolean,
        val classpath: List<File>,
        val mainClass: String,
        val gameArgs: List<String>,
        /** 版本 JSON `arguments.jvm` 展开结果（prepare 期已展开落 plan）。 */
        val jsonJvmArgs: List<String> = emptyList(),
        val launcherName: String = LAUNCHER_NAME,
        /** 当前渲染器主库裸名（如 libgl4es_114.so）；null = 未选渲染器，不注入 libname。 */
        val rendererLibName: String? = null,
    )

    /**
     * 生成最终 JVM 参数（不含 args[0] 的 java 可执行文件，由 JLI 入口自己补）。
     *
     * 顺序：用户参数 → 可覆盖 -D（跳过用户同键）→ purge → 内存/库组 → cacio →
     * -cp / mainClass / 游戏参数。
     */
    fun buildJvmArgs(input: JvmArgsInput): List<String> {
        val args = ArrayList<String>(input.userArguments.size + 96)
        args.addAll(input.userArguments)
        // 版本 JSON 的 JVM 参数段（ZL2 getMinecraftJVMArgs 的产出）：用户参数之后、
        // 启动器自管 -D 之前——后者最后出现，同键时以启动器的值为准（last wins）。
        args.addAll(input.jsonJvmArgs)
        args.addAll(buildOverridableArgs(input, input.userArguments))
        purgeUserArguments(args)
        // 渲染器主库：用户同名 -D 已被 purge 清掉，这里由启动器统一注入
        // （ZL2 progressFinalUserArgs 同款；LWJGL 经 LD_LIBRARY_PATH 解析裸名，
        // nativeLibDir 在 LD 段内，APK 内置或用户 renderers/ 目录替换版都能命中）
        input.rendererLibName?.let { args.add("-Dorg.lwjgl.opengl.libname=$it") }
        // boot 类（java.awt.Toolkit 等）的 System.loadLibrary 只搜 sun.boot.library.path，
        // 不认 LD_LIBRARY_PATH：JRE 自带 natives 在 lib/，而 cacio 的 CTCToolkit 初始化会
        // loadLibrary("awt_xawt")——JRE 包里没有这个库（Pojav 构建已移除，由启动器 APK 的
        // 假 xawt 顶替），缺此路径必报 "Can't load library: <java.home>/lib/libawt_xawt.so"。
        args.add(
            "-Dsun.boot.library.path=" +
                File(input.javaHome, "lib").absolutePath + ":" + input.nativeLibDir,
        )
        args.addAll(buildMemoryArgs(input))
        args.addAll(
            buildCacioArgs(
                isJava8 = input.isJava8,
                screenWidth = input.screenWidth,
                screenHeight = input.screenHeight,
                cacioDir = input.cacioDir,
                cacioAgentJar = input.cacioAgentJar,
            )
        )
        args.add("-cp")
        args.add(input.classpath.joinToString(":") { it.absolutePath })
        args.add(input.mainClass)
        args.addAll(input.gameArgs)
        return args
    }

    /**
     * jre_launcher.c:159-165 把 argv[0] 当 progname 传给 JLI_Launch，
     * JVM 参数从 argv[1] 起解析——所以命令行首必须补上 java 可执行文件。
     */
    fun withJavaExecutable(runtimeHome: File, args: List<String>): List<String> =
        listOf(File(runtimeHome, "bin/java").absolutePath) + args

    /** 用户参数里是否已经有同一个 -D 键（覆盖判断用）。 */
    fun userHasKey(userArgs: List<String>, key: String): Boolean =
        userArgs.any { it == "-D$key" || it.startsWith("-D$key=") }

    /** 把用户输入的 JVM 参数行按引号规则摊平成独立参数。 */
    fun parseUserArguments(raw: List<String>): List<String> =
        raw.flatMap { it.splitPreservingQuotes() }.filter { it.isNotBlank() }

    /** 任务书 §4 的 -D 全集；用户已提供同键的项跳过。 */
    fun buildOverridableArgs(input: JvmArgsInput, userArgs: List<String>): List<String> {
        val result = ArrayList<String>(OVERRIDABLE_D_KEYS.size)
        fun add(key: String, value: String) {
            if (userHasKey(userArgs, key)) {
                VLog.info(TAG, "跳过 -D$key：用户参数已提供同键")
                return
            }
            result.add("-D$key=$value")
        }

        add("java.home", input.javaHome.absolutePath)
        add("java.io.tmpdir", input.cacheDir.absolutePath)
        add("jna.boot.library.path", input.nativeLibDir)
        add("user.home", input.userHome.absolutePath)
        if (input.useLocalLanguage) {
            add("user.language", input.userLanguage)
            add("user.country", input.userCountry)
        }
        add("user.timezone", input.userTimezone)
        add("os.name", "Linux")
        add("os.version", input.osVersion)
        add("pojav.path.minecraft", input.gameDir.absolutePath)
        add("pojav.path.private.account", input.filesPrivateDir.absolutePath)
        add("org.lwjgl.vulkan.libname", "libvulkan.so")
        add("org.lwjgl.librarypath", input.lwjglNativesDir.absolutePath)
        add("glfwstub.windowWidth", input.screenWidth.toString())
        add("glfwstub.windowHeight", input.screenHeight.toString())
        add("glfwstub.initEgl", "false")
        add("ext.net.resolvPath", input.resolvConfPath)
        add("log4j2.formatMsgNoLookups", "true")
        add("java.rmi.server.useCodebaseOnly", "true")
        add("com.sun.jndi.rmi.object.trustURLCodebase", "false")
        add("com.sun.jndi.cosnaming.object.trustURLCodebase", "false")
        add("net.minecraft.clientmodname", input.launcherName)
        add("fml.earlyprogresswindow", "false")
        add("fml.ignoreInvalidMinecraftCertificates", "true")
        add("fml.ignorePatchDiscrepancies", "true")
        add("loader.disable_forked_guis", "true")
        add("jdk.lang.Process.launchMechanism", "FORK")
        add("sodium.checks.issue2561", "false")
        add("file.encoding", "UTF-8")
        add("sun.stdout.encoding", "UTF-8")
        add("sun.stderr.encoding", "UTF-8")
        add("cpu.name", input.socName)
        return result
    }

    /** 内存、OpenAL、LWJGL 本地库与 CPU 核数参数（purge 之后追加）。 */
    fun buildMemoryArgs(input: JvmArgsInput): List<String> = listOf(
        "-Xms${input.memoryMB}M",
        "-Xmx${input.memoryMB}M",
        "-Dorg.lwjgl.openal.libname=${input.nativeLibDir}/libopenal.so",
        "-Dorg.lwjgl.freetype.libname=${input.lwjglNativesDir.absolutePath}/libfreetype.so",
        "-Dorg.lwjgl.spvc.libname=spirv-cross-c-shared",
        "-Dorg.lwjgl.system.allocator=system",
        "-XX:ActiveProcessorCount=${input.availableProcessors}",
    )

    /** 删除用户传来的、必须由启动器统一控制的参数。 */
    fun purgeUserArguments(args: MutableList<String>) {
        val before = args.size
        args.removeAll { candidate -> PURGED_PREFIXES.any { candidate.startsWith(it) } }
        val removed = before - args.size
        if (removed > 0) VLog.info(TAG, "purge 掉 $removed 条用户参数")
    }

    /**
     * cacio（AWT）参数。
     *
     * JDK8 用 net.java.openjdk.cacio 并追加 -Xbootclasspath/p:；
     * JDK17+ 用 com.github.caciocavallosilano.cacio，必须挂 javaagent、
     * 10 条 --add-exports 与 6 条 --add-opens，再追加 -Xbootclasspath/a:。
     *
     * 注：任务书写 14 条 add-exports/opens，参考实现实为 10 + 6 = 16 条，
     * 这里按参考实现落 16 条（少一条 JDK17 下 AWT 反射会失败）。
     */
    fun buildCacioArgs(
        isJava8: Boolean,
        screenWidth: Int,
        screenHeight: Int,
        cacioDir: File,
        cacioAgentJar: File?,
    ): List<String> {
        val args = ArrayList<String>(24)
        args.add("-Djava.awt.headless=false")
        args.add("-Dcacio.managed.screensize=${screenWidth}x${screenHeight}")
        args.add("-Dcacio.font.fontmanager=sun.awt.X11FontManager")
        args.add("-Dcacio.font.fontscaler=sun.font.FreetypeFontScaler")
        args.add("-Dswing.defaultlaf=javax.swing.plaf.nimbus.NimbusLookAndFeel")

        if (isJava8) {
            args.add("-Dawt.toolkit=net.java.openjdk.cacio.ctc.CTCToolkit")
            args.add("-Djava.awt.graphicsenv=net.java.openjdk.cacio.ctc.CTCGraphicsEnvironment")
        } else {
            args.add("-Dawt.toolkit=com.github.caciocavallosilano.cacio.ctc.CTCToolkit")
            args.add("-Djava.awt.graphicsenv=com.github.caciocavallosilano.cacio.ctc.CTCGraphicsEnvironment")
            if (cacioAgentJar != null && cacioAgentJar.isFile) {
                args.add("-javaagent:${cacioAgentJar.absolutePath}")
            } else {
                VLog.error(TAG, "JDK17+ 缺少 cacio agent，AWT 将不可用: ${cacioAgentJar?.absolutePath ?: "null"}")
            }
            args.addAll(CACIOCAVALLO17_ADD_EXPORTS)
            args.addAll(CACIOCAVALLO17_ADD_OPENS)
        }

        val jars = jarFiles(cacioDir)
        if (jars.isNotEmpty()) {
            val prefix = if (isJava8) "-Xbootclasspath/p:" else "-Xbootclasspath/a:"
            args.add(prefix + jars.joinToString(":") { it.absolutePath })
        } else {
            VLog.error(TAG, "cacio 目录下没有 jar: ${cacioDir.absolutePath}")
        }
        return args
    }

    // ---------------------------------------------------------------------
    // 环境变量
    // ---------------------------------------------------------------------

    data class EnvInput(
        val runtimeHome: File,
        val javaHome: File,
        val nativeLibDir: String,
        val filesExternalDir: File,
        val cacheDir: File,
        val runtimeModDir: File,
        val ldLibraryPath: String,
        val existingPath: String?,
        val screenWidth: Int,
        val screenHeight: Int,
        val javaVmPointer: String?,
        val applicationPointer: String?,
    )

    /**
     * 环境变量集合。DALVIK_JAVAVM / DALVIK_APPLICATION 来自 JNI 桥，
     * 纯逻辑层只负责放进 map；取不到时由调用点告警。
     */
    fun buildEnvMap(input: EnvInput): Map<String, String> {
        val env = LinkedHashMap<String, String>(16)
        env["POJAV_NATIVEDIR"] = input.nativeLibDir
        env["JAVA_HOME"] = input.javaHome.absolutePath
        env["HOME"] = input.filesExternalDir.absolutePath
        env["TMPDIR"] = input.cacheDir.absolutePath
        env["LD_LIBRARY_PATH"] = input.ldLibraryPath
        env["PATH"] = listOfNotNull(
            File(input.runtimeHome, "bin").absolutePath,
            input.existingPath?.takeIf { it.isNotBlank() },
        ).joinToString(":")
        env["AWTSTUB_WIDTH"] = input.screenWidth.toString()
        env["AWTSTUB_HEIGHT"] = input.screenHeight.toString()
        env["MOD_ANDROID_RUNTIME"] = input.runtimeModDir.absolutePath
        input.javaVmPointer?.let { env["DALVIK_JAVAVM"] = it }
        input.applicationPointer?.let { env["DALVIK_APPLICATION"] = it }
        env["ALSOFT_DRIVERS"] = "opensl"
        return env
    }

    /**
     * 渲染器环境变量输入（ZL2 GameLauncher.setRendererEnv 的输入面）。
     */
    data class RendererEnvInput(
        val rendererId: String,
        val rendererEGL: String?,
        val rendererEnv: Map<String, String>,
        val rendererLibrary: String?,
        val mesaBased: Boolean,
        val needsZinkDriverOverride: Boolean,
        /** 应用原生库目录（VPaths.nativeLibDir 字符串形式）。 */
        val nativeLibDir: String,
        val cacheDir: File,
    )

    /**
     * 渲染器环境变量，语义逐项对齐 ZL2 GameLauncher.setRendererEnv（2026-11-08 性能排查：
     * 此前 Vaelin 只注入 POJAV_RENDERER，缺 gl4es 的 LIBGL_* 块——LIBGL_NOERROR 缺失时
     * gl4es 每次 GL 调用都要做错误状态同步，是渲染负载掉帧的主因之一）。
     *
     * 与 ZL2 的差异（有意为之）：LIBGL_ES 兜底不做 EGL 硬件探测（ZL2 getDetectedVersion），
     * 直接按 rendererId 推导（opengles3* → 3，其余 → 2）；R1 活动渲染器 opengles2 走
     * 显式分支，不受影响。SFPEW 单缓冲呈现优化（Android 15+ 特性门控）R1 不引入。
     */
    fun buildRendererEnv(input: RendererEnvInput): Map<String, String> {
        val env = LinkedHashMap<String, String>()
        // ZL2 把渲染器 id 写给 SDL 的 OpenGL 库选择；Vaelin 无 SDL 路径，保留以对齐 env 面
        env["SDL_OPENGL_LIBRARY"] = input.rendererId

        if (input.rendererId.startsWith("opengles2")) {
            env["LIBGL_ES"] = "2"
            env["LIBGL_MIPMAP"] = "3"
            env["LIBGL_NOERROR"] = "1"
            env["LIBGL_NOINTOVLHACK"] = "1"
            env["LIBGL_NORMALIZE"] = "1"
        }

        // 渲染器自声明 env 优先级低于上面的固定块（ZL2 同序：固定块先写，renderer env 覆盖）
        env.putAll(input.rendererEnv)

        input.rendererEGL?.let { eglName ->
            env["POJAVEXEC_EGL"] = eglName
            val eglPath = if (eglName.startsWith("/")) {
                eglName
            } else {
                File(input.nativeLibDir, eglName).absolutePath
            }
            if (eglPath.isNotEmpty() && File(eglPath).isFile) {
                env["SDL_EGL_LIBRARY"] = eglPath
            }
        }

        env["POJAV_RENDERER"] = input.rendererId

        if (input.mesaBased) {
            // Mesa 系渲染器的 OSMesa 桥依据该变量定位 Mesa 库
            env["LIB_MESA_NAME"] = input.rendererLibrary ?: "null"
            env["MESA_GLSL_CACHE_DIR"] = input.cacheDir.absolutePath
        }

        if (input.needsZinkDriverOverride) {
            env["MESA_LOADER_DRIVER_OVERRIDE"] = "zink"
            env["MESA_GL_VERSION_OVERRIDE"] = "4.6"
            env["MESA_GLSL_VERSION_OVERRIDE"] = "460"
            env["force_glsl_extensions_warn"] = "true"
            env["allow_higher_compat_version"] = "true"
            env["allow_glsl_extension_directive_midshader"] = "true"
        }

        // LIBGL_ES 兜底：ZL2 用 EGL 探测 GLES 主版本 <3 时回退 2（Vaelin 简化为按 id 推导，
        // "2 是整个应用的最低要求"语义保留在空串分支）
        if (!env.containsKey("LIBGL_ES")) {
            env["LIBGL_ES"] = if (input.rendererId.startsWith("opengles")) {
                input.rendererId.replace("opengles", "").replace("_5", "").ifEmpty { "2" }
            } else {
                "3"
            }
        }
        return env
    }

    /**
     * resolv.conf 内容：系统 DNS（过滤 IPv6）在前，公共 DNS 兜底并去重。
     * Android 上 glibc 风格的 DNS 解析器需要这个文件。
     */
    fun buildResolvConf(servers: List<String>, preferChinaDns: Boolean): String {
        val publicDns = if (preferChinaDns) {
            listOf("223.5.5.5", "119.29.29.29")
        } else {
            listOf("1.1.1.1", "1.0.0.1")
        }
        val all = LinkedHashSet<String>()
        servers.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.contains(":") }
            .forEach { all.add(it) }
        all.addAll(publicDns)
        return all.joinToString("\n") { "nameserver $it" }
    }

    // ---------------------------------------------------------------------
    // 内部工具
    // ---------------------------------------------------------------------

    private fun under(root: File, relative: String): File =
        File(root, relative.trimStart('/', '\\'))

    private fun archCandidates(arch: String?): List<String> {
        val segments = arch?.split("/")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: return emptyList()
        val out = LinkedHashSet<String>()
        for (segment in segments) {
            out.add(segment)
            out.addAll(aliasesFor(segment))
        }
        return out.toList()
    }

    private fun aliasesFor(segment: String): List<String> = when (segment.lowercase()) {
        "arm64" -> listOf("aarch64")
        "aarch64" -> listOf("arm64")
        "arm", "armhf", "armv7", "armv7l" -> listOf("armhf", "armv7", "armv7l")
        "x86_64", "amd64", "x64" -> listOf("amd64", "x64")
        "x86", "i386", "i486", "i586", "i686" -> listOf("i386", "i486", "i586", "i686")
        else -> emptyList()
    }

    private fun jarFiles(dir: File): List<File> =
        dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".jar", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: emptyList()
}
