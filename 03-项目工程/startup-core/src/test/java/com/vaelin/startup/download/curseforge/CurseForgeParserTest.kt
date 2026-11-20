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

import com.vaelin.startup.download.Json
import com.vaelin.startup.download.source.AddonKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CurseForgeParserTest {

    @Test
    fun `parse search with data wrapper and pagination`() {
        val doc = Json.parse(
            """
            {
              "data": [
                {
                  "id": 238222,
                  "gameId": 432,
                  "name": "Just Enough Items (JEI)",
                  "slug": "jei",
                  "summary": "JEI is an item and recipe viewing mod.",
                  "downloadCount": 300000000,
                  "authors": [{"name": "mezz", "url": "https://www.curseforge.com/members/mezz"}],
                  "logo": {"url": "https://media.forgecdn.net/avatars/xxx.png"},
                  "categories": [{"name": "Utility"}],
                  "classId": 6,
                  "dateModified": "2024-05-01T00:00:00Z"
                }
              ],
              "pagination": {"index": 0, "pageSize": 20, "resultCount": 1, "totalCount": 12345}
            }
            """.trimIndent()
        )
        val page = CurseForgeParser.parseSearch(doc)
        assertEquals(12345, page.totalHits)
        assertEquals(20, page.limit)
        assertTrue(page.hasMore())

        val item = page.items.single()
        assertEquals("curseforge", item.sourceId)
        assertEquals("238222", item.projectId)
        assertEquals("Just Enough Items (JEI)", item.name)
        assertEquals("mezz", item.author)
        assertEquals(AddonKind.MOD, item.kind)
        assertEquals(300_000_000L, item.downloads)
        assertEquals(0L, item.follows)
        assertEquals("https://media.forgecdn.net/avatars/xxx.png", item.iconUrl)
        assertTrue(item.gameVersions.isEmpty()) // search 不直接给游戏版本
    }

    @Test
    fun `parse project detail`() {
        val doc = Json.parse(
            """
            {
              "data": {
                "id": 238222,
                "name": "JEI",
                "slug": "jei",
                "summary": "s",
                "downloadCount": 1,
                "authors": [{"name": "mezz"}],
                "classId": 6552,
                "dateModified": "2024-01-01T00:00:00Z"
              }
            }
            """.trimIndent()
        )
        val project = CurseForgeParser.parseProject(doc)
        assertEquals(AddonKind.SHADER, project.kind) // classId 6552 → SHADER
        assertEquals("238222", project.projectId)
    }

    @Test
    fun `parse files with releaseType hashes loaders and empty downloadUrl`() {
        val doc = Json.parse(
            """
            {
              "data": [
                {
                  "id": 5616345,
                  "modId": 238222,
                  "displayName": "jei-1.20.1-15.3.0.4",
                  "fileName": "jei-1.20.1-15.3.0.4.jar",
                  "releaseType": 1,
                  "fileLength": 1234567,
                  "fileDate": "2024-04-01T00:00:00Z",
                  "downloadCount": 1000,
                  "downloadUrl": null,
                  "gameVersions": ["1.20.1", "Forge", "NeoForge"],
                  "hashes": [
                    {"value": "ABCDEF", "algo": 2},
                    {"value": "0123456789ABCDEF", "algo": 1}
                  ]
                },
                {
                  "id": 5616346,
                  "modId": 238222,
                  "displayName": "beta build",
                  "fileName": "jei-beta.jar",
                  "releaseType": 2,
                  "fileLength": 10,
                  "downloadUrl": "https://edge.forgecdn.net/files/5616/346/jei-beta.jar",
                  "gameVersions": ["1.19.2", "Fabric"],
                  "hashes": []
                }
              ]
            }
            """.trimIndent()
        )
        val versions = CurseForgeParser.parseFiles(doc, "238222")
        assertEquals(2, versions.size)

        val release = versions[0]
        assertEquals("5616345", release.versionId)
        assertEquals("release", release.releaseType)
        assertEquals(listOf("1.20.1"), release.gameVersions) // 加载器条目被剥离
        assertEquals(listOf("forge", "neoforge"), release.loaders)
        assertEquals("0123456789abcdef", release.files.single().sha1) // algo=1 才取
        assertEquals("", release.files.single().downloadUrl) // null → 空串，编排层补全
        assertEquals(1234567L, release.files.single().sizeBytes)

        val beta = versions[1]
        assertEquals("beta", beta.releaseType)
        assertEquals(listOf("fabric"), beta.loaders)
        assertEquals(
            "https://edge.forgecdn.net/files/5616/346/jei-beta.jar",
            beta.files.single().downloadUrl,
        )
        // primaryFile 无 primary 标记时回退首文件
        assertEquals("jei-beta.jar", beta.primaryFile()?.filename)
    }

    @Test
    fun `parse download url wrapper`() {
        assertEquals(
            "https://edge.forgecdn.net/files/1/2/x.jar",
            CurseForgeParser.parseDownloadUrl(Json.parse("""{"data":"https://edge.forgecdn.net/files/1/2/x.jar"}""")),
        )
        assertNull(CurseForgeParser.parseDownloadUrl(Json.parse("""{}""")))
    }
}
