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

class MrpackParserTest {

    @Test
    fun `parse full manifest`() {
        val info = MrpackParser.parse(
            """
            {
              "formatVersion": 1,
              "game": "minecraft",
              "versionId": "1.0.0",
              "name": "测试整合包",
              "summary": "一个测试包",
              "files": [
                {
                  "path": "mods/sodium.jar",
                  "hashes": {"sha1": "AAAA", "sha512": "BBBB"},
                  "env": {"client": "required", "server": "required"},
                  "downloads": ["https://cdn.test/sodium.jar"],
                  "fileSize": 1000
                },
                {
                  "path": "mods/server-only.jar",
                  "hashes": {"sha1": "CCCC", "sha512": "DDDD"},
                  "env": {"client": "unsupported", "server": "required"},
                  "downloads": ["https://cdn.test/server-only.jar"],
                  "fileSize": 500
                },
                {
                  "path": "config/xxx.cfg",
                  "hashes": {"sha1": "EEEE", "sha512": "FFFF"},
                  "downloads": ["https://cdn.test/xxx.cfg"],
                  "fileSize": 10
                }
              ],
              "dependencies": {
                "minecraft": "1.20.1",
                "fabric-loader": "0.15.7"
              }
            }
            """.trimIndent()
        )
        assertEquals(ModpackFormat.MRPACK, info.format)
        assertEquals("测试整合包", info.name)
        assertEquals("1.0.0", info.version)
        assertEquals("1.20.1", info.gameVersion)
        assertEquals("fabric", info.loader)
        assertEquals("0.15.7", info.loaderVersion)
        // env.client=unsupported 的文件被跳过
        assertEquals(2, info.files.size)
        assertEquals("mods/sodium.jar", info.files[0].relativePath)
        assertEquals("aaaa", info.files[0].sha1)
        assertEquals("config/xxx.cfg", info.files[1].relativePath)
    }

    @Test
    fun `forge dependency maps to forge loader`() {
        val info = MrpackParser.parse(
            """
            {
              "formatVersion": 1, "game": "minecraft", "versionId": "v", "name": "n",
              "files": [],
              "dependencies": {"minecraft": "1.20.1", "forge": "47.2.0"}
            }
            """.trimIndent()
        )
        assertEquals("forge", info.loader)
        assertEquals("47.2.0", info.loaderVersion)
    }

    @Test
    fun `missing minecraft dependency rejected`() {
        try {
            MrpackParser.parse(
                """{"formatVersion":1,"game":"minecraft","versionId":"v","name":"n","files":[],"dependencies":{}}"""
            )
            fail("应抛 ModpackParseException")
        } catch (e: ModpackParseException) {
            assertTrue(e.message!!.contains("minecraft"))
        }
    }

    @Test
    fun `unsupported formatVersion rejected`() {
        try {
            MrpackParser.parse(
                """{"formatVersion":2,"game":"minecraft","versionId":"v","name":"n","files":[],"dependencies":{"minecraft":"1.20.1"}}"""
            )
            fail("应拒绝 formatVersion=2")
        } catch (e: ModpackParseException) {
            assertTrue(e.message!!.contains("formatVersion"))
        }
    }

    @Test
    fun `unsafe path skipped and no-download file skipped`() {
        val info = MrpackParser.parse(
            """
            {
              "formatVersion": 1, "game": "minecraft", "versionId": "v", "name": "n",
              "files": [
                {"path": "../evil.jar", "hashes": {"sha1": "A", "sha512": "B"},
                 "downloads": ["https://x.test/e.jar"], "fileSize": 1},
                {"path": "mods/no-url.jar", "hashes": {"sha1": "C", "sha512": "D"},
                 "downloads": [], "fileSize": 1}
              ],
              "dependencies": {"minecraft": "1.20.1"}
            }
            """.trimIndent()
        )
        assertTrue(info.files.isEmpty())
    }
}
