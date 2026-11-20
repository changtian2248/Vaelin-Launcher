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

import java.io.Closeable
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 一次 HTTP 响应的最小视图。
 *
 * 不暴露 socket / 头列表，只给下载器真正会用到的三样东西：状态码、长度、流。
 * `close()` 同时负责断开连接，避免 keep-alive 连接在重试场景下堆积。
 */
class HttpResponse(
    val code: Int,
    val contentLength: Long,
    val body: InputStream?,
    private val connection: HttpURLConnection?,
) : Closeable {

    val isSuccess: Boolean get() = code in 200..299

    override fun close() {
        try {
            body?.close()
        } catch (_: Exception) {
            // 关流失败不影响已经写入的内容，不必把错误往上抛
        }
        connection?.disconnect()
    }
}

/**
 * HTTP 传输层接口。
 *
 * 抽成接口的唯一目的是让单测能塞一个假实现进去：下载逻辑（续传、校验、重试）
 * 是本轮真正要验证的东西，而它不该依赖网络是否通。
 */
interface HttpTransport {

    /**
     * 发起请求。`rangeStart >= 0` 时带上 `Range: bytes=<rangeStart>-`。
     * 调用方负责关闭返回的响应。
     */
    fun open(url: String, method: String = "GET", rangeStart: Long = -1L): HttpResponse
}

/**
 * 基于 `java.net.HttpURLConnection` 的实现：Android 自带、零新增依赖。
 *
 * 没有选 OkHttp，是因为这里的需求只有 GET、Range 和重定向，OkHttp 能多给的
 * 连接池复用与 HTTP/2 在「一次装游戏下几千个文件」的串行小文件场景里收益有限，
 * 却要多背 1.5MB 的依赖和一套 ProGuard 规则。
 */
class UrlConnectionTransport(
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val userAgent: String = DEFAULT_USER_AGENT,
) : HttpTransport {

    override fun open(url: String, method: String, rangeStart: Long): HttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", userAgent)
        // 让服务端别压缩：Content-Encoding: gzip 会把 Range 的字节序弄乱
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (rangeStart >= 0) {
            connection.setRequestProperty("Range", "bytes=$rangeStart-")
        }

        val code = connection.responseCode
        val stream = if (code in 200..299) {
            try {
                connection.inputStream
            } catch (_: Exception) {
                null
            }
        } else {
            connection.errorStream
        }
        return HttpResponse(code, connection.contentLengthLong, stream, connection)
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        const val DEFAULT_READ_TIMEOUT_MS = 30_000
        const val DEFAULT_USER_AGENT = "VaelinLauncher/0.1 (Android)"
    }
}
