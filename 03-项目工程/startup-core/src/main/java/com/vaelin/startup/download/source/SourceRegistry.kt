/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现。源注册表：UI/编排经这里拿源，下载路径统一过地区闸。
 */

package com.vaelin.startup.download.source

/**
 * 源注册表。三个职责：
 * 1. 按 id 提供源（检索/详情/版本列表——不过闸，全球可用）；
 * 2. 下载地址解析统一从 [prepareDownload] 走，内部先过地区闸——
 *    新注册的源不用自己写闸逻辑，自动继承合规（规格书第 38 条）；
 * 3. 列举全部源给源切换 UI。
 */
class SourceRegistry(
    private val gate: SourceGate,
    sources: List<ContentSource> = emptyList(),
) {

    private val byId = LinkedHashMap<String, ContentSource>()

    init {
        sources.forEach(::register)
    }

    fun register(source: ContentSource) {
        byId[source.sourceId] = source
    }

    fun get(sourceId: String): ContentSource? = byId[sourceId]

    fun require(sourceId: String): ContentSource =
        byId[sourceId] ?: throw SourceException("未知的资源源: $sourceId")

    fun all(): List<ContentSource> = byId.values.toList()

    // ---- 检索与展示（不过闸） ----

    fun search(sourceId: String, query: AddonSearchQuery): AddonSearchPage =
        require(sourceId).search(query)

    fun project(sourceId: String, projectId: String): AddonProject =
        require(sourceId).project(projectId)

    fun versions(
        sourceId: String,
        projectId: String,
        gameVersion: String? = null,
        loader: String? = null,
    ): List<AddonVersion> = require(sourceId).versions(projectId, gameVersion, loader)

    // ---- 文件分发（过闸） ----

    /**
     * 解析下载地址并已过地区闸。下载编排层唯一入口。
     * 被拦时抛 [SourceGateException]。
     */
    fun prepareDownload(sourceId: String, file: AddonFile): String {
        gate.assertDownloadAllowed()
        return require(sourceId).resolveDownloadUrl(file)
    }

    /** UI 开关态：当前地区是否禁止分发（用于隐藏下载按钮、置灰安装入口）。 */
    fun isDistributionBlocked(): Boolean = gate.isDistributionBlocked()
}
