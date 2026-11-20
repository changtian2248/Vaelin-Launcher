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

import java.io.ByteArrayInputStream

/**
 * 单测用的 HTTP 替身：只按预设的 URL → 响应返回，绝不打开 socket。
 *
 * 同时记录每次请求的 Range 起点，用来断言「续传确实带上了 Range 头」——
 * 这是断点续传最容易写错的一处，光看文件内容对了还不足以证明走了续传路径。
 */
class FakeHttpTransport : HttpTransport {

    data class Call(val url: String, val method: String, val rangeStart: Long)

    val calls = ArrayList<Call>()

    private val handlers = HashMap<String, (Long) -> HttpResponse>()

    fun handle(url: String, handler: (Long) -> HttpResponse) {
        handlers[url] = handler
    }

    /**
     * 固定返回一份内容。
     *
     * [honorRange] 为 true 时按 Range 切出剩余片段并返回 206，模拟支持续传的 CDN；
     * 为 false 时永远回 200 整份内容，模拟忽略 Range 的服务端。
     */
    fun serve(url: String, content: ByteArray, honorRange: Boolean = true) {
        handle(url) { rangeStart ->
            if (honorRange && rangeStart > 0 && rangeStart < content.size) {
                val slice = content.copyOfRange(rangeStart.toInt(), content.size)
                HttpResponse(206, slice.size.toLong(), ByteArrayInputStream(slice), null)
            } else {
                HttpResponse(200, content.size.toLong(), ByteArrayInputStream(content), null)
            }
        }
    }

    fun serveText(url: String, text: String, honorRange: Boolean = true) {
        serve(url, text.toByteArray(Charsets.UTF_8), honorRange)
    }

    fun fail(url: String, code: Int = 500) {
        handle(url) { HttpResponse(code, 0L, ByteArrayInputStream(ByteArray(0)), null) }
    }

    override fun open(url: String, method: String, rangeStart: Long): HttpResponse {
        calls += Call(url, method, rangeStart)
        return handlers[url]?.invoke(rangeStart)
            ?: HttpResponse(404, 0L, ByteArrayInputStream(ByteArray(0)), null)
    }

    val callCount: Int get() = calls.size
}
