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
 * 版本清单（`version_manifest_v2.json`）里的单个版本。
 *
 * 这是「去哪下 version json」的索引，不含任何游戏内容信息；真正的元数据在
 * 每个版本自己的 version json 里，所以要两步才能把下载计划拼出来。
 */
data class ManifestVersion(
    val id: String,
    val type: String,
    val url: String,
    val sha1: String,
    val size: Long,
    val releaseTime: String,
) {
    val isRelease: Boolean get() = type == TYPE_RELEASE

    val isSnapshot: Boolean get() = type == TYPE_SNAPSHOT

    companion object {
        const val TYPE_RELEASE = "release"
        const val TYPE_SNAPSHOT = "snapshot"
        const val TYPE_OLD_BETA = "old_beta"
        const val TYPE_OLD_ALPHA = "old_alpha"
    }
}

/**
 * 版本清单全文。
 *
 * `latest` 是 Mojang 自己维护的两个指针，不能靠 `versions` 的顺序推：列表顺序
 * 虽然是新到旧，但快照与正式版混在一起，直接取首条会把快照当成最新正式版。
 */
data class VersionManifest(
    val latestRelease: String,
    val latestSnapshot: String,
    val versions: List<ManifestVersion>,
) {

    fun find(versionId: String): ManifestVersion? = versions.firstOrNull { it.id == versionId }

    fun latestOf(type: String): ManifestVersion? = versions.firstOrNull { it.type == type }

    companion object {

        /**
         * 解析清单。单个版本条目缺 id 或 url 时跳过而不是整篇失败：清单是外部数据，
         * 一条脏数据不该让启动器连版本列表都打不开。
         */
        fun parse(text: String): VersionManifest {
            val root = Json.parse(text)

            val versions = root.arrayField("versions").orEmpty().mapNotNull { entry ->
                val id = entry.stringField("id") ?: return@mapNotNull null
                val url = entry.stringField("url") ?: return@mapNotNull null
                ManifestVersion(
                    id = id,
                    type = entry.stringField("type") ?: "",
                    url = url,
                    sha1 = entry.stringField("sha1") ?: "",
                    size = entry.longField("size") ?: -1L,
                    releaseTime = entry.stringField("releaseTime") ?: "",
                )
            }

            val latest = root.field("latest")
            return VersionManifest(
                latestRelease = latest.stringField("release") ?: fallbackLatest(versions, ManifestVersion.TYPE_RELEASE),
                latestSnapshot = latest.stringField("snapshot") ?: fallbackLatest(versions, ManifestVersion.TYPE_SNAPSHOT),
                versions = versions,
            )
        }

        /** `latest` 整块缺失时（离线包、代理改写的清单）退回到列表里第一条同类型版本 */
        private fun fallbackLatest(versions: List<ManifestVersion>, type: String): String =
            versions.firstOrNull { it.type == type }?.id.orEmpty()
    }
}
