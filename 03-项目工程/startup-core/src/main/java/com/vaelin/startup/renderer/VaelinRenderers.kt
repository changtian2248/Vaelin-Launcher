/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * 结构来自 ZalithLauncher2 的 game/renderer/Renderers.kt（GPL-3.0），
 * 与它的两处行为差异是刻意的：
 *
 *  1. setCurrentRenderer 找不到渲染器时抛异常，不再静默回退到列表首个。
 *     ZL2 的回退是为老用户配置迁移服务的；Vaelin 没有历史包袱，
 *     静默回退会让「渲染器 dlopen 失败」这类问题被掩盖成「游戏画质不对」。
 *  2. 去掉 getLegacyRendererIds()。它依赖 MobileGlues 的 ConfigurableRenderer
 *     配置体系。MobileGlues 本体已作为普通渲染器接入（内置第 10 个，
 *     builtin/MobileGluesRenderer.kt：固定默认配置写盘，无用户可调 UI），
 *     但 ZL2 那套「每渲染器可调配置 + 旧配置迁移」的 ConfigurableRenderer
 *     层 Vaelin 仍然不引入。
 */

package com.vaelin.startup.renderer

import com.vaelin.startup.renderer.builtin.FreedrenoRenderer
import com.vaelin.startup.renderer.builtin.GL4ESRenderer
import com.vaelin.startup.renderer.builtin.KopperZinkRenderer
import com.vaelin.startup.renderer.builtin.MobileGluesRenderer
import com.vaelin.startup.renderer.builtin.NGGL4ESRenderer
import com.vaelin.startup.renderer.builtin.PanfrostRenderer
import com.vaelin.startup.renderer.builtin.VirGLRenderer
import com.vaelin.startup.renderer.pojav.PojavGL4ES25Renderer
import com.vaelin.startup.renderer.pojav.PojavLTWRenderer
import com.vaelin.startup.renderer.pojav.PojavZinkRenderer
import com.vaelin.startup.util.VLog

private const val TAG = "Renderers"

/**
 * 渲染器注册表。内置渲染器与后续的插件渲染器都汇总到这里。
 */
object VaelinRenderers {

    private val renderers: MutableList<RendererInterface> = mutableListOf()
    private var currentRenderer: RendererInterface? = null
    private var initialized = false

    /**
     * 注册全部内置渲染器。
     *
     * 前七个是 ZL2 的原样实现（MobileGlues 插在第 4 位，与 ZL2 Renderers.kt:51-60 的
     * 注册顺序一致）；后三个是 Vaelin 为兼容 Pojav 生态的渲染器 .so 补的薄实现，
     * 详见 docs/04 §2 的三处冲突说明。
     */
    fun init(reset: Boolean = false) {
        if (initialized && !reset) return
        initialized = true

        if (reset) {
            renderers.clear()
            currentRenderer = null
        }

        addRenderers(
            NGGL4ESRenderer,
            GL4ESRenderer,
            KopperZinkRenderer,
            MobileGluesRenderer,
            VirGLRenderer,
            FreedrenoRenderer,
            PanfrostRenderer,
            PojavZinkRenderer,
            PojavGL4ES25Renderer,
            PojavLTWRenderer
        )
    }

    fun getRenderers(): List<RendererInterface> = renderers

    fun addRenderers(vararg items: RendererInterface) {
        items.forEach { addRenderer(it) }
    }

    /**
     * @return true 表示成功加入；false 表示唯一标识冲突（同名渲染器只保留第一个）
     */
    fun addRenderer(renderer: RendererInterface): Boolean {
        val dup = renderers.firstOrNull {
            it.getUniqueIdentifier() == renderer.getUniqueIdentifier()
        }
        return if (dup != null) {
            VLog.warning(
                TAG,
                "renderer unique identifier conflict: " +
                    "${renderer.getRendererName()}/${renderer.getUniqueIdentifier()} " +
                    "vs ${dup.getRendererName()}"
            )
            false
        } else {
            renderers.add(renderer)
            VLog.info(
                TAG,
                "renderer loaded: ${renderer.getRendererName()} " +
                    "(${renderer.getRendererId()} / ${renderer.getUniqueIdentifier()})"
            )
            true
        }
    }

    /**
     * 按唯一标识选择渲染器。
     *
     * 找不到就抛异常——不静默回退。调用方要么把配置修正，要么显式选列表里的某一项。
     */
    fun setCurrentRenderer(uniqueIdentifier: String) {
        check(initialized) { "VaelinRenderers.init() has not been called" }
        val found = renderers.firstOrNull { it.getUniqueIdentifier() == uniqueIdentifier }
            ?: throw IllegalArgumentException(
                "renderer not found: $uniqueIdentifier " +
                    "(available: ${renderers.joinToString { it.getRendererId() }})"
            )
        currentRenderer = found
        VLog.info(TAG, "current renderer = ${found.getRendererName()} ($uniqueIdentifier)")
    }

    /**
     * 按 getRendererId() 选择渲染器，用于配置文件里直接写 "opengles2" 这类短名的场景。
     */
    fun setCurrentRendererById(rendererId: String) {
        check(initialized) { "VaelinRenderers.init() has not been called" }
        val found = renderers.firstOrNull { it.getRendererId() == rendererId }
            ?: throw IllegalArgumentException(
                "renderer id not found: $rendererId " +
                    "(available: ${renderers.joinToString { it.getRendererId() }})"
            )
        currentRenderer = found
        VLog.info(TAG, "current renderer = ${found.getRendererName()} ($rendererId)")
    }

    fun getCurrentRenderer(): RendererInterface {
        check(initialized) { "VaelinRenderers.init() has not been called" }
        return currentRenderer
            ?: throw IllegalStateException("no renderer selected; call setCurrentRenderer first")
    }

    fun isCurrentRendererValid(): Boolean = initialized && currentRenderer != null
}
