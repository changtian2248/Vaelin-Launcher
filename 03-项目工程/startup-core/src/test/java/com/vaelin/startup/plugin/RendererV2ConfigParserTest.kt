/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vaelin.startup.plugin

import com.vaelin.startup.download.JsonParseException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 用 download/Json.kt 解析 ZL2 RendererConfig JSON（零新增依赖）。
 * 覆盖 **| 路径展开、sealed env 按 "type" 分派、缺字段显式报错。
 */
class RendererV2ConfigParserTest {

    private val nativeDir = "/opt/app/com.test.v2/lib/arm64"

    private val fullJson = """
        {
          "displayName": "测试 V2 渲染器",
          "rendererId": "test_v2",
          "rendererGLPath": "**|libglv2.so",
          "rendererEGLPath": "**|libeglv2.so",
          "dlopenLibPaths": ["**|libextra.so", "/system/lib64/libfoo.so"],
          "env": [
            {"type": "NormalEnv", "key": "MESA_LOADER_DRIVER_OVERRIDE", "value": "zink"},
            {"type": "NormalEnv", "key": "LIB_MESA_NAME", "value": "**|libmesa.so"},
            {"type": "ToggleableEnv", "key": "TOGGLE_ON", "value": "1", "toggle": true},
            {"type": "ToggleableEnv", "key": "TOGGLE_OFF", "value": "0", "toggle": false},
            {"type": "SelectableEnv", "key": "SELECTABLE", "check": true,
             "items": {"defaultValue": "a", "values": ["b"]}},
            {"type": "CustomizableEnv", "key": "CUSTOM", "defaultValue": "x"}
          ],
          "minMCVer": "1.20",
          "maxMCVer": null
        }
    """.trimIndent()

    @Test
    fun `完整配置解析与路径展开`() {
        val c = RendererV2ConfigParser.parse(fullJson, nativeDir)
        assertEquals("测试 V2 渲染器", c.displayName)
        assertEquals("test_v2", c.rendererId)
        assertEquals(File(nativeDir, "libglv2.so").absolutePath, c.rendererGLPath)
        assertEquals(File(nativeDir, "libeglv2.so").absolutePath, c.rendererEGLPath)
        assertEquals(
            listOf(File(nativeDir, "libextra.so").absolutePath, "/system/lib64/libfoo.so"),
            c.dlopenLibPaths
        )
        assertEquals("1.20", c.minMCVer)
        assertNull(c.maxMCVer)
    }

    @Test
    fun `env 按 type 分派 只收固定生效项`() {
        val fixed = RendererV2ConfigParser.parse(fullJson, nativeDir).fixedEnv
        assertEquals("zink", fixed["MESA_LOADER_DRIVER_OVERRIDE"])
        assertEquals(File(nativeDir, "libmesa.so").absolutePath, fixed["LIB_MESA_NAME"])
        assertEquals("1", fixed["TOGGLE_ON"])
        // toggle=false、SelectableEnv、CustomizableEnv 都不能混进固定环境变量
        assertFalse(fixed.containsKey("TOGGLE_OFF"))
        assertFalse(fixed.containsKey("SELECTABLE"))
        assertFalse(fixed.containsKey("CUSTOM"))
        assertEquals(3, fixed.size)
    }

    @Test
    fun `缺少必需字段时显式抛异常`() {
        val bad = """{"rendererId":"x","rendererGLPath":"a","rendererEGLPath":"b"}"""
        val ex = assertThrows(IllegalArgumentException::class.java) {
            RendererV2ConfigParser.parse(bad, nativeDir)
        }
        assertTrue(ex.message!!.contains("缺少必需字段"))
    }

    @Test
    fun `非法 JSON 抛 JsonParseException`() {
        assertThrows(JsonParseException::class.java) {
            RendererV2ConfigParser.parse("{not json", nativeDir)
        }
    }

    @Test
    fun `unicode 转义与重复键后者覆盖`() {
        val json = """
            {
              "displayName": "AB",
              "displayName": "\u0043D",
              "rendererId": "x",
              "rendererGLPath": "a",
              "rendererEGLPath": "b",
              "dlopenLibPaths": [],
              "env": [],
              "minMCVer": null,
              "maxMCVer": null
            }
        """.trimIndent()
        assertEquals("CD", RendererV2ConfigParser.parse(json, nativeDir).displayName)
    }
}
