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

import com.vaelin.startup.launch.LaunchException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("NonAsciiCharacters")
class VersionJsonMergerTest {

    /** 按名字查父 JSON 的测试桩：没登记的名字直接抛带路径的 LaunchException（模拟协调者行为）。 */
    private fun merge(child: String, parents: Map<String, String>): String =
        VersionJsonMerger.merge(child) { inherits ->
            parents[inherits]
                ?: throw LaunchException("父版本 JSON 不存在：/fake/versions/$inherits/$inherits.json")
        }

    /** vanilla 父版本（1.20.1 裁剪版）：有 mainClass、libraries、arguments.game/jvm、assetIndex。 */
    private fun vanillaJson(): String = """
        {
          "id": "1.20.1",
          "type": "release",
          "mainClass": "net.minecraft.client.main.Main",
          "assets": "5",
          "assetIndex": {"id": "5", "url": "https://example.invalid/indexes/5.json", "sha1": "idxsha", "size": 100},
          "javaVersion": {"component": "java-runtime-gamma", "majorVersion": 17},
          "libraries": [
            {"name": "com.example:plain:1.0", "downloads": {"artifact": {"path": "com/example/plain/1.0/plain-1.0.jar", "url": "https://example.invalid/plain-1.0.jar"}}},
            {"name": "org.lwjgl:lwjgl:3.3.1", "downloads": {"artifact": {"path": "org/lwjgl/lwjgl/3.3.1/lwjgl-3.3.1.jar", "url": "https://example.invalid/lwjgl.jar"}}}
          ],
          "arguments": {
            "game": ["--username", "${'$'}{auth_player_name}", "--version", "${'$'}{version_name}"],
            "jvm": ["-Djava.library.path=${'$'}{natives_directory}", "-Dlauncher.brand=${'$'}{launcher_name}"]
          }
        }
    """.trimIndent()

    /** Forge 风格子版本：无 mainClass、带 inheritsFrom、自带 libraries 与 arguments。 */
    private fun forgeJson(): String = """
        {
          "id": "1.20.1-forge-47.2.0",
          "inheritsFrom": "1.20.1",
          "type": "",
          "libraries": [
            {"name": "net.minecraftforge:forge:1.20.1-47.2.0:universal", "downloads": {"artifact": {"path": "net/minecraftforge/forge/1.20.1-47.2.0/forge-1.20.1-47.2.0-universal.jar", "url": "https://example.invalid/forge-universal.jar"}}},
            {"name": "com.example:plain:1.1", "downloads": {"artifact": {"path": "com/example/plain/1.1/plain-1.1.jar", "url": "https://example.invalid/plain-1.1.jar"}}}
          ],
          "arguments": {
            "game": ["--launchTarget", "forgeclient", "--tweakClass", "net.minecraftforge.fml.loading.FMLClientTweaker"],
            "jvm": ["-Dforge.enableExperimentalGameplay=true"]
          }
        }
    """.trimIndent()

    private fun gameTokens(mergedText: String): List<String> =
        Json.parse(mergedText).field("arguments").arrayField("game")
            ?.map { (it as JsonValue.Str).value }
            ?: emptyList()

    private fun jvmTokens(mergedText: String): List<String> =
        Json.parse(mergedText).field("arguments").arrayField("jvm")
            ?.map { (it as JsonValue.Str).value }
            ?: emptyList()

    private fun libraryNames(mergedText: String): List<String?> =
        Json.parse(mergedText).arrayField("libraries")?.map { it.stringField("name") } ?: emptyList()

    @Test
    fun `forge 风格合并 mainClass 继承父 libraries 拼接去重取子 arguments 追加`() {
        val merged = merge(forgeJson(), mapOf("1.20.1" to vanillaJson()))
        val root = Json.parse(merged)

        // 子覆盖父：id；空串不覆盖：type 保留父的 release
        assertEquals("1.20.1-forge-47.2.0", root.stringField("id"))
        assertEquals("release", root.stringField("type"))
        // 子缺 mainClass → 继承父
        assertEquals("net.minecraft.client.main.Main", root.stringField("mainClass"))
        // assetIndex 从父继承
        assertEquals("5", root.field("assetIndex").stringField("id"))
        // 合并产物自包含，不再携带 inheritsFrom
        assertNull(root.field("inheritsFrom"))

        // libraries：父在前子在后；同名坐标 com.example:plain 取子的 1.1，且沿用父条目的原位置
        assertEquals(
            listOf(
                "com.example:plain:1.1",
                "org.lwjgl:lwjgl:3.3.1",
                "net.minecraftforge:forge:1.20.1-47.2.0:universal",
            ),
            libraryNames(merged),
        )

        // game：父的在前，子的追加（tweakClass 完整串保留）
        assertEquals(
            listOf(
                "--username", "${'$'}{auth_player_name}",
                "--version", "${'$'}{version_name}",
                "--launchTarget", "forgeclient",
                "--tweakClass", "net.minecraftforge.fml.loading.FMLClientTweaker",
            ),
            gameTokens(merged),
        )

        // jvm：父在前，子直接追加在后
        assertEquals(
            listOf(
                "-Djava.library.path=${'$'}{natives_directory}",
                "-Dlauncher.brand=${'$'}{launcher_name}",
                "-Dforge.enableExperimentalGameplay=true",
            ),
            jvmTokens(merged),
        )

        // 合并产物可以被 VersionJson.parse 直接消费
        val parsed = VersionJson.parse(merged)
        assertEquals("net.minecraft.client.main.Main", parsed.mainClass)
        assertEquals(3, parsed.libraries.size)
        assertNull(parsed.jar)
    }

    @Test
    fun `fabric 风格子的 mainClass 覆盖父`() {
        val fabric = """
            {
              "id": "fabric-loader-0.14.21-1.20.1",
              "inheritsFrom": "1.20.1",
              "mainClass": "net.fabricmc.loader.impl.launch.knot.KnotClient",
              "arguments": {
                "game": ["--fabric.addMods", "mods-placeholder"],
                "jvm": ["-Dfabric.dli.env=client"]
              }
            }
        """.trimIndent()

        val merged = merge(fabric, mapOf("1.20.1" to vanillaJson()))
        val root = Json.parse(merged)

        // 子带 mainClass → 子的覆盖父的
        assertEquals("net.fabricmc.loader.impl.launch.knot.KnotClient", root.stringField("mainClass"))
        assertEquals(
            listOf(
                "--username", "${'$'}{auth_player_name}",
                "--version", "${'$'}{version_name}",
                "--fabric.addMods", "mods-placeholder",
            ),
            gameTokens(merged),
        )
        assertEquals(
            listOf(
                "-Djava.library.path=${'$'}{natives_directory}",
                "-Dlauncher.brand=${'$'}{launcher_name}",
                "-Dfabric.dli.env=client",
            ),
            jvmTokens(merged),
        )
    }

    @Test
    fun `jar 字段子有则用子无则继承父`() {
        val childWithJar = """{"id":"child","inheritsFrom":"p","jar":"1.20.1"}"""
        val childWithoutJar = """{"id":"child","inheritsFrom":"p"}"""
        val parent = """{"id":"p","mainClass":"p.Main","jar":"1.20.1"}"""

        assertEquals("1.20.1", Json.parse(merge(childWithJar, mapOf("p" to parent))).stringField("jar"))
        // 子没写 jar → 继承父的（决定 client jar 文件名时优先 jar 字段）
        assertEquals("1.20.1", Json.parse(merge(childWithoutJar, mapOf("p" to parent))).stringField("jar"))
        assertEquals(
            "1.20.1",
            VersionJson.parse(merge(childWithoutJar, mapOf("p" to parent))).jar,
        )
    }

    @Test
    fun `三层继承链递归合并`() {
        val vanilla = """
            {"id":"vanilla","mainClass":"vanilla.Main","libraries":[{"name":"a:vanilla-lib:1.0"}],"arguments":{"game":["--vanilla"]}}
        """.trimIndent()
        val layerB = """
            {"id":"layer-b","inheritsFrom":"vanilla","libraries":[{"name":"b:layer-b-lib:1.0"}],"arguments":{"game":["--layer-b"]}}
        """.trimIndent()
        val layerA = """
            {"id":"layer-a","inheritsFrom":"layer-b","libraries":[{"name":"c:layer-a-lib:1.0"}],"arguments":{"game":["--layer-a"]}}
        """.trimIndent()

        val merged = merge(layerA, mapOf("layer-b" to layerB, "vanilla" to vanilla))
        val root = Json.parse(merged)

        assertEquals("vanilla.Main", root.stringField("mainClass"))
        assertEquals("layer-a", root.stringField("id"))
        assertNull(root.field("inheritsFrom"))
        assertEquals(
            listOf("a:vanilla-lib:1.0", "b:layer-b-lib:1.0", "c:layer-a-lib:1.0"),
            libraryNames(merged),
        )
        assertEquals(listOf("--vanilla", "--layer-b", "--layer-a"), gameTokens(merged))
    }

    @Test
    fun `同名 --flag 去重跳过 flag 与其值 而 --tweakClass 完整串不去重`() {
        val parent = """
            {"id":"p","mainClass":"p.Main","arguments":{"game":["--gameDir","/parent","--fullscreen","--tweakClass","forge.FMLTweaker"]}}
        """.trimIndent()
        val child = """
            {"id":"c","inheritsFrom":"p","arguments":{"game":["--gameDir","/child","--fullscreen","--tweakClass","optifine.OptiFineTweaker","--extra"]}}
        """.trimIndent()

        val merged = merge(child, mapOf("p" to parent))

        // 子的 --gameDir（含值）与 --fullscreen 与父重复被跳过；两个 --tweakClass 完整串都保留
        assertEquals(
            listOf(
                "--gameDir", "/parent",
                "--fullscreen",
                "--tweakClass", "forge.FMLTweaker",
                "--tweakClass", "optifine.OptiFineTweaker",
                "--extra",
            ),
            gameTokens(merged),
        )
        assertFalse(gameTokens(merged).contains("/child"))
    }

    @Test
    fun `带 rules 的对象参数条目按结构相等去重`() {
        val ruleEntry = """{"rules":[{"action":"allow","features":{"is_demo_user":true}}],"value":"--demo"}"""
        val parent = """{"id":"p","mainClass":"p.Main","arguments":{"game":["--base",$ruleEntry]}}"""
        val child = """{"id":"c","inheritsFrom":"p","arguments":{"game":[$ruleEntry,"--child-only"]}}"""

        val merged = merge(child, mapOf("p" to parent))

        // 父的 2 个 token + 子新增的 1 个；结构相同的对象条目不重复出现
        val game = Json.parse(merged).field("arguments").arrayField("game")!!
        assertEquals(3, game.size)
        assertEquals(1, game.count { it is JsonValue.Obj })
    }

    @Test
    fun `父版本 JSON 缺失时 resolveParent 的异常原样上抛带路径`() {
        val error = assertThrows(LaunchException::class.java) {
            merge(forgeJson(), parents = emptyMap())
        }
        // 协调者负责报哪个文件缺失；合并器禁止静默跳过继承
        assertTrue("实际消息：${error.message}", error.message!!.contains("父版本 JSON 不存在"))
        assertTrue("实际消息：${error.message}", error.message!!.contains("1.20.1"))
    }

    @Test
    fun `继承链超过 10 层判定环形继承显式报错`() {
        val parents = HashMap<String, String>()
        for (i in 0 until 12) {
            parents["ring-$i"] = """{"id":"ring-$i","inheritsFrom":"ring-${i + 1}"}"""
        }
        // 父链一路声明下去，永远没有终点
        parents["ring-12"] = """{"id":"ring-12","inheritsFrom":"ring-0"}"""

        val error = assertThrows(LaunchException::class.java) {
            merge(parents["ring-0"]!!, parents)
        }
        assertTrue("实际消息：${error.message}", error.message!!.contains("环形继承"))
    }

    @Test
    fun `父 JSON 顶层不是对象或 libraries 不是数组时显式报错`() {
        val child = """{"id":"c","inheritsFrom":"p"}"""
        val arrayRoot = """["not-an-object"]"""

        // 父文档顶层是数组 → 拒绝合并
        assertThrows(LaunchException::class.java) {
            merge(child, mapOf("p" to arrayRoot))
        }

        // 子的 libraries 字段不是数组 → 拒绝合并（不静默丢弃）
        val badChild = """{"id":"c","inheritsFrom":"p","libraries":{"name":"x"}}"""
        val parent = """{"id":"p","mainClass":"p.Main"}"""
        val error = assertThrows(LaunchException::class.java) {
            merge(badChild, mapOf("p" to parent))
        }
        assertTrue("实际消息：${error.message}", error.message!!.contains("libraries"))
    }
}
