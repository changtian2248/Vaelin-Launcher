/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现。
 */

package com.vaelin.startup.renderer.pojav

import com.vaelin.startup.renderer.RendererInterface

/**
 * Pojav 的 `vulkan_zink` 档位，映射到 ZL2 生态实际存在的 zink 实现（Kopper）。
 *
 * 为什么不是同名文件映射：Pojav 的 `vulkan_zink` 加载单一 `libOSMesa.so`，
 * 而 ZL2 的发行包里**没有** `libOSMesa.so`（实测 ZL2 2.8.1 arm64-v8a APK，
 * 只有按版本拆开的 `libOSMesa_8.so` / `libOSMesa_2121.so` / `libOSMesa_2300d.so`），
 * 它的 zink 走 Kopper —— `libglxshim.so` 作为 GL 入口、`libEGL_mesa.so` 作为 EGL 实现，
 * zink 驱动本体在 `libzink_dri.so`。
 *
 * 因此这里保留 Pojav 的 id 与 uuid 以便配置迁移，但库组合用 ZL2 实际能跑起来的那套。
 * `needsZinkDriverOverride = true` 会让宿主自动写入 `MESA_LOADER_DRIVER_OVERRIDE=zink`
 * （见 `GameLauncher.kt:512-513` 的对应逻辑），无需在此重复声明。
 */
object PojavZinkRenderer : RendererInterface {

    override fun getRendererId(): String = "vulkan_zink"

    override fun getUniqueIdentifier(): String = "2d9e4b71-8c35-4a6f-b0e8-7d5c3a291f64"

    override fun getRendererName(): String = "Zink (Pojav 兼容档)"

    override fun getRendererSummary(): String =
        "Pojav 的 vulkan_zink 名称，实际走 ZL2 的 Kopper zink（libglxshim + libEGL_mesa + libzink_dri）"

    override val mesaBased: Boolean = true

    override val needsZinkDriverOverride: Boolean = true

    override fun getMinMCVersion(): String = "1.17.1"

    override fun getMaxMCVersion(): String = "26.2"

    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy {
        mapOf(
            "LIBGL_ES" to "3"
        )
    }

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }

    override fun getRendererLibrary(): String = "libglxshim.so"

    override fun getRendererEGL(): String = "libEGL_mesa.so"
}
