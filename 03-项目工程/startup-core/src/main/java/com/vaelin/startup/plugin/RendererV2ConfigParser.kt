/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Vaelin 自有实现，不含 ZalithLauncher2 的逐字代码。
 *
 * 解析 ZL2 RendererV2 插件的 RendererConfig JSON（fclPlugin_V2 指向的 string 资源）。
 * Vaelin 无 kotlinx.serialization，也不引新依赖，改用 download/Json.kt 的零依赖解析器。
 * 契约依据：02-参考源码/.../game/plugin/renderer_v2/data/RendererConfig.kt:18-155、
 * .../path/UrlManager.kt:111-115（GLOBAL_JSON 未自定义 classDiscriminator，
 * sealed 默认用 "type" 字段，值取 @SerialName：NormalEnv / ToggleableEnv /
 * SelectableEnv / CustomizableEnv）。
 */

package com.vaelin.startup.plugin

import com.vaelin.startup.download.Json
import com.vaelin.startup.download.arrayField
import com.vaelin.startup.download.asBoolean
import com.vaelin.startup.download.asString
import com.vaelin.startup.download.field
import com.vaelin.startup.download.stringField
import java.io.File

/** RendererConfig 解析后的中立结果（已做过 **| 路径展开）。 */
data class RendererV2Config(
    val displayName: String,
    val rendererId: String,
    val rendererGLPath: String,
    val rendererEGLPath: String,
    val dlopenLibPaths: List<String>,
    /** 固定生效的环境变量：NormalEnv 全收 + ToggleableEnv 且 toggle=true 的 value */
    val fixedEnv: Map<String, String>,
    val minMCVer: String?,
    val maxMCVer: String?
)

object RendererV2ConfigParser {

    private const val NATIVE_PATH_PREFIX = "**|"
    private const val DISCRIMINATOR = "type"

    /**
     * @throws IllegalArgumentException 必需字段缺失（显式报错，不静默给默认值）
     * @throws com.vaelin.startup.download.JsonParseException JSON 语法错误
     */
    fun parse(configJson: String, nativeLibraryDir: String): RendererV2Config {
        val root = Json.parse(configJson)

        fun required(name: String): String = root.stringField(name)
            ?: throw IllegalArgumentException("RendererConfig 缺少必需字段 $name")

        fun resolve(raw: String): String =
            if (raw.startsWith(NATIVE_PATH_PREFIX))
                File(nativeLibraryDir, raw.removePrefix(NATIVE_PATH_PREFIX)).absolutePath
            else raw

        val dlopen = root.arrayField("dlopenLibPaths").orEmpty()
            .mapNotNull { it.asString() }
            .map { resolve(it) }

        // env 是 sealed 数组，按 "type" 分派：R1 只取有固定 value 的两类，
        // SelectableEnv / CustomizableEnv 的值由用户在 UI 里决定，R1 显式忽略，
        // 不把它们静默当成正常环境变量。
        val fixedEnv = LinkedHashMap<String, String>()
        root.arrayField("env").orEmpty().forEach { entry ->
            val type = entry.field(DISCRIMINATOR).asString() ?: return@forEach
            val key = entry.field("key").asString() ?: return@forEach
            when (type) {
                "NormalEnv" -> {
                    val value = entry.field("value").asString() ?: return@forEach
                    fixedEnv[key] = resolve(value)
                }
                "ToggleableEnv" -> {
                    val toggle = entry.field("toggle").asBoolean() ?: true
                    if (toggle) {
                        val value = entry.field("value").asString() ?: return@forEach
                        fixedEnv[key] = resolve(value)
                    }
                }
                else -> Unit // SelectableEnv / CustomizableEnv：R1 不接可配置 UI 项
            }
        }

        return RendererV2Config(
            displayName = required("displayName"),
            rendererId = required("rendererId"),
            rendererGLPath = resolve(required("rendererGLPath")),
            rendererEGLPath = resolve(required("rendererEGLPath")),
            dlopenLibPaths = dlopen,
            fixedEnv = fixedEnv,
            minMCVer = root.stringField("minMCVer"),
            maxMCVer = root.stringField("maxMCVer")
        )
    }
}
