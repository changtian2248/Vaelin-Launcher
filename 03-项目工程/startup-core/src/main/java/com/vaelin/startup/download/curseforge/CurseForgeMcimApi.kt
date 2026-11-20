/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现。CurseForge v1 低层客户端（当前后端 = MCIM 镜像）。
 * 与 ModrinthApi 同款约定：同步阻塞、限速器前置、404 单独分类。
 */

package com.vaelin.startup.download.curseforge

import com.vaelin.startup.download.HttpTransport
import com.vaelin.startup.download.Json
import com.vaelin.startup.download.JsonValue
import com.vaelin.startup.download.source.AddonSearchPage
import com.vaelin.startup.download.source.AddonSearchQuery
import com.vaelin.startup.download.source.AddonProject
import com.vaelin.startup.download.source.AddonVersion
import com.vaelin.startup.download.source.IntervalRateLimiter
import com.vaelin.startup.download.source.RateLimiter
import com.vaelin.startup.download.source.SourceException
import com.vaelin.startup.download.source.SourceNotFoundException
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URLEncoder

class CurseForgeMcimApi(
    private val transport: HttpTransport,
    private val baseUrl: String = CurseForgeEndpoints.MCIM_BASE_URL,
    private val rateLimiter: RateLimiter = IntervalRateLimiter(),
) {

    fun search(query: AddonSearchQuery): AddonSearchPage {
        val params = buildList {
            add("gameId=$MINECRAFT_GAME_ID")
            // kind 映射不到 CF 类目时（DATA_PACK/WORLD）不给 classId，让服务端按游戏全类搜
            query.kind?.let { CurseForgeClassIds.fromKind(it) }?.let { add("classId=$it") }
            if (query.text.isNotBlank()) add("searchFilter=${enc(query.text.trim())}")
            query.gameVersion?.takeIf { it.isNotBlank() }?.let { add("gameVersion=${enc(it.trim())}") }
            query.loader?.takeIf { it.isNotBlank() }
                ?.let { CurseForgeLoaderTypes.fromLoaderName(it) }
                ?.let { add("modLoaderType=$it") }
            add("sortField=${CurseForgeSortFields.fromSort(query.sort)}")
            add("sortOrder=desc")
            add("index=${query.offset.coerceAtLeast(0)}")
            add("pageSize=${query.limit.coerceIn(1, 50)}")
        }.joinToString("&")
        return CurseForgeParser.parseSearch(getJson("${CurseForgeEndpoints.search(baseUrl)}?$params"))
    }

    fun project(modId: String): AddonProject =
        CurseForgeParser.parseProject(getJson(CurseForgeEndpoints.mod(baseUrl, enc(modId))))

    fun files(modId: String, gameVersion: String? = null, loader: String? = null): List<AddonVersion> {
        val params = buildList {
            if (!gameVersion.isNullOrBlank()) add("gameVersion=${enc(gameVersion.trim())}")
            if (!loader.isNullOrBlank()) {
                CurseForgeLoaderTypes.fromLoaderName(loader)?.let { add("modLoaderType=$it") }
            }
        }.joinToString("&")
        val url = CurseForgeEndpoints.files(baseUrl, enc(modId)) +
            if (params.isEmpty()) "" else "?$params"
        return CurseForgeParser.parseFiles(getJson(url), modId)
    }

    /** download-url 端点：官方 API 对禁止第三方下载的项目会在这里给出临时直链。 */
    fun resolveDownloadUrl(modId: String, fileId: String): String {
        val doc = getJson(CurseForgeEndpoints.downloadUrl(baseUrl, enc(modId), enc(fileId)))
        return CurseForgeParser.parseDownloadUrl(doc)
            ?: throw SourceException("CurseForge 未返回下载地址（mod=$modId file=$fileId）")
    }

    // ---- HTTP ----

    private fun getJson(url: String): JsonValue {
        rateLimiter.acquire()
        transport.open(url, "GET").use { resp ->
            val body = resp.body ?: throw SourceException("CurseForge 响应没有正文（HTTP ${resp.code}）: $url")
            val text = body.readText()
            if (resp.code == 404) {
                throw SourceNotFoundException("CurseForge 资源不存在（HTTP 404）: $url")
            }
            if (!resp.isSuccess) {
                throw SourceException("CurseForge 请求失败（HTTP ${resp.code}）: $url — ${text.take(200)}")
            }
            return try {
                Json.parse(text)
            } catch (e: Exception) {
                throw SourceException("CurseForge 返回的 JSON 解析失败: $url", e)
            }
        }
    }

    private fun InputStream.readText(): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
