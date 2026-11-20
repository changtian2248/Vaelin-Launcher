/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * R1 · T1.10 —— 不依赖 Android 的「安装器 JVM 启动」纯 JVM 调度器。
 *
 * 这是 ZL2 GameJVMRunner.kt 的逻辑层移植：把
 *   waitForJvmExclusiveProcessesStopped / startJvmServiceAndWaitExit / runJvmRetryRuntimes
 * 三条编排抽成可在普通 JUnit 里跑的状态机。socket、Service、进程查询、时间全部通过
 * JvmProcessControl / JvmClock / JvmSleeper 注入；本文件不 import android.*，
 * 不引用 VLog / VPaths，不含协程。
 *
 * 关于「时钟推进」的契约（单测零真实等待的关键）：
 * - 真实实现：JvmClock = System.currentTimeMillis()；JvmEventSource.awaitEvent(window)
 *   用 DatagramSocket 的 soTimeout 阻塞，真实时钟自己前进，JvmSleeper 只在
 *   互斥进程轮询 / 重试退避 / 冷静期里真睡。
 * - Fake 实现：awaitEvent 不阻塞、返回内存事件；时钟不动，由调度器在循环末尾
 *   检测到「时钟没前进」时调用 sleeper.sleep(window) 推进假时钟。
 * 两种实现都保证 deadline 单调收敛，15 分钟超时在 Fake 里几毫秒内走完。
 */

package com.vaelin.startup.jvm

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 安装器 JVM 调度器。
 *
 * @param control 宿主能力（绑定接收端、起/停服务、查询/强杀进程）
 * @param clock 可注入时间源
 * @param sleeper 可注入休眠
 * @param config 全部阈值，默认照搬 ZL2 GameJVMRunner.kt:44-58
 * @param onProgress PROGRESS 事件回调（转给上层 UI）
 * @param onStatus 状态日志回调（Android 层接 VLog.info；纯 JVM 层不落日志）
 */
class InstallerJvmScheduler(
    private val control: JvmProcessControl,
    private val clock: JvmClock,
    private val sleeper: JvmSleeper,
    private val config: JvmSchedulerConfig = JvmSchedulerConfig(),
    private val onProgress: (String) -> Unit = {},
    private val onStatus: (String) -> Unit = {},
) {
    companion object {
        /**
         * JRE 升级重试链（ZL2 Jre.kt:23-26 / GameJVMRunner.kt:96-105：8 → 17 → 21）。
         * 这里只透传 jreName 字符串；Vaelin 侧若运行时命名不同，调用 [runWithRuntimes]
         * 传入实际链覆盖。纯 JVM 层不解析 JRE 目录。
         */
        val DEFAULT_RUNTIME_CHAIN: List<String?> =
            listOf("Internal-8", "Internal-17", "Internal-21")
    }

    /** 同一实例上并发启动互斥（ZL2 靠进程独占槽，这里再补一道实例级保护） */
    private val running = AtomicBoolean(false)

    /** 完整入口：含互斥清场 + JRE 8→17→21 运行时重试。 */
    fun run(request: InstallJvmRequest): Int =
        runWithRuntimes(request, DEFAULT_RUNTIME_CHAIN)

    /** 单次入口：只跑一次（不做 JRE 升级重试），便于独立验证五个失败/成功场景。 */
    fun runOnce(request: InstallJvmRequest): Int = guarded {
        waitForJvmExclusiveProcessesStopped()
        singleAttempt(request)
    }

    /** 可自定义运行时重试链的入口。退出码非 0 依次换链中下一项，耗尽抛 [JvmCrashException]。 */
    fun runWithRuntimes(request: InstallJvmRequest, runtimeChain: List<String?>): Int = guarded {
        require(runtimeChain.isNotEmpty()) { "runtimeChain must not be empty" }
        var lastCode = 0
        var success: Int? = null
        for (jreName in runtimeChain) {
            waitForJvmExclusiveProcessesStopped()
            val attempt = if (jreName != null) request.copy(jreName = jreName) else request
            lastCode = singleAttempt(attempt)
            if (lastCode == 0) {
                success = 0
                break
            }
            onStatus("Installer JVM exited with code $lastCode; retrying with another runtime.")
        }
        success ?: throw JvmCrashException(lastCode)
    }

    private fun guarded(block: () -> Int): Int {
        if (!running.compareAndSet(false, true)) throw ConcurrentJvmStartException()
        try {
            return block()
        } finally {
            running.set(false)
        }
    }

    // -----------------------------------------------------------------
    // 单次启动：先绑接收端 → 拉服务（重试）→ 等退出事件 → 清理
    // -----------------------------------------------------------------

    private fun singleAttempt(request: InstallJvmRequest): Int {
        // 先起接收端再拉起服务：JVM 秒退时退出码不会因 socket 尚未绑定而丢失
        // （ZL2 GameJVMRunner.kt:177-178 的语义）。绑定失败直接冒泡，不进 try，
        // 因此不会调用 stopService，也就不会起一个没人收码的僵尸服务。
        val source = control.openEventSource()
        try {
            startServiceWithRetry(request)
            return awaitExit(source)
        } finally {
            // 无论成败、取消还是超时都收掉接收端，单例状态跨轮残留会毒化下一次运行
            // （ZL2 GameJVMRunner.kt:225-226 的语义）。stopService 是兜底清理，
            // 正常退出时 :jvm 已 stopSelf，重复请求无害。
            source.close()
            control.stopService()
        }
    }

    /** startForegroundService 失败重试，指数退避（ZL2 :190-210）。 */
    private fun startServiceWithRetry(request: InstallJvmRequest) {
        var attempt = 0
        while (true) {
            try {
                control.startService(request)
                return
            } catch (e: Exception) {
                attempt++
                if (attempt > config.serviceStartRetryMax) {
                    throw JvmServiceStartException(
                        "Failed to start installer JVM service after $attempt attempts.", e
                    )
                }
                val backoff = config.serviceStartRetryBaseDelayMs * (1L shl (attempt - 1))
                sleeper.sleep(backoff)
            }
        }
    }

    /** 收 PROGRESS / EXIT 事件；超时或被杀都以可见异常结束，不永久挂起。 */
    private fun awaitExit(source: JvmEventSource): Int {
        val deadline = clock.nowMillis() + config.jvmExitTimeoutMs
        while (true) {
            val before = clock.nowMillis()
            val remaining = deadline - before
            if (remaining <= 0) {
                // 超时以可见错误结束而不是永久挂起（ZL2 GameJVMRunner.kt:217）
                throw JvmExitTimeoutException(config.jvmExitTimeoutMs)
            }
            val window = minOf(config.pollWindowMs, remaining)
            val event = source.awaitEvent(window)
            when (event) {
                is JvmEvent.Exit -> return event.code
                is JvmEvent.Progress -> onProgress(event.text)
                null -> {
                    // 服务活着但没事件 → 继续等；服务已消失 → 与「超时」区分开
                    if (!control.isTargetProcessAlive()) throw JvmProcessKilledException()
                }
            }
            // 时间单调推进：真实实现里 awaitEvent 的阻塞已推进真实时钟；
            // Fake 不阻塞，这里用 sleeper 推进假时钟，避免忙循环与假超时。
            if (clock.nowMillis() <= before) sleeper.sleep(window)
        }
    }

    // -----------------------------------------------------------------
    // 互斥进程清场（ZL2 GameJVMRunner.kt:117-160 精确移植）
    // -----------------------------------------------------------------

    private fun waitForJvmExclusiveProcessesStopped() {
        val start = clock.nowMillis()
        var lastLog = -config.waitLogIntervalMs
        var forceKilled = false
        var sawBlocking = false
        while (true) {
            val blocking = control.listBlockingProcesses()
            if (blocking.isEmpty()) {
                if (!sawBlocking) return
                // 见过阻塞进程后，清空还需一个冷静期，防止进程刚死又被判为可用
                sleeper.sleep(config.postProcessExitCooldownMs)
                if (control.listBlockingProcesses().isEmpty()) return
                continue
            }
            sawBlocking = true
            val elapsed = clock.nowMillis() - start
            if (elapsed >= config.waitBeforeForceKillMs + config.killGraceMs) {
                throw JvmExclusiveProcessTimeoutException(blocking)
            }
            if (elapsed >= config.waitBeforeForceKillMs && !forceKilled) {
                forceKilled = true
                onStatus("Force-stopping blocking JVM processes: ${blocking.joinToString()}")
                control.stopAllNonMainProcesses()
            }
            if (elapsed - lastLog >= config.waitLogIntervalMs) {
                lastLog = elapsed
                onStatus("Waiting for other processes to stop... (${blocking.joinToString()})")
            }
            sleeper.sleep(config.exclusivePollMs)
        }
    }
}
