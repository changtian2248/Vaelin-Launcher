/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * 解压链路参考 ZalithLauncher2 的 game/multirt/RuntimesManager.kt
 * （installRuntimeBinPack / uncompressTarXZ），读的是同一套 assets 结构：
 *   assets/runtimes/<jre>/universal.tar.xz  —— 与平台无关的部分
 *   assets/runtimes/<jre>/bin-arm64.tar.xz  —— 平台相关的 .so 与可执行文件
 *   assets/runtimes/<jre>/version           —— 单个字符的版本号，用于判断是否需要重解
 *
 * 与 ZL2 的两处差异：
 * 1. 不做 unpack200。ZL2 只在 JDK 8 上有 .pack 文件，需要 libunpack200.so 跑一遍；
 *    Vaelin 当前只带 jre-17 与 jre-21，这两个包里没有 .pack。
 *    万一将来加了带 .pack 的运行时，warnIfPackedJars 会打警告而不是静默产出坏运行时。
 * 2. 不依赖 Room/AllSettings，安装状态只由 <dest>/version 文件判定。
 */

package com.vaelin.startup.multirt

import android.content.Context
import com.vaelin.startup.util.Architecture
import com.vaelin.startup.util.TarXz
import com.vaelin.startup.util.VLog
import com.vaelin.startup.util.VPaths
import com.vaelin.startup.util.readString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** assets 里一个可用的 JRE 包 */
data class JreAsset(
    val name: String,
    /** 相对 assets 的目录，例如 runtimes/jre-17 */
    val assetDir: String,
    /** assets 里 version 文件的内容；解压后写进运行时目录，用于判断是否需要重解 */
    val version: String,
    /** 当前设备需要的 bin 包文件名，例如 bin-arm64.tar.xz */
    val binPackName: String,
)

object JreInstaller {

    private const val TAG = "JreInstaller"
    private const val RUNTIMES_ASSET_ROOT = "runtimes"

    /** 已安装运行时的版本号；未安装返回 null */
    fun installedVersion(name: String): String? {
        val f = File(File(VPaths.multirtDir, name), "version")
        if (!f.exists()) return null
        return runCatching { f.readText() }.getOrNull()
    }

    /** 列出 assets 里与当前设备架构匹配的 JRE 包 */
    suspend fun listAvailable(context: Context): List<JreAsset> = withContext(Dispatchers.IO) {
        val binPackName =
            "bin-" + Architecture.archAsString(Architecture.getDeviceArchitecture()) + ".tar.xz"
        val names = context.assets.list(RUNTIMES_ASSET_ROOT)?.toList().orEmpty()
        names.mapNotNull { name ->
            val dir = "$RUNTIMES_ASSET_ROOT/$name"
            val files = context.assets.list(dir)?.toList().orEmpty()
            if (binPackName !in files) return@mapNotNull null
            val version = runCatching {
                context.assets.open("$dir/version").use { it.readString() }
            }.getOrElse { e ->
                VLog.warning(TAG, "读取 $dir/version 失败，跳过该运行时", e)
                return@mapNotNull null
            }
            // version 文件里只有一个字符，末尾的换行会导致与新解压的比较失败，统一 trim
            JreAsset(name, dir, version.trim(), binPackName)
        }.sortedBy { it.name }
    }

    /**
     * 保证 [name] 这个运行时在磁盘上可用，返回它的根目录。
     *
     * 已装且版本一致时直接返回；否则删目录重解。
     * 解压中途失败会删掉半成品，避免留下「看起来装了其实缺文件」的运行时。
     */
    suspend fun ensureInstalled(
        context: Context,
        name: String,
        onProgress: (String) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val asset = listAvailable(context).firstOrNull { it.name == name }
            ?: throw IOException("assets 里没有与当前设备架构匹配的运行时: $name")

        val dest = File(VPaths.multirtDir, name)
        val installed = installedVersion(name)
        if (installed == asset.version && dest.isDirectory) {
            VLog.info(TAG, "运行时 $name 已是最新（version=${asset.version}），跳过解压")
            return@withContext dest
        }

        try {
            if (dest.exists()) {
                onProgress("清理旧运行时 $name")
                if (!dest.deleteRecursively()) {
                    throw IOException("无法删除旧运行时目录: ${dest.absolutePath}")
                }
            }
            if (!dest.mkdirs() && !dest.isDirectory) {
                throw IOException("无法创建运行时目录: ${dest.absolutePath}")
            }

            onProgress("解压 $name 公共部分")
            context.assets.open("${asset.assetDir}/universal.tar.xz").use { input ->
                TarXz.extract(input, dest, TAG, onProgress)
            }

            onProgress("解压 $name 平台相关部分")
            context.assets.open("${asset.assetDir}/${asset.binPackName}").use { input ->
                TarXz.extract(input, dest, TAG, onProgress)
            }

            warnIfPackedJars(dest)

            File(dest, "version").writeText(asset.version)
            VLog.info(TAG, "运行时 $name 就绪: ${dest.absolutePath}")
            dest
        } catch (e: Exception) {
            runCatching { dest.deleteRecursively() }
            VLog.error(TAG, "解压运行时 $name 失败，已清理半成品", e)
            throw e
        }
    }

    /** 删除已安装的运行时 */
    fun remove(name: String) {
        val dest = File(VPaths.multirtDir, name)
        if (dest.exists() && !dest.deleteRecursively()) {
            VLog.warning(TAG, "删除运行时 $name 失败: ${dest.absolutePath}")
        }
    }

    /** JDK 8 的运行时里会有 .pack，需要 libunpack200.so 转换；当前打包的 17/21 没有 */
    private fun warnIfPackedJars(dest: File) {
        val packed = dest.walkTopDown().firstOrNull { it.isFile && it.extension == "pack" }
        if (packed != null) {
            VLog.warning(
                TAG,
                "运行时里存在 .pack 文件（${packed.relativeTo(dest)}），" +
                    "当前未集成 unpack200，该运行时的部分 jar 不可用"
            )
        }
    }
}
