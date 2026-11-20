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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AssetCleanupTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val h1 = "0123456789abcdef0123456789abcdef01234567"
    private val h2 = "fedcba9876543210fedcba9876543210fedcba98"

    private fun objectsDir(): File = File(temp.root, "assets/objects")

    private fun put(hash: String): File {
        val file = File(File(objectsDir(), hash.take(2)), hash)
        file.parentFile?.mkdirs()
        file.writeText("asset")
        return file
    }

    @Test
    fun `列出不在必需集合里的对象文件`() {
        put(h1)
        val orphan = put(h2)

        val stale = AssetCleanup(objectsDir()).findStale(setOf(h1))

        assertEquals(1, stale.size)
        assertEquals(h2, stale[0].hash)
        assertEquals(orphan, stale[0].file)
    }

    @Test
    fun `只列不删`() {
        val orphan = put(h2)

        AssetCleanup(objectsDir()).findStale(setOf(h1))

        // 删除是不可逆的，清理器只负责给清单，落不落刀由调用方决定
        assertTrue("清理器不应删除文件", orphan.exists())
    }

    @Test
    fun `必需集合为空时全部视为孤儿`() {
        put(h1)
        put(h2)

        assertEquals(2, AssetCleanup(objectsDir()).findStale(emptySet()).size)
    }

    @Test
    fun `objects 目录不存在时返回空列表`() {
        assertTrue(AssetCleanup(File(temp.root, "not-created")).findStale(setOf(h1)).isEmpty())
    }
}
