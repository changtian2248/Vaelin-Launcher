/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现。端点集中地：换镜像（如 mcimirror）只改构造参数。
 */

package com.vaelin.startup.download.modrinth

/** Modrinth v2 API 端点。baseUrl 由构造注入，便于切镜像与单测。 */
object ModrinthEndpoints {

    const val DEFAULT_BASE_URL = "https://api.modrinth.com/v2"

    // ZL2 同款 MCIM 镜像，留作国内网络劣化时的备选（是否启用待用户裁决，先不接）：
    // const val MCIM_BASE_URL = "https://mod.mcimirror.top/modrinth/v2"

    fun search(base: String) = "$base/search"

    fun project(base: String, id: String) = "$base/project/$id"

    fun versions(base: String, projectId: String) = "$base/project/$projectId/version"
}
