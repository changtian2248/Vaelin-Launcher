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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@Suppress("NonAsciiCharacters")
class VersionDownloadPlannerTest {

    @get:Rule
    val temp = TemporaryFolder()

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
            "size": 100
          },
          "downloads": {
            "client": { "url": "https://example.invalid/client.jar", "sha1": "csha", "size": 1000 }
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
              "natives": { "linux": "natives-linux-${'$'}{arch}" }
            },
            {
              "name": "com.example:legacy:2.0"
            }
          ]
        }
    """.trimIndent()

    private fun planOf(
        gameDir: File,
        text: String = versionJson,
        abi: String = "arm64-v8a",
    ): DownloadPlan = VersionDownloadPlanner(gameDir, abi).plan(VersionJson.parse(text))

    /** 目标路径统一成 `/` 分隔再断言，避免测试在 Windows 与 Linux 上写法不同。 */
    private fun DownloadPlan.relativeTargets(gameDir: File): List<String> =
        requests.map { it.target.relativeTo(gameDir).invariantSeparatorsPath }

    @Test
    fun `生成客户端 jar、资源索引与库任务`() {
        val gameDir = File(temp.root, "game")
        val plan = planOf(gameDir)

        val targets = plan.relativeTargets(gameDir)
        assertTrue(targets.toString(), targets.contains("versions/1.20.1/1.20.1.jar"))
        assertTrue(targets.toString(), targets.contains("assets/indexes/5.json"))
        assertTrue(targets.toString(), targets.contains("libraries/com/example/plain/1.0/plain-1.0.jar"))

        assertEquals("versions/1.20.1/1.20.1.json", plan.versionJsonFile.relativeTo(gameDir).invariantSeparatorsPath)
        assertEquals("versions/1.20.1/natives", plan.nativesDir.relativeTo(gameDir).invariantSeparatorsPath)
        assertEquals(1000L + 100L + 10L + 20L + 30L, plan.totalExpectedBytes)
    }

    @Test
    fun `native 包被登记到解压列表`() {
        val gameDir = File(temp.root, "game")
        val plan = planOf(gameDir)

        val native = plan.natives.single()
        assertEquals("org.lwjgl:lwjgl:3.3.1", native.libraryName)
        assertEquals("natives-linux-arm64", native.classifier)
        assertEquals(
            "libraries/org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-natives-linux-arm64.jar",
            native.jarFile.relativeTo(gameDir).invariantSeparatorsPath,
        )
        assertEquals(plan.nativesDir, native.extractDir)
    }

    @Test
    fun `非本机架构的 native 包被跳过并记录原因`() {
        val gameDir = File(temp.root, "game")

        // 同一份 json 按 x86_64 规划：arm64 分类器不该被选中
        val plan = planOf(gameDir, abi = "x86_64")

        assertTrue(plan.natives.isEmpty())
        val skipped = plan.skippedLibraries.single { it.name == "org.lwjgl:lwjgl:3.3.1" }
        assertTrue(skipped.reason, skipped.reason.contains("native"))
    }

    @Test
    fun `没有下载地址的库用 maven 坐标兜底`() {
        val gameDir = File(temp.root, "game")
        val plan = planOf(gameDir)

        val legacy = plan.requests.single { it.target.name == "legacy-2.0.jar" }
        assertEquals(
            "https://libraries.minecraft.net/com/example/legacy/2.0/legacy-2.0.jar",
            legacy.url,
        )
        assertEquals("", legacy.expectedSha1.orEmpty())
        assertEquals(-1L, legacy.expectedSize)
    }

    @Test
    fun `重复目标只保留一个任务`() {
        val gameDir = File(temp.root, "game")
        val duplicated = """
            {
              "id": "1.20.1",
              "mainClass": "net.minecraft.client.main.Main",
              "libraries": [
                {
                  "name": "com.example:plain:1.0",
                  "downloads": {
                    "artifact": {
                      "path": "com/example/plain/1.0/plain-1.0.jar",
                      "url": "https://example.invalid/plain.jar",
                      "sha1": "psha",
                      "size": 10
                    }
                  }
                },
                {
                  "name": "com.example:plain:1.0",
                  "downloads": {
                    "artifact": {
                      "path": "com/example/plain/1.0/plain-1.0.jar",
                      "url": "https://example.invalid/plain.jar",
                      "sha1": "psha",
                      "size": 10
                    }
                  }
                }
              ]
            }
        """.trimIndent()

        val plan = planOf(gameDir, duplicated)

        assertEquals(1, plan.requests.count { it.target.name == "plain-1.0.jar" })
    }

    @Test
    fun `没有 client 与 assetIndex 时对应字段为 null`() {
        val gameDir = File(temp.root, "game")
        val plan = planOf(
            gameDir,
            """{ "id": "1.20.1", "mainClass": "net.minecraft.client.main.Main" }""",
        )

        assertTrue(plan.requests.isEmpty())
        assertEquals(null, plan.clientJarFile)
        assertEquals(null, plan.assetIndexFile)
        assertNotNull(plan.versionJsonFile)
    }
}
