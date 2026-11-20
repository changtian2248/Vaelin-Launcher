/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * 行为参考 ZalithLauncher2 的 utils/logging/Logger.kt 与 PojavLauncher 的 Logger，
 * 但去掉了 CoroutineScope + Channel 的异步管道与 AllSettings 的保留天数配置，
 * 换成同步写 + 常量保留期：启动链在 JVM 拉起前后都可能在非协程上下文里打日志，
 * 同步写能保证崩溃前的最后一行一定落盘。
 */

package com.vaelin.startup.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VLog {

    private const val LOGCAT_TAG = "Vaelin"
    private const val RETENTION_DAYS = 7
    private const val EMPTY_FILE_GRACE_MS = 60_000L

    private var writer: PrintWriter? = null
    private var currentFile: File? = null
    private var processTag: String = "main"
    private var enabled: Boolean = false

    /** 日志文件路径，供「导出诊断包」之类的功能取用；未初始化时为 null */
    val currentLogFile: File? get() = currentFile

    @Synchronized
    fun initialize(context: Context) {
        if (enabled) return
        processTag = processTagOf(context)
        currentFile = runCatching { createLogFile() }.getOrNull()
        writer = currentFile?.let {
            runCatching { PrintWriter(it.writer(), true) }.getOrNull()
        }
        enabled = true
        deleteOldLogs()
        info(
            "VLog",
            "log started: ${currentFile?.absolutePath ?: "<file unavailable, logcat only>"}"
        )
    }

    fun error(tag: String, msg: String, t: Throwable? = null) = write("E", tag, msg, t)
    fun warning(tag: String, msg: String, t: Throwable? = null) = write("W", tag, msg, t)
    fun info(tag: String, msg: String, t: Throwable? = null) = write("I", tag, msg, t)
    fun debug(tag: String, msg: String, t: Throwable? = null) = write("D", tag, msg, t)

    @Synchronized
    private fun write(level: String, tag: String, msg: String, t: Throwable?) {
        val line = "[${timeStamp()}] [$tag/$level] $msg"

        // android.util.Log 在 JVM 单元测试里是抛异常的 stub，
        // 包一层 runCatching 让同一份代码在两种环境下都能跑。
        runCatching {
            when (level) {
                "E" -> Log.e(LOGCAT_TAG, line, t)
                "W" -> Log.w(LOGCAT_TAG, line, t)
                "I" -> Log.i(LOGCAT_TAG, line)
                else -> Log.d(LOGCAT_TAG, line)
            }
        }

        val w = writer ?: return
        runCatching {
            w.println(line)
            t?.printStackTrace(w)
            w.flush()
        }
    }

    private fun timeStamp(): String =
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    /**
     * 进程名后缀：主进程是 "main"，其余取 ":" 之后的部分。
     * 多进程下每进程一个日志文件，否则两个进程会抢同一个 FileWriter。
     */
    private fun processTagOf(context: Context): String {
        val name = context.applicationInfo.processName
        val idx = name.lastIndexOf(':')
        return if (idx < 0) "main" else name.substring(idx + 1)
    }

    private fun createLogFile(): File {
        val dir = VPaths.launcherLogsDir
        dir.mkdirs()
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss", Locale.US)
        var counter = 0
        while (true) {
            val suffix = if (counter == 0) "" else ".$counter"
            val f = File(dir, "log_${fmt.format(Date())}_$processTag$suffix.log")
            if (f.createNewFile()) return f
            counter++
        }
    }

    private fun deleteOldLogs() {
        val files = VPaths.launcherLogsDir.listFiles() ?: return
        val now = System.currentTimeMillis()
        val cutoff = now - RETENTION_DAYS * 86_400_000L
        files.forEach { f ->
            val expired = f.lastModified() < cutoff
            val staleEmpty = f != currentFile && f.length() == 0L &&
                f.lastModified() < now - EMPTY_FILE_GRACE_MS
            if (expired || staleEmpty) {
                runCatching { f.delete() }
            }
        }
    }
}
