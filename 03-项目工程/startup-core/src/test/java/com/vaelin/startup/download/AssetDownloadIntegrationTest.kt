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
import java.security.MessageDigest

/**
 * 规划器与下载器的联调：确认资源计划交给 `FileDownloader` 后，sha1 校验真的会
 * 挡住坏内容，而已经下完的对象不会在下次启动时被重新拉一遍。
 */
class AssetDownloadIntegrationTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val h1 = "0123456789abcdef0123456789abcdef01234567"

    private fun sha1OfText(text: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
        return digest.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }

    @Test
    fun `sha1 不符时删除落盘文件`() {
        val transport = FakeHttpTransport()
        // 服务端给出的是错误内容，与索引里的 hash 对不上
        transport.serveText(MojangAssetSource.objectUrl(h1), "corrupted")
        val plan = AssetDownloadPlanner(temp.root).plan(listOf(AssetObject("a.ogg", h1, 9)))
        val request = plan.requests.single()

        val result = FileDownloader(transport, maxRetries = 0).download(request)

        assertTrue(result is DownloadResult.Failure)
        assertTrue((result as DownloadResult.Failure).reason.contains("sha1"))
        assertFalse("校验失败的文件必须删掉，否则下次会被当成完整份", request.target.exists())
    }

    @Test
    fun `已完整的对象在重新规划时被跳过`() {
        val content = "hello asset"
        val bytes = content.toByteArray(Charsets.UTF_8)
        val hash = sha1OfText(content)
        val transport = FakeHttpTransport()
        transport.serve(MojangAssetSource.objectUrl(hash), bytes)
        val planner = AssetDownloadPlanner(temp.root)
        val objectSpec = AssetObject("minecraft/texts/en_us.lang", hash, bytes.size.toLong())

        val first = FileDownloader(transport).download(planner.plan(listOf(objectSpec)).requests.single())
        assertTrue(first is DownloadResult.Success)
        val callsAfterDownload = transport.callCount

        val second = planner.plan(listOf(objectSpec))

        assertTrue(second.requests.isEmpty())
        assertEquals("已存在的完整对象不应再次请求网络", callsAfterDownload, transport.callCount)
    }
}
