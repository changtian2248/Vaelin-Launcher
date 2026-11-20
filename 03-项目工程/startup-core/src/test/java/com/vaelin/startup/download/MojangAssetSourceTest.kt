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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class MojangAssetSourceTest {

    private val h1 = "0123456789abcdef0123456789abcdef01234567"
    private val indexUrl = "https://launchermeta.mojang.com/assets/indexes/17.json"

    @Test
    fun `fetchIndex 通过传输层拉取并解析`() {
        val transport = FakeHttpTransport()
        transport.serveText(
            indexUrl,
            """{"objects": {"minecraft/texts/en_us.lang": {"hash": "$h1", "size": 42}}}""",
        )
        val source = MojangAssetSource(transport)

        val objects = source.fetchIndex(indexUrl)

        assertEquals(1, objects.size)
        assertEquals("minecraft/texts/en_us.lang", objects[0].path)
        assertEquals(h1, objects[0].hash)
        assertEquals(42L, objects[0].size)
        assertEquals(1, transport.callCount)
        assertEquals(indexUrl, transport.calls[0].url)
    }

    @Test
    fun `objectUrl 按前两位目录拼地址`() {
        assertEquals(
            "https://resources.download.minecraft.net/${h1.take(2)}/$h1",
            MojangAssetSource.objectUrl(h1),
        )
    }

    @Test
    fun `objectUrl 把大写 hash 归一后再切目录`() {
        // 大小写不归一的话前两位目录会变成 "01" 之外的值，CDN 直接 404
        assertEquals(
            "https://resources.download.minecraft.net/${h1.take(2)}/$h1",
            MojangAssetSource.objectUrl(h1.uppercase()),
        )
    }

    @Test
    fun `索引返回非 2xx 时抛 IOException`() {
        val transport = FakeHttpTransport()
        transport.fail(indexUrl, 503)
        val source = MojangAssetSource(transport)

        val error = runCatching { source.fetchIndex(indexUrl) }.exceptionOrNull()

        assertTrue("应当是 IOException，实际是 $error", error is IOException)
    }
}
