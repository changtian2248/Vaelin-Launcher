/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * R1 · T1.10 —— JVMHandler 协议/参数解析单测（纯 JVM）。
 */

package com.vaelin.startup.jvm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JVMHandlerTest {

    // ---- 跨进程消息协议 ----

    @Test
    fun `parse exit code`() {
        assertEquals(JvmEvent.Exit(0), JVMHandler.parse("EXIT:0"))
        assertEquals(JvmEvent.Exit(-3), JVMHandler.parse("EXIT:-3"))
        assertEquals(JvmEvent.Exit(42), JVMHandler.parse("EXIT: 42 "))
    }

    @Test
    fun `parse progress preserves text`() {
        assertEquals(JvmEvent.Progress("Installing 30%"), JVMHandler.parse("PROGRESS:Installing 30%"))
        assertEquals(JvmEvent.Progress(""), JVMHandler.parse("PROGRESS:"))
    }

    @Test
    fun `parse unknown message returns null`() {
        assertNull(JVMHandler.parse("random installer stdout"))
        assertNull(JVMHandler.parse(""))
    }

    @Test
    fun `malformed exit code throws explicitly`() {
        val e = expectThrows<JvmSchedulerException> { JVMHandler.parse("EXIT:not-a-number") }
        assertTrue(e.message!!.contains("Malformed exit message"))
    }

    @Test
    fun `encode round trip`() {
        assertEquals(JvmEvent.Exit(7), JVMHandler.parse(JVMHandler.encodeExit(7)))
        assertEquals(JvmEvent.Progress("hello"), JVMHandler.parse(JVMHandler.encodeProgress("hello")))
    }

    // ---- installer 命令串解析（契约来自 Install.ForgeLike.kt:355-364）----

    @Test
    fun `parse real installer args`() {
        val args = JVMHandler.parseServiceArgs(
            "-cp /data/a.jar:/data/b.jar net.minecraftforge.installer.SimpleInstaller --install /sdcard/game"
        )
        assertEquals(listOf(File("/data/a.jar"), File("/data/b.jar")), args.classpath)
        assertEquals("net.minecraftforge.installer.SimpleInstaller", args.mainClass)
        assertEquals(listOf("--install", "/sdcard/game"), args.gameArgs)
        assertTrue(args.extraJvmArgs.isEmpty())
    }

    @Test
    fun `parse args with jvm flags and quoted path`() {
        val args = JVMHandler.parseServiceArgs(
            "-Xmx512m -Duser.home=\"/sdcard/My Games\" -cp /a.jar:/b.jar com.example.Main --offline"
        )
        assertEquals(listOf("-Xmx512m", "-Duser.home=/sdcard/My Games"), args.extraJvmArgs)
        assertEquals("com.example.Main", args.mainClass)
        assertEquals(listOf("--offline"), args.gameArgs)
    }

    @Test
    fun `parse classpath alias`() {
        val args = JVMHandler.parseServiceArgs("-classpath /x.jar pkg.Main")
        assertEquals(listOf(File("/x.jar")), args.classpath)
        assertEquals("pkg.Main", args.mainClass)
    }

    @Test
    fun `missing classpath throws`() {
        val e = expectThrows<JvmSchedulerException> {
            JVMHandler.parseServiceArgs("-Xmx512m pkg.Main")
        }
        assertTrue(e.message!!.contains("No classpath"))
    }

    @Test
    fun `missing main class throws`() {
        val e = expectThrows<JvmSchedulerException> {
            JVMHandler.parseServiceArgs("-cp /a.jar -Xmx512m")
        }
        assertTrue(e.message!!.contains("No mainClass"))
    }

    @Test
    fun `missing classpath value after dash cp throws`() {
        val e = expectThrows<JvmSchedulerException> {
            JVMHandler.parseServiceArgs("-cp")
        }
        assertTrue(e.message!!.contains("Missing classpath"))
    }

    @Test
    fun `normalize log line strips cr and collapses whitespace`() {
        assertEquals("Installing 50%", JVMHandler.normalizeLogLine("  Installing   50%\r"))
    }
}
