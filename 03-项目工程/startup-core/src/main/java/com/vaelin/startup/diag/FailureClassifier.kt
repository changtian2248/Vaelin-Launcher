/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Vaelin 自有实现。T1.13 的失败分类纯函数，零 Android 依赖。
 */

package com.vaelin.startup.diag

import com.vaelin.startup.renderer.RendererLoadException

/**
 * 把一次失败信号判成三类之一。
 *
 * 判定顺序（显式、可复述）：
 *  1. 异常是 [RendererLoadException]（或阶段是 RENDERER_LOAD）→ RENDERER_DLOPEN_FAILED。
 *     渲染器加载失败最明确，优先级最高，避免被后续的 JVM 退出码误归类。
 *  2. 阶段 JVM_BOOT 且（退出码非 0 或抛异常）→ JVM_START_FAILED：java 进程没拉起来。
 *  3. 阶段 GAME_RUNNING 且（退出码非 0 或进程被杀）→ MC_CRASHED：跑起来后崩了。
 *  4. 其余兜底 → JVM_START_FAILED（无有效退出码、无已知原因，按启动失败处理，
 *     不静默判成成功）。
 *
 * 用假的 exitCode / throwable 即可驱动全部三个分支，不依赖 Android。
 */
object FailureClassifier {

    fun classify(event: LaunchFailureEvent): FailureCategory = when {
        event.throwable is RendererLoadException ||
            event.stage == LaunchStage.RENDERER_LOAD ->
            FailureCategory.RENDERER_DLOPEN_FAILED

        event.stage == LaunchStage.JVM_BOOT &&
            (event.exitCode != 0 || event.throwable != null) ->
            FailureCategory.JVM_START_FAILED

        event.stage == LaunchStage.GAME_RUNNING &&
            (event.exitCode != 0 || event.gameProcessKilled) ->
            FailureCategory.MC_CRASHED

        else -> FailureCategory.JVM_START_FAILED
    }
}
