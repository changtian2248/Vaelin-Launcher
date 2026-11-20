/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Vaelin 自有实现。R1 只接四类插件；FFmpeg 类（net.kdt.pojavlaunch.ffmpeg）
 * 排在 R1 之外，理由见 PluginDiscovery.EXCLUDED_PACKAGES 的注释。
 */

package com.vaelin.startup.plugin

import com.vaelin.startup.renderer.RendererInterface

/** R1 接受的插件类别。metaKeyDescription 仅供日志/报告说明用，不参与判定。 */
enum class PluginType(val metaKeyDescription: String) {
    DRIVER("fclPlugin + driver"),
    RENDERER("fclPlugin / zalithRendererPlugin + renderer"),
    RENDERER_V2("fclPlugin_V2 (string 资源 → RendererConfig JSON)"),
    NATIVES("FCLNativePlugin")
}

/**
 * 一个已安装 APK 的插件侧快照。
 *
 * [rendererV2ConfigJson] 由数据源侧把 `fclPlugin_V2` 指向的 string 资源读好再传进来：
 * 核心发现层不碰 Android 资源 id，也不引 kotlinx.serialization。
 */
data class PluginPackageInfo(
    val packageName: String,
    val appName: String,
    val appVersion: String,
    val nativeLibraryDir: String,
    val metaData: PluginMetaData,
    val rendererV2ConfigJson: String? = null,
    val isSystemApp: Boolean = false
)

/**
 * 插件声明出来的一个渲染器载体（已实现 RendererInterface，可直接注入注册表）。
 *
 * [libraryPresent] 是扫描期的库存在性校验结果：声明的 .so 在磁盘上找不到时为 false，
 * [PluginRegistrar] 不会把它注入注册表，并记入 skipped。发现层不静默丢弃，显式留痕。
 * 启动期真正的「加载失败 → 抛异常 → 中止」由 renderer 包的 RendererLibraryLoader 负责。
 */
data class PluginRendererDescriptor(
    val packageName: String,
    val type: PluginType,
    val renderer: RendererInterface,
    /** getRendererLibrary() 的原值，供库存在性校验与负向验收复述 */
    val declaredLibrary: String,
    val libraryPresent: Boolean,
    /** 扫描期实际检查过的绝对路径，负向验收可原样复述「已查找：…」 */
    val searchedPaths: List<String> = emptyList()
)

/**
 * 单个插件包的解析结果。
 *
 * 一个包可同时命中多类（对齐 ZL2 PluginLoader.kt:65-75 四个 parseApkPlugin 都调）：
 * [types] 记全部命中类别，[renderers] 只含 RENDERER / RENDERER_V2 两类产出的渲染器。
 */
data class DiscoveredPlugin(
    val packageInfo: PluginPackageInfo,
    val types: Set<PluginType>,
    val renderers: List<PluginRendererDescriptor>,
    /** NATIVES 插件的 environment 解析结果（R1 只解析留痕，启动链拼接由后续任务接） */
    val environment: Map<String, String> = emptyMap(),
    /** DRIVER 插件的 driver 名（R1 只识别，不参与渲染器注册） */
    val driverName: String? = null
)

data class SkippedPlugin(val packageName: String, val reason: String)

data class PluginDiscoveryResult(
    val plugins: List<DiscoveredPlugin>,
    /** 所有插件声明的渲染器，按发现顺序展平 */
    val renderers: List<PluginRendererDescriptor>,
    /** 不合规/被排除的包，带原因——不静默丢弃，供日志与报告复述 */
    val skipped: List<SkippedPlugin>
)
