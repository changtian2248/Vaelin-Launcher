/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Vaelin 自有实现。T1.13 的落盘与状态层。
 *
 * 纯 JVM、零 Android 依赖：输出目录、VLog 尾部读取、时间、日志下沉全部构造注入，
 * 生产侧由 DiagRuntime 接 VPaths.launcherLogsDir / VLog.currentLogFile / VLog.error。
 */

package com.vaelin.startup.diag

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 每次启动失败写一个 `crash-<时间戳>.log` 到 [outputDir]，
 * 并把最新状态暴露为 [lastState] 供 UI 读取（R1 不做墓碑弹窗）。
 *
 * @param recentLogProvider 取「最近 N 行 VLog」；生产侧读 VLog.currentLogFile 尾部，
 *                          单测注入固定列表，不触发任何 Android 初始化。
 * @param logSink 额外写一条到运行日志；生产侧传 `{ VLog.error("Diag", it) }`。
 */
class CrashReporter(
    private val outputDir: File,
    private val recentLogProvider: () -> List<String> = { emptyList() },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val recentLogLineCount: Int = 50,
    private val logSink: (String) -> Unit = {}
) {
    @Volatile
    var lastState: GameProcessState? = null
        private set

    fun report(event: LaunchFailureEvent): CrashReport {
        val category = FailureClassifier.classify(event)
        val timestamp = now()
        val stackTrace = stackTraceOf(event.throwable)
        val recent = recentLogProvider().takeLast(recentLogLineCount)
        val baseName = "crash-${FILE_TIME_FORMAT.format(Date(timestamp))}"
        val file = uniqueFile(outputDir, baseName)

        val message = buildMessage(event, category)
        val body = render(
            category = category,
            event = event,
            message = message,
            stackTrace = stackTrace,
            recent = recent,
            timestamp = timestamp,
            logFile = file.name
        )

        // 写盘失败也要显式留痕，不静默吞掉：否则「报告已生成」会是假象。
        val writeError = runCatching {
            outputDir.mkdirs()
            file.writeText(body)
        }.exceptionOrNull()
        logSink("[$CATEGORY_PREFIX${category.logTag}] $message -> ${file.name}")
        if (writeError != null) {
            logSink(
                "[$CATEGORY_PREFIX${category.logTag}] 崩溃日志写入失败：" +
                    "${writeError.javaClass.simpleName}: ${writeError.message}"
            )
        }

        lastState = GameProcessState(
            processAlive = false,
            lastCategory = category,
            lastExitCode = event.exitCode,
            note = if (event.gameProcessKilled) ":game 进程被杀" else message
        )

        return CrashReport(
            category = category,
            rendererId = event.rendererId,
            targetVersion = event.targetVersion,
            jvmExitCode = event.exitCode,
            stackTrace = stackTrace,
            recentVLogLines = recent,
            message = message,
            timestampMillis = timestamp,
            logFile = file.name
        )
    }

    /**
     * :game 进程被杀的专用入口（T1.13 第 3 条）。
     * 只留日志落盘 + [lastState]，不做弹窗。
     */
    fun recordGameProcessKilled(
        rendererId: String,
        targetVersion: String,
        exitCode: Int = 0
    ): CrashReport = report(
        LaunchFailureEvent(
            stage = LaunchStage.GAME_RUNNING,
            exitCode = exitCode,
            rendererId = rendererId,
            targetVersion = targetVersion,
            gameProcessKilled = true
        )
    )

    private fun buildMessage(event: LaunchFailureEvent, category: FailureCategory): String = when {
        event.gameProcessKilled -> ":game 进程被系统终止（退出码 ${event.exitCode}）"
        category == FailureCategory.RENDERER_DLOPEN_FAILED ->
            event.throwable?.message ?: "渲染器库加载失败"
        category == FailureCategory.MC_CRASHED ->
            "游戏进程非 0 退出（退出码 ${event.exitCode}）"
        else -> "JVM 启动失败（退出码 ${event.exitCode}）"
    }

    /** 文本块：字段逐行 + 堆栈段 + 最近 N 行 VLog 段，便于 grep 与人工阅读。 */
    private fun render(
        category: FailureCategory,
        event: LaunchFailureEvent,
        message: String,
        stackTrace: String,
        recent: List<String>,
        timestamp: Long,
        logFile: String
    ): String = buildString {
        appendLine("==== Vaelin crash report ====")
        appendLine("分类: ${category.logTag}")
        appendLine("时间: $timestamp (${ISO_TIME_FORMAT.format(Date(timestamp))})")
        appendLine("文件: $logFile")
        appendLine("说明: $message")
        appendLine("渲染器 id: ${event.rendererId}")
        appendLine("目标版本: ${event.targetVersion}")
        appendLine("JVM 退出码: ${event.exitCode}")
        appendLine("启动阶段: ${event.stage}")
        appendLine("游戏进程被杀: ${event.gameProcessKilled}")
        appendLine("---- 堆栈 ----")
        appendLine(stackTrace.trimEnd())
        appendLine("---- 最近 ${recent.size} 行 VLog ----")
        if (recent.isEmpty()) appendLine("(无)")
        else recent.forEach { appendLine(it) }
        appendLine("==== end ====")
    }

    private fun uniqueFile(dir: File, baseName: String): File {
        var counter = 0
        while (true) {
            val suffix = if (counter == 0) "" else ".$counter"
            val f = File(dir, "$baseName$suffix.log")
            if (!f.exists()) return f
            counter++
        }
    }

    private fun stackTraceOf(t: Throwable?): String {
        if (t == null) return "(无异常堆栈)"
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    private companion object {
        const val CATEGORY_PREFIX = "category="
        val FILE_TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss-SSS", Locale.US)
        val ISO_TIME_FORMAT = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
    }
}
