/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现。
 */

package com.vaelin.launcher.game

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.vaelin.startup.util.VLog
import com.vaelin.startup.util.VPaths

/**
 * 主进程侧接收 :game 崩溃广播（T1.13 衔接）。
 *
 * 在 app/AndroidManifest.xml 中不声明 android:process，故运行于主进程；
 * 广播显式定向（GameCrashContract.RECEIVER_CLASS），即使主进程已被回收，
 * 系统也会为这条显式广播重新拉起主进程并分发，从而「不静默黑屏」。
 */
class GameCrashReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "GameCrashReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != GameCrashContract.ACTION_GAME_PROCESS_CRASHED) return

        // 冷启动时 MainActivity 可能从没执行过，VLog/VPaths 未初始化；
        // 两者都是幂等的，这里补一次，保证崩溃记录落主进程自己的日志。
        VPaths.init(context.applicationContext)
        VLog.initialize(context.applicationContext)

        val reason = intent.getStringExtra(GameCrashContract.EXTRA_REASON) ?: "未知原因"
        val detail = intent.getStringExtra(GameCrashContract.EXTRA_DETAIL) ?: "无详情"
        val versionId = intent.getStringExtra(GameCrashContract.EXTRA_VERSION_ID).orEmpty()
        VLog.error(TAG, "收到 :game 异常通知：reason=$reason version=$versionId detail=$detail")

        val ui = Intent(context, GameCrashActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(GameCrashContract.EXTRA_REASON, reason)
            .putExtra(GameCrashContract.EXTRA_DETAIL, detail)
            .putExtra(GameCrashContract.EXTRA_VERSION_ID, versionId)
        runCatching { context.startActivity(ui) }
            .onFailure { t ->
                VLog.error(TAG, "启动崩溃提示界面失败：${t.javaClass.name}: ${t.message}", t)
            }
    }
}
