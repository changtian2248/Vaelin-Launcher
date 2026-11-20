/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vaelin.startup.plugin

import com.vaelin.startup.renderer.RendererLibPaths
import com.vaelin.startup.renderer.VaelinRenderers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 用假的 [PluginPackageSource]（等价假 PackageManager 数据源）+ 注入的 fileExists
 * 覆盖任务书要求的四场景：无插件 / 单个插件 / 两个插件同名冲突 / 插件库不存在。
 * 纯 JVM，不查系统、不依赖 Robolectric。
 */
class PluginDiscoveryTest {

    private class FakeSource(private val infos: List<PluginPackageInfo>) : PluginPackageSource {
        override fun queryPlugins(): List<PluginPackageInfo> = infos
    }

    private val nativeDir = "/opt/vaelin/lib/arm64"
    private val userDir = "/opt/vaelin/files/renderers"
    private lateinit var libPaths: RendererLibPaths

    @Before
    fun setUp() {
        libPaths = RendererLibPaths(nativeDir, userDir)
        VaelinRenderers.init(reset = true)
    }

    private fun info(
        packageName: String,
        vararg meta: Pair<String, Any?>,
        v2Json: String? = null,
        isSystem: Boolean = false
    ) = PluginPackageInfo(
        packageName = packageName,
        appName = "插件 $packageName",
        appVersion = "1.0",
        nativeLibraryDir = "/opt/app/$packageName/lib/arm64",
        metaData = PluginMetaData(meta.toMap()),
        rendererV2ConfigJson = v2Json,
        isSystemApp = isSystem
    )

    private fun legacyRenderer(packageName: String, lib: String = "libgl4es_114.so") = info(
        packageName,
        "fclPlugin" to true,
        "renderer" to "opengles2:$lib:libEGL.so",
        "des" to "测试渲染器",
        "pojavEnv" to "POJAV_RENDERER=opengles2:DLOPEN=libA.so,libB.so:LIB_MESA_NAME=libmesa.so:FOO=bar",
        "minMCVer" to "1.17",
        "maxMCVer" to 1214
    )

    private fun discover(
        vararg infos: PluginPackageInfo,
        existing: (String) -> Boolean = { true }
    ) = PluginDiscovery(FakeSource(infos.toList()), libPaths, existing).discover()

    @Test
    fun `无插件时结果为空`() {
        val result = discover()
        assertTrue(result.plugins.isEmpty())
        assertTrue(result.renderers.isEmpty())
        assertTrue(result.skipped.isEmpty())
    }

    @Test
    fun `单个旧架构插件可发现并注入注册表`() {
        val result = discover(legacyRenderer("com.test.gl4es"))
        assertEquals(1, result.plugins.size)
        assertTrue(result.plugins[0].types.contains(PluginType.RENDERER))
        assertEquals(1, result.renderers.size)

        val d = result.renderers[0]
        val expectedLib = "/opt/app/com.test.gl4es/lib/arm64/libgl4es_114.so"
        assertEquals(expectedLib, d.declaredLibrary)
        assertEquals(listOf(expectedLib), d.searchedPaths)
        assertTrue(d.libraryPresent)
        assertEquals(
            listOf(
                "/opt/app/com.test.gl4es/lib/arm64/libA.so",
                "/opt/app/com.test.gl4es/lib/arm64/libB.so"
            ),
            d.renderer.getDlopenLibrary().value
        )
        val env = d.renderer.getRendererEnv().value
        // POJAV_RENDERER 按 ZL2 语义用于覆盖 rendererId，不塞进 env（由启动链注入）
        assertEquals("opengles2", d.renderer.getRendererId())
        assertFalse(env.containsKey("POJAV_RENDERER"))
        assertEquals("/opt/app/com.test.gl4es/lib/arm64/libmesa.so", env["LIB_MESA_NAME"])
        assertEquals("bar", env["FOO"])
        assertEquals("1.17", d.renderer.getMinMCVersion())
        // meta-data 里 maxMCVer 是 Number，getString 必须兼容转字符串
        assertEquals("1214", d.renderer.getMaxMCVersion())

        val reg = PluginRegistrar.register(result)
        assertEquals(1, reg.registeredCount)
        assertTrue(reg.outcomes[0].registered)
    }

    @Test
    fun `两个插件同名冲突时 addRenderer 返回 false 不覆盖`() {
        val result = discover(
            legacyRenderer("com.test.same", "libFirst.so"),
            legacyRenderer("com.test.same", "libSecond.so")
        )
        assertEquals(2, result.renderers.size)

        val reg = PluginRegistrar.register(result)
        assertEquals(1, reg.registeredCount)
        assertTrue(reg.outcomes[0].registered)
        assertFalse(reg.outcomes[1].registered)
        assertTrue(reg.outcomes[1].reason.contains("冲突"))

        val kept = VaelinRenderers.getRenderers().filter {
            it.getUniqueIdentifier() == "com.test.same"
        }
        assertEquals(1, kept.size)
        // 首个生效，未被第二个覆盖（沿用 VaelinRenderers.addRenderer 语义）
        assertEquals(
            "/opt/app/com.test.same/lib/arm64/libFirst.so",
            kept[0].getRendererLibrary()
        )
    }

    @Test
    fun `插件库不存在时不注入且留痕`() {
        val result = discover(legacyRenderer("com.test.missing")) { false }
        val d = result.renderers[0]
        assertFalse(d.libraryPresent)
        assertTrue(result.skipped.any { it.reason.contains("渲染器库不存在") })

        val reg = PluginRegistrar.register(result)
        assertEquals(0, reg.registeredCount)
        assertFalse(reg.outcomes[0].registered)
        assertTrue(reg.outcomes[0].reason.contains("库不存在"))
        assertTrue(
            VaelinRenderers.getRenderers().none {
                it.getUniqueIdentifier() == "com.test.missing"
            }
        )
    }

    @Test
    fun `FFmpeg 类插件显式排除且留痕`() {
        val result = discover(
            info("net.kdt.pojavlaunch.ffmpeg", "fclPlugin" to true, "driver" to "ffmpeg")
        )
        assertTrue(result.plugins.isEmpty())
        assertTrue(result.skipped.any { it.reason.contains("FFmpeg") })
    }

    @Test
    fun `系统应用声明插件 key 时跳过且留痕`() {
        val result = discover(
            info("com.android.fake", "fclPlugin" to true, isSystem = true)
        )
        assertTrue(result.plugins.isEmpty())
        assertTrue(result.skipped.any { it.reason.contains("系统应用") })
    }

    @Test
    fun `旧架构缺必需 meta-data 时留痕不产出渲染器`() {
        val result = discover(info("com.test.bad", "fclPlugin" to true))
        assertTrue(result.renderers.isEmpty())
        assertTrue(result.skipped.any { it.reason.contains("缺 renderer/des/pojavEnv") })
    }

    @Test
    fun `DRIVER 与 NATIVES 类别识别`() {
        val drv = discover(info("com.test.drv", "fclPlugin" to true, "driver" to "turnip"))
        assertTrue(drv.plugins[0].types.contains(PluginType.DRIVER))
        assertEquals("turnip", drv.plugins[0].driverName)
        assertTrue(drv.renderers.isEmpty())

        val libDir = "/opt/app/com.test.native/lib/arm64"
        val nat = discover(
            info(
                "com.test.native",
                "FCLNativePlugin" to true,
                "environment" to "FOO={nativeLibraryDir}/libx.so BAR=baz"
            )
        )
        assertTrue(nat.plugins[0].types.contains(PluginType.NATIVES))
        assertEquals(
            File(libDir, "libx.so").absolutePath,
            nat.plugins[0].environment["FOO"]
        )
        assertEquals("baz", nat.plugins[0].environment["BAR"])
    }

    @Test
    fun `V2 插件解析且同包不再加载旧架构`() {
        val json = """
            {
              "displayName": "V2 渲染器",
              "rendererId": "test_v2",
              "rendererGLPath": "**|libv2.so",
              "rendererEGLPath": "**|libegl.so",
              "dlopenLibPaths": ["**|libextra.so"],
              "env": [{"type": "NormalEnv", "key": "FOO", "value": "bar"}],
              "minMCVer": "1.20",
              "maxMCVer": null
            }
        """.trimIndent()
        val result = discover(
            info(
                "com.test.v2",
                "fclPlugin" to true,
                "fclPlugin_V2" to 1024,
                "renderer" to "legacy:liblegacy.so:libEGL.so",
                "des" to "旧架构",
                "pojavEnv" to "FOO=legacy",
                v2Json = json
            )
        )
        assertEquals(1, result.renderers.size)
        val d = result.renderers[0]
        assertEquals(PluginType.RENDERER_V2, d.type)
        assertEquals("test_v2", d.renderer.getRendererId())
        assertTrue(d.renderer.mesaBased)
        assertTrue(result.plugins[0].types.contains(PluginType.RENDERER_V2))
        assertFalse(result.plugins[0].types.contains(PluginType.RENDERER))
    }

    @Test
    fun `注册后注册表能按 uniqueId 查到旧架构插件渲染器`() {
        val result = discover(legacyRenderer("com.test.gl4es"))
        val reg = PluginRegistrar.register(result)
        assertEquals(1, reg.registeredCount)

        // 注册表 = 10 个内置（含 MobileGlues）+ 1 个插件
        assertEquals(11, VaelinRenderers.getRenderers().size)

        // 启动链选择路径（setCurrentRenderer）按 uniqueId 必须命中插件渲染器
        VaelinRenderers.setCurrentRenderer("com.test.gl4es")
        val current = VaelinRenderers.getCurrentRenderer()
        assertEquals("opengles2", current.getRendererId())
        assertEquals(
            "/opt/app/com.test.gl4es/lib/arm64/libgl4es_114.so",
            current.getRendererLibrary(),
        )
        // pojavEnv 里的 LIB_MESA_NAME 已展开成插件 nativeLibraryDir 下的绝对路径
        assertEquals(
            "/opt/app/com.test.gl4es/lib/arm64/libmesa.so",
            current.getRendererEnv().value["LIB_MESA_NAME"],
        )
    }

    @Test
    fun `注册后注册表能按 uniqueId 查到 V2 插件渲染器`() {
        val json = """
            {
              "displayName": "V2 渲染器",
              "rendererId": "test_v2",
              "rendererGLPath": "**|libv2.so",
              "rendererEGLPath": "**|libegl.so",
              "env": [{"type": "NormalEnv", "key": "FOO", "value": "bar"}],
              "minMCVer": "1.20",
              "maxMCVer": null
            }
        """.trimIndent()
        val result = discover(
            info("com.test.v2", "fclPlugin" to true, "fclPlugin_V2" to 1024, v2Json = json)
        )
        val reg = PluginRegistrar.register(result)
        assertEquals(1, reg.registeredCount)

        // 插件渲染器以包名作 uniqueIdentifier（对齐 ZL2 RendererPlugin 语义）
        VaelinRenderers.setCurrentRenderer("com.test.v2")
        val current = VaelinRenderers.getCurrentRenderer()
        assertEquals("test_v2", current.getRendererId())
        // **| 前缀经 RendererV2ConfigParser 用 File 展开，期望值也用 File 拼接
        //（Windows 上 absolutePath 用反斜杠，直接写字面量会两边对不上）
        val v2LibDir = "/opt/app/com.test.v2/lib/arm64"
        assertEquals(
            File(v2LibDir, "libv2.so").absolutePath,
            current.getRendererLibrary(),
        )
        assertEquals(
            File(v2LibDir, "libegl.so").absolutePath,
            current.getRendererEGL(),
        )
        assertEquals("bar", current.getRendererEnv().value["FOO"])
    }
}
