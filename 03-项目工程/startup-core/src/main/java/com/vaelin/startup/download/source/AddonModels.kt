/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现。源框架的统一领域模型：规格书第 38 条要求资源源可插拔
 * （Modrinth / CurseForge / XyeBBS 等），新源接入时必须映射到这套模型，
 * UI 与下载编排只认这套模型，不认任何具体平台的字段名。
 */

package com.vaelin.startup.download.source

/** 资源大类。首版落地 MOD / SHADER / RESOURCE_PACK，WORLD 预留（任务范围 A）。 */
enum class AddonKind {
    MOD,
    SHADER,
    RESOURCE_PACK,
    DATA_PACK,
    MODPACK,
    WORLD,
}

/** 排序方式。各源自行映射到自己的排序字段名。 */
enum class AddonSort {
    RELEVANCE,
    DOWNLOADS,
    FOLLOWS,
    NEWEST,
    UPDATED,
}

/** 一次搜索请求。所有字段可空——空表示不按该维度过滤。 */
data class AddonSearchQuery(
    val text: String = "",
    val kind: AddonKind? = null,
    val gameVersion: String? = null,
    /** 加载器短名（modrinth 命名：fabric / forge / quilt / neoforge；资源包传 null） */
    val loader: String? = null,
    val sort: AddonSort = AddonSort.RELEVANCE,
    val offset: Int = 0,
    val limit: Int = 20,
)

/**
 * 搜索结果里的一个项目。
 *
 * 刻意只保留「列表页 + 详情页首屏」会显示的字段；长描述（Modrinth body 动辄
 * 上百 KB）不进这个模型，详情页按需单独拉取，避免列表滚动时 OOM。
 */
data class AddonProject(
    /** 源 id，对应 ContentSource.sourceId，如 "modrinth" */
    val sourceId: String,
    /** 源内唯一 id（Modrinth base62 id；CurseForge 数字 id） */
    val projectId: String,
    val slug: String,
    val name: String,
    /** 作者显示名；详情接口不提供的源填空串，不在 UI 上虚构 */
    val author: String,
    val summary: String,
    val iconUrl: String?,
    val kind: AddonKind,
    val categories: List<String>,
    /** 搜索 hits 给出的兼容游戏版本列表（可能不全，仅用于列表展示与初步过滤） */
    val gameVersions: List<String>,
    /** 加载器短名列表；搜索接口不提供的源可为空 */
    val loaders: List<String>,
    val downloads: Long,
    val follows: Long,
    val updatedMillis: Long,
    /** Modrinth 风格的三态："required" / "optional" / "unsupported"；源不提供时为 null */
    val clientSide: String?,
    val serverSide: String?,
)

/** 一页搜索结果。 */
data class AddonSearchPage(
    val items: List<AddonProject>,
    val totalHits: Int,
    val offset: Int,
    val limit: Int,
) {
    /** 按当前 offset/limit 是否还有下一页 */
    fun hasMore(): Boolean = offset + items.size < totalHits
}

/** 一个可下载文件。sha1/sha512 至少给一个，缺失时下载退化为弱校验（由调用方决定）。 */
data class AddonFile(
    val filename: String,
    val sizeBytes: Long,
    val sha1: String?,
    val sha512: String?,
    /** 直接的下载地址（CDN 直链或经重定向的最终地址，取决于源） */
    val downloadUrl: String,
    /** 该版本的主文件（一个版本通常只有一个主文件） */
    val primary: Boolean,
)

/** 版本依赖。type 取值："required" / "optional" / "embedded" / "incompatible"。 */
data class AddonDependency(
    val projectId: String?,
    val versionId: String?,
    val fileName: String?,
    val type: String,
)

/**
 * 一个项目版本（Modrinth version / CurseForge file 的统称）。
 *
 * ZL2 的 ModrinthVersion 有个教训：changelog 字段被某些作者写到极大，导致启动器
 * OOM，所以那里标了「不应该被代码使用」。这里干脆不进模型，变更日志将来按需
 * 单独接口拉取。
 */
data class AddonVersion(
    val sourceId: String,
    val versionId: String,
    val projectId: String,
    val name: String,
    val versionNumber: String,
    /** "release" / "beta" / "alpha" */
    val releaseType: String,
    val gameVersions: List<String>,
    val loaders: List<String>,
    val publishedMillis: Long,
    val downloads: Long,
    val featured: Boolean,
    val files: List<AddonFile>,
    val dependencies: List<AddonDependency>,
) {
    /** 主文件；源不给 primary 标记时退回第一个文件 */
    fun primaryFile(): AddonFile? = files.firstOrNull { it.primary } ?: files.firstOrNull()
}
