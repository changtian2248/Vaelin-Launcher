/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * R1 · T1.10 —— 协议契约 / 进程互斥 / 日志切分单测（纯 JVM）。
 * 零网络、零真实进程；日志文件用 java 临时文件，不连接外部世界。
 */

package com.vaelin.startup.jvm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

class JvmContractTest {

    // ---- 协议字面量（两侧不同进程，靠字面量对齐，改值即破坏协议）----

    @Test
    fun `protocol constants are stable`() {
        assertEquals(53151, PROCESS_SERVICE_PORT)
        assertEquals("service.jvm.args", SERVICE_JVM_ARGS)
        assertEquals("service.jre.name", SERVICE_JRE_NAME)
        assertEquals("service.user.home", SERVICE_USER_HOME)
        assertEquals("EXIT:", MSG_EXIT_PREFIX)
        assertEquals("PROGRESS:", MSG_PROGRESS_PREFIX)
    }

    // ---- 进程互斥（精确匹配，ZL2 JvmExclusiveProcessTest.kt 语义）----

    @Test
    fun `exclusive process names match exactly`() {
        val main = "com.vaelin.launcher"
        assertTrue(isJvmExclusiveProcess("$main:jvm", main))
        assertTrue(isJvmExclusiveProcess("$main:game", main))
        assertFalse(isJvmExclusiveProcess("$main:jvmx", main))
        assertFalse(isJvmExclusiveProcess("$main:filemanager", main))
        assertFalse(isJvmExclusiveProcess(main, main))
        assertFalse(isJvmExclusiveProcess("com.other.app:jvm", main))
        assertFalse(isJvmExclusiveProcess("$main:other", main))
    }

    // ---- JvmCrashException 携带退出码 ----

    @Test
    fun `crash exception carries code`() {
        val e = JvmCrashException(137)
        assertEquals(137, e.code)
        assertTrue(e.message!!.contains("137"))
        // 继承契约：直接父类是 JvmSchedulerException（用反射避免编译期恒真判断）
        assertEquals(JvmSchedulerException::class.java, e.javaClass.superclass)
    }

    // ---- ProcessLogTailer.splitCompleteLines（纯字节切分）----

    @Test
    fun `splitCompleteLines keeps only complete lines`() {
        val bytes = "one\ntwo\nthr".toByteArray(StandardCharsets.UTF_8)
        val (lines, remainder) = ProcessLogTailer.splitCompleteLines(bytes)
        assertEquals(listOf("one", "two"), lines)
        assertEquals("thr", String(remainder, StandardCharsets.UTF_8))
    }

    @Test
    fun `splitCompleteLines handles CRLF`() {
        val bytes = "a\r\nb\r\n".toByteArray(StandardCharsets.UTF_8)
        val (lines, remainder) = ProcessLogTailer.splitCompleteLines(bytes)
        assertEquals(listOf("a", "b"), lines)
        assertEquals(0, remainder.size)
    }

    @Test
    fun `splitCompleteLines with no newline returns all as remainder`() {
        val bytes = "partial".toByteArray(StandardCharsets.UTF_8)
        val (lines, remainder) = ProcessLogTailer.splitCompleteLines(bytes)
        assertTrue(lines.isEmpty())
        assertEquals("partial", String(remainder, StandardCharsets.UTF_8))
    }

    @Test
    fun `splitCompleteLines does not cut multibyte utf8`() {
        // "中" 3 字节 + "文" 3 字节 + "\n" 1 字节 = 7 字节。
        // 只取前 4 字节 = 一个完整 "中" + "文" 的首字节（半个多字节字符）。
        // 没有换行 → 全部 4 字节作为余量跨轮保留，不被强行解码成半个字符。
        val full = "中文\n".toByteArray(StandardCharsets.UTF_8)
        assertEquals(7, full.size)
        val cut = full.copyOfRange(0, 4)
        val (lines, remainder) = ProcessLogTailer.splitCompleteLines(cut)
        assertTrue(lines.isEmpty())
        assertEquals(4, remainder.size)
    }

    // ---- ProcessLogTailer.poll（增量读 + 截断重读）----

    @Test
    fun `tailer reads only newly appended lines`() {
        val file = File.createTempFile("vaelin-tailer", ".log")
        try {
            file.writeText("old line\n", StandardCharsets.UTF_8)
            val sink = mutableListOf<String>()
            val tailer = ProcessLogTailer(file, LogLineSink { sink.addAll(it) })
            assertEquals(0, tailer.poll()) // 起始偏移在末尾，老内容不读

            file.appendText("Installing 10%\nInstalling 20%\n", StandardCharsets.UTF_8)
            assertEquals(2, tailer.poll())
            assertEquals(listOf("Installing 10%", "Installing 20%"), sink)

            assertEquals(0, tailer.poll()) // 没有新内容不重复读
        } finally {
            file.delete()
        }
    }

    @Test
    fun `tailer re-reads from start when file is truncated`() {
        val file = File.createTempFile("vaelin-tailer", ".log")
        try {
            file.writeText("first\nsecond\n", StandardCharsets.UTF_8)
            val sink = mutableListOf<String>()
            val tailer = ProcessLogTailer(file, LogLineSink { sink.addAll(it) })
            assertEquals(0, tailer.poll())

            file.writeText("rewritten\n", StandardCharsets.UTF_8) // 比旧偏移短 = 截断
            assertEquals(1, tailer.poll())
            assertEquals(listOf("rewritten"), sink)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `tailer joins remainder across polls`() {
        val file = File.createTempFile("vaelin-tailer", ".log")
        try {
            file.writeText("", StandardCharsets.UTF_8)
            val sink = mutableListOf<String>()
            val tailer = ProcessLogTailer(file, LogLineSink { sink.addAll(it) })

            file.appendText("hel", StandardCharsets.UTF_8) // 无换行，先攒着
            assertEquals(0, tailer.poll())
            file.appendText("lo\n", StandardCharsets.UTF_8) // 拼接后成完整行
            assertEquals(1, tailer.poll())
            assertEquals(listOf("hello"), sink)
        } finally {
            file.delete()
        }
    }
}
