/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现。ZL2 完全没有 LTW（全仓搜 "ltw" 只命中
 * usePlatformDefaultWidth 子串和两处中文注释）。
 *
 * 实测：ZL2 2.8.1 arm64-v8a 的 APK 里 `lib/arm64-v8a/` 下**没有** `libltw.so`。
 * 所以要真正启用这一档，必须另外引入 PojavLauncherTeam/LTW 的 `ltw-release.aar`
 * （其构建脚本从 `releases/latest/download/ltw-release.aar` 取）。
 * 库到位之前，选中这一档会在 dlopen 阶段失败并抛出明确错误，不会静默回退到别的渲染器。
 */

package com.vaelin.startup.renderer.pojav

import com.vaelin.startup.renderer.RendererInterface

/**
 * Pojav 的 `opengles3_ltw` 档位：走 LTW（Lightweight Translation Wrapper），
 * 用 `POJAVEXEC_EGL` 把 EGL 调用导向 LTW 自己的实现。
 */
object PojavLTWRenderer : RendererInterface {

    override fun getRendererId(): String = "opengles3_ltw"

    override fun getUniqueIdentifier(): String = "5a8c2e93-1f47-4d8b-a6c0-3e9b7f2d4815"

    override fun getRendererName(): String = "LTW (Pojav)"

    override fun getRendererSummary(): String =
        "Pojav 的 opengles3_ltw 档位，走 libltw.so 并提供独立 EGL 实现（需另行引入 LTW 的 aar，当前发行包未含此库）"

    override fun getMinMCVersion(): String = "1.17"

    override fun getMaxMCVersion(): String = "1.21.4"

    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy {
        mapOf("POJAVEXEC_EGL" to "libltw.so")
    }

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy {
        listOf("libltw.so")
    }

    override fun getRendererLibrary(): String = "libltw.so"

    override fun getRendererEGL(): String = "libltw.so"
}
