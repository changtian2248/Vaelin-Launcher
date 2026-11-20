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

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@Suppress("NonAsciiCharacters")
class BatchDownloaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val okUrl = "https://example.invalid/ok.bin"
    private val badUrl = "https://example.invalid/bad.bin"

    @Test
    fun `单个文件失败不影响其它文件`() = runBlocking {
        val transport = FakeHttpTransport().apply {
            serveText(okUrl, "abc")
            fail(badUrl, 503)
        }
        val downloader = FileDownloader(transport, maxRetries = 0)
        val requests = listOf(
            DownloadRequest(okUrl, File(temp.root, "ok.bin")),
            DownloadRequest(badUrl, File(temp.root, "bad.bin")),
        )
        val progress = ArrayList<BatchProgress>()

        val result = BatchDownloader(downloader, parallelism = 2).downloadAll(requests) { progress += it }

        assertEquals(1, result.succeeded.size)
        assertEquals(1, result.failed.size)
        assertEquals(badUrl, result.failed.single().request.url)
        assertEquals(3L, result.downloadedBytes)
        assertFalse(result.isFullSuccess)
        // 每个请求都应恰好回调一次进度，且完成数递增到总数
        assertEquals(2, progress.size)
        assertEquals(2, progress.maxOf { it.completed })
        assertEquals(2, progress.first().total)
    }

    @Test
    fun `全部成功时汇总为成功并累计字节`() = runBlocking {
        val first = "https://example.invalid/a.bin"
        val second = "https://example.invalid/b.bin"
        val transport = FakeHttpTransport().apply {
            serveText(first, "aaaa")
            serveText(second, "bb")
        }
        val requests = listOf(
            DownloadRequest(first, File(temp.root, "a.bin")),
            DownloadRequest(second, File(temp.root, "b.bin")),
        )

        val result = BatchDownloader(FileDownloader(transport), parallelism = 4).downloadAll(requests)

        assertTrue(result.isFullSuccess)
        assertEquals(2, result.succeeded.size)
        assertEquals(6L, result.downloadedBytes)
        assertEquals("aaaa", File(temp.root, "a.bin").readText())
        assertEquals("bb", File(temp.root, "b.bin").readText())
    }

    @Test
    fun `空请求列表直接返回空结果`() = runBlocking {
        val result = BatchDownloader(FileDownloader(FakeHttpTransport())).downloadAll(emptyList())

        assertTrue(result.isFullSuccess)
        assertEquals(0, result.succeeded.size)
        assertEquals(0L, result.downloadedBytes)
    }

    @Test
    fun `并发数下限至少为一`() = runBlocking {
        val url = "https://example.invalid/one.bin"
        val transport = FakeHttpTransport().apply { serveText(url, "x") }
        val requests = listOf(DownloadRequest(url, File(temp.root, "one.bin")))

        // parallelism 传 0 时不能死锁
        val result = BatchDownloader(FileDownloader(transport), parallelism = 0).downloadAll(requests)

        assertTrue(result.isFullSuccess)
    }
}
