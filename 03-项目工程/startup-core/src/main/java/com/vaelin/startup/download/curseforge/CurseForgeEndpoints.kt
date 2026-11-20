/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现。端点集中地：默认走 MCIM 镜像（用户裁定：无 CF key 期间
 * 镜像代持凭据临时顶替，拿到 key 后换 CurseForgeApi 官方实现同接口接入）。
 */

package com.vaelin.startup.download.curseforge

object CurseForgeEndpoints {

    /** MCIM 镜像（路径与官方 v1 完全一致，只是域名换 + 免 key） */
    const val MCIM_BASE_URL = "https://mod.mcimirror.top/curseforge/v1"

    /** 官方源（待用 key 启用；与 MCIM 同构，届时仅换 baseUrl + 加 x-api-key 头） */
    const val OFFICIAL_BASE_URL = "https://api.curseforge.com/v1"

    fun search(base: String) = "$base/mods/search"

    fun mod(base: String, modId: String) = "$base/mods/$modId"

    fun files(base: String, modId: String) = "$base/mods/$modId/files"

    fun downloadUrl(base: String, modId: String, fileId: String) =
        "$base/mods/$modId/files/$fileId/download-url"
}
