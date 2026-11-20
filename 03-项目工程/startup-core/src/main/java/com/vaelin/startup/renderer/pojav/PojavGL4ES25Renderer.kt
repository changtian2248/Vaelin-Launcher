/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现。为兼容 Pojav 生态的渲染器 .so 而补的薄实现，
 * 不来自 ZalithLauncher2 —— ZL2 的渲染器表里没有这一项。
 * 冲突背景见 docs/04 §2.2：ZL2 的 "opengles3" 指向 libng_gl4es.so，
 * 而 Pojav 的 "opengles3" 指向 libgl4es_114.so，同名不同库，因此并存而不合并。
 */

package com.vaelin.startup.renderer.pojav

import com.vaelin.startup.renderer.RendererInterface

/**
 * Pojav 的 `opengles2_5` 档位：同样是 libgl4es_114.so，
 * 但在 Pojav 里它代表 GL4ES 的 2.5 兼容档，与 ZL2 的 `opengles2` 区分开。
 */
object PojavGL4ES25Renderer : RendererInterface {

    override fun getRendererId(): String = "opengles2_5"

    override fun getUniqueIdentifier(): String = "7c1f3a20-5b8d-4e6a-9d2f-1a4b6c8e0f31"

    override fun getRendererName(): String = "GL4ES 2.5 (Pojav)"

    override fun getRendererSummary(): String =
        "Pojav 的 opengles2_5 档位，复用 libgl4es_114.so"

    override fun getMinMCVersion(): String = "1.7.10"

    override fun getMaxMCVersion(): String = "1.21.4"

    override fun getRendererEnv(): Lazy<Map<String, String>> = lazy { emptyMap() }

    override fun getDlopenLibrary(): Lazy<List<String>> = lazy { emptyList() }

    override fun getRendererLibrary(): String = "libgl4es_114.so"
}
