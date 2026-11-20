/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.vaelin.startup.download.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SourceRegistryGateTest {

    private class FakeSource(
        override val sourceId: String = "fake",
    ) : ContentSource {
        var searchCount = 0
        override val displayName: String get() = "Fake"
        override fun search(query: AddonSearchQuery): AddonSearchPage {
            searchCount++
            return AddonSearchPage(emptyList(), 0, 0, 20)
        }
        override fun project(projectId: String): AddonProject = throw UnsupportedOperationException()
        override fun versions(projectId: String, gameVersion: String?, loader: String?) = emptyList<AddonVersion>()
    }

    private fun registryOf(system: String?, sim: String?, vararg sources: ContentSource): SourceRegistry {
        val detector = RegionDetector(object : RegionSignals {
            override fun systemCountry(): String? = system
            override fun simCountry(): String? = sim
        })
        return SourceRegistry(SourceGate(detector), sources.toList())
    }

    @Test
    fun `search stays available on mainland per spec`() {
        val fake = FakeSource()
        val registry = registryOf("CN", null, fake)
        registry.search("fake", AddonSearchQuery(text = "sodium"))
        assertEquals(1, fake.searchCount)
        assertTrue(registry.isDistributionBlocked())
    }

    @Test
    fun `download blocked on mainland`() {
        val registry = registryOf("CN", null, FakeSource())
        val file = AddonFile("a.jar", 1L, "x", null, "https://example.test/a.jar", true)
        try {
            registry.prepareDownload("fake", file)
            fail("应抛出 SourceGateException")
        } catch (e: SourceGateException) {
            assertTrue(e.message!!.contains("不开放"))
        }
    }

    @Test
    fun `download allowed overseas`() {
        val registry = registryOf("US", "US", FakeSource())
        val file = AddonFile("a.jar", 1L, "x", null, "https://example.test/a.jar", true)
        assertEquals("https://example.test/a.jar", registry.prepareDownload("fake", file))
        assertFalse(registry.isDistributionBlocked())
    }

    @Test
    fun `unknown source id rejected`() {
        val registry = registryOf("US", "US", FakeSource())
        try {
            registry.search("nope", AddonSearchQuery())
            fail("应抛出 SourceException")
        } catch (e: SourceException) {
            assertTrue(e.message!!.contains("nope"))
        }
    }

    @Test
    fun `gate applies to newly registered source automatically`() {
        val registry = registryOf("CN", null)
        val late = FakeSource(sourceId = "late")
        registry.register(late)
        val file = AddonFile("b.jar", 1L, "x", null, "https://example.test/b.jar", true)
        try {
            registry.prepareDownload("late", file)
            fail("后注册的源也必须继承地区闸")
        } catch (e: SourceGateException) {
            // 预期
        }
    }
}
