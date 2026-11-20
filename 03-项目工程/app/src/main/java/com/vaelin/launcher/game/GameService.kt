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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.vaelin.startup.util.VLog
import com.vaelin.startup.util.VPaths

/**
 * :game 进程的前台服务（T1.10b A.2）。
 *
 * 作用有二：
 *  1. 让 :game 进程有一个被系统承认的组件，启动游戏后 `adb shell ps` 能看到独立 pid；
 *  2. 前台服务保活，避免游戏进程在切后台时被立刻回收。
 *
 * 与 ZL2 game/launch/GameService.kt:35-73 同构，但去掉了 NotificationCompat / R.string /
 * R.mipmap / NotificationChannelData 依赖——app 模块没有 res 目录，也不许改构建脚本，
 * 因此用平台 Notification.Builder + 系统 drawable + 硬编码渠道名。
 *
 * specialUse 类型的声明（Manifest + 三参数 startForeground）照 ZL2 AndroidManifest.xml:171-179
 * 处理：targetSdk 34 要求 FGS 显式声明 type 与 FOREGROUND_SERVICE_SPECIAL_USE 权限。
 */
class GameService : Service() {

    companion object {
        private const val TAG = "GameService"
        private const val CHANNEL_ID = "vaelin_game_process"
        private const val NOTIFICATION_ID = 0x5641
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // :game 是独立进程，Application 没有自定义类，必须自己初始化 VPaths/VLog，
        // 否则 VLog.kt:44-48 的 writer 为 null，日志不落盘。
        VPaths.init(applicationContext)
        VLog.initialize(applicationContext)
        GameCrashWatchdog.install(applicationContext)
        VLog.info(TAG, "onCreate in process=${applicationInfo.processName}")
        startForegroundNotification()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNotification()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // 与 VMActivity.onDestroy 的 markNormalExit() 配合：正常退出不广播，
        // 系统回收/异常销毁时才通知主进程（GameCrashWatchdog.notifyUnexpectedExit）。
        GameCrashWatchdog.notifyUnexpectedExit(applicationContext)
        VLog.info(TAG, "onDestroy")
        super.onDestroy()
    }

    private fun startForegroundNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Vaelin 游戏进程",
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Vaelin 游戏进程运行中")
            .setContentText(":game 进程已启动，正在承载游戏画面")
            // 系统资源 id，不需要本地 res（app 模块当前无 res 目录）
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

        // 前台身份提升失败不是渲染/启动的关键依赖（ZL2 亦用 runCatching 忽略），
        // 但这里显式落 warning，不静默吞掉。
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            VLog.info(TAG, "startForeground OK")
        }.onFailure { t ->
            VLog.warning(
                TAG,
                "startForeground 失败（保活降级，已显式记录）：${t.javaClass.name}: ${t.message}",
                t,
            )
        }
    }
}
