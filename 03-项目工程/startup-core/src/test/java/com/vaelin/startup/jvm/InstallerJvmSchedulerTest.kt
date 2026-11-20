/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * R1 · T1.10 —— InstallerJvmScheduler 状态机单测。
 * 全部零网络、零真实进程、零真实等待（Fake 时钟 + 内存事件队列）。
 */

package com.vaelin.startup.jvm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class InstallerJvmSchedulerTest {

    /** 把 15 分钟 / 15 秒 级阈值压到毫秒，让超时与清场在单测里毫秒级走完 */
    private fun fastConfig() = JvmSchedulerConfig(
        serviceStartRetryMax = 4,
        serviceStartRetryBaseDelayMs = 10L,
        waitBeforeForceKillMs = 1_000L,
        killGraceMs = 500L,
        waitLogIntervalMs = 500L,
        jvmExitTimeoutMs = 1_000L,
        postProcessExitCooldownMs = 200L,
        pollWindowMs = 250L,
        exclusivePollMs = 100L,
    )

    private val request = InstallJvmRequest(
        jvmArgs = "-cp /data/a.jar:/data/b.jar net.minecraftforge.installer.SimpleInstaller --install",
        jreName = "Internal-8",
        userHome = "/sdcard/game",
    )

    // -----------------------------------------------------------------
    // 五个必测场景
    // -----------------------------------------------------------------

    /** 1. 正常退出码：收 PROGRESS + EXIT:0，接收端与兜底清理都执行 */
    @Test
    fun `normal exit code with progress events`() {
        val h = FakeHarness(fastConfig())
        h.control.enqueue(
            JvmEvent.Progress("10%"),
            JvmEvent.Progress("done"),
            JvmEvent.Exit(0),
        )
        val code = h.scheduler.runOnce(request)
        assertEquals(0, code)
        assertEquals(listOf("10%", "done"), h.progress)
        assertEquals(1, h.control.startCalls.size)
        assertEquals(1, h.control.closeCalls)   // 接收端被收掉
        assertEquals(1, h.control.stopCalls)    // 兜底 stopService
    }

    /** 2. socket 始终不绑定：显式报错，且绝不拉起一个没人收码的僵尸服务 */
    @Test
    fun `socket never binds aborts before starting service`() {
        val h = FakeHarness(fastConfig())
        h.control.bindFails = true
        val e = expectThrows<JvmSocketBindException> { h.scheduler.runOnce(request) }
        assertTrue(e.message!!.contains("bind"))
        assertEquals(0, h.control.startCalls.size)
        assertEquals(0, h.control.closeCalls)
        assertEquals(0, h.control.stopCalls)
    }

    /** 3. 超时：服务活着但迟迟不回报退出码，以可见异常结束而非永久挂起 */
    @Test
    fun `timeout when service alive but no exit code`() {
        val h = FakeHarness(fastConfig()) // 1000ms / 250ms 窗口 = 4 轮
        h.control.targetAlive = true
        val e = expectThrows<JvmExitTimeoutException> { h.scheduler.runOnce(request) }
        assertTrue(e.message!!.contains("Timed out"))
        assertTrue(h.clock.now >= 1_000L)
        assertEquals(1, h.control.closeCalls)  // 超时也要收接收端
        assertEquals(1, h.control.stopCalls)
    }

    /** 4. 目标进程被杀：与「超时」区分，抛 JvmProcessKilledException */
    @Test
    fun `target process killed before reporting exit code`() {
        val h = FakeHarness(fastConfig())
        h.control.targetAlive = false
        val e = expectThrows<JvmProcessKilledException> { h.scheduler.runOnce(request) }
        assertTrue(e.message!!.contains("killed"))
        assertEquals(1, h.control.closeCalls)
    }

    /** 5. 并发启动互斥：两个线程同时 runOnce，第二个被拒且不影响第一个 */
    @Test
    fun `concurrent start is mutually exclusive`() {
        val h = FakeHarness(fastConfig())
        h.control.enqueue(JvmEvent.Exit(0))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        h.control.awaitBlocker = {
            entered.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        val firstCode = AtomicInteger(-999)
        val firstErr = AtomicReference<Throwable?>(null)
        val secondErr = AtomicReference<Throwable?>(null)

        val t1 = Thread {
            try {
                firstCode.set(h.scheduler.runOnce(request))
            } catch (t: Throwable) {
                firstErr.set(t)
            }
        }
        t1.start()
        assertTrue("first run should enter awaitEvent", entered.await(5, TimeUnit.SECONDS))

        val t2 = Thread {
            try {
                h.scheduler.runOnce(request)
            } catch (t: Throwable) {
                secondErr.set(t)
            }
        }
        t2.start()
        t2.join(5_000)
        release.countDown()
        t1.join(5_000)

        assertNull(firstErr.get())
        assertEquals(0, firstCode.get())
        assertTrue(
            "second run must be rejected, got ${secondErr.get()}",
            secondErr.get() is ConcurrentJvmStartException
        )
    }

    // -----------------------------------------------------------------
    // 补充边界：重试 / 清场 / 陈旧状态
    // -----------------------------------------------------------------

    /** 非零退出码 + JRE 8→17→21 全失败 → 可见 JvmCrashException */
    @Test
    fun `runtime retry chain exhausts with crash`() {
        val h = FakeHarness(fastConfig())
        h.control.enqueue(JvmEvent.Exit(1), JvmEvent.Exit(1), JvmEvent.Exit(1))
        val e = expectThrows<JvmCrashException> {
            h.scheduler.runWithRuntimes(request, listOf("Internal-8", "Internal-17", "Internal-21"))
        }
        assertEquals(1, e.code)
        assertEquals(3, h.control.startCalls.size)
        assertEquals("Internal-8", h.control.startCalls[0].jreName)
        assertEquals("Internal-17", h.control.startCalls[1].jreName)
        assertEquals("Internal-21", h.control.startCalls[2].jreName)
    }

    /** JRE 重试中途成功即停止，不再拉起后续运行时 */
    @Test
    fun `runtime retry stops at first success`() {
        val h = FakeHarness(fastConfig())
        h.control.enqueue(JvmEvent.Exit(1), JvmEvent.Exit(0))
        val code = h.scheduler.runWithRuntimes(request, listOf("a", "b", "c"))
        assertEquals(0, code)
        assertEquals(2, h.control.startCalls.size)
    }

    /** startForegroundService 失败重试后成功（指数退避由 Fake 时钟推进） */
    @Test
    fun `service start retries then succeeds`() {
        val h = FakeHarness(fastConfig())
        h.control.enqueue(JvmEvent.Exit(0))
        h.control.startFailures = 2
        assertEquals(0, h.scheduler.runOnce(request))
        assertEquals(3, h.control.startCalls.size)
        assertTrue(h.clock.now > 0L)
    }

    /** 重试耗尽：显式 JvmServiceStartException，接收端仍被清理 */
    @Test
    fun `service start retries exhausted`() {
        val h = FakeHarness(fastConfig())
        h.control.startFailures = 10
        val e = expectThrows<JvmServiceStartException> { h.scheduler.runOnce(request) }
        assertTrue(e.message!!.contains("5 attempts"))
        assertEquals(5, h.control.startCalls.size) // 4 次重试 + 1 次最终失败
        assertEquals(1, h.control.closeCalls)
    }

    /** 互斥进程清场：到点强杀 → 冷静期复查 → 继续启动 */
    @Test
    fun `blocking exclusive processes are force stopped`() {
        val h = FakeHarness(fastConfig())
        h.control.blocking.add("com.vaelin.launcher:jvm(999)")
        h.control.enqueue(JvmEvent.Exit(0))
        assertEquals(0, h.scheduler.runOnce(request))
        assertEquals(1, h.control.forceKillCalls)
        assertTrue(h.control.blocking.isEmpty())
    }

    /** 互斥进程始终不退：显式超时，且绝不进入启动流程 */
    @Test
    fun `exclusive process that never stops times out`() {
        val h = FakeHarness(fastConfig())
        h.control.clearBlockingOnKill = false
        h.control.blocking.add("com.vaelin.launcher:game(888)")
        val e = expectThrows<JvmExclusiveProcessTimeoutException> { h.scheduler.runOnce(request) }
        assertTrue(e.message!!.contains(":game(888)"))
        assertEquals(0, h.control.startCalls.size)
        assertEquals(1, h.control.forceKillCalls)
    }

    /** 连续两轮不会复用上一轮的陈旧退出码（先绑新接收端再拉服务） */
    @Test
    fun `sequential runs do not reuse stale exit code`() {
        val h = FakeHarness(fastConfig())
        h.control.enqueue(JvmEvent.Exit(7))
        assertEquals(7, h.scheduler.runOnce(request))
        h.control.enqueue(JvmEvent.Exit(0))
        assertEquals(0, h.scheduler.runOnce(request))
        assertFalse(h.control.eventQueue.isNotEmpty())
    }

    /** withPrefix 只改 jvmArgs，不污染其它字段；空前缀返回自身 */
    @Test
    fun `request withPrefix keeps other fields`() {
        val prefixed = request.withPrefix("-Dfoo=bar")
        assertTrue(prefixed.jvmArgs.startsWith("-Dfoo=bar "))
        assertEquals(request.jreName, prefixed.jreName)
        assertEquals(request.userHome, prefixed.userHome)
        assertEquals(request, request.withPrefix(null))
        assertEquals(request, request.withPrefix("  "))
    }
}
