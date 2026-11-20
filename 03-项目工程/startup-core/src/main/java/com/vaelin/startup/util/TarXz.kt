/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * 解压实现来自 ZalithLauncher2 的 game/multirt/RuntimesManager.kt（uncompressTarXz）。
 * 抽成独立工具是因为 JRE 运行包与 LWJGL 组件包用的是同一种 .tar.xz 分发格式，
 * 两处各写一份最后一定会漂移。
 */

package com.vaelin.startup.util

import android.system.Os
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.coroutineContext

object TarXz {

    private const val BUFFER_SIZE = 8192

    /**
     * 把一个 .tar.xz 流解开到 [dest]。
     *
     * 行为约定：
     * - 含 `..` 的条目一律拒绝，防止 tar 穿越写到目录外；
     * - 文件大小与已存在文件一致时跳过，支持被打断后的续解；
     * - 符号链接创建失败只记警告。Android 上 JRE 的 lib/ *.so 别名有时建不出来，
     *   但不该因为一条链接就让整个运行时解不出来。
     */
    suspend fun extract(
        input: InputStream,
        dest: File,
        tag: String,
        onProgress: (String) -> Unit = {},
    ) {
        val buffer = ByteArray(BUFFER_SIZE)
        TarArchiveInputStream(XZCompressorInputStream(BufferedInputStream(input))).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                coroutineContext.ensureActive()

                val entryName = entry.name
                if (entryName.isBlank() || entryName.contains("..")) {
                    throw IOException("非法 tar 条目名: $entryName")
                }
                val out = File(dest, entryName)

                when {
                    entry.isSymbolicLink -> {
                        out.parentFile?.mkdirs()
                        runCatching {
                            if (out.exists()) out.delete()
                            Os.symlink(entry.linkName, out.absolutePath)
                        }.onFailure { e ->
                            VLog.warning(tag, "符号链接创建失败: $entryName -> ${entry.linkName}", e)
                        }
                    }

                    entry.isDirectory -> {
                        out.mkdirs()
                    }

                    else -> {
                        if (out.exists() && out.length() == entry.size) continue
                        out.parentFile?.mkdirs()
                        onProgress(entryName)
                        // 不用 InputStream.copyTo：它的第二参数是缓冲区大小（Int），
                        // 这里想复用同一个 ByteArray，只能自己读循环。
                        FileOutputStream(out).use { os ->
                            while (true) {
                                val n = tar.read(buffer)
                                if (n < 0) break
                                if (n > 0) os.write(buffer, 0, n)
                            }
                        }
                        // 解压出的 .so 必须可执行，否则后面 dlopen 会被 SELinux 拦掉
                        if (entryName.endsWith(".so")) {
                            runCatching { Os.chmod(out.absolutePath, 0b111_101_101) }
                        }
                    }
                }
            }
        }
    }
}
