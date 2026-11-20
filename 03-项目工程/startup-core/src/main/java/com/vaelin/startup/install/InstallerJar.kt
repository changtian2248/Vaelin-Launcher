/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * T1.5.5 —— Forge installer jar 的下载校验与 MANIFEST Main-Class 读取
 * （纯 JVM：HttpTransport 注入，可单测）。
 *
 * ZL2 对照点：ZL2 的 installer 落盘在 tempGameDir 的 .temp/forge_like_installer.jar
 * （Download.ForgeLike.kt:34），校验交给 downloadFileFromSources 的多源重试；
 * Vaelin 复用自家 FileDownloader（sha1 / 断点续传 / 重试都在里面），
 * 多源候选由本层逐个尝试，失败原因全部带出。
 */

package com.vaelin.startup.install

import com.vaelin.startup.download.DownloadRequest
import com.vaelin.startup.download.DownloadResult
import com.vaelin.startup.download.FileDownloader
import com.vaelin.startup.download.HttpTransport
import com.vaelin.startup.util.VLog
import java.io.File
import java.util.jar.Attributes
import java.util.jar.JarFile

private const val TAG = "InstallerJar"

object InstallerJar {

    /**
     * 下载 installer jar 到 [target]。
     *
     * 校验信息按三级解析（禁止 FileDownloader 的「无校验静默成功」路径）：
     * 1. .sha1 侧车（候选顺序与 installer 同：BMCLAPI 镜像优先、官方兜底）→ sha1 强校验；
     * 2. HEAD 拿 Content-Length（BMCLAPI 对 HEAD 回 302 且不带长度，会自动跳过，
     *    官方 maven 返回真实长度）→ 大小弱校验，原因显式声明在异常/日志里；
     * 3. 都拿不到 → 显式抛错，不发起无校验的下载。
     */
    fun download(
        transport: HttpTransport,
        installerUrls: List<String>,
        sha1Urls: List<String>,
        target: File,
    ): File {
        require(installerUrls.isNotEmpty()) { "installerUrls 不能为空" }
        val expectedSha1 = sha1Urls.asSequence().mapNotNull { url ->
            fetchSha1Sidecar(transport, url)
        }.firstOrNull()
        val expectedSize = if (expectedSha1 == null) {
            installerUrls.asSequence().mapNotNull { contentLengthOf(transport, it) }.firstOrNull() ?: -1L
        } else {
            -1L
        }
        if (expectedSha1 == null && expectedSize <= 0L) {
            throw ForgeInstallException(
                "无法确定 installer jar 的校验信息（sha1 侧车与 Content-Length 都拿不到）: ${installerUrls.first()}",
            )
        }
        val verification = when {
            expectedSha1 != null -> "sha1=$expectedSha1"
            else -> "size=$expectedSize（弱校验：镜像未提供可用的 sha1 侧车）"
        }

        val failures = mutableListOf<String>()
        for (url in installerUrls) {
            val result = FileDownloader(transport).download(
                DownloadRequest(
                    url = url,
                    target = target,
                    expectedSha1 = expectedSha1,
                    expectedSize = expectedSize,
                ),
            )
            when (result) {
                is DownloadResult.Success -> {
                    if (expectedSha1 == null) {
                        // 弱校验命中：不假装验过，把事实写进日志供排查
                        VLog.info(TAG, "installer jar 以大小校验通过（$verification）: $url")
                    }
                    return result.file
                }
                is DownloadResult.Failure -> failures += "$url → ${result.reason}"
            }
        }
        throw ForgeInstallException(
            "installer jar 下载失败（$verification，已尝试 ${installerUrls.size} 个源）:\n" +
                failures.joinToString("\n"),
        )
    }

    /**
     * 读取 installer jar 的 MANIFEST.MF Main-Class。
     *
     * Forge installer 的 Main-Class 是 net.minecraftforge.installer.SimpleInstaller
     * （1.13+ 的 1.5.x 安装器与新 2.0.x 安装器同名）；读不到显式抛错——拼不出
     * `java -cp installer.jar <Main> --installClient` 就不该往下走。
     */
    fun readInstallerMainClass(jar: File): String {
        if (!jar.isFile) {
            throw ForgeInstallException("installer jar 不存在: ${jar.absolutePath}")
        }
        try {
            JarFile(jar).use { installer ->
                val manifest = installer.manifest
                    ?: throw ForgeInstallException(
                        "installer jar 没有 MANIFEST.MF，无法确定 Main-Class: ${jar.absolutePath}",
                    )
                return manifest.mainAttributes.getValue(Attributes.Name.MAIN_CLASS)
                    ?.trim()
                    ?.trimEnd('/')
                    ?.takeIf { it.isNotBlank() }
                    ?: throw ForgeInstallException(
                        "installer jar 的 MANIFEST.MF 缺少 Main-Class 属性: ${jar.absolutePath}",
                    )
            }
        } catch (e: ForgeInstallException) {
            throw e
        } catch (e: Exception) {
            throw ForgeInstallException(
                "读取 installer jar MANIFEST 失败: ${jar.absolutePath}（${e.message}）",
                e,
            )
        }
    }

    /**
     * .sha1 侧车文本 → 40 位十六进制哈希；无效返回 null。
     * 侧车常见两种格式：纯哈希、或 "<hash>  <文件名>"（maven 标准格式），取首段即可。
     */
    internal fun parseSha1Sidecar(text: String): String? {
        val candidate = text.trim().split(Regex("\\s+")).firstOrNull()?.trim().orEmpty()
        val isHex40 = candidate.length == 40 && candidate.all { c ->
            c.isDigit() || c in 'a'..'f' || c in 'A'..'F'
        }
        return candidate.takeIf { isHex40 }?.lowercase()
    }

    /** 拉 .sha1 侧车文本；网络/解析失败一律返回 null（交给下一候选或大小校验）。 */
    private fun fetchSha1Sidecar(transport: HttpTransport, url: String): String? = try {
        transport.open(url, "GET").use { resp ->
            val body = resp.body
            if (!resp.isSuccess || body == null) {
                null
            } else {
                parseSha1Sidecar(body.bufferedReader(Charsets.UTF_8).use { it.readText() })
            }
        }
    } catch (e: Exception) {
        null
    }

    /** HEAD 拿 Content-Length；仅认 2xx 且长度为正，其余视为「拿不到」（返回 null）。 */
    private fun contentLengthOf(transport: HttpTransport, url: String): Long? = try {
        transport.open(url, "HEAD").use { resp ->
            if (resp.isSuccess && resp.contentLength > 0L) resp.contentLength else null
        }
    } catch (e: Exception) {
        null
    }
}
