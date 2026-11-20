/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * R1 · T1.10 安装器 JVM 进程打通 —— 纯 JVM 契约层。
 *
 * 本文件是「逻辑层移植」产物：协议常量、进程互斥判定、配置数值、状态机所需的
 * 抽象接口全部来自 ZL2 game/download/jvm_server/（GameJVMRunner.kt / _JvmService.kt /
 * JVMSocketServer.kt），但剥掉了 GlobalContext、AllSettings、PathManager、协程
 * 与一切 android.* 依赖。目的：让「启动 → 等 socket 绑定 → 收进度 → 拿退出码 →
 * 超时/失败」这条状态机可以在普通 JUnit 单测里零网络、零真实进程地独立验证。
 *
 * 本文件（以及同层其它纯 JVM 文件）不得 import android.*，也不得引用 VLog / VPaths。
 */

package com.vaelin.startup.jvm

// ---------------------------------------------------------------------
// 跨进程 UDP 协议与 Intent extra 契约
// 与 ZL2 _JvmService.kt:29-45 保持一致，值不可改动（两侧是不同进程，靠字面量对齐）
// ---------------------------------------------------------------------

/** 主进程 JVMSocketServer 绑定的回环端口 */
const val PROCESS_SERVICE_PORT = 53151

const val SERVICE_JVM_ARGS = "service.jvm.args"
const val SERVICE_JRE_NAME = "service.jre.name"
const val SERVICE_USER_HOME = "service.user.home"
const val SERVICE_POST_SUMMARY = "service.post.summary"
const val SERVICE_POST_PROGRESS = "service.post.progress"

/** :jvm 进程发往主进程的消息前缀 */
const val MSG_EXIT_PREFIX = "EXIT:"
const val MSG_PROGRESS_PREFIX = "PROGRESS:"

// ---------------------------------------------------------------------
// 进程互斥（ZL2 _JvmService.kt: JVM_EXCLUSIVE_SUFFIXES / isJvmExclusiveProcess）
// ---------------------------------------------------------------------

private val JVM_EXCLUSIVE_SUFFIXES = listOf(":jvm", ":game")

/**
 * 判断一个进程名是否占用了「JVM 独占槽」。
 *
 * 精确匹配而非 startsWith：com.vaelin.launcher:jvm 命中，
 * com.vaelin.launcher:jvmx、其它包名前缀均不命中（ZL2 JvmExclusiveProcessTest.kt）。
 */
fun isJvmExclusiveProcess(processName: String, mainProcessName: String): Boolean =
    JVM_EXCLUSIVE_SUFFIXES.any { processName == mainProcessName + it }

// ---------------------------------------------------------------------
// 可调度的配置（数值照搬 ZL2 GameJVMRunner.kt:44-58，Duration 折算为毫秒）
// ---------------------------------------------------------------------

data class JvmSchedulerConfig(
    /** startForegroundService 的最大重试次数 */
    val serviceStartRetryMax: Int = 4,
    /** 重试指数退避基数：500ms * 2^(attempt-1) */
    val serviceStartRetryBaseDelayMs: Long = 500L,
    /** 等待互斥进程自行退出多久后强杀 */
    val waitBeforeForceKillMs: Long = 15_000L,
    /** 强杀后再给多久宽限，超过即以超时结束 */
    val killGraceMs: Long = 5_000L,
    /** 等待期间打日志的间隔 */
    val waitLogIntervalMs: Long = 2_000L,
    /** 等服务进程回报退出码的总超时 */
    val jvmExitTimeoutMs: Long = 15L * 60L * 1000L,
    /** 互斥进程清空后的冷静期，防止进程刚死又被判为可用 */
    val postProcessExitCooldownMs: Long = 1_000L,
    /** 单次 awaitEvent 的轮询窗口；窗口之间检查目标进程是否仍存活 */
    val pollWindowMs: Long = 100L,
    /** 互斥进程轮询间隔（ZL2 用 delay(100ms)） */
    val exclusivePollMs: Long = 100L,
)

// ---------------------------------------------------------------------
// 请求与事件
// ---------------------------------------------------------------------

/**
 * 一次安装器 JVM 启动请求。jvmArgs 是 ZL2 传入的单条命令串
 * （`-cp <cp> <mainClass> <args>`，见 Install.ForgeLike.kt:355-364）。
 */
data class InstallJvmRequest(
    val jvmArgs: String,
    val jreName: String? = null,
    val userHome: String? = null,
    val postSummary: String? = null,
) {
    /** JRE 升级重试时给命令串加前缀（对应 ZL2 的 prefixArgs(jre)） */
    fun withPrefix(prefix: String?): InstallJvmRequest =
        if (prefix.isNullOrBlank()) this else copy(jvmArgs = "$prefix $jvmArgs")
}

/** :jvm 进程通过 UDP 送达的事件 */
sealed class JvmEvent {
    data class Progress(val text: String) : JvmEvent()
    data class Exit(val code: Int) : JvmEvent()
}

// ---------------------------------------------------------------------
// 抽象接口：把 socket、Service、进程查询、时间全部挡在纯 JVM 状态机之外
// ---------------------------------------------------------------------

/**
 * 主进程侧的「接收端」。真实实现由 JVMSocketServer 绑定 UDP socket 后提供；
 * 单测里用内存 Fake，全程零网络、零真实进程。
 */
interface JvmEventSource : AutoCloseable {
    /** 阻塞等待下一条事件；超过 timeoutMillis 返回 null。 */
    fun awaitEvent(timeoutMillis: Long): JvmEvent?
    override fun close()
}

/**
 * 宿主能力集合。Android 侧实现持有 Context，纯 JVM 单测用 Fake。
 */
interface JvmProcessControl {
    /**
     * 绑定接收端。**必须先于 startService 调用**：JVM 秒退时退出码不会因
     * socket 尚未绑定而丢失（ZL2 GameJVMRunner.kt:186 的语义注释）。
     * 绑定失败抛 [JvmSocketBindException]；调度器据此显式中止，不起僵尸服务。
     */
    fun openEventSource(): JvmEventSource

    /** 启动 :jvm 前台服务；抛异常表示本次启动失败，由调度器重试。 */
    fun startService(request: InstallJvmRequest)

    /** 停止服务并收掉接收端，无论成败都要执行，避免单例状态跨轮残留毒化下一次运行。 */
    fun stopService()

    /** 列出占用 JVM 独占槽的其它进程，形如 "com.vaelin.launcher:jvm(1234)" */
    fun listBlockingProcesses(): List<String>

    /** 强杀所有占槽的非主进程 */
    fun stopAllNonMainProcesses()

    /** :jvm 目标进程是否仍存活（用于区分「超时」与「目标进程被杀」） */
    fun isTargetProcessAlive(): Boolean
}

/** 可注入时间源；Fake 用假时钟让 15 分钟超时在毫秒内走完 */
interface JvmClock {
    fun nowMillis(): Long
}

/** 可注入休眠；Fake 不真睡，只推进假时钟 */
interface JvmSleeper {
    fun sleep(millis: Long)
}

// ---------------------------------------------------------------------
// 异常体系：全部显式报错并中止，禁止「记日志后继续」掩盖错误
// ---------------------------------------------------------------------

open class JvmSchedulerException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** UDP 接收端绑定失败（socket 始终不绑定） */
class JvmSocketBindException(message: String, cause: Throwable? = null) :
    JvmSchedulerException(message, cause)

/** startForegroundService 重试 serviceStartRetryMax 次仍失败 */
class JvmServiceStartException(message: String, cause: Throwable? = null) :
    JvmSchedulerException(message, cause)

/** 服务活着但迟迟不回报退出码，超过 jvmExitTimeoutMs */
class JvmExitTimeoutException(timeoutMs: Long) :
    JvmSchedulerException("Timed out waiting for the JVM process to exit. (timeout=${timeoutMs}ms)")

/** 服务进程在回报退出码之前被杀 / 消失 */
class JvmProcessKilledException :
    JvmSchedulerException("The installer JVM process was killed before it reported an exit code.")

/** 互斥进程始终不肯退出，超过 waitBeforeForceKillMs + killGraceMs */
class JvmExclusiveProcessTimeoutException(blocking: List<String>) :
    JvmSchedulerException("Timed out waiting for exclusive processes to stop: ${blocking.joinToString()}")

/** 同一调度器实例上并发启动第二次 */
class ConcurrentJvmStartException :
    JvmSchedulerException("An installer JVM run is already in progress on this scheduler.")
