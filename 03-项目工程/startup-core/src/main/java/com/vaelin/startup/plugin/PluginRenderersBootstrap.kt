/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Vaelin 自有实现。把 T1.12 的插件发现/注册链接到两个进程的启动时序里：
 * 主进程（MainActivity.onCreate）与 :game 进程（VaelinLauncher.launch）各自调用
 * [scanAndRegister]，注册表是 per-process 的 object，两边都得扫，否则 :game 按
 * uniqueId 选插件渲染器时会抛「渲染器不存在」。
 */

package com.vaelin.startup.plugin

import android.content.Context
import com.vaelin.startup.renderer.RendererLibPathsProvider
import com.vaelin.startup.util.VLog

/**
 * 插件扫描 + 注册的一次性接线（本文件是接线层，核心逻辑都在 PluginDiscovery /
 * PluginRegistrar，那两个文件保持零改动）。
 *
 * 失败语义（团队准则「禁止静默降级」的插件版）：
 *  - 插件环境是外部变量（装了什么 APK 系统说了算），扫描整体异常不让启动器崩，
 *    但必须把异常原文记进 VLog，不留「插件悄悄失效」；
 *  - 单个插件被拒收的原因（缺 meta-data / 库不存在 / uniqueId 冲突 / 系统应用）由
 *    PluginDiscovery 的 skipped 与 PluginRegistrar 的 outcomes 承载，这里逐条落日志，
 *    保证日志里能看到「哪个插件、因为什么被拒收」。
 */
object PluginRenderersBootstrap {

    private const val TAG = "PluginBootstrap"

    /** 本进程是否已完成一次成功扫描；失败时留 false，允许下次 onCreate 重试 */
    @Volatile
    private var scanned = false

    private val lock = Any()

    /**
     * 扫描已安装插件并把渲染器注入 [com.vaelin.startup.renderer.VaelinRenderers]。
     *
     * 前置条件：`VPaths.init(context)` 已执行（libPaths 解析需要 nativeLibDir /
     * filesExternal）。进程内幂等：成功扫描过一次后直接返回 null，避免 Activity
     * 重建时重复 queryIntentActivities、也避免对同一插件重复注册刷「冲突」假警告。
     *
     * @return 本次的注册结果；已扫过或扫描失败时为 null（原因见日志）。
     */
    fun scanAndRegister(context: Context): RegistrationResult? {
        if (scanned) return null
        synchronized(lock) {
            if (scanned) return null
            val result = runCatching { scanOnce(context) }
                .onFailure { t ->
                    VLog.error(TAG, "插件扫描失败（不影响内置渲染器，插件渲染器本进程不可用）", t)
                }
                .getOrNull()
            if (result != null) scanned = true
            return result
        }
    }

    private fun scanOnce(context: Context): RegistrationResult {
        val discovery = PluginDiscovery(
            source = AndroidPluginPackageSource(context),
            libPaths = RendererLibPathsProvider.fromVPaths(),
        )
        val found = discovery.discover()

        found.plugins.forEach { plugin ->
            val typeNames = plugin.types.joinToString("/") { it.name }
            VLog.info(
                TAG,
                "插件命中: ${plugin.packageInfo.packageName} " +
                    "(${plugin.packageInfo.appName} ${plugin.packageInfo.appVersion}) " +
                    "类型=[$typeNames] 渲染器 ${plugin.renderers.size} 个",
            )
        }
        // 拒收原因逐条留痕：这是「哪个插件被拒收及原因」的主日志出口
        found.skipped.forEach { skip ->
            VLog.warning(TAG, "插件拒收: ${skip.packageName} — ${skip.reason}")
        }

        val registration = PluginRegistrar.register(found)
        registration.outcomes.filter { !it.registered }.forEach { outcome ->
            VLog.warning(
                TAG,
                "渲染器注册被拒: ${outcome.packageName} 库=${outcome.declaredLibrary} — ${outcome.reason}",
            )
        }
        VLog.info(
            TAG,
            "插件扫描完成: 插件包 ${found.plugins.size} 个，声明渲染器 ${found.renderers.size} 个，" +
                "注册成功 ${registration.registeredCount} 个，拒收 ${registration.outcomes.size - registration.registeredCount} 个",
        )
        return registration
    }
}
