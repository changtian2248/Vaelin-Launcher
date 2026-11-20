/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Vaelin 自有实现。把 Android Bundle 的 meta-data 读取收敛成纯 Kotlin 类型：
 * 插件发现层（PluginDiscovery）只认这个类，不 import android.os.Bundle，
 * 因此能在纯 JVM 单测里用 Map 直接喂数据，不需要 Robolectric / mockito。
 * 生产侧由 AndroidPluginPackageSource 把 Bundle 转成 Map 后构造本对象。
 */

package com.vaelin.startup.plugin

/**
 * 插件 APK 的 <meta-data> 快照（只读、类型化）。
 *
 * 对应 ZL2 的用法（均已逐行核对）：
 *  - Bundle.getBoolean("fclPlugin" / "zalithRendererPlugin" / "FCLNativePlugin")
 *    → [getBoolean]
 *  - Bundle.getString("renderer" / "des" / "pojavEnv" / "driver" / "environment")
 *    → [getString]
 *  - ApkPluginManager.getVersionString("minMCVer")：String 或 Number 都收
 *    → [getString] 同样兼容 Number
 *  - Bundle.getInt("fclPlugin_V2", -1)：string 资源 id
 *    → [getInt]，但 v2 的 JSON 内容由数据源侧直接解析好放进
 *      PluginPackageInfo.rendererV2ConfigJson，核心层不碰资源 id。
 */
class PluginMetaData(private val values: Map<String, Any?>) {

    val keys: Set<String> get() = values.keys

    fun has(key: String): Boolean = values.containsKey(key)

    fun raw(key: String): Any? = values[key]

    fun getString(key: String): String? = when (val v = values[key]) {
        is String -> v
        // getVersionString 里 minMCVer/maxMCVer 可能是 int/float，转字符串
        is Number -> v.toString()
        else -> null
    }

    fun getBoolean(key: String, default: Boolean = false): Boolean = when (val v = values[key]) {
        is Boolean -> v
        // 少数插件把布尔写成字符串 "true"
        is String -> v.equals("true", ignoreCase = true)
        else -> default
    }

    fun getInt(key: String, default: Int = 0): Int = when (val v = values[key]) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull() ?: default
        else -> default
    }
}
