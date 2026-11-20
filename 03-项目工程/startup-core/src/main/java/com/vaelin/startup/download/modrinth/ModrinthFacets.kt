/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现。Modrinth search 接口的 facets 语法：
 * 外层数组每项之间是 AND，内层数组各项之间是 OR。
 * 例：[["project_type:mod"],["versions:1.20.1","versions:1.20"],["categories:fabric"]]
 * 语义：是 mod，且 MC 版本是 1.20.1 或 1.20，且加载器是 fabric。
 */

package com.vaelin.startup.download.modrinth

import com.vaelin.startup.download.source.AddonKind
import com.vaelin.startup.download.source.AddonSearchQuery
import com.vaelin.startup.download.source.AddonSort

/** 项目类型 → Modrinth project_type 字符串。 */
internal fun AddonKind.toModrinthProjectType(): String = when (this) {
    AddonKind.MOD -> "mod"
    AddonKind.SHADER -> "shader"
    AddonKind.RESOURCE_PACK -> "resourcepack"
    AddonKind.DATA_PACK -> "datapack"
    AddonKind.MODPACK -> "modpack"
    AddonKind.WORLD -> "worldgen" // Modrinth 无 world 类型，worldgen 最接近；世界下载后续轮再定
}

/** 反向映射：project_type → 项目类型。未知类型落到 MOD（保守，列表不被空项打断）。 */
internal fun modrinthProjectTypeToKind(type: String): AddonKind = when (type) {
    "shader" -> AddonKind.SHADER
    "resourcepack" -> AddonKind.RESOURCE_PACK
    "datapack" -> AddonKind.DATA_PACK
    "modpack" -> AddonKind.MODPACK
    else -> AddonKind.MOD
}

/** 排序 → Modrinth index 参数。 */
internal fun AddonSort.toModrinthIndex(): String = when (this) {
    AddonSort.RELEVANCE -> "relevance"
    AddonSort.DOWNLOADS -> "downloads"
    AddonSort.FOLLOWS -> "follows"
    AddonSort.NEWEST -> "newest"
    AddonSort.UPDATED -> "updated"
}

object ModrinthFacets {

    /**
     * 生成 facets 查询参数值（不含 URL 编码，编码由调用方做）。
     * 无任何过滤时返回 null（不传 facets 参数，Modrinth 默认全类型）。
     */
    fun toFacetParam(query: AddonSearchQuery): String? {
        val andGroups = mutableListOf<List<String>>()

        query.kind?.let { andGroups += listOf("project_type:${it.toModrinthProjectType()}") }
        query.gameVersion?.takeIf { it.isNotBlank() }?.let { version ->
            andGroups += listOf("versions:${version.trim()}")
        }
        query.loader?.takeIf { it.isNotBlank() }?.let { loader ->
            andGroups += listOf("categories:${loader.trim().lowercase()}")
        }

        if (andGroups.isEmpty()) return null
        return andGroups.joinToString(prefix = "[", postfix = "]", separator = ",") { group ->
            group.joinToString(prefix = "[", postfix = "]", separator = ",") { "\"$it\"" }
        }
    }
}
