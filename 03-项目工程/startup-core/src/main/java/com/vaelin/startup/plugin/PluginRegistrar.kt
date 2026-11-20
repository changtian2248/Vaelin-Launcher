/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Vaelin 自有实现。把发现层产出的插件渲染器注入 VaelinRenderers 注册表。
 */

package com.vaelin.startup.plugin

import com.vaelin.startup.renderer.VaelinRenderers

/** 单个渲染器的注册结果。 */
data class RegistrationOutcome(
    val packageName: String,
    val declaredLibrary: String,
    val registered: Boolean,
    val reason: String
)

data class RegistrationResult(
    val outcomes: List<RegistrationOutcome>,
    val skipped: List<SkippedPlugin>
) {
    val registeredCount: Int get() = outcomes.count { it.registered }
}

/**
 * 插件渲染器 → `VaelinRenderers.addRenderer` 的桥（T1.12 第 3 条）。
 *
 * 沿用 VaelinRenderers.kt:81-102 的语义：addRenderer 按 getUniqueIdentifier()
 * 查重，冲突返回 false 不覆盖（首个生效）。这里把每个结果显式记成
 * [RegistrationOutcome]，冲突/库缺失都不静默吞掉，供 UI 列表与日志复述。
 *
 * 库存在性校验：[PluginDiscovery] 已把 libraryPresent=false 的描述符标好，
 * 这里再挡一次，绝不把 .so 不存在的渲染器注进「可切换」列表。
 */
object PluginRegistrar {

    fun register(result: PluginDiscoveryResult): RegistrationResult {
        val outcomes = mutableListOf<RegistrationOutcome>()
        val skipped = result.skipped.toMutableList()

        for (descriptor in result.renderers) {
            if (!descriptor.libraryPresent) {
                outcomes += RegistrationOutcome(
                    packageName = descriptor.packageName,
                    declaredLibrary = descriptor.declaredLibrary,
                    registered = false,
                    reason = "库不存在：${descriptor.declaredLibrary}" +
                        "（已查找：${descriptor.searchedPaths.joinToString("、")}）"
                )
                continue
            }
            val added = VaelinRenderers.addRenderer(descriptor.renderer)
            outcomes += RegistrationOutcome(
                packageName = descriptor.packageName,
                declaredLibrary = descriptor.declaredLibrary,
                registered = added,
                reason = if (added) "已注册"
                else "唯一标识冲突：addRenderer 返回 false，保留首个同名渲染器"
            )
            if (!added) {
                skipped += SkippedPlugin(
                    descriptor.packageName,
                    "渲染器 uniqueIdentifier 冲突，addRenderer 返回 false 未覆盖"
                )
            }
        }
        return RegistrationResult(outcomes, skipped)
    }
}
