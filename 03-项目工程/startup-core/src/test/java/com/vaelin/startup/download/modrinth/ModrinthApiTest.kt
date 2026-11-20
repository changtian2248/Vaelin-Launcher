/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.vaelin.startup.download.modrinth

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

class ModrinthApiTest {

    private class CountingLimiter : RateLimiter {
        var count = 0
        override fun acquire() { count++ }
    }

    private fun newApi(transport: FakeHttpTransport, limiter: CountingLimiter = CountingLimiter()) =
        ModrinthApi(transport, baseUrl = "https://test.local/v2", rateLimiter = limiter)

    @Test
    fun `search builds url and parses page`() {
        val transport = FakeHttpTransport()
        transport.serveText(
            "https://test.local/v2/search?limit=20&offset=0&index=relevance&query=sodium&facets=" +
                "%5B%5B%22project_type%3Amod%22%5D%5D",
            """{"hits":[],"offset":0,"limit":20,"total_hits":0}"""
        )
        val api = newApi(transport)
        val page = api.search(AddonSearchQuery(text = "sodium", kind = AddonKind.MOD))
        assertEquals(0, page.totalHits)
        assertEquals(1, transport.callCount)
    }

    @Test
    fun `search encodes cjk query`() {
        val transport = FakeHttpTransport()
        transport.serveText(
            "https://test.local/v2/search?limit=20&offset=0&index=downloads&query=%E4%BC%98%E5%8C%96",
            """{"hits":[],"offset":0,"limit":20,"total_hits":0}"""
        )
        val api = newApi(transport)
        api.search(AddonSearchQuery(text = "优化", sort = AddonSort.DOWNLOADS))
        assertEquals(1, transport.callCount)
    }

    @Test
    fun `versions filters encoded as json arrays`() {
        val transport = FakeHttpTransport()
        transport.serveText(
            "https://test.local/v2/project/AANobbMI/version?game_versions=%5B%221.20.1%22%5D&" +
                "loaders=%5B%22fabric%22%5D",
            "[]"
        )
        val api = newApi(transport)
        val versions = api.versions("AANobbMI", gameVersion = "1.20.1", loader = "fabric")
        assertTrue(versions.isEmpty())
    }

    @Test
    fun `project 404 maps to not found`() {
        val transport = FakeHttpTransport()
        transport.fail("https://test.local/v2/project/missing", 404)
        val api = newApi(transport)
        try {
            api.project("missing")
            fail("应抛 SourceNotFoundException")
        } catch (e: SourceNotFoundException) {
            assertTrue(e.message!!.contains("404"))
        }
    }

    @Test
    fun `server error maps to source exception`() {
        val transport = FakeHttpTransport()
        transport.fail("https://test.local/v2/search?limit=20&offset=0&index=relevance", 500)
        val api = newApi(transport)
        try {
            api.search(AddonSearchQuery())
            fail("应抛 SourceException")
        } catch (e: SourceException) {
            assertTrue(e.message!!.contains("500"))
        }
    }

    @Test
    fun `every request passes through rate limiter`() {
        val transport = FakeHttpTransport()
        transport.serveText("https://test.local/v2/search?limit=20&offset=0&index=relevance", """{"hits":[]}""")
        val limiter = CountingLimiter()
        val api = newApi(transport, limiter)
        api.search(AddonSearchQuery())
        api.search(AddonSearchQuery())
        assertEquals(2, limiter.count)
    }
}
