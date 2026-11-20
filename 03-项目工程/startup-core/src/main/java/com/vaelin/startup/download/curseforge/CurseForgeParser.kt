/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现。CurseForge v1 JSON → 统一模型。全部静态纯函数。
 * CF 的响应都包一层 {"data": ...}（search 另有 pagination），这里负责剥壳。
 */

package com.vaelin.startup.download.curseforge

import com.vaelin.startup.download.JsonValue
import com.vaelin.startup.download.arrayField
import com.vaelin.startup.download.asArray
import com.vaelin.startup.download.asString
import com.vaelin.startup.download.field
import com.vaelin.startup.download.longField
import com.vaelin.startup.download.source.AddonFile
import com.vaelin.startup.download.source.AddonProject
import com.vaelin.startup.download.source.AddonSearchPage
import com.vaelin.startup.download.source.AddonVersion
import com.vaelin.startup.download.stringField
import java.time.Instant
import java.time.format.DateTimeParseException

object CurseForgeParser {

    const val SOURCE_ID = "curseforge"

    // ---- search ----

    /** 解析 {"data": [...], "pagination": {...}}。 */
    fun parseSearch(root: JsonValue): AddonSearchPage {
        val items = root.arrayField("data").orEmpty().mapNotNull { parseProjectData(it) }
        val pagination = root.field("pagination")
        val total = pagination?.longField("totalCount")?.toInt() ?: items.size
        val index = pagination?.field("index")?.let { (it as? JsonValue.Num)?.raw?.toIntOrNull() } ?: 0
        val pageSize = pagination?.field("pageSize")?.let { (it as? JsonValue.Num)?.raw?.toIntOrNull() } ?: items.size
        return AddonSearchPage(
            items = items,
            totalHits = total,
            offset = index,
            limit = pageSize,
        )
    }

    // ---- project detail（也是 search 条目用的同一形状） ----

    fun parseProject(root: JsonValue): AddonProject {
        val data = root.field("data") ?: root
        return parseProjectData(data) ?: throw IllegalArgumentException("CurseForge 项目缺少 id/name")
    }

    private fun parseProjectData(obj: JsonValue): AddonProject? {
        val id = obj.field("id").let { (it as? JsonValue.Num)?.raw?.toIntOrNull() } ?: return null
        val name = obj.stringField("name") ?: return null
        val authors = obj.arrayField("authors").orEmpty()
            .mapNotNull { it.stringField("name") }
        return AddonProject(
            sourceId = SOURCE_ID,
            projectId = id.toString(),
            slug = obj.stringField("slug").orEmpty(),
            name = name,
            author = authors.firstOrNull().orEmpty(),
            summary = obj.stringField("summary").orEmpty(),
            iconUrl = obj.field("logo")?.stringField("url"),
            kind = CurseForgeClassIds.toKind(
                obj.field("classId")?.let { (it as? JsonValue.Num)?.raw?.toIntOrNull() },
            ),
            categories = obj.arrayField("categories").orEmpty()
                .mapNotNull { it.stringField("name") },
            gameVersions = emptyList(), // search/detail 不直接给游戏版本列表，版本接口才有
            loaders = emptyList(),
            downloads = obj.longField("downloadCount") ?: 0L,
            follows = 0L, // CF 无 follows 概念
            updatedMillis = parseInstant(obj.stringField("dateModified")),
            clientSide = null,
            serverSide = null,
        )
    }

    // ---- files（版本） ----

    fun parseFiles(root: JsonValue, projectId: String): List<AddonVersion> =
        root.arrayField("data").orEmpty().mapNotNull { parseFile(it, projectId) }

    private fun parseFile(obj: JsonValue, projectId: String): AddonVersion? {
        val id = obj.field("id").let { (it as? JsonValue.Num)?.raw?.toIntOrNull() } ?: return null
        val fileName = obj.stringField("fileName") ?: return null

        val gameVersionsRaw = obj.arrayField("gameVersions").orEmpty()
            .mapNotNull { it.asString() }
        val gameVersions = gameVersionsRaw.filterNot { CurseForgeLoaderTypes.isLoaderName(it) }
        val loaders = gameVersionsRaw.filter { CurseForgeLoaderTypes.isLoaderName(it) }
            .map { CurseForgeLoaderTypes.normalizeLoaderName(it) }
            .distinct()

        val sha1 = obj.arrayField("hashes").orEmpty()
            .firstOrNull { it.field("algo")?.let { a -> (a as? JsonValue.Num)?.raw == "1" } == true }
            ?.stringField("value")?.lowercase()

        val releaseCode = obj.field("releaseType")?.let { (it as? JsonValue.Num)?.raw?.toIntOrNull() } ?: 1

        val file = AddonFile(
            filename = fileName,
            sizeBytes = obj.longField("fileLength") ?: 0L,
            sha1 = sha1,
            sha512 = null, // v1 API 不提供 sha512
            // CF 的 downloadUrl 可能为 null（禁止第三方下载的项目）：留空串，
            // 源适配层发现空串时改走 /download-url 端点解析（见 CurseForgeMcimSource）
            downloadUrl = obj.stringField("downloadUrl").orEmpty(),
            primary = false, // files 接口不带 mainFileId；单文件版本由 primaryFile() 回退首文件
        )

        return AddonVersion(
            sourceId = SOURCE_ID,
            versionId = id.toString(),
            projectId = projectId,
            name = obj.stringField("displayName").orEmpty(),
            versionNumber = fileName,
            releaseType = CurseForgeReleaseTypes.fromCode(releaseCode),
            gameVersions = gameVersions,
            loaders = loaders,
            publishedMillis = parseInstant(obj.stringField("fileDate")),
            downloads = obj.longField("downloadCount") ?: 0L,
            featured = false,
            files = listOf(file),
            dependencies = emptyList(), // v1 files 的 dependencies 是 fileId 引用，解析留给后续
        )
    }

    /** download-url 端点响应是 {"data": "https://..."}。 */
    fun parseDownloadUrl(root: JsonValue): String? = root.stringField("data")

    // ---- 时间 ----

    private fun parseInstant(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return try {
            Instant.parse(raw).toEpochMilli()
        } catch (_: DateTimeParseException) {
            0L
        }
    }
}
