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

/**
 * assets index 里的一个资源对象。
 *
 * [path] 是游戏内部的逻辑路径（`minecraft/sounds/ambient/cave/cave1.ogg`），只用于
 * 展示、日志与清理时回溯来源；真正落盘的文件名永远是 [hash]，因为同一份内容可能
 * 被多个路径共用，Mojang 的 CDN 也只认 hash。
 */
data class AssetObject(
    val path: String,
    val hash: String,
    val size: Long,
)

/**
 * asset index 解析。
 *
 * 结构固定为 `{"objects": {"<path>": {"hash": "<sha1>", "size": <long>}}}`，
 * 但字段缺失在历史上出现过（第三方整包会删 size），所以这里按「坏的跳过、好的保留」
 * 降级，而不是整篇报错——一个坏条目不该让几千个资源全下不了。
 *
 * 名字带 `Parser` 后缀是为了跟 `VersionJson.kt` 里那个 `AssetIndex`（version json
 * 里 `assetIndex` 字段的声明，只有 id/url/sha1/size）区分：那个描述「索引文件本身」，
 * 这里解析「索引文件的内容」。
 */
object AssetIndexParser {

    /** 解析索引文档。`objects` 缺失或不是对象时返回空列表。 */
    fun parse(text: String): List<AssetObject> {
        val root = Json.parse(text)
        val objects = root.objectField("objects") ?: return emptyList()

        val result = ArrayList<AssetObject>(objects.size)
        for ((path, value) in objects) {
            if (path.isBlank()) continue
            val hash = value.stringField("hash")?.trim()?.lowercase()
            // 没有合法 sha1 的条目既不能定位对象文件也不能校验，只能跳过
            if (hash.isNullOrEmpty() || !isSha1Hex(hash)) continue
            val size = value.longField("size") ?: 0L
            result += AssetObject(path, hash, if (size < 0L) 0L else size)
        }
        result.sortBy { it.path }
        return result
    }

    /** 是否是 40 位十六进制 sha1（大小写不敏感，比较前统一转小写）。 */
    fun isSha1Hex(value: String): Boolean =
        value.length == SHA1_HEX_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' }

    const val SHA1_HEX_LENGTH = 40
}
