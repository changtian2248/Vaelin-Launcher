/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * T1.5.5 —— Forge promotions 解析与 installer URL 构造单测（纯 JVM）。
 */

package com.vaelin.startup.install

import com.vaelin.startup.jvm.expectThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForgePromotionsTest {

    private val promotionsJson = """
        {
          "homepage": "https://files.minecraftforge.net/net/minecraftforge/forge/",
          "promos": {
            "1.20.1-latest": "47.3.0",
            "1.20.1-recommended": "47.2.0",
            "1.20.4-latest": "49.0.30"
          }
        }
    """.trimIndent()

    @Test
    fun `parse reads promos map`() {
        val promos = ForgePromotions.parse(promotionsJson)
        assertEquals("47.2.0", promos.recommended("1.20.1"))
        assertEquals("47.3.0", promos.latest("1.20.1"))
        assertNull(promos.recommended("1.20.4"))
        assertEquals("49.0.30", promos.latest("1.20.4"))
    }

    @Test
    fun `resolve prefers recommended`() {
        val build = ForgePromotions.parse(promotionsJson).resolve("1.20.1")
        assertEquals("1.20.1", build.mcVersion)
        assertEquals("47.2.0", build.forgeVersion)
        assertEquals(ForgeChannel.RECOMMENDED, build.channel)
    }

    @Test
    fun `resolve falls back to latest with channel marker`() {
        val latestOnly = """{"promos":{"1.20.1-latest":"47.3.0"}}"""
        val build = ForgePromotions.parse(latestOnly).resolve("1.20.1")
        assertEquals("47.3.0", build.forgeVersion)
        assertEquals(ForgeChannel.LATEST, build.channel)
    }

    @Test
    fun `resolve throws when mc version has no entry`() {
        val e = expectThrows<ForgeInstallException> {
            ForgePromotions.parse(promotionsJson).resolve("1.99.9")
        }
        assertTrue(e.message!!.contains("1.99.9"))
    }

    @Test
    fun `parse throws without promos object`() {
        expectThrows<ForgeInstallException> { ForgePromotions.parse("""{"homepage":"https://x"}""") }
    }

    @Test
    fun `parse throws on malformed json`() {
        // 用普通转义字符串构造坏 JSON：原始字符串结尾紧贴引号会干扰 """ 终止符词法
        expectThrows<ForgeInstallException> { ForgePromotions.parse("{\"promos\": }") }
    }

    @Test
    fun `installer urls bmclapi first official fallback`() {
        val build = ForgePromotions.parse(promotionsJson).resolve("1.20.1")
        val urls = build.installerUrls()
        assertEquals(2, urls.size)
        assertEquals(
            "https://bmclapi2.bangbang93.com/maven/net/minecraftforge/forge/1.20.1-47.2.0/" +
                "forge-1.20.1-47.2.0-installer.jar",
            urls[0],
        )
        assertEquals(
            "https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.2.0/" +
                "forge-1.20.1-47.2.0-installer.jar",
            urls[1],
        )
    }

    @Test
    fun `sha1 sidecar urls mirror installer urls`() {
        val build = ForgePromotions.parse(promotionsJson).resolve("1.20.1")
        val sha1Urls = build.installerSha1Urls()
        assertEquals(build.installerUrls().map { "$it.sha1" }, sha1Urls)
    }

    @Test
    fun `full version keeps explicit mc prefix when promotions already carries it`() {
        val build = ForgeBuild("1.20.1", "47.2.0", ForgeChannel.RECOMMENDED)
        assertEquals("1.20.1-47.2.0", build.fullVersion)
        val prefixed = ForgeBuild("1.20.1", "1.20.1-47.2.0", ForgeChannel.RECOMMENDED)
        assertEquals("1.20.1-47.2.0", prefixed.fullVersion)
    }

    @Test
    fun `lookup accepts underscore variant for pre-release keys`() {
        val json = """{"promos":{"1.7.10_pre4-recommended":"10.13.0.1152"}}"""
        val build = ForgePromotions.parse(json).resolve("1.7.10-pre4")
        assertEquals("10.13.0.1152", build.forgeVersion)
    }
}
