/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现。CurseForge 整合包 zip（manifest.json）解析。
 * 字段对齐 CurseForge manifest 规范与 ZL2 CurseForgeManifest。
 */

package com.vaelin.startup.download.modpack

import com.vaelin.startup.download.Json
import com.vaelin.startup.download.JsonValue
import com.vaelin.startup.download.arrayField
import com.vaelin.startup.download.field
import com.vaelin.startup.download.stringField
import java.net.URLEncoder

object CurseForgePackParser {

    /**
     * 解析 manifest.json 文本。
     *
     * 语义（对齐 ZL2 CurseForgePack.readCurseForge）：
     * - manifestType 必须是 "minecraftModpack"（调用方已按此识别，这里再验一次）；
     * - minecraft.modLoaders 取 primary=true 的项，id 形如 "forge-47.2.0"，按第一个 '-' 拆
     *   加载器与版本；没有主加载器取第一个；
     * - files[]：显式给了 fileName 的拼 CDN 直链（无需 API key）；既无 url 又无 fileName 的
     *   条目无法离线解析地址，跳过并留给「CF 源解析」后续增强（需 API key，见阶段4）；
     *   required=false 的保留 required 标记，安装层失败不判死。
     */
    fun parse(text: String): ModpackInfo {
        val root = try {
            Json.parse(text)
        } catch (e: Exception) {
            throw ModpackParseException("manifest.json 解析失败: ${e.message}", e)
        }
        if (root.stringField("manifestType") != "minecraftModpack") {
            throw ModpackParseException("不是 CurseForge 整合包清单（manifestType=${root.stringField("manifestType")}）")
        }

        val minecraft = root.field("minecraft")
            ?: throw ModpackParseException("CurseForge 清单缺少 minecraft 段")
        val gameVersion = minecraft.stringField("version")
            ?: throw ModpackParseException("CurseForge 清单缺少 minecraft.version")

        var loader: String? = null
        var loaderVersion: String? = null
        val modLoaders = minecraft.arrayField("modLoaders").orEmpty()
        val primaryLoader = modLoaders.firstOrNull { it.field("primary").let { p ->
            p is JsonValue.Bool && p.value
        } } ?: modLoaders.firstOrNull()
        primaryLoader?.stringField("id")?.let { id ->
            val dash = id.indexOf('-')
            if (dash > 0) {
                loader = id.substring(0, dash).lowercase()
                loaderVersion = id.substring(dash + 1)
            }
        }

        val files = root.arrayField("files").orEmpty().mapNotNull { parseFile(it) }

        return ModpackInfo(
            format = ModpackFormat.CURSEFORGE_ZIP,
            name = root.stringField("name").orEmpty(),
            version = root.stringField("version").orEmpty(),
            summary = "作者：${root.stringField("author").orEmpty()}",
            gameVersion = gameVersion,
            loader = loader,
            loaderVersion = loaderVersion,
            files = files,
        )
    }

    private fun parseFile(obj: JsonValue): ModpackFile? {
        val fileId = obj.field("fileID").let {
            (it as? JsonValue.Num)?.raw?.toIntOrNull()
        } ?: return null
        val fileName = obj.stringField("fileName")
        val url = obj.stringField("url")
        val required = obj.field("required").let {
            (it as? JsonValue.Bool)?.value ?: true
        }
        val downloadUrl = when {
            !url.isNullOrBlank() -> url
            !fileName.isNullOrBlank() -> curseForgeCdnUrl(fileId, fileName)
            else -> return null // 离线解析不了地址：跳过（阶段4接 CF 源后按 projectID/fileID 解析）
        }
        val targetName = fileName ?: fileNameFromUrl(url!!)
        return ModpackFile(
            relativePath = "mods/$targetName",
            urls = listOf(downloadUrl),
            sha1 = null, // CF 清单不带哈希；下载后只能靠大小/完整性弱校验
            required = required,
        )
    }

    /** CF CDN 直链（ZL2 curseForgeCDNUrl 同款）：fileId 千分位拆两段路径。 */
    fun curseForgeCdnUrl(fileId: Int, fileName: String): String {
        val encoded = URLEncoder.encode(fileName, Charsets.UTF_8.name()).replace("+", "%20")
        return "https://edge.forgecdn.net/files/${fileId / 1000}/${fileId % 1000}/$encoded"
    }

    private fun fileNameFromUrl(url: String): String =
        url.substringAfterLast('/').substringBefore('?')
}
