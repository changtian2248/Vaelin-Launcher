/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.vaelin.startup.download

import java.io.ByteArrayOutputStream
import java.io.IOException

/** Mojang 的公开端点。集中放一处，换镜像/代理时只改这里。 */
object MojangEndpoints {
    const val VERSION_MANIFEST = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"
    const val RESOURCES_BASE = "https://resources.download.minecraft.net/"
    const val LIBRARIES_BASE = "https://libraries.minecraft.net/"
}

/**
 * 版本元数据的拉取入口：版本清单、单版本 version json。
 *
 * 只负责「取到文本并解析」，不落盘也不重试——重试与落盘是下载器与调用方的事，
 * 混在一起会让「清单拉失败」和「某个 jar 拉失败」变成同一种错误。
 */
class MojangVersionSource(
    private val transport: HttpTransport,
    private val manifestUrl: String = MojangEndpoints.VERSION_MANIFEST,
    private val maxDocumentBytes: Long = DEFAULT_MAX_DOCUMENT_BYTES,
) {

    fun fetchManifest(): VersionManifest = VersionManifest.parse(readText(manifestUrl))

    fun fetchVersionJson(url: String): VersionJson = VersionJson.parse(readText(url))

    /** 读取一个文本文档（清单 / version json / assets index）。 */
    fun readText(url: String): String {
        transport.open(url, "GET").use { response ->
            if (!response.isSuccess) {
                throw IOException("HTTP ${response.code}: $url")
            }
            val body = response.body ?: throw IOException("响应没有正文: $url")

            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            while (true) {
                val read = body.read(buffer)
                if (read < 0) break
                total += read
                // 挡住「服务端返回了 2GB 的 html 错误页」这种事故，而不是把内存打爆
                if (total > maxDocumentBytes) {
                    throw IOException("文档超过 $maxDocumentBytes 字节上限: $url")
                }
                output.write(buffer, 0, read)
            }
            return output.toString("UTF-8")
        }
    }

    companion object {
        const val DEFAULT_MAX_DOCUMENT_BYTES = 32L * 1024 * 1024
    }
}
