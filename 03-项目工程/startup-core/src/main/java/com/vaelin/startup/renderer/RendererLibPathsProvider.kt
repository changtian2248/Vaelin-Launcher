/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Vaelin 自有实现。生产侧把 VPaths 的目录接线到纯 JVM 的 RendererLibPaths。
 * 本文件新增于 renderer 包，不修改 VaelinRenderers.kt 与已有 9 个渲染器文件。
 */

package com.vaelin.startup.renderer

import com.vaelin.startup.util.VPaths
import java.io.File

/**
 * 目录来源（T1.12「换 .so 不重编」的生产侧接线）。
 *
 *  - nativeLibraryDir = VPaths.nativeLibDir（APK 解包目录，只读）
 *  - userRendererDir  = File(VPaths.filesExternal, "renderers")
 *    ——复用既有「用户可写外部私有目录」派生，不新增 VPaths 字段（util 包
 *    不在本任务写边界内）。用户把替换版 libgl4es_114.so 放这里即可生效，
 *    无需重编 APK；查找顺序 nativeLibraryDir 优先、用户目录其次见
 *    [RendererLibPaths.resolve]。
 *
 * 注意：VPaths 的 lateinit 字段必须先经过 VPaths.init(context)，
 * 否则访问会抛 UninitializedPropertyAccessException。核心逻辑（RendererLibPaths
 * / RendererLibraryLoader）不引用 VPaths，因此 JVM 单测不触碰此处。
 */
object RendererLibPathsProvider {

    const val USER_RENDERER_SUBDIR = "renderers"

    fun fromVPaths(): RendererLibPaths = RendererLibPaths(
        nativeLibraryDir = VPaths.nativeLibDir,
        userRendererDir = File(VPaths.filesExternal, USER_RENDERER_SUBDIR).absolutePath
    )

    /** 换 .so 时可复述的目录说明，供报告与 UI 提示。 */
    fun describe(): String =
        "应用目录 ${VPaths.nativeLibDir} 优先，用户可写目录 " +
            File(VPaths.filesExternal, USER_RENDERER_SUBDIR).absolutePath + " 其次"
}
