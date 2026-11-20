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
 * 资源侧的两个入口：拉 asset index、拼单个对象的下载地址。
 *
 * 复用 [MojangVersionSource] 的文档读取（同一套大小上限与错误语义），避免这里
 * 又写一份「读流 + 截断保护」的代码，两处行为不一致时最难查。
 */
class MojangAssetSource(
    transport: HttpTransport,
    private val resourcesBase: String = MojangEndpoints.RESOURCES_BASE,
) {

    private val documents = MojangVersionSource(transport)

    /** 拉取并解析 asset index。非 2xx / 没有正文时抛 `IOException`。 */
    fun fetchIndex(indexUrl: String): List<AssetObject> =
        AssetIndexParser.parse(documents.readText(indexUrl))

    /** 单个对象的下载地址：`<resourcesBase>/<hash 前两位>/<hash>`。 */
    fun objectUrl(hash: String): String = objectUrl(hash, resourcesBase)

    companion object {

        /**
         * 拼对象地址。hash 统一按小写处理：索引里偶尔出现大写，而 CDN 路径是全小写，
         * 直接比对会 404；`take(2)` 必须在归一之后，否则前两位目录会跟文件名对不上。
         */
        fun objectUrl(hash: String, resourcesBase: String = MojangEndpoints.RESOURCES_BASE): String {
            val normalized = hash.trim().lowercase()
            require(normalized.length >= OBJECT_PREFIX_LENGTH) {
                "hash 太短，切不出前两位目录: $hash"
            }
            return resourcesBase.trimEnd('/') + "/" + normalized.take(OBJECT_PREFIX_LENGTH) + "/" + normalized
        }

        /** 对象文件按 hash 前两位分桶，避免单个目录塞几十万个文件。 */
        const val OBJECT_PREFIX_LENGTH = 2
    }
}
