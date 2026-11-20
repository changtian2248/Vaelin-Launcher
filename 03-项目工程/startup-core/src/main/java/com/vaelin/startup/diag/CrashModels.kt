/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Vaelin 自有实现。T1.13 的数据模型，纯 JVM、零 Android 依赖。
 */

package com.vaelin.startup.diag

/**
 * 三类启动失败（任务书 T1.13）：
 *  - [JVM_START_FAILED]：java 进程拉不起来（VMLauncher 返回非 0 或 ZLBridge 异常）
 *  - [RENDERER_DLOPEN_FAILED]：渲染器库加载失败
 *  - [MC_CRASHED]：游戏进程跑起来后非 0 退出
 *
 * [logTag] 就是落盘日志里可 grep 的分类字符串，保证三类在日志里可区分。
 */
enum class FailureCategory(val logTag: String) {
    JVM_START_FAILED("JVM_START_FAILED"),
    RENDERER_DLOPEN_FAILED("RENDERER_DLOPEN_FAILED"),
    MC_CRASHED("MC_CRASHED")
}

/** 启动编排上报失败时所处的阶段——分类的关键输入之一。 */
enum class LaunchStage {
    /** 正在拉起 java 进程：setLdLibraryPath / dlopen JRE / buildJvmArgs / launchJVM 之前后 */
    JVM_BOOT,
    /** 渲染器主库 dlopen 阶段 */
    RENDERER_LOAD,
    /** 游戏主循环已运行 */
    GAME_RUNNING
}

/**
 * 一次失败信号。全部字段由调用方（启动编排或 UI 观测点）填入，
 * 单测用假的 exitCode / throwable 驱动，不依赖 Android、不启动真进程。
 */
data class LaunchFailureEvent(
    val stage: LaunchStage,
    val exitCode: Int = 0,
    val throwable: Throwable? = null,
    val rendererId: String = "",
    val targetVersion: String = "",
    /** :game 进程被系统杀掉（而非正常退出） */
    val gameProcessKilled: Boolean = false
)

/** 落盘的崩溃报告内容（crash-<时间戳>.log）。 */
data class CrashReport(
    val category: FailureCategory,
    val rendererId: String,
    val targetVersion: String,
    val jvmExitCode: Int,
    val stackTrace: String,
    val recentVLogLines: List<String>,
    val message: String,
    val timestampMillis: Long,
    val logFile: String
)

/** 可被 UI 读到的 :game 进程状态快照（R1 不做墓碑弹窗）。 */
data class GameProcessState(
    val processAlive: Boolean,
    val lastCategory: FailureCategory?,
    val lastExitCode: Int?,
    val note: String
)
