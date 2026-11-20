/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * R1 · T1.10 —— 主进程侧安装器 JVM 编排入口（ZL2 GameJVMRunner.kt 的 Android 薄封装）。
 *
 * 真正的状态机（互斥清场 / 4 次服务启动重试 / 超时 / 进度 / 退出码 / JRE 重试）
 * 全在纯 JVM 的 InstallerJvmScheduler 里，本文件只负责：
 *   1. 组装 AndroidJvmProcessControl + 真实时钟/休眠
 *   2. 把工程 assets 实际可用的运行时链交给调度器
 *   3. 把状态日志接 VLog
 */

package com.vaelin.startup.jvm

import android.content.Context
import com.vaelin.startup.util.VLog
import com.vaelin.startup.util.VPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object GameJVMRunner {

    private const val TAG = "GameJVMRunner"

    /**
     * 工程 assets 实测只有 jre-17 / jre-21，没有 jre-8（JreInstaller.kt:16-19 注释亦如此），
     * 故 installer 重试链从 17 起、只有两档；与 ZL2 Jre.kt 的 8→17→21 相比少了 8。
     * 退出码非 0 时由 [InstallerJvmScheduler.runWithRuntimes] 依次换下一档，
     * 全失败抛 [JvmCrashException]。
     */
    val INSTALLER_RUNTIME_CHAIN: List<String?> = listOf("jre-17", "jre-21")

    suspend fun runInstaller(
        context: Context,
        jvmArgs: String,
        userHome: String? = null,
        postSummary: String? = null,
        onProgress: (String) -> Unit = {},
        onStatus: (String) -> Unit = {},
    ): Int = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        VPaths.init(appContext)

        val control = AndroidJvmProcessControl(appContext)
        val scheduler = InstallerJvmScheduler(
            control = control,
            clock = SystemJvmClock,
            sleeper = ThreadJvmSleeper,
            config = JvmSchedulerConfig(),
            onProgress = onProgress,
            onStatus = { message ->
                VLog.info(TAG, message)
                onStatus(message)
            },
        )
        val request = InstallJvmRequest(
            jvmArgs = jvmArgs,
            userHome = userHome,
            postSummary = postSummary,
        )
        scheduler.runWithRuntimes(request, INSTALLER_RUNTIME_CHAIN)
    }
}
