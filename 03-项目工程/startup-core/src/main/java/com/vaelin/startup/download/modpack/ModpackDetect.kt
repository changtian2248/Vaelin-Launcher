/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现。格式识别：按 zip 根目录的特征条目判定。
 * 识别依据（对齐 ZL2 SimplePackParser 与各格式官方规范）：
 *   .mrpack        → 根有 modrinth.index.json
 *   CurseForge zip → 根有 manifest.json 且 manifestType == "minecraftModpack"
 *   MCBBS v2       → 规范未确认前不识别（识别到了也拒绝，见 McbbsV2 placeholder）
 */

package com.vaelin.startup.download.modpack

import com.vaelin.startup.download.Json
import com.vaelin.startup.download.stringField
import java.io.File
import java.util.zip.ZipFile

object ModpackDetect {

    private const val MRPACK_INDEX = "modrinth.index.json"
    private const val CF_MANIFEST = "manifest.json"

    /**
     * 判定 zip 的整合包格式。
     * 只读 zip 目录（ Central Directory ），不解压任何实体。
     * 无法识别返回 [ModpackFormat.UNKNOWN]；识别到 MCBBS v2 特征会显式抛
     * [ModpackParseException]（规范未定，拒绝比瞎猜安全）。
     */
    fun detect(zipFile: File): ModpackFormat {
        val rootEntries = rootEntryNames(zipFile)
        when {
            MRPACK_INDEX in rootEntries -> return ModpackFormat.MRPACK
            CF_MANIFEST in rootEntries -> {
                val type = readManifestType(zipFile)
                // manifest.json 也可能是别的游戏的包：看 manifestType 再定
                return if (type == "minecraftModpack") ModpackFormat.CURSEFORGE_ZIP else ModpackFormat.UNKNOWN
            }
        }
        return ModpackFormat.UNKNOWN
    }

    /** 列出 zip 根目录下的条目名（不含目录自身，不含子目录内容）。 */
    fun rootEntryNames(zipFile: File): Set<String> = ZipFile(zipFile).use { zip ->
        zip.entries().asSequence()
            .map { it.name }
            .filter { !it.endsWith("/") }
            .filter { !it.removeSuffix("/").contains("/") } // 只留根级文件
            .toSet()
    }

    /** 列出指定前缀下的全部实体相对路径（剥离前缀）。防 zip-slip：剔除 .. 与绝对路径。 */
    fun listPrefixedEntries(zipFile: File, prefixes: List<String>): List<String> =
        ZipFile(zipFile).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory }
                .mapNotNull { entry ->
                    val prefix = prefixes.firstOrNull { entry.name.startsWith("$it/") }
                        ?: return@mapNotNull null
                    entry.name.removePrefix("$prefix/")
                }
                .filter { it.isNotEmpty() && isSafeRelativePath(it) }
                .toList()
        }

    /** 相对路径安全判定：拒绝绝对路径与 .. 穿越（zip-slip 防线）。 */
    fun isSafeRelativePath(path: String): Boolean {
        if (path.startsWith("/") || path.startsWith("\\")) return false
        val normalized = path.replace('\\', '/')
        return normalized.split('/').none { it == ".." }
    }

    private fun readManifestType(zipFile: File): String? = runCatching {
        ZipFile(zipFile).use { zip ->
            val entry = zip.getEntry(CF_MANIFEST) ?: return null
            zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { reader ->
                Json.parse(reader.readText()).stringField("manifestType")
            }
        }
    }.getOrNull()
}
