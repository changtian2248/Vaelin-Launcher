package com.vaelin.startup.launch

import com.vaelin.startup.download.Json
import com.vaelin.startup.download.JsonValue
import com.vaelin.startup.download.RuleEnvironment
import com.vaelin.startup.download.arrayField
import com.vaelin.startup.download.field
import java.io.File

/** 展开游戏参数模板时需要的上下文值。 */
data class ArgumentContext(
    val versionId: String,
    val versionType: String,
    val gameDir: File,
    val assetsDir: File,
    val assetIndexId: String,
    val librariesDir: File = File(gameDir, "libraries"),
    val playerName: String = OfflineAccount.DEFAULT_PLAYER_NAME,
    val width: Int = 1280,
    val height: Int = 720,
)

/**
 * 从 Mojang version json 里取出游戏参数并展开占位符。
 *
 * 两套格式都支持：
 * - 现代版：`arguments.game` 数组，元素可为字符串，或带 `rules` 与 `value` 的对象（value 可为字符串或字符串数组）。
 * - 旧版：`minecraftArguments` 单个字符串，按空白切分（保留双引号内的整段）。
 *
 * `rules` 独立实现完整 last-match-wins 语义（非空规则初始 disallow，按序命中覆盖），
 * 与 [com.vaelin.startup.download.LibraryRules] 的库规则同构，不照抄简化实现。
 * 纯 JVM，可独立单测。
 */
object GameArgumentExpander {
    private val PLACEHOLDER = Regex("\\$\\{([^}]+)\\}")

    /** 占位符 → 值。未列入的扩展占位符替换为空串，保证参数位置不丢失。 */
    fun values(context: ArgumentContext): Map<String, String> = buildMap {
        put("auth_player_name", context.playerName)
        put("auth_uuid", OfflineAccount.uuidFor(context.playerName))
        put("auth_access_token", OfflineAccount.ACCESS_TOKEN)
        put("auth_session", OfflineAccount.AUTH_SESSION)
        put("user_type", OfflineAccount.USER_TYPE)
        put("user_properties", "{}")
        put("version_name", context.versionId)
        put("version_type", context.versionType)
        put("game_directory", context.gameDir.absolutePath)
        put("assets_root", context.assetsDir.absolutePath)
        put("assets_index_name", context.assetIndexId)
        put("game_assets", File(context.assetsDir, "virtual/legacy").absolutePath)
        put("resolution_width", context.width.toString())
        put("resolution_height", context.height.toString())
        put("quickPlayPath", "")
        put("quickPlaySingleplayer", "")
        put("quickPlayMultiplayer", "")
        put("quickPlayRealms", "")
    }

    /** 替换单个 token 里的 `${key}`；未知 key 原样保留，不静默丢弃。 */
    fun expandToken(token: String, values: Map<String, String>): String =
        PLACEHOLDER.replace(token) { match -> values[match.groupValues[1]] ?: match.value }

    fun expandTokens(tokens: List<String>, values: Map<String, String>): List<String> =
        tokens.map { expandToken(it, values) }

    /** 按空白切分，但把双引号包起来的内容当成一个 token（去掉引号）。 */
    fun tokenize(template: String): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (ch in template) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                !inQuotes && ch.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        out.add(current.toString())
                        current.setLength(0)
                    }
                }
                else -> current.append(ch)
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out
    }

    /** 解析 version json 原文，取出游戏参数 token 列表（尚未展开占位符）。 */
    fun gameArguments(versionJsonText: String, environment: RuleEnvironment): List<String> {
        val root = Json.parse(versionJsonText)
        val modern = root.field("arguments").arrayField("game")
        if (modern != null) {
            val out = ArrayList<String>()
            for (entry in modern) {
                if (entry is JsonValue.Str) {
                    out.add(entry.value)
                    continue
                }
                if (entry !is JsonValue.Obj) continue
                if (!argumentRuleAllows(entry.arrayField("rules"), environment)) continue
                when (val value = entry.fields["value"]) {
                    is JsonValue.Str -> out.add(value.value)
                    is JsonValue.Arr -> for (item in value.items) {
                        if (item is JsonValue.Str) out.add(item.value)
                    }
                    else -> {}
                }
            }
            return out
        }
        val legacy = (root.field("minecraftArguments") as? JsonValue.Str)?.value
            ?: throw LaunchException("version json 既无 arguments.game 也无 minecraftArguments，无法确定游戏参数")
        return tokenize(legacy)
    }

    /** 一步到位：解析 + 展开。 */
    fun build(
        versionJsonText: String,
        context: ArgumentContext,
        environment: RuleEnvironment,
    ): List<String> = expandTokens(gameArguments(versionJsonText, environment), values(context))

    /**
     * 展开 `arguments.jvm`（Mojang 版本 JSON 的 JVM 参数段）。
     *
     * ZL2 对应 LaunchArgs.getMinecraftJVMArgs（2026-11-08 性能排查补齐的兼容缺口：
     * 此前 Vaelin 从未把这段送进命令行，-Djava.library.path / jna.tmpdir /
     * minecraft.launcher.brand 等全部缺失；模组版的 -XX 旗标也会经此进入）。
     *
     * 变量映射（对齐 ZL2 varArgMap）：natives_directory → 可读缓存目录（ZL2 同款，
     * 原 natives 目录不可写）、launcher_name/version、game_directory、user_home、
     * library_directory、version_name、classpath_separator。
     * 特殊重写（ZL2 同款）：-Djava.library.path 强制指向 natives_directory；
     * -DignoreList 追加版本 jar 名。
     * classpath 处理：`${classpath}` 与 `-cp`/`-classpath` token 丢弃——Vaelin 的
     * buildJvmArgs 在末尾统一追加自己的 -cp（ZL2 同款判重逻辑不适用其结构）。
     * 纯 JVM，可独立单测。
     */
    fun buildJvmArgs(
        versionJsonText: String,
        context: ArgumentContext,
        environment: RuleEnvironment,
        classpathPaths: List<String>,
        nativesDir: File,
        userHome: File,
        launcherName: String,
        launcherVersion: String,
    ): List<String> {
        val root = Json.parse(versionJsonText)
        val jvm = root.field("arguments").arrayField("jvm")
            ?: return emptyList()
        val values = mapOf(
            "classpath" to classpathPaths.joinToString(":"),
            "classpath_separator" to ":",
            "natives_directory" to nativesDir.absolutePath,
            "launcher_name" to launcherName,
            "launcher_version" to launcherVersion,
            "game_directory" to context.gameDir.absolutePath,
            "user_home" to userHome.absolutePath,
            "library_directory" to context.librariesDir.absolutePath,
            "version_name" to context.versionId,
        )
        val out = ArrayList<String>()
        for (entry in jvm) {
            val tokens = when (entry) {
                is JsonValue.Str -> listOf(entry.value)
                is JsonValue.Obj -> {
                    if (!argumentRuleAllows(entry.arrayField("rules"), environment)) continue
                    when (val value = entry.fields["value"]) {
                        is JsonValue.Str -> listOf(value.value)
                        is JsonValue.Arr -> value.items.filterIsInstance<JsonValue.Str>().map { it.value }
                        else -> emptyList()
                    }
                }
                else -> continue
            }
            for (token in tokens) {
                // classpath 由 buildJvmArgs 统一追加，避免重复 -cp（JVM 对重复 -cp 后者胜）
                if (token == "-cp" || token == "-classpath" || token == "\${classpath}") continue
                var expanded = expandToken(token, values)
                if (expanded.startsWith("-Djava.library.path=")) {
                    expanded = "-Djava.library.path=${nativesDir.absolutePath}"
                }
                if (expanded.startsWith("-DignoreList=")) {
                    expanded = "$expanded,${context.versionId}.jar"
                }
                out.add(expanded)
            }
        }
        return out
    }

    /** last-match-wins：非空规则初始 disallow，逐条命中覆盖，最后一条命中决定结果。 */
    private fun argumentRuleAllows(rules: List<JsonValue>?, environment: RuleEnvironment): Boolean {
        if (rules.isNullOrEmpty()) return true
        var allowed = false
        for (rule in rules) {
            val obj = rule as? JsonValue.Obj ?: continue
            if (!ruleMatches(obj, environment)) continue
            val action = (obj.fields["action"] as? JsonValue.Str)?.value
            allowed = action.equals("allow", ignoreCase = true)
        }
        return allowed
    }

    private fun ruleMatches(obj: JsonValue.Obj, environment: RuleEnvironment): Boolean {
        val os = obj.fields["os"] as? JsonValue.Obj
        if (os != null) {
            val osName = (os.fields["name"] as? JsonValue.Str)?.value
            if (osName != null && !osName.equals(environment.osName, ignoreCase = true)) return false
            val osArch = (os.fields["arch"] as? JsonValue.Str)?.value
            if (osArch != null && !osArch.equals(environment.osArch, ignoreCase = true)) return false
            val osVersion = (os.fields["version"] as? JsonValue.Str)?.value
            if (osVersion != null) {
                if (environment.osVersion.isEmpty()) return false
                val matched = runCatching { Regex(osVersion).containsMatchIn(environment.osVersion) }
                    .getOrDefault(false)
                if (!matched) return false
            }
        }
        val features = obj.fields["features"] as? JsonValue.Obj
        if (features != null) {
            for ((name, expectedValue) in features.fields) {
                val expected = (expectedValue as? JsonValue.Bool)?.value ?: false
                val actual = environment.features[name] ?: false
                if (expected != actual) return false
            }
        }
        return true
    }
}
