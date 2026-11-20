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

import com.vaelin.startup.multirt.VRuntime
import com.vaelin.startup.profile.GameDirectoryMode
import com.vaelin.startup.profile.VersionProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@Suppress("NonAsciiCharacters")
class LaunchPlanTest {

    private fun plan(): LaunchPlan {
        val profile = VersionProfile(
            id = "1.20.1-vanilla",
            name = "我的世界",
            minecraftVersion = "1.20.1",
            modLoader = "fabric",
            modLoaderVersion = "0.15.0",
            rendererId = "opengles2",
            rendererUniqueId = "uid-1",
            memoryMB = 2048,
            extraJvmArgs = listOf("-Dx=1", "-Dy=中文 值"),
            javaRuntime = null,
            lastPlayedAt = 1700000000000L,
        )
        val runtime = VRuntime(
            name = "jre-17",
            versionString = "17.0.8",
            arch = "arm64",
            javaVersion = 17,
            isProvidedByLauncher = true,
            isJDK8 = false,
        )
        return LaunchPlan.build(
            profile = profile,
            gameDirectory = File("/data/games/versions/1.20.1-vanilla"),
            gameDirectoryMode = GameDirectoryMode.ISOLATED,
            runtime = runtime,
            runtimeHome = File("/data/games/runtimes/jre-17"),
            classpath = listOf(File("/libs/a.jar"), File("/libs/b.jar"), File("/versions/1.20.1/1.20.1.jar")),
            mainClass = "net.minecraft.client.main.Main",
            gameArgs = listOf("--username", "Steve", "--userType", "legacy", "--中文", "参数=含等号"),
            lwjglComponentDir = "lwjgl/3.3.3",
        )
    }

    @Test
    fun `编码再解码完整还原所有字段含中文与等号`() {
        val original = plan()

        val restored = LaunchPlanCodec.decode(LaunchPlanCodec.encode(original))

        assertEquals(original, restored)
        assertEquals(3, restored.classpath.size)
        assertEquals("lwjgl/3.3.3", restored.lwjglComponentDir)
        assertEquals(listOf("-Dx=1", "-Dy=中文 值"), restored.extraJvmArgs)
        assertEquals(listOf("--username", "Steve", "--userType", "legacy", "--中文", "参数=含等号"), restored.gameArgs)
        // 用 original 自己的 absolutePath 做期望，避免硬编码 Unix 路径在 Windows 上带盘符
        assertEquals(original.classpath, restored.classpath)
    }

    @Test
    fun `toVRuntime 与 toProfile 还原出可直接交给 VaelinLauncher 的对象`() {
        val p = plan()

        assertEquals("jre-17", p.toVRuntime().name)
        assertEquals("arm64", p.toVRuntime().arch)
        assertEquals(17, p.toVRuntime().javaVersion)
        assertEquals(true, p.toVRuntime().isProvidedByLauncher)
        assertEquals(false, p.toVRuntime().isJDK8)
        assertEquals("1.20.1-vanilla", p.toProfile().id)
        assertEquals(File("/data/games/versions/1.20.1-vanilla").absolutePath, p.gameDirectory)
        assertEquals(GameDirectoryMode.ISOLATED, p.gameDirectoryMode)
        assertEquals("我的世界", p.toProfile().name)
        assertEquals(2048, p.toProfile().memoryMB)
        assertEquals(listOf("-Dx=1", "-Dy=中文 值"), p.toProfile().extraJvmArgs)
        assertEquals(
            listOf(File("/libs/a.jar"), File("/libs/b.jar")).map { it.absolutePath },
            p.classpathFiles().take(2).map { it.absolutePath },
        )
        assertNull(p.toProfile().javaRuntime)
    }

    @Test
    fun `可选字符串为 null 时不写该行解码后仍为 null`() {
        val withNulls = plan().copy(
            runtimeVersion = null,
            runtimeArch = null,
            modLoaderVersion = null,
        )
        val encoded = LaunchPlanCodec.encode(withNulls)

        assertTrue(encoded.lineSequence().none { it.startsWith("runtimeVersion=") })
        assertTrue(encoded.lineSequence().none { it.startsWith("runtimeArch=") })
        assertTrue(encoded.lineSequence().none { it.startsWith("modLoaderVersion=") })
        assertTrue(encoded.lineSequence().none { it.startsWith("javaRuntime=") })
        assertNull(LaunchPlanCodec.decode(encoded).runtimeVersion)
        assertNull(LaunchPlanCodec.decode(encoded).runtimeArch)
        assertNull(LaunchPlanCodec.decode(encoded).modLoaderVersion)
        assertNull(LaunchPlanCodec.decode(encoded).javaRuntime)
    }

    @Test(expected = LaunchException::class)
    fun `缺少必需字段时显式报错`() {
        LaunchPlanCodec.decode("fmt=Mw==")
    }

    @Test(expected = LaunchException::class)
    fun `格式版本不匹配时显式报错`() {
        val encoded = LaunchPlanCodec.encode(plan())
        val unsupported = encoded.replaceFirst(Regex("(?m)^fmt=.*$"), "fmt=OTk=")
        LaunchPlanCodec.decode(unsupported)
    }

    @Test(expected = LaunchException::class)
    fun `非法 Base64 时显式报错`() {
        LaunchPlanCodec.decode("fmt=!!!not-base64!!!")
    }
}
