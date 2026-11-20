/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * R1 · T1.10 —— 纯 JVM 调度器的 Fake 宿主。
 * 零网络、零真实进程、零真实等待：socket/Service/进程查询/时间全部替换为内存实现。
 */

package com.vaelin.startup.jvm

import java.util.ArrayDeque

/** 假时钟：只有 sleeper.sleep 会推进它。 */
class FakeClock(var now: Long = 0L) : JvmClock {
    override fun nowMillis(): Long = now
}

/** 假休眠：不真睡，只推进假时钟。 */
class FakeSleeper(private val clock: FakeClock) : JvmSleeper {
    var totalSlept: Long = 0L
        private set

    override fun sleep(millis: Long) {
        clock.now += millis
        totalSlept += millis
    }
}

/**
 * 假宿主能力集合。所有断言可观察的副作用都计数/记录。
 * [reentrantProbe] 只触发一次，用于在调度器持有 running 锁期间重入验证并发互斥。
 */
class FakeJvmProcessControl : JvmProcessControl {
    val eventQueue = ArrayDeque<JvmEvent>()
    val blocking = mutableListOf<String>()
    val startCalls = mutableListOf<InstallJvmRequest>()
    var stopCalls = 0
        private set
    var closeCalls = 0
        private set
    var forceKillCalls = 0
        private set
    var bindFails = false
    var targetAlive = true
    var startFailures = 0
    var clearBlockingOnKill = true
    var reentrantProbe: (() -> Unit)? = null
    /** 每次 awaitEvent 进入时调用（不清空），用于线程测试里用 latch 卡住首个启动 */
    var awaitBlocker: (() -> Unit)? = null

    fun enqueue(vararg events: JvmEvent) {
        events.forEach(eventQueue::addLast)
    }

    override fun openEventSource(): JvmEventSource {
        if (bindFails) throw JvmSocketBindException("fake UDP bind failure")
        return object : JvmEventSource {
            // 每次 open 返回全新对象，模拟 JVMSocketServer.start() 清掉上一轮单例状态
            override fun awaitEvent(timeoutMillis: Long): JvmEvent? {
                awaitBlocker?.invoke()
                val probe = reentrantProbe
                reentrantProbe = null
                probe?.invoke()
                return if (eventQueue.isEmpty()) null else eventQueue.removeFirst()
            }

            override fun close() {
                closeCalls++
            }
        }
    }

    override fun startService(request: InstallJvmRequest) {
        startCalls.add(request)
        if (startFailures > 0) {
            startFailures--
            throw IllegalStateException("fake startForegroundService failure")
        }
    }

    override fun stopService() {
        stopCalls++
    }

    override fun listBlockingProcesses(): List<String> = blocking.toList()

    override fun stopAllNonMainProcesses() {
        forceKillCalls++
        if (clearBlockingOnKill) blocking.clear()
    }

    override fun isTargetProcessAlive(): Boolean = targetAlive
}

/** 轻量断言助手：跨文件可用，避免 JUnit 版本对 assertThrows 的 SAM 差异。 */
inline fun <reified T : Throwable> expectThrows(block: () -> Unit): T {
    try {
        block()
    } catch (e: Throwable) {
        if (e is T) return e
        throw AssertionError("Expected ${T::class.java.name} but got $e", e)
    }
    throw AssertionError("Expected ${T::class.java.name} but nothing was thrown")
}

/** 把上面三个 Fake 组装成一个可直接跑 [InstallerJvmScheduler] 的夹具。 */
class FakeHarness(config: JvmSchedulerConfig = JvmSchedulerConfig()) {
    val clock = FakeClock()
    val sleeper = FakeSleeper(clock)
    val control = FakeJvmProcessControl()
    val progress = mutableListOf<String>()
    val status = mutableListOf<String>()
    val scheduler = InstallerJvmScheduler(
        control = control,
        clock = clock,
        sleeper = sleeper,
        config = config,
        onProgress = { progress.add(it) },
        onStatus = { status.add(it) },
    )
}
