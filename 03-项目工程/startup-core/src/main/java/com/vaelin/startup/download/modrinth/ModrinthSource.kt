/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现。Modrinth → ContentSource 适配器：把统一查询/模型
 * 与 ModrinthApi 对起来，供 SourceRegistry 持有。
 */

package com.vaelin.startup.download.modrinth

import com.vaelin.startup.download.source.AddonProject
import com.vaelin.startup.download.source.AddonSearchPage
import com.vaelin.startup.download.source.AddonSearchQuery
import com.vaelin.startup.download.source.AddonVersion
import com.vaelin.startup.download.source.ContentSource

/**
 * Modrinth 源。薄适配层：不藏逻辑，逻辑在 ModrinthApi / ModrinthParser。
 * 限速、镜像地址、传输层都在构造 ModrinthApi 时决定，这里不再重复暴露。
 */
class ModrinthSource(
    private val api: ModrinthApi,
) : ContentSource {

    override val sourceId: String get() = ModrinthParser.SOURCE_ID

    override val displayName: String get() = "Modrinth"

    override fun search(query: AddonSearchQuery): AddonSearchPage = api.search(query)

    override fun project(projectId: String): AddonProject = api.project(projectId)

    override fun versions(projectId: String, gameVersion: String?, loader: String?): List<AddonVersion> =
        api.versions(projectId, gameVersion, loader)
}
