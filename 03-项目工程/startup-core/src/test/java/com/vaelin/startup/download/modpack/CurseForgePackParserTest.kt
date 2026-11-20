/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.vaelin.startup.download.modpack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CurseForgePackParserTest {

    @Test
    fun `parse full manifest`() {
        val info = CurseForgePackParser.parse(
            """
            {
              "manifestType": "minecraftModpack",
              "manifestVersion": 1,
              "name": "CF Pack",
              "version": "2.0",
              "author": "someone",
              "overrides": "overrides",
              "minecraft": {
                "version": "1.20.1",
                "modLoaders": [
                  {"id": "forge-47.2.0", "primary": true},
                  {"id": "fabric-0.15.7", "primary": false}
                ]
              },
              "files": [
                {"projectID": 238222, "fileID": 5616345, "fileName": "jei-1.20.1.jar", "required": true},
                {"projectID": 238223, "fileID": 5616346, "fileName": "optional-thing.jar", "required": false},
                {"projectID": 238224, "fileID": 5616347, "url": "https://direct.test/mod.jar", "required": true}
              ]
            }
            """.trimIndent()
        )
        assertEquals(ModpackFormat.CURSEFORGE_ZIP, info.format)
        assertEquals("CF Pack", info.name)
        assertEquals("1.20.1", info.gameVersion)
        // primary 加载器 forge-47.2.0 拆成 forge + 47.2.0
        assertEquals("forge", info.loader)
        assertEquals("47.2.0", info.loaderVersion)
        assertEquals(3, info.files.size)

        val jei = info.files[0]
        assertEquals("mods/jei-1.20.1.jar", jei.relativePath)
        assertEquals("https://edge.forgecdn.net/files/5616/345/jei-1.20.1.jar", jei.urls.single())
        assertNull(jei.sha1)
        assertTrue(jei.required)

        assertTrue(!info.files[1].required)

        val direct = info.files[2]
        assertEquals("https://direct.test/mod.jar", direct.urls.single())
    }

    @Test
    fun `cdn url splits file id in thousands`() {
        assertEquals(
            "https://edge.forgecdn.net/files/1234/567/a%20b.jar",
            CurseForgePackParser.curseForgeCdnUrl(1234567, "a b.jar"),
        )
        assertEquals(
            "https://edge.forgecdn.net/files/5/67/x.jar",
            CurseForgePackParser.curseForgeCdnUrl(5067, "x.jar"),
        )
    }

    @Test
    fun `wrong manifestType rejected`() {
        try {
            CurseForgePackParser.parse(
                """{"manifestType":"other","manifestVersion":1,"name":"n","version":"v","author":"a","minecraft":{"version":"1.20.1","modLoaders":[]},"files":[]}"""
            )
            fail("应拒绝非 minecraftModpack")
        } catch (e: ModpackParseException) {
            assertTrue(e.message!!.contains("manifestType"))
        }
    }

    @Test
    fun `file without name url or resolvable id skipped`() {
        val info = CurseForgePackParser.parse(
            """
            {
              "manifestType": "minecraftModpack", "manifestVersion": 1,
              "name": "n", "version": "v", "author": "a",
              "minecraft": {"version": "1.20.1", "modLoaders": []},
              "files": [
                {"projectID": 1, "required": true}
              ]
            }
            """.trimIndent()
        )
        assertTrue(info.files.isEmpty())
    }
}
