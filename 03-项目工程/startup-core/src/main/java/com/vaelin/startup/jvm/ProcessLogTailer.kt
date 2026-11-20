/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * R1 · T1.10 —— installer 进程日志的增量读取（ZL2 ProcessLogTailer.kt 的逻辑层移植）。
 *
 * 与 ZL2 的差异：剥掉 kotlinx.coroutines（Job / delay / isActive），改为同步的
 * [poll]，由宿主线程循环调用。这样纯 JVM 单测可直接驱动增量读、验证 UTF-8
 * 跨轮拼接与截断，不需要起协程或真线程。Android 层若需后台 tail，用
 * [runUntil] 包一层线程即可。
 */

package com.vaelin.startup.jvm

import java.io.File
import java.io.RandomAccessFile

/** 日志行消费者；Android 层可接 VLog / UI 进度。 */
fun interface LogLineSink {
    fun appendLines(lines: List<String>)
}

class ProcessLogTailer(
    private val logFile: File,
    private val sink: LogLineSink,
) {
    companion object {
        /** 未闭合尾部字节上限；超过即丢弃，防止无换行日志把内存吃满 */
        const val MAX_PENDING_BYTES = 1024 * 1024

        /**
         * 把一段字节切成「完整行列表」+「末尾未闭合余量」。
         * 只处理最后一个 '\n' 之前的内容，余下字节跨轮拼接，避免切断 UTF-8 多字节字符。
         */
        fun splitCompleteLines(bytes: ByteArray): Pair<List<String>, ByteArray> {
            val lastNewline = bytes.indexOfLast { it == '\n'.code.toByte() }
            if (lastNewline < 0) return emptyList<String>() to bytes
            val text = String(bytes, 0, lastNewline + 1, Charsets.UTF_8)
            val lines = text.split('\n')
                .map { it.removeSuffix("\r") }
                .filter { it.isNotEmpty() }
            val remainder = if (lastNewline + 1 < bytes.size) {
                bytes.copyOfRange(lastNewline + 1, bytes.size)
            } else {
                ByteArray(0)
            }
            return lines to remainder
        }
    }

    /** 起始读偏移：文件已存在时从末尾开始，只看本次运行新增的内容 */
    private var offset: Long = if (logFile.exists()) logFile.length() else 0L
    private var remainder = ByteArray(0)

    /** 同步增量读一次，返回本次产出的完整行数。 */
    fun poll(): Int {
        if (!logFile.exists()) return 0
        val length = logFile.length()
        if (length < offset) {
            // 文件被清空 / 重写（installer 重跑）：从头再来
            offset = 0
            remainder = ByteArray(0)
        }
        if (length <= offset) return 0
        val size = (length - offset).toInt()
        val bytes = ByteArray(size)
        RandomAccessFile(logFile, "r").use { raf ->
            raf.seek(offset)
            raf.readFully(bytes)
        }
        offset = length
        val (lines, newRemainder) = splitCompleteLines(remainder + bytes)
        remainder = if (newRemainder.size > MAX_PENDING_BYTES) ByteArray(0) else newRemainder
        if (lines.isNotEmpty()) {
            sink.appendLines(lines.map(JVMHandler::normalizeLogLine))
        }
        return lines.size
    }

    /**
     * 后台 tail 循环体（供 Android 层放进线程）。stop() 返回 true 即退出。
     * sleeper 用真实实现时真睡，单测里推进假时钟。
     */
    fun runUntil(stop: () -> Boolean, sleeper: JvmSleeper, intervalMs: Long = 250L) {
        while (!stop()) {
            sleeper.sleep(intervalMs)
            poll()
        }
    }
}
