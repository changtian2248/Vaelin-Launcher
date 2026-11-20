/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vaelin.startup.diag

import com.vaelin.startup.renderer.RendererLoadException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** T1.13 落盘：三类分类、字段完整性、最近 VLog 行、:game 被杀状态、文件不覆盖。 */
class CrashReporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun reporter(
        logs: List<String> = listOf("line-1", "line-2", "line-3"),
        now: () -> Long = { 1_700_000_000_000L },
        sink: MutableList<String> = mutableListOf(),
        limit: Int = 2
    ): Pair<CrashReporter, MutableList<String>> {
        val r = CrashReporter(
            outputDir = tmp.root,
            recentLogProvider = { logs },
            now = now,
            recentLogLineCount = limit,
            logSink = { sink += it }
        )
        return r to sink
    }

    @Test
    fun `JVM 启动失败落盘字段完整`() {
        val (r, sink) = reporter()
        val report = r.report(
            LaunchFailureEvent(
                stage = LaunchStage.JVM_BOOT,
                exitCode = 1,
                throwable = IllegalStateException("jre dlopen failed"),
                rendererId = "opengles2",
                targetVersion = "1.21.4"
            )
        )
        assertEquals(FailureCategory.JVM_START_FAILED, report.category)
        assertEquals("opengles2", report.rendererId)
        assertEquals("1.21.4", report.targetVersion)
        assertEquals(1, report.jvmExitCode)
        assertTrue(report.stackTrace.contains("jre dlopen failed"))
        assertEquals(listOf("line-2", "line-3"), report.recentVLogLines)

        val file = tmp.root.resolve(report.logFile)
        assertTrue(file.isFile)
        val body = file.readText()
        assertTrue(body.contains("分类: JVM_START_FAILED"))
        assertTrue(body.contains("渲染器 id: opengles2"))
        assertTrue(body.contains("目标版本: 1.21.4"))
        assertTrue(body.contains("JVM 退出码: 1"))
        assertTrue(body.contains("启动阶段: JVM_BOOT"))
        assertTrue(body.contains("jre dlopen failed"))
        assertTrue(body.contains("line-3"))
        assertFalse(body.contains("line-1")) // takeLast(2) 截掉了最旧一行
        assertTrue(body.trimEnd().endsWith("==== end ===="))
        assertTrue(sink.any { it.contains("category=JVM_START_FAILED") })
    }

    @Test
    fun `渲染器 dlopen 失败分类与堆栈`() {
        val (r, _) = reporter()
        val error = RendererLoadException(
            "opengles2", "libgl4es_114.so", listOf("/a/libgl4es_114.so")
        )
        val report = r.report(
            LaunchFailureEvent(
                stage = LaunchStage.RENDERER_LOAD,
                throwable = error,
                rendererId = "opengles2",
                targetVersion = "1.20.1"
            )
        )
        assertEquals(FailureCategory.RENDERER_DLOPEN_FAILED, report.category)
        val body = tmp.root.resolve(report.logFile).readText()
        assertTrue(body.contains("分类: RENDERER_DLOPEN_FAILED"))
        assertTrue(body.contains("渲染器 opengles2 加载失败：找不到 libgl4es_114.so"))
        assertTrue(body.contains("RendererLoadException"))
    }

    @Test
    fun `游戏进程非0退出分类 MC_CRASHED`() {
        val (r, _) = reporter()
        val report = r.report(
            LaunchFailureEvent(
                stage = LaunchStage.GAME_RUNNING,
                exitCode = 139,
                rendererId = "zink",
                targetVersion = "1.21.1"
            )
        )
        assertEquals(FailureCategory.MC_CRASHED, report.category)
        assertTrue(tmp.root.resolve(report.logFile).readText().contains("分类: MC_CRASHED"))
    }

    @Test
    fun `game 进程被杀留记录与状态`() {
        val (r, _) = reporter()
        assertNull(r.lastState)
        val report = r.recordGameProcessKilled("opengles2", "1.21.4", 9)
        assertEquals(FailureCategory.MC_CRASHED, report.category)

        val state = requireNotNull(r.lastState)
        assertFalse(state.processAlive)
        assertEquals(FailureCategory.MC_CRASHED, state.lastCategory)
        assertEquals(9, state.lastExitCode)
        assertTrue(state.note.contains(":game 进程被杀"))
        val body = tmp.root.resolve(report.logFile).readText()
        assertTrue(body.contains("游戏进程被杀: true"))
    }

    @Test
    fun `同时间戳连写不覆盖已有文件`() {
        val (r, _) = reporter()
        val a = r.report(LaunchFailureEvent(LaunchStage.JVM_BOOT, exitCode = 1))
        val b = r.report(LaunchFailureEvent(LaunchStage.JVM_BOOT, exitCode = 2))
        assertTrue(a.logFile != b.logFile)
        assertTrue(b.logFile.contains(".1.log"))
        assertTrue(tmp.root.resolve(a.logFile).isFile)
        assertTrue(tmp.root.resolve(b.logFile).isFile)
    }

    @Test
    fun `无 VLog 行时写占位`() {
        val (r, _) = reporter(logs = emptyList())
        val report = r.report(LaunchFailureEvent(LaunchStage.JVM_BOOT, exitCode = 1))
        val body = tmp.root.resolve(report.logFile).readText()
        assertTrue(body.contains("---- 最近 0 行 VLog ----"))
        assertTrue(body.contains("(无)"))
        assertTrue(body.contains("(无异常堆栈)"))
    }
}
