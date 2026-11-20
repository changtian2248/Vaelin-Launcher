/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Vaelin 自有实现。T1.13 的生产侧接线：把纯 JVM 的 CrashReporter 接到
 * VPaths.launcherLogsDir / VLog.currentLogFile / VLog.error，并把三类失败
 * 收敛成 4 个上报入口供启动编排调用。
 *
 * 边界：本任务不改 VaelinLauncher.kt（launch 包不在允许写入范围），
 * 因此这里只提供 API；由集成层在对应阶段回调，见各方法注释。
 * 前置条件：VPaths.init(context) 与 VLog.initialize(context) 必须先于首次上报，
 * 否则 VPaths 的 lateinit 字段会抛 UninitializedPropertyAccessException。
 */

package com.vaelin.startup.diag

import com.vaelin.startup.renderer.RendererLoadException
import com.vaelin.startup.util.VLog
import com.vaelin.startup.util.VPaths

object DiagRuntime {

    private const val TAG = "Diag"
    const val RECENT_VLOG_LINES = 50

    private val delegate: CrashReporter by lazy {
        CrashReporter(
            outputDir = VPaths.launcherLogsDir,
            recentLogProvider = { recentVLogLines(RECENT_VLOG_LINES) },
            logSink = { VLog.error(TAG, it) }
        )
    }

    /** 可被 UI 读到的最新 :game 进程/失败状态（R1 不做墓碑弹窗）。 */
    val lastState: GameProcessState? get() = delegate.lastState

    /** 直接拿底层 reporter，便于集成层复用同一个 lastState。 */
    fun reporter(): CrashReporter = delegate

    /** 读 VLog 当前日志文件的尾部 N 行（纯 File IO，不触发 Android 初始化）。 */
    fun recentVLogLines(limit: Int): List<String> {
        val file = VLog.currentLogFile ?: return emptyList()
        return runCatching { file.readLines().takeLast(limit) }.getOrElse { emptyList() }
    }

    /**
     * T1.12 渲染器库加载失败 → RENDERER_DLOPEN_FAILED。
     * 集成层捕获 RendererLoadException 后，先上报再向外抛/中止启动。
     */
    fun reportRendererLoadFailure(
        error: RendererLoadException,
        rendererId: String,
        targetVersion: String
    ): CrashReport = delegate.report(
        LaunchFailureEvent(
            stage = LaunchStage.RENDERER_LOAD,
            throwable = error,
            rendererId = rendererId,
            targetVersion = targetVersion
        )
    )

    /** VMLauncher 返回非 0 或 JRE dlopen 抛异常 → JVM_START_FAILED。 */
    fun reportJvmStartFailure(
        exitCode: Int,
        rendererId: String,
        targetVersion: String,
        throwable: Throwable? = null
    ): CrashReport = delegate.report(
        LaunchFailureEvent(
            stage = LaunchStage.JVM_BOOT,
            exitCode = exitCode,
            throwable = throwable,
            rendererId = rendererId,
            targetVersion = targetVersion
        )
    )

    /** 游戏进程跑起来后非 0 退出 → MC_CRASHED。正常退出（0）不应上报。 */
    fun reportGameCrash(
        exitCode: Int,
        rendererId: String,
        targetVersion: String
    ): CrashReport {
        require(exitCode != 0) { "reportGameCrash 只用于非 0 退出；正常退出不应上报失败" }
        return delegate.report(
            LaunchFailureEvent(
                stage = LaunchStage.GAME_RUNNING,
                exitCode = exitCode,
                rendererId = rendererId,
                targetVersion = targetVersion
            )
        )
    }

    /** :game 进程被系统杀掉：只留日志 + lastState，不做弹窗。 */
    fun reportGameProcessKilled(
        rendererId: String,
        targetVersion: String,
        exitCode: Int = 0
    ): CrashReport = delegate.recordGameProcessKilled(rendererId, targetVersion, exitCode)
}
