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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

@Suppress("NonAsciiCharacters")
class FileDownloaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val url = "https://example.invalid/file.bin"

    private val counter = AtomicInteger(0)

    /** 用真实实现算一遍期望 sha1，避免把哈希常数写死在测试里。 */
    private fun probeSha1(bytes: ByteArray): String {
        val probe = File(temp.root, "probe-${counter.incrementAndGet()}.bin")
        probe.writeBytes(bytes)
        return sha1Of(probe).also { probe.delete() }
    }

    @Test
    fun `下载成功后校验 sha1 并写入目标文件`() {
        val content = "hello".toByteArray()
        val transport = FakeHttpTransport().apply { serve(url, content) }
        val target = File(temp.root, "plain.bin")

        val result = FileDownloader(transport, maxRetries = 0).download(
            DownloadRequest(url, target, expectedSha1 = probeSha1(content), expectedSize = content.size.toLong())
        )

        assertTrue(result is DownloadResult.Success)
        val success = result as DownloadResult.Success
        assertFalse(success.skipped)
        assertEquals("hello", target.readText())
        assertEquals(content.size.toLong(), success.bytes)
        assertEquals(1, transport.callCount)
    }

    @Test
    fun `sha1 不匹配时失败并删除落盘文件`() {
        val content = "hello".toByteArray()
        val transport = FakeHttpTransport().apply { serve(url, content) }
        val target = File(temp.root, "corrupt.bin")

        val result = FileDownloader(transport, maxRetries = 0).download(
            DownloadRequest(url, target, expectedSha1 = "0".repeat(40))
        )

        assertTrue(result is DownloadResult.Failure)
        val failure = result as DownloadResult.Failure
        assertTrue("实际原因：${failure.reason}", failure.reason.contains("sha1 校验失败"))
        // 坏文件必须删掉，否则下次启动会把它当成已下载完成
        assertFalse(target.exists())
    }

    @Test
    fun `本地文件已完整时跳过且不发任何请求`() {
        val content = "cached".toByteArray()
        val target = File(temp.root, "cached.bin")
        target.writeBytes(content)
        val transport = FakeHttpTransport()

        val result = FileDownloader(transport).download(
            DownloadRequest(url, target, expectedSha1 = probeSha1(content))
        )

        assertTrue(result is DownloadResult.Success)
        val success = result as DownloadResult.Success
        assertTrue(success.skipped)
        assertEquals(0L, success.bytes)
        assertEquals(0, transport.callCount)
    }

    @Test
    fun `续传时带上 Range 头且只追加缺失部分`() {
        val content = "hello world".toByteArray()
        val target = File(temp.root, "resume.bin")
        target.writeBytes("hello".toByteArray())
        val transport = FakeHttpTransport().apply { serve(url, content) }

        val result = FileDownloader(transport, maxRetries = 0).download(
            DownloadRequest(
                url = url,
                target = target,
                expectedSha1 = probeSha1(content),
                expectedSize = content.size.toLong(),
                resume = true,
            )
        )

        assertTrue(result is DownloadResult.Success)
        assertEquals("hello world", target.readText())
        // 关键断言：请求确实从第 5 字节开始，而不是整份重下
        assertEquals(5L, transport.calls.single().rangeStart)
        assertEquals("GET", transport.calls.single().method)
    }

    @Test
    fun `服务端忽略 Range 返回 200 时整份重写`() {
        val content = "hello world".toByteArray()
        val target = File(temp.root, "ignore-range.bin")
        target.writeBytes("hello".toByteArray())
        val transport = FakeHttpTransport().apply { serve(url, content, honorRange = false) }

        val result = FileDownloader(transport, maxRetries = 0).download(
            DownloadRequest(
                url = url,
                target = target,
                expectedSha1 = probeSha1(content),
                resume = true,
            )
        )

        assertTrue(result is DownloadResult.Success)
        // 200 代表服务端没认 Range：必须丢掉旧分片重写，否则会拼出 "hellohello world"
        assertEquals("hello world", target.readText())
        assertEquals(5L, transport.calls.single().rangeStart)
    }

    @Test
    fun `失败后按 maxRetries 重试`() {
        val transport = FakeHttpTransport().apply { fail(url, 503) }
        val target = File(temp.root, "retry.bin")

        val result = FileDownloader(transport, maxRetries = 2).download(DownloadRequest(url, target))

        assertTrue(result is DownloadResult.Failure)
        assertTrue((result as DownloadResult.Failure).reason.contains("已重试 2 次"))
        // 首次 + 2 次重试
        assertEquals(3, transport.callCount)
    }

    @Test
    fun `服务端回 416 时丢弃本地分片`() {
        val target = File(temp.root, "stale.bin")
        target.writeBytes("stale-part".toByteArray())
        val transport = FakeHttpTransport().apply {
            handle(url) { HttpResponse(416, 0L, ByteArrayInputStream(ByteArray(0)), null) }
        }

        val result = FileDownloader(transport, maxRetries = 0).download(
            // 必须带 sha1：没有校验信息时 isComplete 会把任意非空文件当成完整份直接跳过
            DownloadRequest(url, target, expectedSha1 = "0".repeat(40), resume = true)
        )

        assertTrue(result is DownloadResult.Failure)
        assertTrue((result as DownloadResult.Failure).reason.contains("416"))
        assertFalse(target.exists())
    }
}
