/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Vaelin 自有实现。发现层只认 PluginPackageSource 接口，不 import android.content.pm.*。
 */

package com.vaelin.startup.plugin

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * 已安装 APK 数据源。
 *
 * 生产侧 [AndroidPluginPackageSource] 用 PackageManager 扫一次；
 * 单测用假实现直接喂 List<PluginPackageInfo>，不查系统、不依赖 Robolectric。
 */
interface PluginPackageSource {
    fun queryPlugins(): List<PluginPackageInfo>
}

/**
 * ZL2 PluginLoader.kt:38-39 / :60-64 的对等实现：
 * `queryIntentActivities(ACTION_MAIN, GET_META_DATA or GET_SHARED_LIBRARY_FILES)`。
 *
 * 与 ZL2 的差异（刻意）：
 *  - 一次扫描就把 Bundle 快照成 PluginMetaData Map，并把 `fclPlugin_V2` 指向的
 *    string 资源读成 JSON 文本，核心发现层因此完全不碰 Android API；
 *  - FFmpeg 不在这里按固定包名单独加载，交由 PluginDiscovery 的排除名单处理。
 *
 * 注意：Android 11+ 需要宿主 AndroidManifest 声明 `<queries>` 才能看到其他已安装应用；
 * app 模块不在本任务边界内，作为剩余风险在报告中标注（需真机验证）。
 */
@SuppressLint("QueryPermissionsNeeded")
class AndroidPluginPackageSource(private val context: Context) : PluginPackageSource {

    private companion object {
        val PACKAGE_FLAGS: Int =
            PackageManager.GET_META_DATA or PackageManager.GET_SHARED_LIBRARY_FILES
    }

    override fun queryPlugins(): List<PluginPackageInfo> {
        val pm = context.packageManager
        val resolves = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN), PACKAGE_FLAGS)
        }.getOrElse { emptyList() }
        return resolves.mapNotNull { resolve ->
            val info = resolve.activityInfo?.applicationInfo ?: return@mapNotNull null
            runCatching { toPluginPackageInfo(pm, info) }.getOrNull()
        }
    }

    // Bundle.get(String) 自 API 33 起废弃。这里要把类型未知的 meta-data 全量快照进 Map，
    // 官方建议的类型化 getter 无法替代这一用途，故显式抑制；行为不变。
    @Suppress("DEPRECATION")
    private fun toPluginPackageInfo(pm: PackageManager, info: ApplicationInfo): PluginPackageInfo {
        val bundle = info.metaData
        val values = LinkedHashMap<String, Any?>()
        bundle?.keySet()?.forEach { key -> values[key] = bundle.get(key) }

        val v2ResId = runCatching { bundle?.getInt("fclPlugin_V2", -1) ?: -1 }
            .getOrElse { -1 }
        val v2Json = readStringResource(pm, info, v2ResId)

        return PluginPackageInfo(
            packageName = info.packageName,
            appName = runCatching { info.loadLabel(pm).toString() }
                .getOrElse { info.packageName },
            appVersion = runCatching {
                pm.getPackageInfo(info.packageName, 0).versionName ?: ""
            }.getOrElse { "" },
            nativeLibraryDir = info.nativeLibraryDir,
            metaData = PluginMetaData(values),
            rendererV2ConfigJson = v2Json,
            isSystemApp = info.flags and ApplicationInfo.FLAG_SYSTEM != 0
        )
    }

    /** ZL2 RendererV2PluginManager.kt:103-107 的对等实现 */
    private fun readStringResource(
        pm: PackageManager,
        info: ApplicationInfo,
        resId: Int
    ): String? {
        if (resId <= 0) return null
        return runCatching { pm.getResourcesForApplication(info).getString(resId) }
            .getOrNull()
    }
}
