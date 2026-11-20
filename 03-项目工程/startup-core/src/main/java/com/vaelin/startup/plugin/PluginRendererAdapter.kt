/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Vaelin 自有实现，不含 ZalithLauncher2 的逐字代码。
 */

package com.vaelin.startup.plugin

import com.vaelin.startup.renderer.RendererInterface

/**
 * 插件声明出来的渲染器载体。
 *
 * 把 ZL2 的 meta-data（旧架构 renderer / pojavEnv）或 RendererConfig JSON（v2）
 * 的解析结果收敛成一个 [RendererInterface] 实现，供
 * `VaelinRenderers.addRenderer` 注入注册表。
 *
 * 纯 JVM：不 import android.*，也不引 kotlinx.serialization，单测可直接构造。
 *
 * 关于 [rendererLibrary] 的取值形态（已逐行核对 ZL2）：
 *  - 旧架构 02-参考源码/.../game/plugin/renderer/RendererPlugin.kt:24-53
 *    `getRendererLibrary() = "$path/$glName"` → 绝对路径
 *  - v2 架构 .../game/plugin/renderer_v2/RendererV2Plugin.kt:31-53
 *    `getRendererLibrary() = renderer.rendererGLPath`，经
 *    RendererConfig.resolveNativePaths 后也是绝对路径
 *  - Vaelin 内置渲染器（如 GL4ESRenderer.kt:36）返回裸文件名 `libgl4es_114.so`
 *
 * 本类原样保留传入值，不做路径改写；「nativeLibraryDir 优先 / 用户目录其次」
 * 的查找由 renderer 包新增的 `RendererLibPaths` + `RendererLibraryLoader` 负责，
 * 两者都能同时处理绝对路径与裸文件名。
 */
class PluginRendererAdapter(
    private val packageName: String,
    private val rendererId: String,
    private val displayName: String,
    private val summary: String? = null,
    private val minMCVersion: String? = null,
    private val maxMCVersion: String? = null,
    private val rendererLibrary: String,
    private val rendererEGL: String? = null,
    private val env: Map<String, String> = emptyMap(),
    private val dlopenLibraries: List<String> = emptyList(),
    override val mesaBased: Boolean = false,
    override val needsZinkDriverOverride: Boolean = false,
    private val supportsSfpew: Boolean = false
) : RendererInterface {

    /** 插件渲染器以包名作唯一标识——与 ZL2 RendererPlugin / RendererV2Data 一致 */
    override fun getUniqueIdentifier(): String = packageName

    override fun getRendererId(): String = rendererId

    override fun supportsSfpewByDefault(): Boolean = supportsSfpew

    override fun getRendererName(): String = displayName

    override fun getRendererSummary(): String? = summary

    override fun getMinMCVersion(): String? = minMCVersion

    override fun getMaxMCVersion(): String? = maxMCVersion

    /** 契约是 Lazy，不能退化成 Map（计划文档 01-文档/04 §T1.8 明确要求） */
    override fun getRendererEnv(): Lazy<Map<String, String>> = lazyOf(env)

    override fun getDlopenLibrary(): Lazy<List<String>> = lazyOf(dlopenLibraries)

    override fun getRendererLibrary(): String = rendererLibrary

    override fun getRendererEGL(): String? = rendererEGL
}
