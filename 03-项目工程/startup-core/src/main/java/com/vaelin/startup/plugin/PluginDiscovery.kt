/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Vaelin 自有实现，不含 ZalithLauncher2 的逐字代码。
 *
 * Vaelin 自己的插件发现层（T1.12 第 3 条）。判定 key 逐行对齐 ZL2 四类
 * Manager，但核心层零 Android import：APK 数据由可注入的 [PluginPackageSource]
 * 提供，库存在性由可注入的 fileExists 提供，能在纯 JVM 单测里完整跑通。
 *
 * R1 只接四类：DRIVER / RENDERER / RENDERER_V2 / NATIVES。
 * FFmpeg 类插件见 [EXCLUDED_PACKAGES] 的排除理由。
 */

package com.vaelin.startup.plugin

import com.vaelin.startup.renderer.RendererLibPaths
import java.io.File

class PluginDiscovery(
    private val source: PluginPackageSource,
    private val libPaths: RendererLibPaths,
    private val fileExists: (String) -> Boolean = { path: String -> File(path).isFile }
) {

    private companion object {
        /**
         * FFmpeg 插件：ZL2 里按固定包名单独加载（FFmpegPluginManager.kt:30-82），
         * 绑录像功能。Vaelin R1 规格书没有录像条目，因此显式排除并留痕，
         * 不是「扫不到」，而是「R1 不接」。
         */
        val EXCLUDED_PACKAGES = setOf("net.kdt.pojavlaunch.ffmpeg")
        const val NATIVE_DIR_PLACEHOLDER = "{nativeLibraryDir}"
    }

    /**
     * 扫描一次全部候选 APK。整包用 runCatching 包住（对齐 ZL2 PluginLoader.kt:67-74），
     * 但异常会记进 [PluginDiscoveryResult.skipped] 而不是只打一行日志——不静默丢弃。
     */
    fun discover(): PluginDiscoveryResult {
        val plugins = mutableListOf<DiscoveredPlugin>()
        val allRenderers = mutableListOf<PluginRendererDescriptor>()
        val skipped = mutableListOf<SkippedPlugin>()

        for (info in source.queryPlugins()) {
            val attempt = runCatching { parseOne(info, skipped) }
            val error = attempt.exceptionOrNull()
            if (error != null) {
                skipped += SkippedPlugin(
                    info.packageName,
                    "解析异常：${error.javaClass.simpleName}: ${error.message}"
                )
                continue
            }
            val parsed = attempt.getOrNull() ?: continue
            if (parsed.types.isEmpty()) continue
            plugins += parsed
            allRenderers += parsed.renderers
        }

        return PluginDiscoveryResult(plugins, allRenderers, skipped)
    }

    private fun parseOne(
        info: PluginPackageInfo,
        skipped: MutableList<SkippedPlugin>
    ): DiscoveredPlugin {
        if (info.packageName in EXCLUDED_PACKAGES) {
            skipped += SkippedPlugin(info.packageName, "FFmpeg 类插件：绑录像功能，R1 规格书无录像条目，排除")
            return emptyPlugin(info)
        }

        val md = info.metaData
        val hasPluginKey = md.has("fclPlugin") || md.has("zalithRendererPlugin") ||
            md.has("fclPlugin_V2") || md.has("FCLNativePlugin")

        // 系统应用：对齐 ZL2 四个 Manager 的 `flags and FLAG_SYSTEM != 0 → return`。
        // 只有声明了插件 key 的系统应用才留痕，普通系统应用属正常过滤。
        if (info.isSystemApp) {
            if (hasPluginKey) {
                skipped += SkippedPlugin(info.packageName, "系统应用声明了插件 key，按 ZL2 规则跳过")
            }
            return emptyPlugin(info)
        }

        val types = LinkedHashSet<PluginType>()
        val renderers = mutableListOf<PluginRendererDescriptor>()
        var environment: Map<String, String> = emptyMap()
        var driverName: String? = null
        val libDir = info.nativeLibraryDir

        // 1) DRIVER —— DriverPluginManager.kt:67-94
        val driver = md.getString("driver")
        if (md.getBoolean("fclPlugin") && !driver.isNullOrEmpty()) {
            types += PluginType.DRIVER
            driverName = driver
        }

        // 2) RENDERER_V2 —— RendererV2PluginManager.kt:52-94（优先于旧架构）
        var v2Loaded = false
        val v2ResId = md.getInt("fclPlugin_V2", -1)
        val v2Json = info.rendererV2ConfigJson
        if (v2ResId > 0) {
            if (v2Json.isNullOrBlank()) {
                skipped += SkippedPlugin(info.packageName, "声明 fclPlugin_V2 但读不到 JSON 资源")
            } else {
                // 缺字段 / JSON 语法错误会抛异常 → 由 discover() 记 skipped，不静默
                val config = RendererV2ConfigParser.parse(v2Json, libDir)
                val adapter = PluginRendererAdapter(
                    packageName = info.packageName,
                    rendererId = config.rendererId,
                    displayName = config.displayName,
                    summary = "来自插件 ${info.appName}",
                    minMCVersion = config.minMCVer,
                    maxMCVersion = config.maxMCVer,
                    rendererLibrary = config.rendererGLPath,
                    rendererEGL = config.rendererEGLPath,
                    env = config.fixedEnv,
                    dlopenLibraries = config.dlopenLibPaths,
                    mesaBased = true // 对齐 RendererV2Plugin.kt:40：v2 全部 mesaBased
                )
                types += PluginType.RENDERER_V2
                v2Loaded = true
                renderers += descriptor(info, PluginType.RENDERER_V2, adapter, skipped)
            }
        }

        // 3) RENDERER 旧架构 —— RendererPluginManager.kt:85-158
        if (md.getBoolean("fclPlugin") || md.getBoolean("zalithRendererPlugin")) {
            // 对齐 RendererPluginManager.kt:96-100：同包已被 V2 加载则不再加载旧架构
            if (!v2Loaded) {
                val rendererString = md.getString("renderer")
                val des = md.getString("des")
                val pojavEnv = md.getString("pojavEnv")
                if (rendererString.isNullOrEmpty() || des.isNullOrEmpty() || pojavEnv == null) {
                    skipped += SkippedPlugin(
                        info.packageName,
                        "旧架构渲染器缺 renderer/des/pojavEnv 必需 meta-data"
                    )
                } else {
                    val parts = rendererString.split(":")
                    if (parts.size < 3) {
                        skipped += SkippedPlugin(
                            info.packageName,
                            "renderer 需为 id:glName:eglName 三段，实际 ${parts.size} 段"
                        )
                    } else {
                        var rendererId = parts[0]
                        val glName = parts[1]
                        val eglName = parts[2]
                        val envList = LinkedHashMap<String, String>()
                        val dlopenList = mutableListOf<String>()
                        // 用 limit=2 保留 value 里的 '='（ZL2 用 split("=")[1] 会截断；
                        // 团队准则要求独立实现完整语义，不照抄简化版）
                        pojavEnv.split(":").forEach { segment ->
                            if (segment.contains("=")) {
                                val kv = segment.split("=", limit = 2)
                                val key = kv[0]
                                val value = kv[1]
                                when (key) {
                                    "POJAV_RENDERER" -> rendererId = value
                                    "DLOPEN" -> value.split(",").forEach { lib ->
                                        dlopenList += "$libDir/$lib"
                                    }
                                    "LIB_MESA_NAME", "MESA_LIBRARY" -> envList[key] = "$libDir/$value"
                                    else -> envList[key] = value
                                }
                            }
                        }
                        // RendererPluginManager.kt:162-164 progressEglName
                        val progressEgl = if (eglName.startsWith("/")) "$libDir$eglName" else eglName
                        val adapter = PluginRendererAdapter(
                            packageName = info.packageName,
                            rendererId = rendererId,
                            displayName = des,
                            summary = "来自插件 ${info.appName}",
                            minMCVersion = md.getString("minMCVer"),
                            maxMCVersion = md.getString("maxMCVer"),
                            rendererLibrary = "$libDir/$glName",
                            rendererEGL = progressEgl,
                            env = envList,
                            dlopenLibraries = dlopenList
                        )
                        types += PluginType.RENDERER
                        renderers += descriptor(info, PluginType.RENDERER, adapter, skipped)
                    }
                }
            }
        }

        // 4) NATIVES —— NativePluginManager.kt:80-146
        if (md.getBoolean("FCLNativePlugin")) {
            val envRaw = md.getString("environment")
            if (envRaw.isNullOrEmpty()) {
                skipped += SkippedPlugin(info.packageName, "NATIVES 插件缺 environment meta-data")
            } else {
                types += PluginType.NATIVES
                environment = parseEnvironment(envRaw, libDir)
            }
        }

        if (hasPluginKey && types.isEmpty()) {
            skipped += SkippedPlugin(info.packageName, "声明了插件 key 但未命中任何 R1 类别")
        }

        return DiscoveredPlugin(info, types, renderers, environment, driverName)
    }

    /**
     * 扫描期库存在性校验：把声明的库解析成候选绝对路径，逐个检查。
     * 找不到不抛异常（启动期真正的「抛异常中止」由 RendererLibraryLoader 负责），
     * 但会记一条 skipped 并把 [PluginRendererDescriptor.libraryPresent] 置 false，
     * 让 PluginRegistrar 拒绝注入——不静默跳过。
     */
    private fun descriptor(
        info: PluginPackageInfo,
        type: PluginType,
        adapter: com.vaelin.startup.renderer.RendererInterface,
        skipped: MutableList<SkippedPlugin>
    ): PluginRendererDescriptor {
        val declared = adapter.getRendererLibrary()
        val candidates = libPaths.resolve(declared)
        val present = candidates.any { fileExists(it) }
        if (!present) {
            skipped += SkippedPlugin(
                info.packageName,
                "渲染器库不存在：$declared（已查找：${candidates.joinToString("、")}）"
            )
        }
        return PluginRendererDescriptor(
            packageName = info.packageName,
            type = type,
            renderer = adapter,
            declaredLibrary = declared,
            libraryPresent = present,
            searchedPaths = candidates
        )
    }

    /** NATIVES environment："KEY=VALUE" 空格分段；{nativeLibraryDir} 占位符替换 + 防穿越。 */
    private fun parseEnvironment(raw: String, libDir: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        raw.split(" ").forEach { entry ->
            if (!entry.contains("=")) return@forEach
            val kv = entry.split("=", limit = 2)
            val key = kv[0]
            var value = kv[1]
            if (value.startsWith(NATIVE_DIR_PLACEHOLDER)) {
                val rest = value.removePrefix(NATIVE_DIR_PLACEHOLDER)
                value = when {
                    rest.isEmpty() -> libDir
                    rest.contains("..") -> libDir // 对齐 safePath 意图：路径穿越时回退到根目录
                    else -> File(libDir, rest.trimStart('/')).absolutePath
                }
            }
            out[key] = value
        }
        return out
    }

    private fun emptyPlugin(info: PluginPackageInfo): DiscoveredPlugin =
        DiscoveredPlugin(info, emptySet(), emptyList())
}
