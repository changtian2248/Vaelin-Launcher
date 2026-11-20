/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * R1 · T1.10 安装器 JVM 进程打通 —— 纯 JVM 协议/参数解析。
 *
 * 关于文件名：任务点名的 ZL2 `jvm_server/JVMHandler.kt` 实测不存在（glob 确认）；
 * ZL2 真实文件 `game/launch/handler/JVMHandler.kt` 是 :game 进程的 AWT 帧渲染
 * （Canvas / ZLBridge.renderAWTScreenFrame() / Compose UI），属于游戏运行时，
 * 移植进 :jvm 会拖入渲染与 UI 子系统并越界。复用清单 §3.1 把它标成
 * 「安装器 JVM 输出解析」是文档误标。
 *
 * 因此本文件沿用同名，职责改为「:jvm 安装器侧真正需要的纯逻辑」：
 *   1. 跨进程 UDP 消息的编解码（EXIT / PROGRESS）
 *   2. installer 命令串 `-cp <cp> <mainClass> <args>` 的结构化拆解
 * 两者都不引用 android.*，可在 JVM 单测里独立断言。
 */

package com.vaelin.startup.jvm

import com.vaelin.startup.util.splitPreservingQuotes
import java.io.File

/**
 * 一次 installer 命令的结构化结果。
 * 与 VaelinLauncher.launch() 的入参一一对应（classpath / mainClass / gameArgs / extraJvmArgs）。
 */
data class ServiceArgs(
    val extraJvmArgs: List<String>,
    val classpath: List<File>,
    val mainClass: String,
    val gameArgs: List<String>,
)

object JVMHandler {

    // -----------------------------------------------------------------
    // 跨进程消息协议（主进程 JVMSocketServer ← :jvm JvmService）
    // -----------------------------------------------------------------

    /** 把 :jvm 上报的一条 UDP 消息解析成事件；无法识别的噪声返回 null。 */
    fun parse(message: String): JvmEvent? = when {
        message.startsWith(MSG_EXIT_PREFIX) -> {
            val raw = message.removePrefix(MSG_EXIT_PREFIX).trim()
            val code = raw.toIntOrNull()
                ?: throw JvmSchedulerException("Malformed exit message (non-numeric code): $message")
            JvmEvent.Exit(code)
        }
        message.startsWith(MSG_PROGRESS_PREFIX) ->
            JvmEvent.Progress(message.removePrefix(MSG_PROGRESS_PREFIX))
        else -> null
    }

    fun encodeExit(code: Int): String = "$MSG_EXIT_PREFIX$code"
    fun encodeProgress(text: String): String = "$MSG_PROGRESS_PREFIX$text"

    // -----------------------------------------------------------------
    // installer 命令串解析
    // -----------------------------------------------------------------

    /**
     * 解析 ZL2 传入的单条命令串（Install.ForgeLike.kt:355-364：
     * `buildList { add("-cp"); add(classpath.joinToString(File.pathSeparator)); add(mainClass); addAll(args) }.joinToString(" ")`）。
     *
     * 规则：
     * - 用 splitPreservingQuotes 保留引号内的空格（VStrings.kt:22，纯 JVM）。
     * - `-cp` / `-classpath` 后的 token 是 classpath，按 ':' 拆（Android 的
     *   File.pathSeparator 恒为 ':'，不随构建主机平台变化，故协议里写死）。
     * - 以 '-' 开头的 token（-D / -X / -javaagent: 等）归入 extraJvmArgs。
     * - 第一个不以 '-' 开头的 token 是 mainClass；其后全部是 gameArgs。
     * - 缺 classpath 或缺 mainClass 显式报错，禁止静默产出空启动链。
     */
    fun parseServiceArgs(raw: String): ServiceArgs {
        val tokens = raw.splitPreservingQuotes()
        val extra = mutableListOf<String>()
        val classpath = mutableListOf<File>()
        var mainClass: String? = null
        val gameArgs = mutableListOf<String>()

        var i = 0
        while (i < tokens.size) {
            val token = tokens[i]
            when {
                mainClass != null -> gameArgs.add(token)
                token == "-cp" || token == "-classpath" -> {
                    val cp = tokens.getOrNull(i + 1)
                        ?: throw JvmSchedulerException("Missing classpath value after $token")
                    classpath.addAll(cp.split(':').filter { it.isNotBlank() }.map { File(it) })
                    i++
                }
                token.startsWith("-") -> extra.add(token)
                else -> mainClass = token
            }
            i++
        }

        if (classpath.isEmpty()) {
            throw JvmSchedulerException("No classpath in installer args: $raw")
        }
        val main = mainClass
            ?: throw JvmSchedulerException("No mainClass in installer args: $raw")
        return ServiceArgs(extra, classpath, main, gameArgs)
    }

    /** :jvm 进程进度通知的规范化（去掉 installer 日志常见的 \r 与重复空白）。 */
    fun normalizeLogLine(line: String): String =
        line.removeSuffix("\r").trim().replace(Regex("\\s{2,}"), " ")
}
