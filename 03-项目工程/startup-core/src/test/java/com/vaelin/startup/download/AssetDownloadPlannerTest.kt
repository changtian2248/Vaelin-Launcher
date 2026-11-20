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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AssetDownloadPlannerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val h1 = "0123456789abcdef0123456789abcdef01234567"
    private val h2 = "fedcba9876543210fedcba9876543210fedcba98"

    private fun planner() = AssetDownloadPlanner(temp.root)

    private fun objectFile(hash: String, size: Int): File {
        val target = File(File(File(File(temp.root, "assets"), "objects"), hash.take(2)), hash)
        target.parentFile?.mkdirs()
        target.writeBytes(ByteArray(size))
        return target
    }

    @Test
    fun `hash 前两位切成对象子目录`() {
        val plan = planner().plan(listOf(AssetObject("a.ogg", h1, 10)))

        val request = plan.requests.single()
        assertEquals(h1.take(2), request.target.parentFile!!.name)
        assertEquals(h1, request.target.name)
        assertEquals(File(temp.root, "assets/objects"), plan.objectsDir)
    }

    @Test
    fun `对象地址是资源根加前两位目录加 hash`() {
        val plan = planner().plan(listOf(AssetObject("a.ogg", h1, 10)))

        assertEquals(
            "https://resources.download.minecraft.net/${h1.take(2)}/$h1",
            plan.requests.single().url,
        )
    }

    @Test
    fun `已存在且大小一致的对象被跳过`() {
        objectFile(h1, 4)

        val plan = planner().plan(listOf(AssetObject("a.ogg", h1, 4)))

        assertTrue(plan.requests.isEmpty())
        assertEquals(1, plan.skipped.size)
        assertTrue(plan.skipped[0].reason.contains("大小一致"))
    }

    @Test
    fun `大小不一致时生成续传任务`() {
        objectFile(h1, 2)

        val plan = planner().plan(listOf(AssetObject("a.ogg", h1, 8)))

        val request = plan.requests.single()
        assertTrue("本地有残缺文件时应保留它续传", request.resume)
        assertEquals(h1, request.expectedSha1)
        assertEquals(8L, request.expectedSize)
    }

    @Test
    fun `本地没有文件时不续传`() {
        val plan = planner().plan(listOf(AssetObject("a.ogg", h1, 8)))

        assertFalse(plan.requests.single().resume)
    }

    @Test
    fun `非法 hash 被跳过且不生成任务`() {
        val plan = planner().plan(
            listOf(
                AssetObject("bad", "not-a-sha1", 3),
                AssetObject("good", h1, 3),
            )
        )

        assertEquals(1, plan.requests.size)
        assertEquals(1, plan.skipped.size)
        assertEquals("bad", plan.skipped[0].path)
    }

    @Test
    fun `同一 hash 的多个路径只生成一个任务`() {
        val plan = planner().plan(
            listOf(
                AssetObject("minecraft/sounds/a.ogg", h1, 5),
                AssetObject("minecraft/sounds/b.ogg", h1, 5),
            )
        )

        assertEquals(1, plan.requests.size)
        assertEquals(1, plan.skipped.size)
        assertTrue(plan.skipped[0].reason.contains("共用"))
    }

    @Test
    fun `对象列表为空时计划为空`() {
        val plan = planner().plan(emptyList())

        assertTrue(plan.nothingToDo)
        assertTrue(plan.requests.isEmpty())
        assertEquals(0L, plan.totalExpectedBytes)
    }

    @Test
    fun `总字节数汇总已规划对象`() {
        val plan = planner().plan(
            listOf(
                AssetObject("a", h1, 10),
                AssetObject("b", h2, 20),
            )
        )

        assertEquals(30L, plan.totalExpectedBytes)
    }
}
