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
import org.junit.Test

class AssetIndexTest {

    private val h1 = "0123456789abcdef0123456789abcdef01234567"
    private val h2 = "fedcba9876543210fedcba9876543210fedcba98"

    @Test
    fun `解析基本对象列表`() {
        val text = """
            {
              "objects": {
                "minecraft/texts/en_us.lang": {"hash": "$h2", "size": 456},
                "minecraft/sounds/ambient/cave/cave1.ogg": {"hash": "$h1", "size": 123}
              }
            }
        """.trimIndent()

        val objects = AssetIndexParser.parse(text)

        assertEquals(2, objects.size)
        // 输出按 path 排序，sounds 在 texts 之前
        assertEquals("minecraft/sounds/ambient/cave/cave1.ogg", objects[0].path)
        assertEquals(h1, objects[0].hash)
        assertEquals(123L, objects[0].size)
        assertEquals("minecraft/texts/en_us.lang", objects[1].path)
        assertEquals(h2, objects[1].hash)
    }

    @Test
    fun `路径里的斜杠原样保留`() {
        val text = """
            {"objects": {"minecraft/sounds/mob/creeper/say1.ogg": {"hash": "$h1", "size": 7}}}
        """.trimIndent()

        // 逻辑路径只用于展示，落盘名字是 hash；这里确认解析层没有把路径拆开或改写
        assertEquals("minecraft/sounds/mob/creeper/say1.ogg", AssetIndexParser.parse(text).single().path)
    }

    @Test
    fun `objects 为空时返回空列表`() {
        assertTrue(AssetIndexParser.parse("""{"objects": {}}""").isEmpty())
    }

    @Test
    fun `缺 objects 字段时返回空列表`() {
        assertTrue(AssetIndexParser.parse("{}").isEmpty())
        assertTrue(AssetIndexParser.parse("""{"objects": null}""").isEmpty())
    }

    @Test
    fun `缺 hash 或 hash 非法的条目被跳过但其它保留`() {
        val text = """
            {
              "objects": {
                "missing_hash": {"size": 1},
                "short_hash": {"hash": "abc", "size": 2},
                "good": {"hash": "$h1", "size": 3}
              }
            }
        """.trimIndent()

        val objects = AssetIndexParser.parse(text)

        assertEquals(1, objects.size)
        assertEquals("good", objects[0].path)
    }

    @Test
    fun `size 缺失时按零降级`() {
        val text = """{"objects": {"a": {"hash": "$h1"}}}"""

        assertEquals(0L, AssetIndexParser.parse(text).single().size)
    }

    @Test
    fun `非法 JSON 抛出解析异常`() {
        val error = runCatching { AssetIndexParser.parse("{\"objects\": ") }.exceptionOrNull()

        assertTrue("应当是 JsonParseException，实际是 $error", error is JsonParseException)
    }

    @Test
    fun `大写 hash 归一为小写`() {
        val text = """{"objects": {"a": {"hash": "${h1.uppercase()}", "size": 1}}}"""

        assertEquals(h1, AssetIndexParser.parse(text).single().hash)
    }
}
