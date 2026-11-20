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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("NonAsciiCharacters")
class VersionJsonTest {

    /** 结构按 1.20.1 的真实 version json 裁剪，保留下载链路真正读取的字段。 */
    private val versionJson = """
        {
          "id": "1.20.1",
          "type": "release",
          "mainClass": "net.minecraft.client.main.Main",
          "assets": "5",
          "assetIndex": {
            "id": "5",
            "url": "https://example.invalid/indexes/5.json",
            "sha1": "indexsha",
            "size": 123456,
            "totalSize": 789012
          },
          "downloads": {
            "client": {
              "sha1": "clientsha",
              "size": 25000000,
              "url": "https://example.invalid/client.jar"
            }
          },
          "javaVersion": {
            "component": "java-runtime-gamma",
            "majorVersion": 17,
            "version": "17.0.8"
          },
          "libraries": [
            {
              "name": "com.example:plain:1.0",
              "downloads": {
                "artifact": {
                  "path": "com/example/plain/1.0/plain-1.0.jar",
                  "sha1": "psha",
                  "size": 10,
                  "url": "https://example.invalid/plain.jar"
                }
              }
            },
            {
              "name": "org.lwjgl:lwjgl:3.3.1",
              "downloads": {
                "artifact": {
                  "path": "org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1.jar",
                  "sha1": "lsha",
                  "size": 20,
                  "url": "https://example.invalid/lwjgl.jar"
                },
                "classifiers": {
                  "natives-linux-arm64": {
                    "path": "org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-natives-linux-arm64.jar",
                    "sha1": "nsha",
                    "size": 30,
                    "url": "https://example.invalid/lwjgl-natives.jar"
                  }
                }
              },
              "natives": { "linux": "natives-linux-${'$'}{arch}" },
              "rules": [ { "action": "allow", "os": { "name": "linux" } } ]
            },
            {
              "name": "org.lwjgl:lwjgl:3.3.1",
              "natives": { "osx": "natives-macos" },
              "rules": [ { "action": "allow", "os": { "name": "osx" } } ]
            },
            {
              "name": "com.example:no-downloads:1.0"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `解析主类、资源索引、客户端与 Java 版本`() {
        val version = VersionJson.parse(versionJson)

        assertEquals("1.20.1", version.id)
        assertEquals("release", version.type)
        assertEquals("net.minecraft.client.main.Main", version.mainClass)
        assertEquals("5", version.assets)

        val assetIndex = version.assetIndex!!
        assertEquals("5", assetIndex.id)
        assertEquals("https://example.invalid/indexes/5.json", assetIndex.url)
        assertEquals("indexsha", assetIndex.sha1)
        assertEquals(123456L, assetIndex.size)
        assertEquals(789012L, assetIndex.totalSize)

        val client = version.client!!
        assertEquals("https://example.invalid/client.jar", client.url)
        assertEquals("clientsha", client.sha1)
        assertEquals(25000000L, client.size)

        val java = version.javaVersion!!
        assertEquals("java-runtime-gamma", java.component)
        assertEquals(17, java.majorVersion)
        assertEquals("17.0.8", java.version)
    }

    @Test
    fun `解析 libraries 的 artifact、classifiers 与 natives 模板`() {
        val version = VersionJson.parse(versionJson)

        assertEquals(4, version.libraries.size)

        val plain = version.libraries[0]
        assertEquals("com/example/plain/1.0/plain-1.0.jar", plain.downloads?.artifact?.path)
        assertTrue(plain.rules.isEmpty())

        val lwjgl = version.libraries[1]
        assertEquals("natives-linux-\${arch}", lwjgl.natives["linux"])
        assertNotNull(lwjgl.downloads?.classifiers?.get("natives-linux-arm64"))
        assertEquals(1, lwjgl.rules.size)
        assertEquals("allow", lwjgl.rules.single().action)
        assertEquals("linux", lwjgl.rules.single().osName)
        assertTrue(lwjgl.hasNatives)
    }

    @Test
    fun `没有 downloads 的库其 downloads 为 null`() {
        val version = VersionJson.parse(versionJson)
        val noDownloads = version.libraries.last()

        assertNull(noDownloads.downloads)
        assertTrue(!noDownloads.hasNatives)
    }

    @Test
    fun `缺少 id 时抛异常`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            VersionJson.parse("""{ "mainClass": "net.minecraft.client.main.Main" }""")
        }

        assertTrue("实际消息：${error.message}", error.message!!.contains("id"))
    }

    @Test
    fun `缺少 mainClass 时抛异常`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            VersionJson.parse("""{ "id": "1.20.1" }""")
        }

        assertTrue("实际消息：${error.message}", error.message!!.contains("mainClass"))
    }

    @Test
    fun `缺少 javaVersion 时为 null`() {
        val version = VersionJson.parse(
            """{ "id": "1.20.1", "mainClass": "net.minecraft.client.main.Main" }"""
        )

        assertNull(version.javaVersion)
        assertNull(version.assetIndex)
        assertNull(version.client)
        assertEquals(0, version.libraries.size)
    }

    @Test
    fun `带 inheritsFrom 的模组版 JSON 允许缺 mainClass 并解析出 jar 字段`() {
        val version = VersionJson.parse(
            """{ "id": "1.20.1-forge-47.2.0", "inheritsFrom": "1.20.1", "jar": "1.20.1" }"""
        )

        assertNull(version.mainClass)
        assertEquals("1.20.1", version.inheritsFrom)
        assertEquals("1.20.1", version.jar)
    }
}
