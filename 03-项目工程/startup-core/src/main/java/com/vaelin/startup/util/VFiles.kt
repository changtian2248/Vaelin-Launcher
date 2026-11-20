/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * 取自 ZalithLauncher2 的 utils/file/FileUtils.kt 中被启动链实际用到的那几个函数。
 * 没有整份复制：原文件 557 行，大部分是 zip 操作与文件计数，
 * 属于 Vaelin 的「不复用」区（模块安装、资源包处理走 Vaelin 自己的实现）。
 */

package com.vaelin.startup.util

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/** 保证父目录存在，返回自身，便于链式调用 */
fun File.ensureParentDirectory(): File {
    parentFile?.let { if (!it.exists() && !it.mkdirs()) { /* 由调用方在写入时暴露失败 */ } }
    return this
}

/** 创建目录，失败不抛异常。用于「尽力而为」的路径兜底 */
fun File.ensureDirectorySilently(): Boolean =
    runCatching { exists() || mkdirs() }.getOrDefault(false)

fun InputStream.readString(): String = bufferedReader().use { it.readText() }

/**
 * 把 assets 里的文件复制到指定路径。
 *
 * @param overwrite false 且目标已存在时直接返回，用于「只在缺失时解压」的场景。
 */
@Throws(IOException::class)
fun Context.copyAssetFile(
    assetPath: String,
    output: File,
    overwrite: Boolean
) {
    if (output.exists() && !overwrite) return
    output.ensureParentDirectory()
    assets.open(assetPath).use { input ->
        FileOutputStream(output).use { out ->
            input.copyTo(out)
            out.flush()
            // 解压出来的 JRE 文件可能几十 MB，落盘后再返回，避免后续步骤读到半截文件
            out.fd.sync()
        }
    }
}
