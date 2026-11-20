/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * T1.5.5 —— installer 产物校验逻辑单测（纯 JVM）：好 / 坏 JSON、缺文件、扫描兜底。
 */

package com.vaelin.startup.install

import com.vaelin.startup.jvm.expectThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ForgeArtifactVerifierTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val dirName = "1.20.1-forge-47.2.0"

    /** 形态对齐 Forge installer 写出的版本 JSON（无 mainClass，靠 inheritsFrom 继承）。 */
    private fun forgeJson(inheritsFrom: String?, id: String = dirName): String {
        val inherits = inheritsFrom?.let { "\"inheritsFrom\": \"$it\"," } ?: ""
        return """
            {
              "id": "$id",
              $inherits
              "time": "2023-09-01T10:00:00+00:00",
              "releaseTime": "2023-09-01T10:00:00+00:00",
              "type": "release",
              "libraries": [],
              "arguments": { "game": [], "jvm": [] }
            }
        """.trimIndent()
    }

    private fun installArtifact(gameDir: File, name: String = dirName, json: String? = forgeJson("1.20.1"), withStubJar: Boolean = true) {
        val dir = File(gameDir, "versions/$name")
        assertTrue(dir.mkdirs() || dir.isDirectory)
        json?.let { File(dir, "$name.json").writeText(it) }
        if (withStubJar) {
            File(dir, "$name.jar").writeBytes(byteArrayOf(0x50, 0x4b))
        }
    }

    @Test
    fun `good artifact passes verification`() {
        val gameDir = temp.newFolder("good")
        installArtifact(gameDir)

        val artifact = ForgeArtifactVerifier.verify(gameDir, "1.20.1", "47.2.0")

        assertEquals(dirName, artifact.forgeId)
        assertEquals(File(gameDir, "versions/$dirName"), artifact.versionDir)
        assertEquals(File(gameDir, "versions/$dirName/$dirName.json"), artifact.versionJsonFile)
        assertEquals(File(gameDir, "versions/$dirName/$dirName.jar"), artifact.stubJarFile)
    }

    @Test
    fun `json without inheritsFrom fails`() {
        val gameDir = temp.newFolder("no-inherits")
        // 无 inheritsFrom 也无 mainClass：VersionJson.parse 直接拒绝
        installArtifact(gameDir, json = forgeJson(inheritsFrom = null))

        val e = expectThrows<ForgeInstallException> {
            ForgeArtifactVerifier.verify(gameDir, "1.20.1", "47.2.0")
        }
        assertTrue(e.message!!.contains("解析失败"))
    }

    @Test
    fun `json pointing at another mc version fails`() {
        val gameDir = temp.newFolder("wrong-inherits")
        installArtifact(gameDir, json = forgeJson("1.20.2"))

        val e = expectThrows<ForgeInstallException> {
            ForgeArtifactVerifier.verify(gameDir, "1.20.1", "47.2.0")
        }
        assertTrue(e.message!!.contains("inheritsFrom='1.20.2'"))
    }

    @Test
    fun `missing stub jar fails`() {
        val gameDir = temp.newFolder("no-jar")
        installArtifact(gameDir, withStubJar = false)

        val e = expectThrows<ForgeInstallException> {
            ForgeArtifactVerifier.verify(gameDir, "1.20.1", "47.2.0")
        }
        assertTrue(e.message!!.contains("桩 jar"))
    }

    @Test
    fun `missing version json fails`() {
        val gameDir = temp.newFolder("no-json")
        installArtifact(gameDir, json = null)

        val e = expectThrows<ForgeInstallException> {
            ForgeArtifactVerifier.verify(gameDir, "1.20.1", "47.2.0")
        }
        assertTrue(e.message!!.contains("缺少"))
    }

    @Test
    fun `empty versions dir fails with explicit message`() {
        val gameDir = temp.newFolder("empty")
        File(gameDir, "versions").mkdirs()

        val e = expectThrows<ForgeInstallException> {
            ForgeArtifactVerifier.verify(gameDir, "1.20.1", "47.2.0")
        }
        assertTrue(e.message!!.contains("1.20.1-forge-47.2.0"))
    }

    @Test
    fun `scan fallback accepts differently named dir with matching json id`() {
        val gameDir = temp.newFolder("scan")
        val altName = "forge-1.20.1-47.2.0"
        installArtifact(gameDir, name = altName, json = forgeJson("1.20.1", id = dirName))

        val artifact = ForgeArtifactVerifier.verify(gameDir, "1.20.1", "47.2.0")
        assertEquals(altName, artifact.forgeId)
    }

    @Test
    fun `scan fallback rejects dir whose id belongs to another forge build`() {
        val gameDir = temp.newFolder("other-build")
        // 目录名与 JSON id 都指向别的 Forge 构建：精确名与扫描规则都不该放过它
        installArtifact(
            gameDir,
            name = "1.20.1-forge-47.1.3",
            json = forgeJson("1.20.1", id = "1.20.1-forge-47.1.3"),
        )

        val e = expectThrows<ForgeInstallException> {
            ForgeArtifactVerifier.verify(gameDir, "1.20.1", "47.2.0")
        }
        assertTrue(e.message!!.contains("47.1.3"))
    }

    @Test
    fun `expected dir name format`() {
        assertEquals("1.20.1-forge-47.2.0", ForgeArtifactVerifier.expectedDirName("1.20.1", "47.2.0"))
    }
}
