/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现。整合包安装器：格式识别 → 清单解析 → 按清单下载 →
 * overrides 落盘 → 结构化结果。同步阻塞契约（与 FileDownloader 同），
 * 调用方自行起线程。
 */

package com.vaelin.startup.download.modpack

import com.vaelin.startup.download.BatchDownloader
import com.vaelin.startup.download.DownloadRequest
import com.vaelin.startup.download.DownloadFailure
import com.vaelin.startup.download.FileDownloader
import com.vaelin.startup.download.HttpTransport
import com.vaelin.startup.download.Json
import com.vaelin.startup.download.stringField
import com.vaelin.startup.download.source.SourceGate
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.zip.ZipFile

/** 安装结果。failedRequired 非空 = 实质失败（调用方/UI 必须显式呈现，不许静默当成功）。 */
data class ModpackInstallResult(
    val info: ModpackInfo,
    val targetDir: File,
    val succeeded: Int,
    val failedRequired: List<DownloadFailure>,
    val failedOptional: List<DownloadFailure>,
    val skippedAlreadyPresent: Int,
    val overridesExtracted: Int,
) {
    val usable: Boolean get() = failedRequired.isEmpty()
}

class ModpackInstaller(
    private val transport: HttpTransport,
    private val gate: SourceGate,
    private val parallelism: Int = BatchDownloader.DEFAULT_PARALLELISM,
) {

    /** 识别格式并解析清单 + overrides 清单（不落盘任何文件）。 */
    fun inspect(zipFile: File): InspectedPack {
        if (!zipFile.isFile) throw ModpackParseException("整合包文件不存在: ${zipFile.absolutePath}")
        val format = ModpackDetect.detect(zipFile)
        if (format == ModpackFormat.UNKNOWN) {
            throw ModpackParseException("无法识别的整合包格式（既不是 .mrpack 也不是 CurseForge zip）")
        }
        if (format == ModpackFormat.MCBBS_V2) {
            // 识别特征已命中但解析规范未落地：显式拒绝（见交接文档，规范确认后实现）
            throw ModpackParseException("MCBBS v2 格式解析尚未落地（规范待确认）")
        }

        val manifestEntry = when (format) {
            ModpackFormat.MRPACK -> "modrinth.index.json"
            ModpackFormat.CURSEFORGE_ZIP -> "manifest.json"
        }
        val manifestText = ZipFile(zipFile).use { zip ->
            val entry = zip.getEntry(manifestEntry)
                ?: throw ModpackParseException("zip 内找不到 $manifestEntry")
            zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }

        val info = when (format) {
            ModpackFormat.MRPACK -> MrpackParser.parse(manifestText)
            ModpackFormat.CURSEFORGE_ZIP -> CurseForgePackParser.parse(manifestText)
        }

        val overridePrefixes = when (format) {
            ModpackFormat.MRPACK -> listOf("overrides", "client-overrides")
            ModpackFormat.CURSEFORGE_ZIP -> listOf(readCfOverridesDir(zipFile))
        }
        val overrides = ModpackDetect.listPrefixedEntries(zipFile, overridePrefixes)
        return InspectedPack(format = format, info = info, overrides = overrides)
    }

    /**
     * 安装整合包到 [targetDir]（实例根目录语义，由调用方决定具体位置）。
     *
     * 流程：过地区闸（文件分发动作）→ 并发下载清单文件 → 解 zip 内 overrides 实体落盘。
     * 单个文件失败不中断；required 失败收集到 [ModpackInstallResult.failedRequired]。
     */
    fun install(
        inspected: InspectedPack,
        zipFile: File,
        targetDir: File,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null,
    ): ModpackInstallResult {
        gate.assertDownloadAllowed()

        targetDir.mkdirs()
        val info = inspected.info
        val total = info.files.size

        // 请求与清单文件成对构造，失败分组直接查 required 标记，不靠路径反推
        val pairs = info.files.map { file ->
            file to DownloadRequest(
                url = file.urls.first(),
                target = File(targetDir, file.relativePath),
                expectedSha1 = file.sha1,
                resume = false,
            )
        }
        val requests = pairs.map { (_, request) -> request }

        val batch = runBlocking {
            BatchDownloader(FileDownloader(transport), parallelism).downloadAll(requests) { p ->
                onProgress?.invoke(p.completed, total)
            }
        }

        val requestToFile = pairs.associate { (file, request) -> request to file }
        val (failedRequired, failedOptional) = batch.failed.partition { failure ->
            requestToFile[failure.request]?.required == true
        }

        val overridesExtracted = extractOverrides(zipFile, inspected, targetDir)

        return ModpackInstallResult(
            info = info,
            targetDir = targetDir,
            succeeded = batch.succeeded.size,
            failedRequired = failedRequired,
            failedOptional = failedOptional,
            skippedAlreadyPresent = batch.skipped,
            overridesExtracted = overridesExtracted,
        )
    }

    // ---- overrides 解包 ----

    /**
     * 把 zip 内 overrides 前缀下的实体落到 targetDir（剥前缀）。
     * 逐实体做 canonical 路径校验（zip-slip 第二道防线，解析层已过滤一次）。
     */
    private fun extractOverrides(zipFile: File, inspected: InspectedPack, targetDir: File): Int {
        val prefixes = when (inspected.format) {
            ModpackFormat.MRPACK -> listOf("overrides", "client-overrides")
            ModpackFormat.CURSEFORGE_ZIP -> listOf(readCfOverridesDir(zipFile))
            else -> return 0
        }
        var count = 0
        ZipFile(zipFile).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory }
                .forEach { entry ->
                    val prefix = prefixes.firstOrNull { entry.name.startsWith("$it/") } ?: return@forEach
                    val relative = entry.name.removePrefix("$prefix/")
                    if (relative.isEmpty() || !ModpackDetect.isSafeRelativePath(relative)) return@forEach

                    val target = File(targetDir, relative)
                    val canonicalDir = target.absoluteFile.canonicalFile.parentFile
                        ?: throw ModpackInstallException("overrides 目标路径非法: $relative")
                    if (!canonicalDir.absolutePath.startsWith(targetDir.canonicalFile.absolutePath)) {
                        throw ModpackInstallException("overrides 路径越界（zip-slip 拦截）: ${entry.name}")
                    }
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    count++
                }
        }
        return count
    }

    private fun readCfOverridesDir(zipFile: File): String = runCatching {
        ZipFile(zipFile).use { zip ->
            val entry = zip.getEntry("manifest.json") ?: return@runCatching "overrides"
            zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { reader ->
                Json.parse(reader.readText()).stringField("overrides") ?: "overrides"
            }
        }
    }.getOrDefault("overrides")
}
