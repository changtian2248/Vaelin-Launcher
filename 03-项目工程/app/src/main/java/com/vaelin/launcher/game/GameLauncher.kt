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

import android.content.Context
import android.content.Intent
import com.vaelin.startup.profile.VersionProfile
import com.vaelin.startup.util.VLog

/**
 * 主进程 → :game 的启动入口（T1.10b A.4）。
 *
 * 顺序与 ZL2 runGame/startGameService 一致（VMActivity.kt:1130-1141、:452-455）：
 * 先拉起 :game 的前台服务，再 startActivity(VMActivity)。两者都声明 android:process=":game"
 * （见 app/AndroidManifest.xml），因此系统会在独立进程创建组件，主进程与 :game 形成两个 pid。
 *
 * 不在 Intent 里传 VersionProfile 对象：它是普通 data class（VersionProfile.kt:38-50），
 * 且 extraJvmArgs 可能较大，契约拆成原始类型，见 GameLaunchContract.write。
 *
 * R1 边界：本入口只负责把参数与进程隔离建起来；真正拉起 JVM 是 VaelinLauncher.launch，
 * 属 T1.11，本文件不调用、不伪装启动成功。
 */
object GameLauncher {

    private const val TAG = "GameLauncher"

    fun start(context: Context, profile: VersionProfile) {
        val app = context.applicationContext
        VLog.info(
            TAG,
            "start(): version=${profile.id} mc=${profile.minecraftVersion} " +
                "renderer=${profile.rendererId}/${profile.rendererUniqueId} heap=${profile.memoryMB}MB " +
                "-> VMActivity in :game",
        )

        // 后台受限状态下 startService 会被系统拒绝；ZL2 同样用 runCatching 忽略
        // （VMActivity.kt:452-455）。VMActivity.onCreate 会在前台再试一次，形成双保险。
        runCatching { app.startService(Intent(app, GameService::class.java)) }
            .onFailure {
                VLog.warning(TAG, "startService(GameService) 被拒（后台受限）：${it.message}")
            }

        val intent = Intent(app, VMActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        GameLaunchContract.write(intent, profile)
        app.startActivity(intent)
        VLog.info(TAG, "startActivity(VMActivity) dispatched")
    }
}
