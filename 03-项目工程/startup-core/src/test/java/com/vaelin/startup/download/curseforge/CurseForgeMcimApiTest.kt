/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.vaelin.startup.download.curseforge

import com.vaelin.startup.download.FakeHttpTransport
import com.vaelin.startup.download.source.AddonKind
import com.vaelin.startup.download.source.AddonSearchQuery
import com.vaelin.startup.download.source.AddonSort
import com.vaelin.startup.download.source.RateLimiter
import com.vaelin.startup.download.source.SourceException
import com.vaelin.startup.download.source.SourceNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CurseForgeMcimApiTest {

    private class CountingLimiter : RateLimiter {
        var count = 0
        override fun acquire() { count++ }
    }

    private fun newApi(transport: FakeHttpTransport, limiter: CountingLimiter = CountingLimiter()) =
        CurseForgeMcimApi(transport, baseUrl = "https://mcim.test/curseforge/v1", rateLimiter = limiter)

    @Test
    fun `search builds url with all filters`() {
        val transport = FakeHttpTransport()
        transport.serveText(
            "https://mcim.test/curseforge/v1/mods/search?gameId=432&classId=6&" +
                "searchFilter=jei&gameVersion=1.20.1&modLoaderType=1&sortField=6&sortOrder=desc&" +
                "index=20&pageSize=20",
            """{"data":[],"pagination":{"index":20,"pageSize":20,"resultCount":0,"totalCount":0}}""",
        )
        val api = newApi(transport)
        val page = api.search(
            AddonSearchQuery(
                text = "jei", kind = AddonKind.MOD, gameVersion = "1.20.1",
                loader = "forge", sort = AddonSort.DOWNLOADS, offset = 20, limit = 20,
            )
        )
        assertEquals(0, page.totalHits)
        assertEquals(1, transport.callCount)
    }

    @Test
    fun `search without kind omits classId`() {
        val transport = FakeHttpTransport()
        transport.serveText(
            "https://mcim.test/curseforge/v1/mods/search?gameId=432&sortField=1&sortOrder=desc&index=0&pageSize=20",
            """{"data":[],"pagination":{"index":0,"pageSize":20,"resultCount":0,"totalCount":0}}""",
        )
        newApi(transport).search(AddonSearchQuery())
        assertEquals(1, transport.callCount)
    }

    @Test
    fun `files endpoint with filters`() {
        val transport = FakeHttpTransport()
        transport.serveText(
            "https://mcim.test/curseforge/v1/mods/238222/files?gameVersion=1.20.1&modLoaderType=4",
            """{"data":[]}""",
        )
        val versions = newApi(transport).files("238222", gameVersion = "1.20.1", loader = "fabric")
        assertTrue(versions.isEmpty())
        assertEquals(1, transport.callCount)
    }

    @Test
    fun `404 maps to not found`() {
        val transport = FakeHttpTransport()
        transport.fail("https://mcim.test/curseforge/v1/mods/999999", 404)
        try {
            newApi(transport).project("999999")
            fail("应抛 SourceNotFoundException")
        } catch (e: SourceNotFoundException) {
            assertTrue(e.message!!.contains("404"))
        }
    }

    @Test
    fun `download url resolution unwraps data`() {
        val transport = FakeHttpTransport()
        transport.serveText(
            "https://mcim.test/curseforge/v1/mods/238222/files/5616345/download-url",
            """{"data":"https://edge.forgecdn.net/files/5616/345/jei.jar"}""",
        )
        assertEquals(
            "https://edge.forgecdn.net/files/5616/345/jei.jar",
            newApi(transport).resolveDownloadUrl("238222", "5616345"),
        )
    }

    @Test
    fun `every request passes through rate limiter`() {
        val transport = FakeHttpTransport()
        transport.serveText(
            "https://mcim.test/curseforge/v1/mods/search?gameId=432&sortField=1&sortOrder=desc&index=0&pageSize=20",
            """{"data":[],"pagination":{"index":0,"pageSize":20,"resultCount":0,"totalCount":0}}""",
        )
        val limiter = CountingLimiter()
        val api = newApi(transport, limiter)
        api.search(AddonSearchQuery())
        api.search(AddonSearchQuery())
        assertEquals(2, limiter.count)
    }
}
