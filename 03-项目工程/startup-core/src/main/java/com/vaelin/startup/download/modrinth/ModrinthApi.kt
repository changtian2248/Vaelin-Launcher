/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现。Modrinth v2 低层客户端：只负责「发请求 → 拿 JSON →
 * 交给解析器」，不承载业务语义。同步阻塞，调用方自行起线程。
 */

package com.vaelin.startup.download.modrinth

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

/**
 * Modrinth v2 API 客户端。
 *
 * @param transport HTTP 传输层，单测传 FakeHttpTransport
 * @param baseUrl API 基址，默认官方；切镜像只换这个参数
 * @param rateLimiter 请求限速（Modrinth 要求约 250ms 一个请求）
 */
class ModrinthApi(
    private val transport: HttpTransport,
    private val baseUrl: String = ModrinthEndpoints.DEFAULT_BASE_URL,
    private val rateLimiter: RateLimiter = IntervalRateLimiter(),
) {

    fun search(query: AddonSearchQuery): AddonSearchPage {
        val params = buildList {
            add("limit=${query.limit.coerceIn(1, 100)}")
            add("offset=${query.offset.coerceAtLeast(0)}")
            add("index=${query.sort.toModrinthIndex()}")
            if (query.text.isNotBlank()) add("query=${enc(query.text)}")
            ModrinthFacets.toFacetParam(query)?.let { add("facets=${enc(it)}") }
        }.joinToString("&")
        val doc = getJson("${ModrinthEndpoints.search(baseUrl)}?$params")
        return ModrinthParser.parseSearch(doc)
    }

    fun project(idOrSlug: String): AddonProject {
        val doc = getJson(ModrinthEndpoints.project(baseUrl, enc(idOrSlug)))
        return ModrinthParser.parseProject(doc)
    }

    fun versions(projectId: String, gameVersion: String? = null, loader: String? = null): List<AddonVersion> {
        val params = buildList {
            if (!gameVersion.isNullOrBlank()) add("game_versions=${enc("[\"${gameVersion.trim()}\"]")}")
            if (!loader.isNullOrBlank()) add("loaders=${enc("[\"${loader.trim().lowercase()}\"]")}")
        }.joinToString("&")
        val url = ModrinthEndpoints.versions(baseUrl, enc(projectId)) +
            if (params.isEmpty()) "" else "?$params"
        return ModrinthParser.parseVersions(getJson(url))
    }

    // ---- HTTP ----

    /** GET 一个 JSON 文档。2xx 之外显式抛错，404 单独分类。 */
    private fun getJson(url: String): JsonValue {
        rateLimiter.acquire()
        transport.open(url, "GET").use { resp ->
            val body = resp.body ?: throw SourceException("Modrinth 响应没有正文（HTTP ${resp.code}）: $url")
            val text = body.readText()
            if (resp.code == 404) {
                throw SourceNotFoundException("Modrinth 资源不存在（HTTP 404）: $url")
            }
            if (!resp.isSuccess) {
                // 截断 body 避免把整页错误 HTML 甩进异常消息
                val snippet = text.take(200)
                throw SourceException("Modrinth 请求失败（HTTP ${resp.code}）: $url — $snippet")
            }
            return try {
                Json.parse(text)
            } catch (e: Exception) {
                throw SourceException("Modrinth 返回的 JSON 解析失败: $url", e)
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
