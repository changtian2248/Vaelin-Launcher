/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现。CurseForge → ContentSource 适配器（MCIM 镜像后端）。
 * 用户裁定：暂时没有官方 API key，用镜像端代持凭据临时顶位；
 * 拿到 key 后写官方 Api 实现（同签名）整体替换，源框架层零改动。
 *
 * 注：CF 少量项目禁止第三方下载、file.downloadUrl 为空——那是下载编排层
 * （DownloadViewModel.download）用 version.projectId/versionId 走
 * /download-url 端点补全的场景，不在源接口的默认直返路径里。
 */

package com.vaelin.startup.download.curseforge

import com.vaelin.startup.download.source.AddonProject
import com.vaelin.startup.download.source.AddonSearchPage
import com.vaelin.startup.download.source.AddonSearchQuery
import com.vaelin.startup.download.source.AddonVersion
import com.vaelin.startup.download.source.ContentSource

class CurseForgeMcimSource(
    private val api: CurseForgeMcimApi,
) : ContentSource {

    override val sourceId: String get() = CurseForgeParser.SOURCE_ID

    override val displayName: String get() = "CurseForge（MCIM 镜像）"

    override fun search(query: AddonSearchQuery): AddonSearchPage = api.search(query)

    override fun project(projectId: String): AddonProject = api.project(projectId)

    override fun versions(projectId: String, gameVersion: String?, loader: String?): List<AddonVersion> =
        api.files(projectId, gameVersion, loader)
}
