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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("NonAsciiCharacters")
class JsonTest {

    @Test
    fun `解析嵌套对象与数组`() {
        val root = Json.parse(
            """{"a":{"b":[1,2,{"c":"x"}]},"d":true,"e":null}"""
        )

        assertEquals("x", root.field("a").field("b").asArray()!![2].stringField("c"))
        assertEquals(true, root.field("d").asBoolean())
        assertTrue(root.field("e") is JsonValue.Null)
    }

    @Test
    fun `转义序列与 unicode 解码为真实字符`() {
        val text = Json.parse(""""line\nquote\"slash\\tab\tunicode\u0041\u4E2D"""")
            .asString()

        assertEquals("line\nquote\"slash\\tab\tunicodeA中", text)
    }

    @Test
    fun `数字保留原始文本`() {
        val value = Json.parse("""{"size":123456789012345678901234567890,"neg":-1.5e3}""")

        assertEquals("123456789012345678901234567890", (value.field("size") as JsonValue.Num).raw)
        assertEquals("-1.5e3", (value.field("neg") as JsonValue.Num).raw)
    }

    @Test
    fun `重复键以后出现的值为准`() {
        // GSON / org.json 都是后者覆盖；如果这里行为不同，同一份文档在不同实现下会解析出两个结果
        assertEquals("b", Json.parse("""{"k":"a","k":"b"}""").stringField("k"))
    }

    @Test
    fun `空数组与空对象`() {
        val root = Json.parse("""{"arr":[],"obj":{}}""")

        assertEquals(0, root.arrayField("arr")!!.size)
        assertEquals(0, root.objectField("obj")!!.size)
    }

    @Test
    fun `字符串字段缺失时返回 null 而不是抛错`() {
        val root = Json.parse("""{"a":1}""")

        assertEquals(null, root.stringField("missing"))
        assertEquals(null, root.field("a").stringField("inner"))
    }

    @Test
    fun `顶层值后有尾随内容时抛异常`() {
        val error = assertThrows(JsonParseException::class.java) {
            Json.parse("""{"a":1} {"b":2}""")
        }

        assertTrue("实际消息：${error.message}", error.message!!.contains("多余内容"))
    }

    @Test
    fun `残缺的 JSON 抛异常并带上偏移量`() {
        assertThrows(JsonParseException::class.java) { Json.parse("""{"a":""") }
        assertThrows(JsonParseException::class.java) { Json.parse("""{"a" 1}""") }
        assertThrows(JsonParseException::class.java) { Json.parse("") }
        assertThrows(JsonParseException::class.java) { Json.parse("""{"a":"\q"}""") }
    }
}
