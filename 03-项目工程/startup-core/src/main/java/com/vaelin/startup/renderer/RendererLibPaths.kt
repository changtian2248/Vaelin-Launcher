/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Vaelin 自有实现，不含 ZalithLauncher2 的逐字代码。
 * 本文件是 T1.12「换 .so 不重编」的目录解析层，新增文件，
 * 不修改 VaelinRenderers.kt 与已有 9 个渲染器文件。
 */

package com.vaelin.startup.renderer

import java.io.File

/**
 * 渲染器 .so 的两级查找目录（T1.12）。
 *
 * 顺序按任务书要求：**应用 nativeLibraryDir 优先，其次用户可放 .so 的目录**。
 * [nativeLibraryDir] 是 APK 解包出的只读目录；[userRendererDir] 是用户可写、
 * 可手工放置/替换渲染器 .so 的目录。
 *
 * 为什么没有给 VPaths 新增 `rendererLibDir` 字段：
 *  - 任务边界禁止改 util 包（VPaths.kt 不在允许写入的两个新包里，且被其他
 *    Agent 的改动范围牵连），也禁止改 build.gradle.kts；
 *  - VPaths.filesExternal 已经是「用户可写的外部私有目录」，其下 logs / native
 *    子目录就是既有约定，复用它派生 `<filesExternal>/renderers` 与之对齐，
 *    不需要为单一用途在全剧对象里加一个字段；
 *  - 生产侧接线见 RendererLibPathsProvider.fromVPaths()。
 *
 * 本类纯 JVM：两个目录字符串由构造注入，不引用 VPaths / android.*，
 * 因此能在 JVM 单测里直接构造，不触发 VPaths 的 lateinit 崩溃。
 */
class RendererLibPaths(
    val nativeLibraryDir: String,
    val userRendererDir: String
) {
    /** 查找顺序（供报告/操作步骤复述） */
    fun searchDirs(): List<String> = listOf(nativeLibraryDir, userRendererDir)

    /**
     * 把渲染器声明的库名解析成按优先级排列的候选绝对路径。
     *
     * 两种来源形态（已逐行核对）：
     *  - 内置渲染器（如 GL4ESRenderer.kt:36）返回裸文件名 `libgl4es_114.so`
     *    → 依次在两级目录下各生成一个候选；
     *  - 插件渲染器（ZL2 RendererPlugin.kt:24-53 / RendererV2Plugin.kt:31-53）
     *    返回绝对路径（指向插件自己的 nativeLibraryDir）→ 原样保留一个候选。
     */
    fun resolve(declared: String): List<String> {
        if (declared.isBlank()) return emptyList()
        // Android 的绝对路径以 '/' 开头；JVM 单测（Windows）可能是 'C:\...'。
        // 两种都视为「插件已给出完整路径」，不再往两级目录里拆。
        if (declared.startsWith("/") || File(declared).isAbsolute) return listOf(declared)
        val name = File(declared).name
        return listOf(
            File(nativeLibraryDir, name).absolutePath,
            File(userRendererDir, name).absolutePath
        ).distinct()
    }

    /** UI/报告用的短名（去掉目录，只留 libxxx.so） */
    fun displayName(declared: String): String = File(declared).name
}
