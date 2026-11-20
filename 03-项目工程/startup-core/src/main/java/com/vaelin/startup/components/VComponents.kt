/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * 组件清单与 assets 布局对齐 ZalithLauncher2 的 components/Components.kt：
 * 两边目录名必须一致，否则从上游取来的二进制包（含 manifest.json）会校验不上。
 */

package com.vaelin.startup.components

import android.content.Context
import android.system.Os
import com.vaelin.startup.util.TarXz
import com.vaelin.startup.util.VLog
import com.vaelin.startup.util.VPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext

/**
 * 随 APK 分发的运行时组件（LWJGL、AWT 补丁、认证库、启动器补丁）。
 *
 * 与 JRE 的区别：JRE 是 `.tar.xz` 打包、按版本解压；这里的分发形态是**目录树**，
 * 直接按 assets 路径递归复制即可。两种都落地在 [VPaths.componentsDir] 下。
 */
object VComponents {

    private const val TAG = "VComponents"

    /** 解压完成标记文件名。R1 用标记文件判定；接下载链后换成 manifest 的 SHA-1 校验。 */
    private const val MARKER = ".installed"

    enum class Component(val installName: String, val assetsDir: String) {
        AUTH_LIBS("auth_libs", "components/auth_libs"),
        CACIOCAVALLO("caciocavallo", "components/caciocavallo"),
        CACIOCAVALLO17("caciocavallo17", "components/caciocavallo17"),
        LWJGL333("lwjgl/3.3.3", "app_runtime/lwjgl/3.3.3"),
        LWJGL341("lwjgl/3.4.1", "app_runtime/lwjgl/3.4.1"),
        LAUNCHER("launcher", "components/launcher"),
    }

    /** 组件在设备上的根目录 */
    fun location(component: Component): File = File(VPaths.componentsDir, component.installName)

    fun isInstalled(component: Component): Boolean = File(location(component), MARKER).exists()

    /**
     * 确保组件已解出。幂等：已有标记直接返回。
     *
     * 失败会清掉半成品目录——半解出来的组件比没有更危险，它会让后续步骤
     * 拿到一堆缺文件的路径然后抛出难以定位的错误。
     */
    suspend fun ensureInstalled(
        context: Context,
        component: Component,
        onProgress: (String) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val dest = location(component)
        if (isInstalled(component)) return@withContext dest

        try {
            if (dest.exists() && !dest.deleteRecursively()) {
                error("无法清理旧的组件目录: ${dest.absolutePath}")
            }
            dest.mkdirs()
            copyTree(context, component.assetsDir, dest, onProgress)
            File(dest, MARKER).writeText(component.installName)
            VLog.info(TAG, "组件就绪: ${component.installName} -> ${dest.absolutePath}")
            dest
        } catch (e: Exception) {
            runCatching { dest.deleteRecursively() }
            VLog.error(TAG, "解出组件 ${component.installName} 失败，已清理", e)
            throw e
        }
    }

    /**
     * 递归复制 assets 子树。
     *
     * `AssetManager.list()` 对文件返回空数组、对目录返回子项名，
     * 所以"空数组即文件"是这里唯一可靠的区分方式。
     */
    private suspend fun copyTree(
        context: Context,
        assetPath: String,
        dest: File,
        onProgress: (String) -> Unit,
    ) {
        coroutineContext.ensureActive()
        val children = context.assets.list(assetPath)

        if (children.isNullOrEmpty()) {
            dest.parentFile?.mkdirs()
            onProgress(dest.name)
            context.assets.open(assetPath).use { input ->
                FileOutputStream(dest).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n > 0) output.write(buffer, 0, n)
                    }
                }
            }
            if (dest.name.endsWith(".so")) {
                runCatching { Os.chmod(dest.absolutePath, 0b111_101_101) }
            }
            return
        }

        dest.mkdirs()
        for (child in children) {
            copyTree(context, "$assetPath/$child", File(dest, child), onProgress)
        }
    }

    /**
     * 如果组件分发形态是单包 `.tar.xz`（后续版本可能改成这样），走这条路径。
     * R1 的 assets 全是目录树，这个函数暂时没有调用点，保留是为了与 [TarXz] 的契约对齐。
     */
    suspend fun ensureInstalledFromArchive(
        context: Context,
        component: Component,
        archiveAssetName: String,
        onProgress: (String) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val dest = location(component)
        if (isInstalled(component)) return@withContext dest
        dest.mkdirs()
        context.assets.open("${component.assetsDir}/$archiveAssetName").use { input ->
            TarXz.extract(input, dest, TAG, onProgress)
        }
        File(dest, MARKER).writeText(component.installName)
        dest
    }
}
