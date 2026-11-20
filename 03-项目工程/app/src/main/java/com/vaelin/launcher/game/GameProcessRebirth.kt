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

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.vaelin.launcher.MainActivity
import com.vaelin.startup.util.VLog
import kotlin.system.exitProcess

/**
 * ProcessPhoenix 式进程自重启（T1.10b A.2 的「重启」半边）。
 *
 * 经典做法：用 AlarmManager + PendingIntent 在极短延时后重新拉起 LAUNCHER Activity，
 * 然后杀掉当前进程并 exitProcess，绕开 Activity 栈回退（ZL2 ProcessPhoenix.triggerRebirth
 * 的等价实现，VMActivity.kt:474；我们未引入 process-phoenix 3.0.0 依赖，故自实现）。
 *
 * 与 ZL2 的差别：不由退出码自动触发，而是 GameCrashActivity 的「重启启动器」按钮触发。
 * 理由：任务要求「主进程能感知并给出提示，不要静默黑屏」，自动重启会盖掉提示。
 */
object GameProcessRebirth {

    private const val TAG = "GameProcessRebirth"
    private const val REQUEST_CODE = 0x5641

    fun triggerRebirth(context: Context) {
        val app = context.applicationContext
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)
            ?: Intent(app, MainActivity::class.java)
        launch.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP,
        )
        val flags = PendingIntent.FLAG_ONE_SHOT or
            PendingIntent.FLAG_CANCEL_CURRENT or
            PendingIntent.FLAG_IMMUTABLE
        val pending = PendingIntent.getActivity(app, REQUEST_CODE, launch, flags)
        val alarm = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.set(AlarmManager.RTC, System.currentTimeMillis() + 150L, pending)

        VLog.info(TAG, "rebirth scheduled; killing process ${android.os.Process.myPid()}")
        android.os.Process.killProcess(android.os.Process.myPid())
        exitProcess(0)
    }
}
