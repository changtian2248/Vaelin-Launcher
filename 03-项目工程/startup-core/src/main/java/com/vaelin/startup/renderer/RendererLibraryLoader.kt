/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Vaelin 自有实现，不含 ZalithLauncher2 的逐字代码。
 * 本文件是 T1.12 的「显式失败」核心：渲染器库 dlopen 失败 → 抛明确异常 → 中止启动。
 * 对照 ZL2 GameLauncher.kt:212-228 只 `Logger.error("Failed to load renderer")` 就往下走
 * 的静默劣化（黑屏但进程继续），此处任何失败都抛 [RendererLoadException]。
 *
 * 新增文件，不修改 VaelinRenderers.kt 与已有 9 个渲染器文件。
 */

package com.vaelin.startup.renderer

import java.io.File

/**
 * 渲染器库加载失败。
 *
 * [message] 直接是可给 UI 显示的文案：「渲染器 xxx 加载失败：找不到 libyyy.so」。
 * 携带 [rendererId] / [libraryName] / [searchedPaths] 供 diag 分类与日志落盘。
 */
class RendererLoadException(
    val rendererId: String,
    val libraryName: String,
    val searchedPaths: List<String>,
    cause: Throwable? = null
) : RuntimeException(
    "渲染器 $rendererId 加载失败：找不到 $libraryName" +
        if (searchedPaths.isEmpty()) "" else "（已查找：${searchedPaths.joinToString("、")}）",
    cause
)

/**
 * 渲染器库加载器（T1.12）。
 *
 * 纯 JVM、零 Android 依赖：dlopen 与文件存在性都是构造注入的函数。
 *  - 生产侧：`RendererLibraryLoader(paths) { ZLBridge.dlopen(it) }`
 *    （ZLBridge 静态块会加载 native 库，JVM 单测里绝不能直接引用）
 *  - 单测：注入假的 dlopen / fileExists，零网络零 native。
 *
 * 查找顺序由 [RendererLibPaths] 决定：应用 nativeLibraryDir 优先，
 * 其次用户可放 .so 的目录（换 .so 不重编 APK 的关键）。
 */
class RendererLibraryLoader(
    private val libPaths: RendererLibPaths,
    private val dlopen: (String) -> Boolean,
    private val fileExists: (String) -> Boolean = { path: String -> File(path).isFile }
) {
    data class LoadedRenderer(
        val rendererId: String,
        val mainLibrary: String,
        val mainLibraryPath: String,
        val extraLibraries: List<String>
    )

    /** 加载主库 + getDlopenLibrary() 里的附加库；任一失败即抛 [RendererLoadException]。 */
    fun load(renderer: RendererInterface): LoadedRenderer {
        val declared = renderer.getRendererLibrary()
        val mainPath = loadLibrary(declared, renderer.getRendererId())
        val extras = renderer.getDlopenLibrary().value.map { declaredExtra ->
            loadLibrary(declaredExtra, renderer.getRendererId())
        }
        return LoadedRenderer(
            rendererId = renderer.getRendererId(),
            mainLibrary = libPaths.displayName(declared),
            mainLibraryPath = mainPath,
            extraLibraries = extras
        )
    }

    /**
     * 加载单个库：按候选路径逐个「存在性检查 → dlopen」。
     *
     * 任何一个候选都不成功就抛异常，**不静默跳过、不返回 false、不记日志后继续**。
     * dlopen 抛异常或返回 false 都算失败，并汇总进 cause 便于落盘排查。
     */
    fun loadLibrary(declared: String, rendererId: String): String {
        val name = libPaths.displayName(declared)
        val candidates = libPaths.resolve(declared)
        val failures = mutableListOf<String>()

        for (path in candidates) {
            if (!fileExists(path)) {
                failures += "$path (文件不存在)"
                continue
            }
            val attempt = runCatching { dlopen(path) }
            val threw = attempt.exceptionOrNull()
            when {
                threw != null ->
                    failures += "$path (dlopen 抛 ${threw.javaClass.simpleName}: ${threw.message})"
                attempt.getOrNull() != true ->
                    failures += "$path (dlopen 返回 false)"
                else -> return path
            }
        }

        throw RendererLoadException(
            rendererId = rendererId,
            libraryName = name,
            searchedPaths = candidates,
            cause = IllegalStateException("候选路径逐一失败：${failures.joinToString("; ")}")
        )
    }
}
