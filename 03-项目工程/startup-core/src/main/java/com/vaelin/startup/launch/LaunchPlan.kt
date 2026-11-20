package com.vaelin.startup.launch

import com.vaelin.startup.multirt.VRuntime
import com.vaelin.startup.profile.GameDirectoryMode
import com.vaelin.startup.profile.VersionProfile
import java.io.File
import java.util.Base64

/**
 * 主进程 prepare 完成后落盘的「启动计划」，:game 进程读它来拉起 JVM。
 *
 * 为什么落盘而不是塞进 Intent：classpath 可达几百个绝对路径，超出 Binder 事务上限，
 * 且 :game 进程拿不到主进程的对象引用。两进程都调 VPaths.init，gameDir 指向同一目录，
 * 故用 `<gameDir>/launch_plans/<versionId>.plan` 作为跨进程契约。
 *
 * 编码：每行 `key=Base64(UTF-8 value)`；列表用重复 key 多行表示，保留顺序；
 * 可选字符串留空则用「缺行」表达 null。零第三方依赖（工程无 kotlinx-serialization），
 * java.util.Base64 在 minSdk=26 与 JVM 单测均可用。纯 JVM，可独立单测。
 */
data class LaunchPlan(
    val versionId: String,
    val runtimeHome: String,
    val runtimeName: String,
    val runtimeVersion: String?,
    val runtimeArch: String?,
    val runtimeJavaVersion: Int,
    val runtimeProvidedByLauncher: Boolean,
    val runtimeIsJdk8: Boolean,
    val profileId: String,
    val profileName: String,
    val gameDirectory: String,
    val gameDirectoryMode: GameDirectoryMode,
    val minecraftVersion: String,
    val modLoader: String,
    val modLoaderVersion: String?,
    val rendererId: String,
    val rendererUniqueId: String,
    val memoryMB: Int,
    val extraJvmArgs: List<String>,
    val javaRuntime: String?,
    val lastPlayedAt: Long,
    /** 所选 LWJGL 组件目录（VComponents installName，如 `lwjgl/3.3.3`），:game 侧拼 natives 路径用。 */
    val lwjglComponentDir: String,
    val classpath: List<String>,
    val mainClass: String,
    val gameArgs: List<String>,
    /** 版本 JSON `arguments.jvm` 展开结果（prepare 期展开，:game 期拼进 JVM 命令行）。 */
    val jsonJvmArgs: List<String> = emptyList(),
) {
    fun toVRuntime(): VRuntime = VRuntime(
        name = runtimeName,
        versionString = runtimeVersion,
        arch = runtimeArch,
        javaVersion = runtimeJavaVersion,
        isProvidedByLauncher = runtimeProvidedByLauncher,
        isJDK8 = runtimeIsJdk8,
    )

    fun toProfile(): VersionProfile = VersionProfile(
        id = profileId,
        name = profileName,
        minecraftVersion = minecraftVersion,
        modLoader = modLoader,
        modLoaderVersion = modLoaderVersion,
        rendererId = rendererId,
        rendererUniqueId = rendererUniqueId,
        memoryMB = memoryMB,
        extraJvmArgs = extraJvmArgs,
        javaRuntime = javaRuntime,
        lastPlayedAt = lastPlayedAt,
    )

    fun classpathFiles(): List<File> = classpath.map { File(it) }

    companion object {
        fun build(
            profile: VersionProfile,
            gameDirectory: File,
            gameDirectoryMode: GameDirectoryMode,
            runtime: VRuntime,
            runtimeHome: File,
            classpath: List<File>,
            mainClass: String,
            gameArgs: List<String>,
            lwjglComponentDir: String,
            jsonJvmArgs: List<String> = emptyList(),
        ): LaunchPlan = LaunchPlan(
            versionId = profile.id,
            runtimeHome = runtimeHome.absolutePath,
            runtimeName = runtime.name,
            runtimeVersion = runtime.versionString,
            runtimeArch = runtime.arch,
            runtimeJavaVersion = runtime.javaVersion,
            runtimeProvidedByLauncher = runtime.isProvidedByLauncher,
            runtimeIsJdk8 = runtime.isJDK8,
            profileId = profile.id,
            profileName = profile.name,
            gameDirectory = gameDirectory.absolutePath,
            gameDirectoryMode = gameDirectoryMode,
            minecraftVersion = profile.minecraftVersion,
            modLoader = profile.modLoader,
            modLoaderVersion = profile.modLoaderVersion,
            rendererId = profile.rendererId,
            rendererUniqueId = profile.rendererUniqueId,
            memoryMB = profile.memoryMB,
            extraJvmArgs = profile.extraJvmArgs,
            javaRuntime = profile.javaRuntime,
            lastPlayedAt = profile.lastPlayedAt,
            lwjglComponentDir = lwjglComponentDir,
            classpath = classpath.map { it.absolutePath },
            mainClass = mainClass,
            gameArgs = gameArgs,
            jsonJvmArgs = jsonJvmArgs,
        )
    }
}

object LaunchPlanCodec {
    const val FORMAT_VERSION = "4"

    private const val K_FMT = "fmt"
    private const val K_VERSION_ID = "versionId"
    private const val K_RUNTIME_HOME = "runtimeHome"
    private const val K_RUNTIME_NAME = "runtimeName"
    private const val K_RUNTIME_VERSION = "runtimeVersion"
    private const val K_RUNTIME_ARCH = "runtimeArch"
    private const val K_RUNTIME_JAVA_VERSION = "runtimeJavaVersion"
    private const val K_RUNTIME_PROVIDED = "runtimeProvidedByLauncher"
    private const val K_RUNTIME_JDK8 = "runtimeIsJdk8"
    private const val K_PROFILE_ID = "profileId"
    private const val K_PROFILE_NAME = "profileName"
    private const val K_GAME_DIRECTORY = "gameDirectory"
    private const val K_GAME_DIRECTORY_MODE = "gameDirectoryMode"
    private const val K_MC_VERSION = "minecraftVersion"
    private const val K_MOD_LOADER = "modLoader"
    private const val K_MOD_LOADER_VERSION = "modLoaderVersion"
    private const val K_RENDERER_ID = "rendererId"
    private const val K_RENDERER_UNIQUE_ID = "rendererUniqueId"
    private const val K_MEMORY_MB = "memoryMB"
    private const val K_EXTRA_JVM_ARGS = "extraJvmArgs"
    private const val K_JAVA_RUNTIME = "javaRuntime"
    private const val K_LAST_PLAYED = "lastPlayedAt"
    private const val K_LWJGL_COMPONENT_DIR = "lwjglComponentDir"
    private const val K_CLASSPATH = "classpath"
    private const val K_MAIN_CLASS = "mainClass"
    private const val K_GAME_ARGS = "gameArgs"
    private const val K_JSON_JVM_ARGS = "jsonJvmArgs"

    private fun enc(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun dec(value: String): String = try {
        String(Base64.getDecoder().decode(value), Charsets.UTF_8)
    } catch (t: IllegalArgumentException) {
        throw LaunchException("启动计划里有非法 Base64 片段：$value", t)
    }

    /** 每行 `key=base64`；列表按元素顺序重复 key；可选字符串为 null 时不写该行。 */
    fun encode(plan: LaunchPlan): String {
        val lines = ArrayList<String>()
        fun put(key: String, value: String) { lines.add("$key=${enc(value)}") }
        fun putOpt(key: String, value: String?) { if (value != null) put(key, value) }

        put(K_FMT, FORMAT_VERSION)
        put(K_VERSION_ID, plan.versionId)
        put(K_RUNTIME_HOME, plan.runtimeHome)
        put(K_RUNTIME_NAME, plan.runtimeName)
        putOpt(K_RUNTIME_VERSION, plan.runtimeVersion)
        putOpt(K_RUNTIME_ARCH, plan.runtimeArch)
        put(K_RUNTIME_JAVA_VERSION, plan.runtimeJavaVersion.toString())
        put(K_RUNTIME_PROVIDED, plan.runtimeProvidedByLauncher.toString())
        put(K_RUNTIME_JDK8, plan.runtimeIsJdk8.toString())
        put(K_PROFILE_ID, plan.profileId)
        put(K_PROFILE_NAME, plan.profileName)
        put(K_GAME_DIRECTORY, plan.gameDirectory)
        put(K_GAME_DIRECTORY_MODE, plan.gameDirectoryMode.storageValue)
        put(K_MC_VERSION, plan.minecraftVersion)
        put(K_MOD_LOADER, plan.modLoader)
        putOpt(K_MOD_LOADER_VERSION, plan.modLoaderVersion)
        put(K_RENDERER_ID, plan.rendererId)
        put(K_RENDERER_UNIQUE_ID, plan.rendererUniqueId)
        put(K_MEMORY_MB, plan.memoryMB.toString())
        for (arg in plan.extraJvmArgs) put(K_EXTRA_JVM_ARGS, arg)
        putOpt(K_JAVA_RUNTIME, plan.javaRuntime)
        put(K_LAST_PLAYED, plan.lastPlayedAt.toString())
        put(K_LWJGL_COMPONENT_DIR, plan.lwjglComponentDir)
        for (cp in plan.classpath) put(K_CLASSPATH, cp)
        put(K_MAIN_CLASS, plan.mainClass)
        for (arg in plan.gameArgs) put(K_GAME_ARGS, arg)
        for (arg in plan.jsonJvmArgs) put(K_JSON_JVM_ARGS, arg)
        return lines.joinToString("\n")
    }

    fun decode(text: String): LaunchPlan {
        val map = LinkedHashMap<String, MutableList<String>>()
        for (raw in text.lineSequence()) {
            val line = raw.trimEnd('\r')
            if (line.isEmpty()) continue
            val eq = line.indexOf('=')
            if (eq <= 0) throw LaunchException("启动计划行缺少 key=value 结构：$line")
            val key = line.substring(0, eq)
            val value = dec(line.substring(eq + 1))
            map.getOrPut(key) { ArrayList() }.add(value)
        }

        fun required(key: String): String = map[key]?.firstOrNull()
            ?: throw LaunchException("启动计划缺少必需字段：$key")
        fun optional(key: String): String? = map[key]?.firstOrNull()
        fun list(key: String): List<String> = map[key]?.toList() ?: emptyList()
        fun int(key: String): Int = required(key).toIntOrNull()
            ?: throw LaunchException("启动计划字段 $key 不是合法整数：${required(key)}")
        fun long(key: String): Long = required(key).toLongOrNull()
            ?: throw LaunchException("启动计划字段 $key 不是合法长整数：${required(key)}")
        fun bool(key: String): Boolean = when (required(key)) {
            "true" -> true
            "false" -> false
            else -> throw LaunchException("启动计划字段 $key 不是合法布尔值：${required(key)}")
        }

        val fmt = required(K_FMT)
        if (fmt != FORMAT_VERSION) throw LaunchException("不支持的启动计划格式版本：$fmt")
        val gameDirectoryModeValue = required(K_GAME_DIRECTORY_MODE)
        val gameDirectoryMode = GameDirectoryMode.entries.firstOrNull {
            it.storageValue == gameDirectoryModeValue
        } ?: throw LaunchException("启动计划包含未知游戏目录模式：$gameDirectoryModeValue")
        return LaunchPlan(
            versionId = required(K_VERSION_ID),
            runtimeHome = required(K_RUNTIME_HOME),
            runtimeName = required(K_RUNTIME_NAME),
            runtimeVersion = optional(K_RUNTIME_VERSION),
            runtimeArch = optional(K_RUNTIME_ARCH),
            runtimeJavaVersion = int(K_RUNTIME_JAVA_VERSION),
            runtimeProvidedByLauncher = bool(K_RUNTIME_PROVIDED),
            runtimeIsJdk8 = bool(K_RUNTIME_JDK8),
            profileId = required(K_PROFILE_ID),
            profileName = required(K_PROFILE_NAME),
            gameDirectory = required(K_GAME_DIRECTORY),
            gameDirectoryMode = gameDirectoryMode,
            minecraftVersion = required(K_MC_VERSION),
            modLoader = required(K_MOD_LOADER),
            modLoaderVersion = optional(K_MOD_LOADER_VERSION),
            rendererId = required(K_RENDERER_ID),
            rendererUniqueId = required(K_RENDERER_UNIQUE_ID),
            memoryMB = int(K_MEMORY_MB),
            extraJvmArgs = list(K_EXTRA_JVM_ARGS),
            javaRuntime = optional(K_JAVA_RUNTIME),
            lastPlayedAt = long(K_LAST_PLAYED),
            lwjglComponentDir = required(K_LWJGL_COMPONENT_DIR),
            classpath = list(K_CLASSPATH),
            mainClass = required(K_MAIN_CLASS),
            gameArgs = list(K_GAME_ARGS),
            jsonJvmArgs = list(K_JSON_JVM_ARGS),
        )
    }
}
