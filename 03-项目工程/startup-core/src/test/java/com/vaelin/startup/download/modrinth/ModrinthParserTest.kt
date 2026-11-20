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

import com.vaelin.startup.download.Json
import com.vaelin.startup.download.source.AddonKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModrinthParserTest {

    @Test
    fun `parse search page`() {
        val doc = Json.parse(
            """
            {
              "hits": [
                {
                  "project_id": "AANobbMI",
                  "project_type": "mod",
                  "slug": "sodium",
                  "author": "jellysquid3",
                  "title": "Sodium",
                  "description": "A modern rendering engine for Minecraft.",
                  "categories": ["optimization", "client"],
                  "versions": ["1.20.1", "1.20.4"],
                  "downloads": 50000000,
                  "follows": 25000,
                  "icon_url": "https://cdn.modrinth.com/data/AANobbMI/icon.png",
                  "date_modified": "2024-05-01T12:34:56.789Z",
                  "client_side": "required",
                  "server_side": "unsupported"
                }
              ],
              "offset": 0,
              "limit": 20,
              "total_hits": 1
            }
            """.trimIndent()
        )
        val page = ModrinthParser.parseSearch(doc)
        assertEquals(1, page.totalHits)
        assertTrue(page.hasMore().not())

        val hit = page.items.single()
        assertEquals("modrinth", hit.sourceId)
        assertEquals("AANobbMI", hit.projectId)
        assertEquals("sodium", hit.slug)
        assertEquals("Sodium", hit.name)
        assertEquals("jellysquid3", hit.author)
        assertEquals(AddonKind.MOD, hit.kind)
        assertEquals(listOf("optimization", "client"), hit.categories)
        assertEquals(listOf("1.20.1", "1.20.4"), hit.gameVersions)
        assertEquals(50_000_000L, hit.downloads)
        assertEquals(1714566896789L, hit.updatedMillis)
        assertEquals("required", hit.clientSide)
    }

    @Test
    fun `parse project detail`() {
        val doc = Json.parse(
            """
            {
              "id": "AANobbMI",
              "slug": "sodium",
              "title": "Sodium",
              "description": "A modern rendering engine.",
              "project_type": "mod",
              "body": "...long markdown...",
              "categories": ["optimization"],
              "additional_categories": [],
              "game_versions": ["1.20.1"],
              "loaders": ["fabric", "quilt"],
              "downloads": 50000000,
              "follows": 25000,
              "icon_url": null,
              "client_side": "required",
              "server_side": "unsupported",
              "updated": "2024-05-01T00:00:00Z",
              "published": "2021-01-01T00:00:00Z",
              "status": "approved",
              "license": {"id": "LGPL-3.0", "name": "LGPL v3", "url": null},
              "team": "abc"
            }
            """.trimIndent()
        )
        val project = ModrinthParser.parseProject(doc)
        assertEquals("AANobbMI", project.projectId)
        assertEquals("", project.author) // v2 详情无作者字段，留空不虚构
        assertEquals(listOf("fabric", "quilt"), project.loaders)
        assertEquals(listOf("1.20.1"), project.gameVersions)
        assertNull(project.iconUrl)
        assertEquals(50000000L, project.downloads)
    }

    @Test
    fun `parse versions with files and dependencies`() {
        val doc = Json.parse(
            """
            [
              {
                "id": "vx1Rw0qZ",
                "project_id": "AANobbMI",
                "name": "Sodium 0.5.8",
                "version_number": "mc1.20.1-0.5.8",
                "version_type": "release",
                "status": "listed",
                "featured": true,
                "game_versions": ["1.20.1"],
                "loaders": ["fabric"],
                "date_published": "2024-04-01T00:00:00Z",
                "downloads": 1000000,
                "dependencies": [
                  {"version_id": null, "project_id": "P7dR8mSH", "file_name": null, "dependency_type": "required"}
                ],
                "files": [
                  {
                    "hashes": {"sha1": "AAAABBBBCCCCDDDD", "sha512": "ffff"},
                    "url": "https://cdn.modrinth.com/data/AANobbMI/versions/vx1/sodium.jar",
                    "filename": "sodium-fabric-0.5.8.jar",
                    "primary": true,
                    "size": 1234567,
                    "file_type": "jar"
                  }
                ]
              },
              {
                "id": "noProject",
                "name": "broken entry without project_id"
              }
            ]
            """.trimIndent()
        )
        val versions = ModrinthParser.parseVersions(doc)
        assertEquals(1, versions.size) // 缺 project_id 的坏项被丢弃

        val v = versions.single()
        assertEquals("vx1Rw0qZ", v.versionId)
        assertEquals("mc1.20.1-0.5.8", v.versionNumber)
        assertEquals("release", v.releaseType)
        assertTrue(v.featured)
        assertEquals(1, v.dependencies.size)
        assertEquals("required", v.dependencies.single().type)
        assertEquals("P7dR8mSH", v.dependencies.single().projectId)

        val file = v.primaryFile()!!
        assertEquals("sodium-fabric-0.5.8.jar", file.filename)
        assertEquals(1234567L, file.sizeBytes)
        assertEquals("aaaabbbbccccdddd", file.sha1) // 统一小写
        assertEquals("https://cdn.modrinth.com/data/AANobbMI/versions/vx1/sodium.jar", file.downloadUrl)
        assertTrue(file.primary)
    }

    @Test
    fun `kind mapping round trip`() {
        assertEquals("mod", AddonKind.MOD.toModrinthProjectType())
        assertEquals("shader", AddonKind.SHADER.toModrinthProjectType())
        assertEquals("resourcepack", AddonKind.RESOURCE_PACK.toModrinthProjectType())
        assertEquals(AddonKind.SHADER, modrinthProjectTypeToKind("shader"))
        assertEquals(AddonKind.RESOURCE_PACK, modrinthProjectTypeToKind("resourcepack"))
        assertEquals(AddonKind.MOD, modrinthProjectTypeToKind("unknown-type"))
    }
}
