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
class MavenCoordinateTest {

    @Test
    fun `解析普通坐标并拼出 maven 路径`() {
        val coordinate = MavenCoordinate.parse("org.lwjgl:lwjgl:3.3.1")!!

        assertEquals("org.lwjgl", coordinate.groupId)
        assertEquals("lwjgl", coordinate.artifactId)
        assertEquals("3.3.1", coordinate.version)
        assertNull(coordinate.classifier)
        assertEquals("jar", coordinate.extension)
        assertEquals("lwjgl-3.3.1.jar", coordinate.fileName)
        assertEquals("org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1.jar", coordinate.relativePath)
    }

    @Test
    fun `带 classifier 的坐标把 classifier 拼进文件名`() {
        val coordinate = MavenCoordinate.parse("org.lwjgl:lwjgl:3.3.1:natives-linux-arm64")!!

        assertEquals("natives-linux-arm64", coordinate.classifier)
        assertEquals("lwjgl-3.3.1-natives-linux-arm64.jar", coordinate.fileName)
    }

    @Test
    fun `带扩展名后缀的坐标单独摘出扩展名`() {
        // Forge 的安装包会写成 group:artifact:version@zip
        val coordinate = MavenCoordinate.parse("net.minecraftforge:forge:1.20.1-47.2.0@zip")!!

        assertEquals("zip", coordinate.extension)
        assertEquals("forge", coordinate.artifactId)
        assertEquals("forge-1.20.1-47.2.0.zip", coordinate.fileName)
    }

    @Test
    fun `坐标段数不足时返回 null`() {
        assertNull(MavenCoordinate.parse("org.lwjgl:lwjgl"))
        assertNull(MavenCoordinate.parse(""))
        assertNull(MavenCoordinate.parse("::"))
    }

    @Test
    fun `解析不出坐标的库不影响下载计划`() {
        val library = Library(
            name = "这不是一个坐标",
            downloads = null,
            natives = emptyMap(),
            rules = emptyList(),
            url = null,
        )

        assertNull(library.coordinate)
    }
}
