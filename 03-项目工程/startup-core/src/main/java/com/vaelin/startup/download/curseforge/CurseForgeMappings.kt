/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现。CurseForge v1 API 的常量映射。
 * 字段码对齐 ZL2（CurseForgeClassID / CurseForgeModLoader / PlatformSortField
 * / PlatformReleaseType / CurseForgeFile.Hash.Algo）与 CurseForge 官方 v1 文档。
 */

package com.vaelin.startup.download.curseforge

import com.vaelin.startup.download.source.AddonKind
import com.vaelin.startup.download.source.AddonSort

/** CF 内容类别 classId。只镜像 Mod 类目（MCIM 不覆盖非 Mod 类目之外的游戏内容）。 */
object CurseForgeClassIds {
    const val MOD = 6
    const val MODPACK = 4471
    const val RESOURCE_PACK = 12
    const val SHADERS = 6552

    fun fromKind(kind: AddonKind): Int? = when (kind) {
        AddonKind.MOD -> MOD
        AddonKind.MODPACK -> MODPACK
        AddonKind.RESOURCE_PACK -> RESOURCE_PACK
        AddonKind.SHADER -> SHADERS
        else -> null
    }

    fun toKind(classId: Int?): AddonKind = when (classId) {
        MODPACK -> AddonKind.MODPACK
        RESOURCE_PACK -> AddonKind.RESOURCE_PACK
        SHADERS -> AddonKind.SHADER
        else -> AddonKind.MOD
    }
}

/** CF 排序字段码（v1 search sortField）。 */
object CurseForgeSortFields {
    fun fromSort(sort: AddonSort): Int = when (sort) {
        AddonSort.RELEVANCE -> 1
        AddonSort.FOLLOWS -> 2
        AddonSort.UPDATED -> 3
        AddonSort.DOWNLOADS -> 6
        AddonSort.NEWEST -> 11
    }
}

/** CF modLoaderType 码（v1 search / files 过滤）。 */
object CurseForgeLoaderTypes {
    const val FORGE = 1
    const val FABRIC = 4
    const val QUILT = 5
    const val NEOFORGE = 6

    fun fromLoaderName(loader: String): Int? = when (loader.lowercase()) {
        "forge" -> FORGE
        "fabric" -> FABRIC
        "quilt" -> QUILT
        "neoforge" -> NEOFORGE
        else -> null
    }

    /** 文件 gameVersions 数组里与加载器同名的条目（显示名口径，忽略大小写） */
    private val LOADER_DISPLAY_NAMES = setOf("forge", "fabric", "quilt", "neoforge")

    fun isLoaderName(entry: String): Boolean = entry.lowercase() in LOADER_DISPLAY_NAMES

    fun normalizeLoaderName(entry: String): String = entry.lowercase()
}

/** CF releaseType 码 → 统一字符串。 */
object CurseForgeReleaseTypes {
    fun fromCode(code: Int): String = when (code) {
        1 -> "release"
        2 -> "beta"
        else -> "alpha"
    }
}

/** CF 哈希算法码：1=SHA1、2=MD5（v1 API 只提供这两种）。 */
object CurseForgeHashAlgos {
    const val SHA1 = 1
    const val MD5 = 2
}

/** Minecraft 在 CF 的 gameId。 */
const val MINECRAFT_GAME_ID = 432
