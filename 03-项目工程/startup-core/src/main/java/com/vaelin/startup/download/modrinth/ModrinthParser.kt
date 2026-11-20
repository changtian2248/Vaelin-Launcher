/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现。用 download 包里的手写 JSON 解析器（纯 JVM 可单测），
 * 不引 org.json（android.jar stub）/ kotlinx.serialization（编译器插件）。
 * 字段对齐 Modrinth v2 官方文档与 ZL2 的 ModrinthSingleProject/ModrinthVersion。
 */

package com.vaelin.startup.download.modrinth

import com.vaelin.startup.download.JsonValue
import com.vaelin.startup.download.arrayField
import com.vaelin.startup.download.asArray
import com.vaelin.startup.download.asBoolean
import com.vaelin.startup.download.asLong
import com.vaelin.startup.download.asString
import com.vaelin.startup.download.field
import com.vaelin.startup.download.longField
import com.vaelin.startup.download.source.AddonDependency
import com.vaelin.startup.download.source.AddonFile
import com.vaelin.startup.download.source.AddonProject
import com.vaelin.startup.download.source.AddonSearchPage
import com.vaelin.startup.download.source.AddonVersion
import com.vaelin.startup.download.stringField
import java.time.Instant
import java.time.format.DateTimeParseException

/** Modrinth JSON → 统一模型的纯函数解析。全部为静态函数，无状态，便于单测。 */
object ModrinthParser {

    const val SOURCE_ID = "modrinth"

    // ---- search ----

    fun parseSearch(root: JsonValue): AddonSearchPage {
        val hits = root.arrayField("hits").orEmpty().mapNotNull { parseSearchHit(it) }
        return AddonSearchPage(
            items = hits,
            totalHits = root.field("total_hits").asLong()?.toInt() ?: hits.size,
            offset = root.field("offset").asLong()?.toInt() ?: 0,
            limit = root.field("limit").asLong()?.toInt() ?: hits.size,
        )
    }

    private fun parseSearchHit(obj: JsonValue): AddonProject? {
        val id = obj.stringField("project_id") ?: return null
        val slug = obj.stringField("slug") ?: return null
        val title = obj.stringField("title") ?: return null
        return AddonProject(
            sourceId = SOURCE_ID,
            projectId = id,
            slug = slug,
            name = title,
            author = obj.stringField("author").orEmpty(),
            summary = obj.stringField("description").orEmpty(),
            iconUrl = obj.stringField("icon_url"),
            kind = modrinthProjectTypeToKind(obj.stringField("project_type").orEmpty()),
            categories = obj.arrayField("categories").orEmpty().mapNotNull { it.asString() },
            gameVersions = obj.arrayField("versions").orEmpty().mapNotNull { it.asString() },
            loaders = emptyList(), // search hits 不带 loaders，详情接口才有
            downloads = obj.longField("downloads") ?: 0L,
            follows = obj.longField("follows") ?: 0L,
            updatedMillis = parseInstant(obj.stringField("date_modified")),
            clientSide = obj.stringField("client_side"),
            serverSide = obj.stringField("server_side"),
        )
    }

    // ---- project detail ----

    fun parseProject(obj: JsonValue): AddonProject {
        val id = obj.stringField("id")
            ?: throw IllegalArgumentException("Modrinth project 缺少 id 字段")
        return AddonProject(
            sourceId = SOURCE_ID,
            projectId = id,
            slug = obj.stringField("slug").orEmpty(),
            name = obj.stringField("title").orEmpty(),
            author = "", // v2 详情接口不带作者；搜索 hits 有。详情页作者位留空不虚构。
            summary = obj.stringField("description").orEmpty(),
            iconUrl = obj.stringField("icon_url"),
            kind = modrinthProjectTypeToKind(obj.stringField("project_type").orEmpty()),
            categories = obj.arrayField("categories").orEmpty().mapNotNull { it.asString() },
            gameVersions = obj.arrayField("game_versions").orEmpty().mapNotNull { it.asString() },
            loaders = obj.arrayField("loaders").orEmpty().mapNotNull { it.asString() },
            downloads = obj.longField("downloads") ?: 0L,
            follows = obj.longField("follows") ?: 0L,
            updatedMillis = parseInstant(obj.stringField("updated")),
            clientSide = obj.stringField("client_side"),
            serverSide = obj.stringField("server_side"),
        )
    }

    // ---- versions ----

    fun parseVersions(root: JsonValue): List<AddonVersion> =
        root.asArray().orEmpty().mapNotNull { parseVersion(it) }

    fun parseVersion(obj: JsonValue): AddonVersion? {
        val id = obj.stringField("id") ?: return null
        val projectId = obj.stringField("project_id") ?: return null
        return AddonVersion(
            sourceId = SOURCE_ID,
            versionId = id,
            projectId = projectId,
            name = obj.stringField("name").orEmpty(),
            versionNumber = obj.stringField("version_number").orEmpty(),
            releaseType = obj.stringField("version_type") ?: "release",
            gameVersions = obj.arrayField("game_versions").orEmpty().mapNotNull { it.asString() },
            loaders = obj.arrayField("loaders").orEmpty().mapNotNull { it.asString() },
            publishedMillis = parseInstant(obj.stringField("date_published")),
            downloads = obj.longField("downloads") ?: 0L,
            featured = obj.field("featured").asBoolean() ?: false,
            files = obj.arrayField("files").orEmpty().mapNotNull { parseFile(it) },
            dependencies = obj.arrayField("dependencies").orEmpty().mapNotNull { parseDependency(it) },
        )
    }

    private fun parseFile(obj: JsonValue): AddonFile? {
        val url = obj.stringField("url") ?: return null
        val filename = obj.stringField("filename") ?: return null
        val hashes = obj.field("hashes")
        return AddonFile(
            filename = filename,
            sizeBytes = obj.longField("size") ?: 0L,
            sha1 = hashes?.stringField("sha1")?.lowercase(),
            sha512 = hashes?.stringField("sha512")?.lowercase(),
            downloadUrl = url,
            primary = obj.field("primary").asBoolean() ?: false,
        )
    }

    private fun parseDependency(obj: JsonValue): AddonDependency? {
        // dependency_type 是必填语义（required/optional/embedded/incompatible），
        // 缺它说明文档结构变了，丢弃该项比瞎猜类型安全
        val type = obj.stringField("dependency_type") ?: return null
        return AddonDependency(
            projectId = obj.stringField("project_id"),
            versionId = obj.stringField("version_id"),
            fileName = obj.stringField("file_name"),
            type = type,
        )
    }

    // ---- 时间 ----

    /** ISO-8601（如 2024-05-01T12:34:56.789Z）→ epoch 毫秒。解析失败返回 0，不抛。 */
    private fun parseInstant(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return try {
            Instant.parse(raw).toEpochMilli()
        } catch (_: DateTimeParseException) {
            0L
        }
    }
}
