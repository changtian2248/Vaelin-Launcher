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

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.vaelin.startup.util.VLog
import com.vaelin.startup.util.VPaths

/**
 * 主进程的崩溃提示界面（T1.10b / T1.13）：:game 异常退出后弹出，而不是静默黑屏。
 *
 * 纯代码构建 View：app 模块当前没有 res 目录，不能引用任何本地 layout/string/style，
 * 主题用系统 @android:style/Theme.Material.Light.Dialog（Manifest 中声明）。
 */
class GameCrashActivity : ComponentActivity() {

    companion object {
        private const val TAG = "GameCrashActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VPaths.init(applicationContext)
        VLog.initialize(applicationContext)

        val reason = intent.getStringExtra(GameCrashContract.EXTRA_REASON) ?: "未知原因"
        val detail = intent.getStringExtra(GameCrashContract.EXTRA_DETAIL) ?: "无详情"
        val versionId = intent.getStringExtra(GameCrashContract.EXTRA_VERSION_ID).orEmpty()
        VLog.error(TAG, "显示游戏进程异常提示：reason=$reason version=$versionId")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
            minimumWidth = 900
        }

        root.addView(
            TextView(this).apply {
                text = "游戏进程已退出"
                textSize = 20f
                setTextColor(Color.BLACK)
                gravity = Gravity.CENTER
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        root.addView(
            TextView(this).apply {
                text = buildString {
                    append("原因：").append(reason).append('\n')
                    append("实例：").append(versionId.ifBlank { "未知" }).append('\n')
                    append('\n')
                    append(detail.take(1500))
                }
                textSize = 13f
                setTextColor(Color.DKGRAY)
            }.let { body ->
                ScrollView(this).apply {
                    addView(body)
                    isFillViewport = true
                }
            },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        root.addView(
            Button(this).apply {
                text = "重启启动器"
                setOnClickListener {
                    VLog.info(TAG, "用户选择重启启动器")
                    GameProcessRebirth.triggerRebirth(this@GameCrashActivity)
                }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        root.addView(
            Button(this).apply {
                text = "关闭"
                setOnClickListener { finish() }
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        setContentView(root)
    }
}
