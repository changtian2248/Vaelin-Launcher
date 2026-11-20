/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vaelin.startup.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * T1.12 显式失败：凭空造不存在的库名，加载必须报错、不能静默跳过。
 * 全部用注入的 dlopen / fileExists，零 native、零网络。
 * 期望路径用 File(...).absolutePath 计算，避免 Windows/Unix 分隔符差异。
 */
class RendererLibraryLoaderTest {

    private val nativeDir = "/opt/vaelin/lib/arm64"
    private val userDir = "/opt/vaelin/files/renderers"
    private val libPaths = RendererLibPaths(nativeDir, userDir)
    private val nativeLib = File(nativeDir, "libgl4es_114.so").absolutePath
    private val userLib = File(userDir, "libgl4es_114.so").absolutePath

    private fun renderer(
        id: String = "opengles2",
        lib: String = "libgl4es_114.so",
        extras: List<String> = emptyList()
    ): RendererInterface = object : RendererInterface {
        override fun getRendererId() = id
        override fun getUniqueIdentifier() = "test-$id"
        override fun getRendererName() = "测试渲染器"
        override fun getRendererEnv(): Lazy<Map<String, String>> = lazyOf(emptyMap())
        override fun getDlopenLibrary(): Lazy<List<String>> = lazyOf(extras)
        override fun getRendererLibrary() = lib
    }

    @Test
    fun `库不存在时抛 RendererLoadException 且列出已查找路径`() {
        val loader = RendererLibraryLoader(
            libPaths = RendererLibPaths("/n", "/u"),
            dlopen = { true },
            fileExists = { false }
        )
        val ex = assertThrows(RendererLoadException::class.java) {
            loader.load(renderer(lib = "libdoes_not_exist.so"))
        }
        assertEquals("opengles2", ex.rendererId)
        assertEquals("libdoes_not_exist.so", ex.libraryName)
        assertEquals(
            listOf(
                File("/n", "libdoes_not_exist.so").absolutePath,
                File("/u", "libdoes_not_exist.so").absolutePath
            ),
            ex.searchedPaths
        )
        assertTrue(ex.message!!.contains("渲染器 opengles2 加载失败：找不到 libdoes_not_exist.so"))
        assertTrue(ex.message!!.contains("已查找"))
        assertTrue(ex.cause!!.message!!.contains("文件不存在"))
    }

    @Test
    fun `dlopen 返回 false 时抛异常不静默继续`() {
        val loader = RendererLibraryLoader(libPaths, dlopen = { false }, fileExists = { true })
        val ex = assertThrows(RendererLoadException::class.java) {
            loader.load(renderer())
        }
        assertTrue(ex.cause!!.message!!.contains("dlopen 返回 false"))
        assertEquals(2, ex.searchedPaths.size)
    }

    @Test
    fun `dlopen 抛异常时汇总进 cause 并向外抛`() {
        val loader = RendererLibraryLoader(
            libPaths,
            dlopen = { throw UnsatisfiedLinkError("boom") },
            fileExists = { true }
        )
        val ex = assertThrows(RendererLoadException::class.java) {
            loader.load(renderer())
        }
        assertTrue(ex.cause!!.message!!.contains("UnsatisfiedLinkError"))
        assertTrue(ex.cause!!.message!!.contains("boom"))
    }

    @Test
    fun `首个候选命中即返回 nativeLibraryDir 优先`() {
        val opened = mutableListOf<String>()
        val loader = RendererLibraryLoader(
            libPaths,
            dlopen = { opened += it; true },
            fileExists = { true }
        )
        val loaded = loader.load(renderer())
        assertEquals(nativeLib, loaded.mainLibraryPath)
        assertEquals("libgl4es_114.so", loaded.mainLibrary)
        assertEquals(listOf(nativeLib), opened)
    }

    @Test
    fun `native 目录缺失时用户目录的替换 so 生效 换 so 不重编`() {
        val opened = mutableListOf<String>()
        val loader = RendererLibraryLoader(
            libPaths,
            dlopen = { opened += it; true },
            fileExists = { it == userLib }
        )
        val loaded = loader.load(renderer())
        assertEquals(userLib, loaded.mainLibraryPath)
        assertEquals(listOf(userLib), opened)
    }

    @Test
    fun `插件绝对路径只按原路径查找`() {
        val absolute = "/data/app/plugin/lib/arm64/libplugin.so"
        val loader = RendererLibraryLoader(
            libPaths,
            dlopen = { true },
            fileExists = { it == absolute }
        )
        val loaded = loader.load(renderer(lib = absolute))
        assertEquals(absolute, loaded.mainLibraryPath)
    }

    @Test
    fun `附加库也逐个校验 任一缺失即中止`() {
        val loader = RendererLibraryLoader(
            libPaths,
            dlopen = { true },
            fileExists = { path -> !path.contains("libextra") }
        )
        assertThrows(RendererLoadException::class.java) {
            loader.load(renderer(extras = listOf("$nativeDir/libextra.so")))
        }
    }

    @Test
    fun `附加库全存在时全部加载`() {
        val opened = mutableListOf<String>()
        val loader = RendererLibraryLoader(
            libPaths,
            dlopen = { opened += it; true },
            fileExists = { true }
        )
        val loaded = loader.load(
            renderer(extras = listOf("$nativeDir/libA.so", "$nativeDir/libB.so"))
        )
        assertEquals(
            listOf("$nativeDir/libA.so", "$nativeDir/libB.so"),
            loaded.extraLibraries
        )
        assertEquals(3, opened.size)
    }
}
