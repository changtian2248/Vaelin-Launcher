package com.vaelin.startup.launch

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 启动参数生成层的契约测试。
 *
 * 这里只测纯 JVM 逻辑（[VLaunchArgs]），不碰 ZLBridge / VMLauncher / Os.setenv。
 * 目录布局全部在临时目录里构造，跑完删除。
 */
class VLaunchArgsTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("vaelin-launch").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    // ------------------------------------------------------------------
    // 目录布局
    // ------------------------------------------------------------------

    @Test
    fun `运行时目录优先 lib 加 arch，缺失时回退 lib`() {
        val standard = dir("jre-std")
        okFile(File(standard, "lib/server/libjvm.so"))

        // 标准 OpenJDK 布局：lib/server/libjvm.so，lib 下没有 arch 子目录
        assertEquals("/lib", VLaunchArgs.resolveLibDir(standard, null))
        assertEquals("/lib", VLaunchArgs.resolveLibDir(standard, "arm64"))
        assertEquals("server", VLaunchArgs.resolveJvmLibDir(standard, "/lib"))

        // Pojav 风格布局：lib/arm64/server/libjvm.so，arch 写成 aarch64 也要能命中
        val pojav = dir("jre-pojav")
        okFile(File(pojav, "lib/arm64/server/libjvm.so"))
        assertEquals("/lib/arm64", VLaunchArgs.resolveLibDir(pojav, "aarch64"))
        assertEquals("server", VLaunchArgs.resolveJvmLibDir(pojav, "/lib/arm64"))
    }

    @Test
    fun `JDK8 的 java home 落在 jre 子目录`() {
        assertEquals(File(root, "jre"), VLaunchArgs.javaHome(root, isJDK8 = true))
        assertEquals(root, VLaunchArgs.javaHome(root, isJDK8 = false))
    }

    @Test
    fun `libjli 目录优先 arch 子目录再回退 lib jli`() {
        val archFirst = dir("jre-archlib")
        okFile(File(archFirst, "lib/arm64/jli/libjli.so"))
        assertEquals(
            File(archFirst, "lib/arm64/jli"),
            VLaunchArgs.resolveJliDir(archFirst, "/lib/arm64")
        )

        val fallback = dir("jre-fallback")
        okFile(File(fallback, "lib/jli/libjli.so"))
        assertEquals(
            File(fallback, "lib/jli"),
            VLaunchArgs.resolveJliDir(fallback, "/lib/arm64")
        )
    }

    @Test
    fun `libjli 平铺在 lib 下时直接用 lib 目录（ZL2 发行包布局）`() {
        // ZL2 的 bin-<arch>.tar.xz 没有 jli 子目录，libjli.so 平铺在 lib/ 下
        // （对齐 ZL2 Launcher.kt:513 的 else javaLibDir 兜底；真机首启缺这层曾报
        //  「缺失 lib/jli/libjli.so」）
        val flat = dir("jre-flat")
        okFile(File(flat, "lib/libjli.so"))
        okFile(File(flat, "lib/server/libjvm.so"))
        assertEquals("/lib", VLaunchArgs.resolveLibDir(flat, "arm64"))
        assertEquals(
            File(flat, "lib"),
            VLaunchArgs.resolveJliDir(flat, "/lib")
        )
    }

    @Test
    fun `递归收集运行时目录下的 so 且忽略非 so`() {
        val runtime = dir("deep")
        okFile(File(runtime, "lib/liba.so"))
        okFile(File(runtime, "lib/server/libb.so"))
        okFile(File(runtime, "lib/libc.txt"))

        val found = VLaunchArgs.collectSharedLibraries(runtime).map { it.absolutePath }
        assertEquals(2, found.size)
        assertTrue(found[0].endsWith("liba.so"))
        assertTrue(found[1].endsWith("libb.so"))
    }

    // ------------------------------------------------------------------
    // LD_LIBRARY_PATH
    // ------------------------------------------------------------------

    @Test
    fun `LD_LIBRARY_PATH 的段序是固定契约`() {
        val jre = dir("jre-ld")
        okFile(File(jre, "lib/jli/libjli.so"))
        okFile(File(jre, "lib/server/libjvm.so"))

        val input = VLaunchArgs.LibraryPathInput(
            javaHome = jre,
            libDir = "/lib",
            jvmLibDir = "server",
            jnaDir = dir("jna"),
            runtimeModDir = dir("mod"),
            lwjglNativesDir = dir("lwjgl"),
            nativeLibDir = "/data/app/vaelin/lib/arm64",
        )

        // 不按 ":" 切分：Windows 的盘符本身带冒号，会把绝对路径切坏。
        // 直接比对整串，段序即相等契约。
        val expected = listOf(
            File(jre, "lib/server").absolutePath,
            File(jre, "lib/jli").absolutePath,
            File(jre, "lib").absolutePath,
            "/system/lib64",
            "/vendor/lib64",
            "/vendor/lib64/hw",
            "/system_ext/lib64",
            File(input.jnaDir, "jna").absolutePath,
            input.runtimeModDir.absolutePath,
            input.lwjglNativesDir.absolutePath,
            input.nativeLibDir,
        )
        assertEquals(expected.joinToString(":"), VLaunchArgs.buildLibraryPath(input))
    }

    @Test
    fun `LD_LIBRARY_PATH 首段是 jvm 目录（免 re-exec 的前缀契约）`() {
        // 上游 JDK launcher 的 RequiresSetenv（java_md.c:276-281）要求 LD_LIBRARY_PATH
        // 以 jvmpath 目录（lib/server）开头才免于 execv <home>/bin/java 重入；
        // Android 10+ W^X 下该 execv 必然 EACCES → exit(1)（真机实测）。
        // ZL2 发行包的 JRE 把 libjli.so 平铺在 lib/（无 jli 子目录），jvm 目录与
        // lib/jli、lib 三个 JRE 段都照常保留在系统段之前。
        val flat = dir("jre-flat-ld")
        okFile(File(flat, "lib/libjli.so"))
        okFile(File(flat, "lib/server/libjvm.so"))

        val input = VLaunchArgs.LibraryPathInput(
            javaHome = flat,
            libDir = "/lib",
            jvmLibDir = "server",
            jnaDir = dir("jna2"),
            runtimeModDir = dir("mod2"),
            lwjglNativesDir = dir("lwjgl2"),
            nativeLibDir = "/data/app/vaelin/lib/arm64",
        )

        val path = VLaunchArgs.buildLibraryPath(input)
        assertTrue(
            "LD 首段必须是 jvm 目录 lib/server：$path",
            path.startsWith(File(flat, "lib/server").absolutePath + ":"),
        )
        assertTrue(
            "LD 第二段必须是字面量 lib/jli：$path",
            path.contains(":" + File(flat, "lib/jli").absolutePath + ":"),
        )
    }

    // ------------------------------------------------------------------
    // -D 全集
    // ------------------------------------------------------------------

    @Test
    fun `任务书要求的 D 键逐个出现`() {
        val input = baseInput()
        val args = VLaunchArgs.buildJvmArgs(input)

        assertHas(args, "java.home", input.javaHome.absolutePath)
        assertHas(args, "java.io.tmpdir", input.cacheDir.absolutePath)
        assertHas(args, "jna.boot.library.path", input.nativeLibDir)
        assertHas(args, "user.home", input.userHome.absolutePath)
        assertHas(args, "user.language", "zh")
        assertHas(args, "user.country", "CN")
        assertHas(args, "user.timezone", "Asia/Shanghai")
        assertHas(args, "os.name", "Linux")
        assertHas(args, "os.version", "Android-14")
        assertHas(args, "pojav.path.minecraft", input.gameDir.absolutePath)
        assertHas(args, "pojav.path.private.account", input.filesPrivateDir.absolutePath)
        assertHas(args, "org.lwjgl.vulkan.libname", "libvulkan.so")
        assertHas(args, "org.lwjgl.librarypath", input.lwjglNativesDir.absolutePath)
        assertHas(args, "glfwstub.windowWidth", "1920")
        assertHas(args, "glfwstub.windowHeight", "1080")
        assertHas(args, "glfwstub.initEgl", "false")
        assertHas(args, "ext.net.resolvPath", input.resolvConfPath)
        assertHas(args, "log4j2.formatMsgNoLookups", "true")
        assertHas(args, "java.rmi.server.useCodebaseOnly", "true")
        assertHas(args, "com.sun.jndi.rmi.object.trustURLCodebase", "false")
        assertHas(args, "com.sun.jndi.cosnaming.object.trustURLCodebase", "false")
        assertHas(args, "net.minecraft.clientmodname", "Vaelin")
        assertHas(args, "fml.earlyprogresswindow", "false")
        assertHas(args, "fml.ignoreInvalidMinecraftCertificates", "true")
        assertHas(args, "fml.ignorePatchDiscrepancies", "true")
        assertHas(args, "loader.disable_forked_guis", "true")
        assertHas(args, "jdk.lang.Process.launchMechanism", "FORK")
        assertHas(args, "sodium.checks.issue2561", "false")
        assertHas(args, "file.encoding", "UTF-8")
        assertHas(args, "sun.stdout.encoding", "UTF-8")
        assertHas(args, "sun.stderr.encoding", "UTF-8")
        assertHas(args, "cpu.name", "Snapdragon 8 Gen 2")
    }

    @Test
    fun `关闭本地语言时只跳过 language 与 country`() {
        val args = VLaunchArgs.buildJvmArgs(baseInput(useLocalLanguage = false))

        assertFalse(args.any { it.startsWith("-Duser.language=") })
        assertFalse(args.any { it.startsWith("-Duser.country=") })
        assertTrue(args.contains("-Duser.timezone=Asia/Shanghai"))
    }

    // ------------------------------------------------------------------
    // purge 与内存参数
    // ------------------------------------------------------------------

    @Test
    fun `purge 清掉用户的 Xmx Xint 与库名参数`() {
        val input = baseInput(
            userArguments = listOf(
                "-Xmx9G",
                "-Xint",
                "-d32",
                "-XX:ActiveProcessorCount=2",
                "-Dorg.lwjgl.opengl.libname=libGL.so",
                "-Dorg.lwjgl.freetype.libname=/old/libfreetype.so",
            )
        )
        val args = VLaunchArgs.buildJvmArgs(input)

        assertFalse("用户 -Xmx9G 必须被清掉", args.contains("-Xmx9G"))
        assertEquals(1, args.count { it.startsWith("-Xmx") })
        assertTrue(args.contains("-Xmx2048M"))
        assertFalse(args.contains("-Xint"))
        assertFalse(args.contains("-d32"))
        assertFalse("用户的 opengl libname 必须被清掉", args.any { it.startsWith("-Dorg.lwjgl.opengl.libname") })
        assertEquals(1, args.count { it.startsWith("-Dorg.lwjgl.freetype.libname=") })
        assertEquals(
            "-Dorg.lwjgl.freetype.libname=${input.lwjglNativesDir.absolutePath}/libfreetype.so",
            args.first { it.startsWith("-Dorg.lwjgl.freetype.libname=") }
        )
        assertEquals(1, args.count { it.startsWith("-XX:ActiveProcessorCount=") })
        assertTrue(args.contains("-XX:ActiveProcessorCount=8"))
    }

    @Test
    fun `内存参数取 profile memoryMB`() {
        val args = VLaunchArgs.buildJvmArgs(baseInput(memoryMB = 3072))

        assertTrue(args.contains("-Xms3072M"))
        assertTrue(args.contains("-Xmx3072M"))
        assertFalse(args.any { it == "-Xms2048M" })
    }

    @Test
    fun `用户显式提供的同键 D 参数不被覆盖`() {
        val args = VLaunchArgs.buildJvmArgs(
            baseInput(userArguments = listOf("-Duser.home=/sdcard/custom"))
        )

        val userHomes = args.filter { it.startsWith("-Duser.home=") }
        assertEquals(listOf("-Duser.home=/sdcard/custom"), userHomes)
    }

    // ------------------------------------------------------------------
    // cacio 分支
    // ------------------------------------------------------------------

    @Test
    fun `JDK17 的 cacio 参数带 agent 与十六项 add exports opens`() {
        val input = baseInput(isJava8 = false)
        val args = VLaunchArgs.buildJvmArgs(input)

        assertTrue(
            args.contains("-Dawt.toolkit=com.github.caciocavallosilano.cacio.ctc.CTCToolkit")
        )
        assertTrue(
            args.contains(
                "-Djava.awt.graphicsenv=com.github.caciocavallosilano.cacio.ctc.CTCGraphicsEnvironment"
            )
        )
        assertEquals(1, args.count { it.startsWith("-javaagent:") })
        assertEquals(
            "-javaagent:${input.cacioAgentJar!!.absolutePath}",
            args.first { it.startsWith("-javaagent:") }
        )
        assertEquals(10, args.count { it.startsWith("--add-exports=") })
        assertEquals(6, args.count { it.startsWith("--add-opens=") })
        assertTrue(
            args.any { it.startsWith("-Xbootclasspath/a:") }
        )
        assertFalse(args.any { it.startsWith("-Xbootclasspath/p:") })

        val expectedBoot = listOf("cacio-1.jar", "cacio-2.jar", "cacio-agent.jar")
            .map { File(input.cacioDir, it).absolutePath }
            .joinToString(":")
        assertEquals(
            "-Xbootclasspath/a:$expectedBoot",
            args.first { it.startsWith("-Xbootclasspath/a:") }
        )
    }

    @Test
    fun `JDK8 的 cacio 参数走 openjdk 包名与 p 段`() {
        val input = baseInput(isJava8 = true)
        val args = VLaunchArgs.buildJvmArgs(input)

        assertTrue(args.contains("-Dawt.toolkit=net.java.openjdk.cacio.ctc.CTCToolkit"))
        assertTrue(
            args.contains("-Djava.awt.graphicsenv=net.java.openjdk.cacio.ctc.CTCGraphicsEnvironment")
        )
        assertFalse(args.any { it.startsWith("-javaagent:") })
        assertEquals(0, args.count { it.startsWith("--add-exports=") })
        assertEquals(0, args.count { it.startsWith("--add-opens=") })
        assertTrue(args.any { it.startsWith("-Xbootclasspath/p:") })
        assertFalse(args.any { it.startsWith("-Xbootclasspath/a:") })
    }

    // ------------------------------------------------------------------
    // 用户参数解析与命令行尾部
    // ------------------------------------------------------------------

    @Test
    fun `带空格的用户参数按引号规则拆分`() {
        val parsed = VLaunchArgs.parseUserArguments(listOf("-Duser.home=\"/sdcard/My Games\" -Xmx2G"))
        assertEquals(listOf("-Duser.home=/sdcard/My Games", "-Xmx2G"), parsed)

        val args = VLaunchArgs.buildJvmArgs(
            baseInput(userArguments = VLaunchArgs.parseUserArguments(listOf("-Dcustom.key=\"a b\"")))
        )
        assertTrue(args.contains("-Dcustom.key=a b"))
    }

    @Test
    fun `classpath 主类与游戏参数在参数尾部`() {
        val input = baseInput()
        val args = VLaunchArgs.buildJvmArgs(input)

        val cpIndex = args.indexOf("-cp")
        assertTrue(cpIndex >= 0)
        assertEquals(
            input.classpath.joinToString(":") { it.absolutePath },
            args[cpIndex + 1]
        )
        assertEquals(input.mainClass, args[cpIndex + 2])
        assertEquals(listOf("--username", "Steve"), args.subList(cpIndex + 3, args.size))
    }

    @Test
    fun `命令行首是 java 可执行文件路径`() {
        val runtimeHome = dir("jre")
        val jvmArgs = listOf("-Xmx1G", "-cp", "a", "Main")
        val argv = VLaunchArgs.withJavaExecutable(runtimeHome, jvmArgs)

        assertEquals(File(runtimeHome, "bin/java").absolutePath, argv.first())
        assertEquals(jvmArgs, argv.drop(1))
        assertFalse(VLaunchArgs.buildJvmArgs(baseInput()).any { it.endsWith("bin/java") })
    }

    // ------------------------------------------------------------------
    // 环境变量与 resolv.conf
    // ------------------------------------------------------------------

    @Test
    fun `环境变量的键值与本机路径一致`() {
        val runtimeHome = dir("runtime")
        val javaHome = dir("runtime", "jre")
        val env = VLaunchArgs.buildEnvMap(
            VLaunchArgs.EnvInput(
                runtimeHome = runtimeHome,
                javaHome = javaHome,
                nativeLibDir = "/data/app/native",
                filesExternalDir = dir("external"),
                cacheDir = dir("cache"),
                runtimeModDir = dir("mod"),
                ldLibraryPath = "/a:/b",
                existingPath = "/usr/bin",
                screenWidth = 1920,
                screenHeight = 1080,
                javaVmPointer = "0x1234",
                applicationPointer = "0x5678",
            )
        )

        assertEquals("/data/app/native", env["POJAV_NATIVEDIR"])
        assertEquals(javaHome.absolutePath, env["JAVA_HOME"])
        assertEquals(dir("external").absolutePath, env["HOME"])
        assertEquals(dir("cache").absolutePath, env["TMPDIR"])
        assertEquals("/a:/b", env["LD_LIBRARY_PATH"])
        assertEquals(
            File(runtimeHome, "bin").absolutePath + ":/usr/bin",
            env["PATH"]
        )
        assertEquals("1920", env["AWTSTUB_WIDTH"])
        assertEquals("1080", env["AWTSTUB_HEIGHT"])
        assertEquals("opensl", env["ALSOFT_DRIVERS"])
        assertEquals(dir("mod").absolutePath, env["MOD_ANDROID_RUNTIME"])
        assertEquals("0x1234", env["DALVIK_JAVAVM"])
        assertEquals("0x5678", env["DALVIK_APPLICATION"])
    }

    @Test
    fun `resolv conf 过滤 IPv6 并补公共 DNS`() {
        val content = VLaunchArgs.buildResolvConf(listOf("8.8.8.8", "2001:db8::1", "8.8.8.8"), false)
        assertEquals(
            "nameserver 8.8.8.8\nnameserver 1.1.1.1\nnameserver 1.0.0.1",
            content
        )
        assertFalse(content.contains("2001:db8"))

        val cn = VLaunchArgs.buildResolvConf(emptyList(), true)
        assertTrue(cn.contains("nameserver 223.5.5.5"))
        assertTrue(cn.contains("nameserver 119.29.29.29"))
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private fun baseInput(
        isJava8: Boolean = false,
        useLocalLanguage: Boolean = true,
        userArguments: List<String> = emptyList(),
        memoryMB: Int = 2048,
    ): VLaunchArgs.JvmArgsInput {
        val jre = dir("jre")
        val cacioDir = dir("components", "caciocavallo17")
        okFile(File(cacioDir, "cacio-1.jar"))
        okFile(File(cacioDir, "cacio-2.jar"))
        val agent = okFile(File(cacioDir, "cacio-agent.jar"))
        val game = dir("game")
        return VLaunchArgs.JvmArgsInput(
            javaHome = jre,
            cacheDir = dir("cache"),
            nativeLibDir = "/data/app/vaelin/lib/arm64",
            gameDir = game,
            filesPrivateDir = dir("private"),
            userHome = dir("external"),
            lwjglNativesDir = dir("lwjgl", "natives", "arm64-v8a"),
            resolvConfPath = File(game, "resolv.conf").absolutePath,
            screenWidth = 1920,
            screenHeight = 1080,
            useLocalLanguage = useLocalLanguage,
            userLanguage = "zh",
            userCountry = "CN",
            userTimezone = "Asia/Shanghai",
            osVersion = "Android-14",
            socName = "Snapdragon 8 Gen 2",
            userArguments = userArguments,
            memoryMB = memoryMB,
            availableProcessors = 8,
            cacioDir = cacioDir,
            cacioAgentJar = agent,
            isJava8 = isJava8,
            classpath = listOf(File(dir("cp"), "a.jar"), File(dir("cp"), "b.jar")),
            mainClass = "net.fabricmc.loader.impl.launch.knot.KnotClient",
            gameArgs = listOf("--username", "Steve"),
        )
    }

    private fun assertHas(args: List<String>, key: String, value: String) {
        assertTrue("缺少 -D$key=$value，实际参数: $args", args.contains("-D$key=$value"))
    }

    @Test
    fun `渲染器主库注入 libname 且用户同名键被清除`() {
        val input = baseInput(
            userArguments = listOf("-Dorg.lwjgl.opengl.libname=user-fake.so"),
        ).copy(rendererLibName = "libgl4es_114.so")

        val args = VLaunchArgs.buildJvmArgs(input)

        assertHas(args, "org.lwjgl.opengl.libname", "libgl4es_114.so")
        assertTrue(
            "用户伪造的 libname 必须被 purge 清掉: $args",
            args.none { it.contains("user-fake.so") },
        )
    }

    @Test
    fun `未选渲染器时不注入 libname`() {
        val args = VLaunchArgs.buildJvmArgs(baseInput())
        assertTrue(args.none { it.startsWith("-Dorg.lwjgl.opengl.libname") })
    }

    @Test
    fun `sun_boot_library_path 含 JRE lib 与 APK nativeLibDir`() {
        // cacio 的 CTCToolkit 初始化 loadLibrary("awt_xawt") 时，boot 类只搜
        // sun.boot.library.path；JRE 包里没有假 xawt，必须能落到 APK nativeLibDir
        val input = baseInput()
        val args = VLaunchArgs.buildJvmArgs(input)

        val expected = "-Dsun.boot.library.path=" +
            File(input.javaHome, "lib").absolutePath + ":" + input.nativeLibDir
        assertTrue("缺少 $expected，实际: $args", args.contains(expected))
    }

    private fun dir(vararg parts: String): File {
        var current = root
        for (part in parts) current = File(current, part)
        current.mkdirs()
        return current
    }

    private fun okFile(file: File): File {
        file.parentFile?.mkdirs()
        file.writeText("")
        return file
    }

    // ---- buildRendererEnv（ZL2 GameLauncher.setRendererEnv 语义，2026-11-08 性能排查）----

    private fun rendererEnvInput(
        rendererId: String,
        rendererEGL: String? = null,
        rendererEnv: Map<String, String> = emptyMap(),
        rendererLibrary: String? = null,
        mesaBased: Boolean = false,
        needsZinkDriverOverride: Boolean = false,
    ) = VLaunchArgs.RendererEnvInput(
        rendererId = rendererId,
        rendererEGL = rendererEGL,
        rendererEnv = rendererEnv,
        rendererLibrary = rendererLibrary,
        mesaBased = mesaBased,
        needsZinkDriverOverride = needsZinkDriverOverride,
        nativeLibDir = "/data/app-libs",
        cacheDir = File("/data/cache"),
    )

    @Test
    fun `opengles2 渲染器注入 gl4es 环境块`() {
        val env = VLaunchArgs.buildRendererEnv(rendererEnvInput("opengles2"))
        assertEquals("2", env["LIBGL_ES"])
        assertEquals("3", env["LIBGL_MIPMAP"])
        assertEquals("1", env["LIBGL_NOERROR"])
        assertEquals("1", env["LIBGL_NOINTOVLHACK"])
        assertEquals("1", env["LIBGL_NORMALIZE"])
        assertEquals("opengles2", env["POJAV_RENDERER"])
        assertEquals("opengles2", env["SDL_OPENGL_LIBRARY"])
    }

    @Test
    fun `渲染器自声明 env 覆盖固定块`() {
        val env = VLaunchArgs.buildRendererEnv(
            rendererEnvInput("opengles2", rendererEnv = mapOf("LIBGL_NOERROR" to "0")),
        )
        // ZL2 同序：固定块先写，getRendererEnv 覆盖
        assertEquals("0", env["LIBGL_NOERROR"])
        assertEquals("1", env["LIBGL_NOINTOVLHACK"])
    }

    @Test
    fun `mesa 系渲染器注入 LIB_MESA_NAME 与 zink 驱动覆盖`() {
        val env = VLaunchArgs.buildRendererEnv(
            rendererEnvInput(
                "opengles3_desktopgl_zink_kopper",
                rendererLibrary = "libglxshim.so",
                mesaBased = true,
                needsZinkDriverOverride = true,
                rendererEGL = "libEGL_mesa.so",
            ),
        )
        assertEquals("libglxshim.so", env["LIB_MESA_NAME"])
        assertEquals(File("/data/cache").absolutePath, env["MESA_GLSL_CACHE_DIR"])
        assertEquals("zink", env["MESA_LOADER_DRIVER_OVERRIDE"])
        assertEquals("4.6", env["MESA_GL_VERSION_OVERRIDE"])
        assertEquals("460", env["MESA_GLSL_VERSION_OVERRIDE"])
        // EGL 库存在才写 SDL_EGL_LIBRARY（nativeLibDir 下不存在该文件 → 不写）
        assertEquals("libEGL_mesa.so", env["POJAVEXEC_EGL"])
        assertEquals(null, env["SDL_EGL_LIBRARY"])
        // opengles3 前缀不进 gl4es 固定块；LIBGL_ES 兜底按 id 推导
        assertEquals(null, env["LIBGL_NOERROR"])
        assertEquals("3_desktopgl_zink_kopper", env["LIBGL_ES"])
    }

    @Test
    fun `非 opengles 前缀渲染器 LIBGL_ES 兜底为 3`() {
        val env = VLaunchArgs.buildRendererEnv(rendererEnvInput("gallium_virgl"))
        assertEquals("3", env["LIBGL_ES"])
    }
}