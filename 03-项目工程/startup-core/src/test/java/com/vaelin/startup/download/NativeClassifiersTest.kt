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
import org.junit.Assert.assertNull
import org.junit.Test

@Suppress("NonAsciiCharacters")
class NativeClassifiersTest {

    /** 构造一个 lwjgl 风格的 library：natives 模板 + 若干 classifiers。 */
    private fun nativeLibrary(
        template: String,
        vararg classifierKeys: String,
    ): Library {
        val classifiers = classifierKeys.associateWith { key ->
            Artifact(
                path = "org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1-$key.jar",
                url = "https://example.invalid/lwjgl-$key.jar",
                sha1 = "sha1-$key",
                size = 10L,
            )
        }
        return Library(
            name = "org.lwjgl:lwjgl:3.3.1",
            downloads = LibraryDownloads(artifact = null, classifiers = classifiers),
            natives = mapOf("linux" to template),
            rules = emptyList(),
            url = null,
        )
    }

    @Test
    fun `Android ABI 映射到 Mojang 与 LWJGL 的架构词汇`() {
        assertEquals("arm64", NativeClassifiers.mojangArch("arm64-v8a"))
        assertEquals("arm32", NativeClassifiers.mojangArch("armeabi-v7a"))
        assertEquals("x86", NativeClassifiers.mojangArch("x86"))
        assertEquals("x86_64", NativeClassifiers.mojangArch("x86_64"))
    }

    @Test
    fun `natives 模板里的占位符被替换成本机架构`() {
        val library = nativeLibrary("natives-linux-\${arch}", "natives-linux-arm64", "natives-linux-x86_64")

        // arm64-v8a 设备必须拿到 natives-linux-arm64 那一份，而不是 x86_64
        assertEquals("natives-linux-arm64", NativeClassifiers.resolve(library, "arm64-v8a"))
        assertEquals("natives-linux-x86_64", NativeClassifiers.resolve(library, "x86_64"))
    }

    @Test
    fun `模板没有占位符时补架构后缀再匹配`() {
        // 1.19+ 的 lwjgl 写成 "linux": "natives-linux"，架构体现在 classifier 后缀上
        val library = nativeLibrary("natives-linux", "natives-linux-arm64", "natives-linux-x86_64")

        assertEquals("natives-linux-arm64", NativeClassifiers.resolve(library, "arm64-v8a"))
    }

    @Test
    fun `只有通用分类器时非默认架构不接受`() {
        // 无架构后缀的包只在小端 x86_64 上成立；arm64 装上去是一个永远不会被加载的 so
        val library = nativeLibrary("natives-linux", "natives-linux")

        assertNull(NativeClassifiers.resolve(library, "arm64-v8a"))
        assertEquals("natives-linux", NativeClassifiers.resolve(library, "x86_64"))
    }

    @Test
    fun `没有对应平台的 natives 条目时返回 null`() {
        val library = Library(
            name = "com.example:no-native:1.0",
            downloads = LibraryDownloads(artifact = null, classifiers = mapOf("something" to Artifact(null, "https://example.invalid/x.jar", null, -1L))),
            natives = mapOf("windows" to "natives-windows-\${arch}"),
            rules = emptyList(),
            url = null,
        )

        assertNull(NativeClassifiers.resolve(library, "arm64-v8a"))
    }

    @Test
    fun `没有 classifiers 时返回 null`() {
        val library = Library(
            name = "com.example:empty:1.0",
            downloads = null,
            natives = mapOf("linux" to "natives-linux-\${arch}"),
            rules = emptyList(),
            url = null,
        )

        assertNull(NativeClassifiers.resolve(library, "arm64-v8a"))
    }
}
