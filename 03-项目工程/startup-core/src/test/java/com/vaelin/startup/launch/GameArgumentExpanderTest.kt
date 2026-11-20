/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.vaelin.startup.launch

import com.vaelin.startup.download.RuleEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@Suppress("NonAsciiCharacters")
class GameArgumentExpanderTest {

    private val environment = RuleEnvironment.android("arm64-v8a")

    private fun context() = ArgumentContext(
        versionId = "1.20.1",
        versionType = "release",
        gameDir = File("/data/games"),
        assetsDir = File("/data/games/assets"),
        assetIndexId = "5",
    )

    /** 现代格式：字符串元素 + 带 rules 的对象（value 可为字符串或数组）+ 无规则对象。 */
    private val modernJson = """
        {
          "id": "1.20.1",
          "type": "release",
          "mainClass": "net.minecraft.client.main.Main",
          "arguments": {
            "game": [
              "--username", "${'$'}{auth_player_name}",
              {"rules": [{"action": "allow", "os": {"name": "linux"}}], "value": ["--uuid", "${'$'}{auth_uuid}"]},
              {"rules": [{"action": "allow", "os": {"name": "osx"}}], "value": ["--bad1", "--bad2"]},
              {"value": ["--accessToken", "${'$'}{auth_access_token}"]},
              "--versionType", "${'$'}{version_type}"
            ]
          }
        }
    """.trimIndent()

    @Test
    fun `现代 arguments game 展开占位符并按规则过滤`() {
        val args = GameArgumentExpander.build(modernJson, context(), environment)

        assertEquals(
            listOf(
                "--username", "Steve",
                "--uuid", OfflineAccount.uuidFor("Steve"),
                "--accessToken", "0",
                "--versionType", "release",
            ),
            args,
        )
        assertTrue("osx 专属规则的参数不该出现在 linux 环境", args.none { it.startsWith("--bad") })
        assertTrue("离线 UUID 应带 version=3/variant=2 特征", OfflineAccount.isOfflineUuid(args[3]))
    }

    @Test
    fun `rules 用 last match wins 而不是命中即返回`() {
        val json = """
            {"id":"x","mainClass":"Main","arguments":{"game":[
              {"rules":[{"action":"disallow","os":{"name":"linux"}},{"action":"allow","os":{"name":"linux"}}],"value":"--kept"},
              {"rules":[{"action":"allow","os":{"name":"linux"}},{"action":"disallow","os":{"name":"linux"}}],"value":"--dropped"}
            ]}}
        """.trimIndent()

        assertEquals(listOf("--kept"), GameArgumentExpander.gameArguments(json, environment))
    }

    @Test
    fun `旧版 minecraftArguments 按空白切分且保留双引号内整段`() {
        val json = """
            {"id":"1.7.10","mainClass":"net.minecraft.client.main.Main",
             "minecraftArguments":"--username ${'$'}{auth_player_name} --version ${'$'}{version_name} --demo \"hello world ${'$'}{version_type}\""}
        """.trimIndent()

        assertEquals(
            listOf("--username", "Steve", "--version", "1.20.1", "--demo", "hello world release"),
            GameArgumentExpander.build(json, context(), environment),
        )
    }

    @Test
    fun `未知占位符原样保留不静默丢弃`() {
        val token = "${'$'}{unknown_key}"

        assertEquals(token, GameArgumentExpander.expandToken(token, GameArgumentExpander.values(context())))
    }

    @Test
    fun `占位符值覆盖账号版本目录与分辨率`() {
        val values = GameArgumentExpander.values(context())

        assertEquals("Steve", values["auth_player_name"])
        assertEquals("0", values["auth_access_token"])
        assertEquals("legacy", values["user_type"])
        assertEquals("{}", values["user_properties"])
        assertEquals("1.20.1", values["version_name"])
        assertEquals("release", values["version_type"])
        assertEquals("5", values["assets_index_name"])
        assertEquals(File("/data/games").absolutePath, values["game_directory"])
        assertEquals("1280", values["resolution_width"])
        assertEquals("720", values["resolution_height"])
    }

    @Test(expected = LaunchException::class)
    fun `既无 arguments game 也无 minecraftArguments 时显式报错`() {
        GameArgumentExpander.gameArguments("""{"id":"x","mainClass":"Main"}""", environment)
    }

    // ---- arguments.jvm 展开（2026-11-08 性能排查补齐的兼容缺口）----

    /** 1.20.1 官方 json 的 jvm 段结构（规则项 + 纯字符串项 + classpath）。 */
    private fun jvmJson(): String = """
        {"arguments":{"jvm":[
            {"rules":[{"action":"allow","os":{"name":"osx"}}],"value":["-XstartOnFirstThread"]},
            {"rules":[{"action":"allow","os":{"name":"windows"}}],"value":"-XX:HeapDumpPath=Trick.exe.heapdump"},
            {"rules":[{"action":"allow","os":{"arch":"x86"}}],"value":"-Xss1M"},
            "-Djava.library.path=${'$'}{natives_directory}",
            "-Djna.tmpdir=${'$'}{natives_directory}",
            "-Dminecraft.launcher.brand=${'$'}{launcher_name}",
            "-Dminecraft.launcher.version=${'$'}{launcher_version}",
            "-DignoreList=bootstraplauncher,securejarhandler",
            "-cp","${'$'}{classpath}"
        ]},"id":"1.20.1","mainClass":"Main"}""".trimIndent()

    @Test
    fun `jvm段 规则过滤掉 osx windows x86 项`() {
        val args = GameArgumentExpander.buildJvmArgs(
            versionJsonText = jvmJson(),
            context = context(),
            environment = environment,
            classpathPaths = listOf("/a.jar", "/b.jar"),
            nativesDir = File("/data/cache"),
            userHome = File("/data/files"),
            launcherName = "Vaelin",
            launcherVersion = "1.0",
        )
        assertTrue(args.none { it.contains("XstartOnFirstThread") })
        assertTrue(args.none { it.contains("HeapDumpPath") })
        assertTrue(args.none { it == "-Xss1M" })
    }

    @Test
    fun `jvm段 变量替换与特殊重写`() {
        val args = GameArgumentExpander.buildJvmArgs(
            versionJsonText = jvmJson(),
            context = context(),
            environment = environment,
            classpathPaths = listOf("/a.jar", "/b.jar"),
            nativesDir = File("/data/cache"),
            userHome = File("/data/files"),
            launcherName = "Vaelin",
            launcherVersion = "1.0",
        )
        assertTrue(args.contains("-Djava.library.path=" + File("/data/cache").absolutePath))
        assertTrue(args.contains("-Djna.tmpdir=" + File("/data/cache").absolutePath))
        assertTrue(args.contains("-Dminecraft.launcher.brand=Vaelin"))
        assertTrue(args.contains("-Dminecraft.launcher.version=1.0"))
        // ignoreList 追加版本 jar 名
        assertTrue(args.contains("-DignoreList=bootstraplauncher,securejarhandler,1.20.1.jar"))
    }

    @Test
    fun `jvm段 丢弃 cp 与 classpath token 由启动器统一追加`() {
        val args = GameArgumentExpander.buildJvmArgs(
            versionJsonText = jvmJson(),
            context = context(),
            environment = environment,
            classpathPaths = listOf("/a.jar"),
            nativesDir = File("/data/cache"),
            userHome = File("/data/files"),
            launcherName = "Vaelin",
            launcherVersion = "1.0",
        )
        assertTrue(args.none { it == "-cp" })
        assertTrue(args.none { it == "-classpath" })
        assertTrue(args.none { it.contains("classpath") && it.startsWith("/") })
        assertTrue(args.none { it.contains("a.jar") })
    }

    @Test
    fun `无 arguments jvm 时返回空列表`() {
        val args = GameArgumentExpander.buildJvmArgs(
            versionJsonText = """{"id":"x","mainClass":"Main"}""",
            context = context(),
            environment = environment,
            classpathPaths = listOf("/a.jar"),
            nativesDir = File("/data/cache"),
            userHome = File("/data/files"),
            launcherName = "Vaelin",
            launcherVersion = "1.0",
        )
        assertEquals(emptyList<String>(), args)
    }
}
