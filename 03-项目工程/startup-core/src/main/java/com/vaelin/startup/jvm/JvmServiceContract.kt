/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * R1 · T1.10 —— :jvm 安装器服务的 Android 侧契约（ZL2 _JvmService.kt 的移植）。
 *
 * 剥掉 GlobalContext / PathManager / kotlinx.coroutines，改吃 VPaths / VLog，
 * 并把「起服务 / 查进程 / 绑接收端」收进 [JvmProcessControl] 实现，
 * 让纯 JVM 调度器（InstallerJvmScheduler）能零 Android 依赖地编排。
 */

package com.vaelin.startup.jvm

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import com.vaelin.startup.util.VPaths
import java.io.File

/** installer 进程的 stdout/stderr 日志文件（对应 ZL2 PathManager.DIR_FILES_EXTERNAL/latest_process.log）。 */
val LATEST_PROCESS_LOG_FILE: File
    get() = File(VPaths.launcherLogsDir, "latest_process.log")

/** 主进程 → :jvm：把一次安装请求交给前台服务。 */
fun startJvmService(context: Context, request: InstallJvmRequest) {
    val intent = Intent(context, JvmService::class.java).apply {
        putExtra(SERVICE_JVM_ARGS, request.jvmArgs)
        request.jreName?.let { putExtra(SERVICE_JRE_NAME, it) }
        request.userHome?.let { putExtra(SERVICE_USER_HOME, it) }
        request.postSummary?.let { putExtra(SERVICE_POST_SUMMARY, it) }
    }
    // minSdk 26：前台服务必须走 startForegroundService，Service 端 5 秒内 startForeground。
    context.startForegroundService(intent)
}

fun stopJvmService(context: Context) {
    context.stopService(Intent(context, JvmService::class.java))
}

internal fun runningAppProcesses(context: Context): List<ActivityManager.RunningAppProcessInfo> {
    val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
    return am.runningAppProcesses.orEmpty()
}

/**
 * 列出占用 JVM 独占槽的其它进程，形如 "com.vaelin.launcher:jvm(1234)"。
 * 精确匹配 :jvm / :game（[isJvmExclusiveProcess]），排除自己。
 */
fun queryBlockingProcesses(context: Context): List<String> {
    val main = context.packageName
    val myPid = android.os.Process.myPid()
    return runningAppProcesses(context)
        .filter { it.pid != myPid && isJvmExclusiveProcess(it.processName, main) }
        .map { "${it.processName}(${it.pid})" }
}

/** 强杀所有占槽的非主进程（ZL2 _JvmService.kt:stopAllNonMainProcesses）。 */
fun killBlockingProcesses(context: Context) {
    val main = context.packageName
    val myPid = android.os.Process.myPid()
    runningAppProcesses(context)
        .filter { it.pid != myPid && isJvmExclusiveProcess(it.processName, main) }
        .forEach { info ->
            runCatching { android.os.Process.killProcess(info.pid) }
        }
}

object SystemJvmClock : JvmClock {
    override fun nowMillis(): Long = System.currentTimeMillis()
}

object ThreadJvmSleeper : JvmSleeper {
    override fun sleep(millis: Long) {
        Thread.sleep(millis)
    }
}

/**
 * 主进程侧 [JvmProcessControl] 的 Android 实现。
 * 接收端用 JVMSocketServer（UDP 回环）；服务用 startForegroundService。
 */
class AndroidJvmProcessControl(private val context: Context) : JvmProcessControl {

    companion object {
        /** startForegroundService 之后 :jvm 进程创建有延迟；这段时间内不判「被杀」，避免误报 */
        private const val START_GRACE_MS = 3_000L
    }

    private val clock = SystemJvmClock

    @Volatile
    private var serviceStartedAt = 0L

    override fun openEventSource(): JvmEventSource = JVMSocketServer.start()

    override fun startService(request: InstallJvmRequest) {
        startJvmService(context, request)
        serviceStartedAt = clock.nowMillis()
    }

    override fun stopService() {
        stopJvmService(context)
        // 清掉 UDP 单例状态，跨轮残留会毒化下一次运行（ZL2 finally 里无条件 stop）。
        JVMSocketServer.stop()
    }

    override fun listBlockingProcesses(): List<String> = queryBlockingProcesses(context)

    override fun stopAllNonMainProcesses() = killBlockingProcesses(context)

    override fun isTargetProcessAlive(): Boolean {
        val jvmProcess = context.packageName + ":jvm"
        if (runningAppProcesses(context).any { it.processName == jvmProcess }) return true
        return clock.nowMillis() - serviceStartedAt < START_GRACE_MS
    }
}
