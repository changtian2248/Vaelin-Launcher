/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.vaelin.startup.launch

import com.vaelin.startup.download.VersionJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@Suppress("NonAsciiCharacters")
class LwjglSelectionTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun versionJson(vararg libraryNames: String): VersionJson {
        val libs = libraryNames.joinToString(",") { """{"name":"$it"}""" }
        return VersionJson.parse("""{"id":"x","mainClass":"Main","libraries":[$libs]}""")
    }

    // ---- detectVersion ----

    @Test
    fun `探测 org_lwjgl 坐标得到版本整数`() {
        assertEquals(331, LwjglSelection.detectVersion(versionJson("org.lwjgl:lwjgl:3.3.1")))
        assertEquals(333, LwjglSelection.detectVersion(versionJson("org.lwjgl:lwjgl:3.3.3")))
        assertEquals(341, LwjglSelection.detectVersion(versionJson("org.lwjgl:lwjgl:3.4.1")))
        assertEquals(299, LwjglSelection.detectVersion(versionJson("org.lwjgl.lwjgl:lwjgl:2.9.9")))
    }

    @Test
    fun `探测按库清单顺序取首个命中`() {
        assertEquals(
            331,
            LwjglSelection.detectVersion(
                versionJson("org.lwjgl:lwjgl:3.3.1", "org.lwjgl.lwjgl:lwjgl:2.9.9"),
            ),
        )
        assertEquals(
            299,
            LwjglSelection.detectVersion(
                versionJson("org.lwjgl.lwjgl:lwjgl:2.9.9", "org.lwjgl:lwjgl:3.3.1"),
            ),
        )
    }

    @Test
    fun `非 lwjgl 主坐标与子模块坐标不参与探测`() {
        // lwjgl-glfw 等子模块不带主版本语义，不能当成探测命中
        assertEquals(0, LwjglSelection.detectVersion(versionJson("org.lwjgl:lwjgl-glfw:3.3.1")))
        assertEquals(0, LwjglSelection.detectVersion(versionJson("com.example:plain:3.3.1")))
        assertEquals(0, LwjglSelection.detectVersion(versionJson()))
        assertEquals(0, LwjglSelection.detectVersion(versionJson("com.example:a:1", "com.example:b:2")))
    }

    // ---- versionDir ----

    @Test
    fun `版本到组件目录的映射与 ZL2 lwjglVersionDir 一致`() {
        assertEquals("lwjgl/3.4.1", LwjglSelection.versionDir(341))
        assertEquals("lwjgl/3.4.1", LwjglSelection.versionDir(342))
        assertEquals("lwjgl/3.3.3", LwjglSelection.versionDir(333))
        assertEquals("lwjgl/3.3.3", LwjglSelection.versionDir(299))
        assertEquals("lwjgl/3.3.3", LwjglSelection.versionDir(0))
    }

    // ---- classpathJars ----

    /** 建一个与 ZL2 3.3.3 组件同构的目录：jar + manifest.json（非 jar）+ natives 子目录。 */
    private fun buildComponentDir(detected: Int): File {
        val dir = File(temp.root, LwjglSelection.versionDir(detected))
        dir.mkdirs()
        File(dir, "lwjgl.jar").writeText("core")
        File(dir, "lwjgl-3.3.3-merged-modules.jar").writeText("merged")
        File(dir, "lwjgl-openal.jar").writeText("openal")
        File(dir, "lwjgl-lwjglx.jar").writeText("lwjglx-bridge")
        File(dir, "manifest.json").writeText("{}")
        val nativesDir = File(File(dir, "natives"), "arm64-v8a")
        nativesDir.mkdirs()
        File(nativesDir, "liblwjgl.so").writeText("so")
        return dir
    }

    @Test
    fun `LWJGL3 按 lwjgl_merged_其余排序且剔除 lwjglx`() {
        buildComponentDir(detected = 331)

        val jars = LwjglSelection.classpathJars(331, temp.root)

        assertEquals(
            listOf("lwjgl.jar", "lwjgl-3.3.3-merged-modules.jar", "lwjgl-openal.jar"),
            jars.map { it.name },
        )
    }

    @Test
    fun `LWJGL2 保留 lwjglx 桥接层且排在最后`() {
        buildComponentDir(detected = 299)

        val jars = LwjglSelection.classpathJars(299, temp.root)

        assertEquals(
            listOf("lwjgl.jar", "lwjgl-3.3.3-merged-modules.jar", "lwjgl-openal.jar", "lwjgl-lwjglx.jar"),
            jars.map { it.name },
        )
    }

    @Test
    fun `组件目录为空或不存在时显式报错`() {
        val empty = File(temp.root, "lwjgl/3.3.3").apply { mkdirs() }

        val emptyError = assertThrows(LaunchException::class.java) {
            LwjglSelection.classpathJars(333, temp.root)
        }
        assertTrue(emptyError.message!!.contains(empty.absolutePath))
        assertTrue(emptyError.message!!.contains("没有 jar"))

        val missingError = assertThrows(LaunchException::class.java) {
            LwjglSelection.classpathJars(341, temp.root)
        }
        assertTrue(missingError.message!!.contains("没有 jar"))
    }

    // ---- choose ----

    @Test
    fun `choose 端到端选出目录与有序 jar`() {
        buildComponentDir(detected = 331)

        val choice = LwjglSelection.choose(versionJson("org.lwjgl:lwjgl:3.3.1"), temp.root)

        assertEquals("lwjgl/3.3.3", choice.componentDir)
        assertEquals(
            listOf("lwjgl.jar", "lwjgl-3.3.3-merged-modules.jar", "lwjgl-openal.jar"),
            choice.classpathJars.map { it.name },
        )
    }

    @Test
    fun `choose 对 3_4_1 版本选 3_4_1 组件目录`() {
        val dir = File(temp.root, "lwjgl/3.4.1")
        dir.mkdirs()
        File(dir, "lwjgl.jar").writeText("core")
        File(dir, "lwjgl-3.4.1-merged-modules.jar").writeText("merged")

        val choice = LwjglSelection.choose(versionJson("org.lwjgl:lwjgl:3.4.1"), temp.root)

        assertEquals("lwjgl/3.4.1", choice.componentDir)
        assertEquals(
            listOf("lwjgl.jar", "lwjgl-3.4.1-merged-modules.jar"),
            choice.classpathJars.map { it.name },
        )
    }
}
