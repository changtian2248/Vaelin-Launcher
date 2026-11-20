/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vaelin.startup.renderer

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** T1.12 换 .so 的目录优先级与两种库名形态。 */
class RendererLibPathsTest {

    private val paths = RendererLibPaths("/native/lib", "/user/renderers")

    @Test
    fun `搜索顺序 nativeLibraryDir 优先`() {
        assertEquals(listOf("/native/lib", "/user/renderers"), paths.searchDirs())
    }

    @Test
    fun `裸文件名展开成两级候选 顺序正确`() {
        assertEquals(
            listOf(
                File("/native/lib", "libgl4es_114.so").absolutePath,
                File("/user/renderers", "libgl4es_114.so").absolutePath
            ),
            paths.resolve("libgl4es_114.so")
        )
    }

    @Test
    fun `以斜杠开头的绝对路径原样保留`() {
        val abs = "/data/app/plugin/lib/libgl.so"
        assertEquals(listOf(abs), paths.resolve(abs))
    }

    @Test
    fun `平台绝对路径原样保留`() {
        val abs = File(File.separator, "libs").resolve("libgl.so").absolutePath
        assertEquals(listOf(abs), paths.resolve(abs))
    }

    @Test
    fun `空声明不产生候选`() {
        assertEquals(emptyList<String>(), paths.resolve(""))
        assertEquals(emptyList<String>(), paths.resolve("   "))
    }

    @Test
    fun `displayName 去掉目录`() {
        assertEquals("libgl4es_114.so", paths.displayName("/a/b/libgl4es_114.so"))
        assertEquals("libgl4es_114.so", paths.displayName("libgl4es_114.so"))
    }
}
