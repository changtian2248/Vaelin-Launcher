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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("NonAsciiCharacters")
class VersionManifestTest {

    /** 结构照抄 version_manifest_v2.json，只把 URL 换成不可路由的域，测试不会真去连。 */
    private val manifestJson = """
        {
          "latest": { "release": "1.20.1", "snapshot": "23w31a" },
          "versions": [
            {
              "id": "23w31a",
              "type": "snapshot",
              "url": "https://example.invalid/23w31a.json",
              "sha1": "aaa",
              "size": 100,
              "releaseTime": "2023-08-02T12:00:00+00:00"
            },
            {
              "id": "1.20.1",
              "type": "release",
              "url": "https://example.invalid/1.20.1.json",
              "sha1": "bbb",
              "size": 200,
              "releaseTime": "2023-06-12T12:00:00+00:00"
            },
            { "id": "1.19.4", "type": "release", "url": "https://example.invalid/1.19.4.json" },
            { "type": "release", "url": "https://example.invalid/no-id.json" },
            { "id": "no-url", "type": "release" }
          ]
        }
    """.trimIndent()

    @Test
    fun `解析版本清单的 latest 指针与版本列表`() {
        val manifest = VersionManifest.parse(manifestJson)

        assertEquals("1.20.1", manifest.latestRelease)
        assertEquals("23w31a", manifest.latestSnapshot)
        // 缺 id 与缺 url 的两条脏数据应被跳过，剩下的才是 3 条
        assertEquals(3, manifest.versions.size)
        assertEquals(listOf("23w31a", "1.20.1", "1.19.4"), manifest.versions.map { it.id })
    }

    @Test
    fun `解析单个版本条目的字段`() {
        val manifest = VersionManifest.parse(manifestJson)
        val version = manifest.find("1.20.1")!!

        assertEquals("release", version.type)
        assertEquals("https://example.invalid/1.20.1.json", version.url)
        assertEquals("bbb", version.sha1)
        assertEquals(200L, version.size)
        assertEquals("2023-06-12T12:00:00+00:00", version.releaseTime)
        assertTrue(version.isRelease)
    }

    @Test
    fun `缺少 sha1 与 size 的条目退化为空值与负一`() {
        val version = VersionManifest.parse(manifestJson).find("1.19.4")!!

        assertEquals("", version.sha1)
        assertEquals(-1L, version.size)
    }

    @Test
    fun `latest 整块缺失时回退到列表首个同类型版本`() {
        val json = """
            {
              "versions": [
                { "id": "23w31a", "type": "snapshot", "url": "https://example.invalid/snap.json" },
                { "id": "1.20.1", "type": "release", "url": "https://example.invalid/rel.json" }
              ]
            }
        """.trimIndent()

        val manifest = VersionManifest.parse(json)

        // 列表顺序是新到旧且快照在前，直接取首条会把快照当成最新正式版
        assertEquals("1.20.1", manifest.latestRelease)
        assertEquals("23w31a", manifest.latestSnapshot)
    }

    @Test
    fun `不存在的版本返回 null`() {
        assertNull(VersionManifest.parse(manifestJson).find("9.9.9"))
    }

    @Test
    fun `空清单不会崩`() {
        val manifest = VersionManifest.parse("""{"versions":[]}""")

        assertEquals(0, manifest.versions.size)
        assertEquals("", manifest.latestRelease)
        assertEquals("", manifest.latestSnapshot)
    }
}
