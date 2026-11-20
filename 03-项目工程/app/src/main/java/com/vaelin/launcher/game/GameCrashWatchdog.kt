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
import com.vaelin.startup.util.VLog
import java.io.PrintWriter
import java.io.StringWriter

/**
 * :game 进程的崩溃看门狗（ProcessPhoenix 式链路的「感知」半边）。
 *
 * 两条触发路径：
 *  1. 未捕获异常：install() 挂 Thread.UncaughtExceptionHandler，写 VLog + 发定向广播，
 *     再交回系统默认 handler（对应 ZL2 ZLApplication.kt:70-88 的全局钩子，但 ZL2 直接
 *     起 ErrorActivity，我们改成广播让主进程提示，满足「不要静默黑屏」）。
 *  2. 意外终止：GameService.onDestroy 调 notifyUnexpectedExit()，覆盖服务被系统回收的场景。
 *
 * 明确边界：进程被 SIGKILL（低内存直接杀、用户强杀）时 ART 层没有任何回调，
 * 这两条路径都收不到，属于只能真机验证的残余风险，报告中标注，不假装已覆盖。
 *
 * 重启（ProcessPhoenix 式）不在这里自动执行：ZL2 的 triggerRebirth 是静默重启，
 * 与「主进程能感知并给出提示」冲突。这里把重启能力交给 GameCrashActivity 的按钮，
 * 由用户看到提示后决定，见 GameProcessRebirth.triggerRebirth。
 */
object GameCrashWatchdog {

    private const val TAG = "GameCrashWatchdog"
    private const val MAX_DETAIL_CHARS = 8000

    @Volatile
    private var installed = false

    /** :game 进程内的正常退出标记（跨进程不共享，仅本进程 Service/Activity 之间有效） */
    @Volatile
    private var normalExit = false

    @Synchronized
    fun install(context: Context) {
        if (installed) return
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val detail = buildDetail(thread, throwable)
            VLog.error(TAG, ":game 未捕获异常：$detail")
            broadcast(app, GameCrashContract.REASON_UNCAUGHT_EXCEPTION, detail, "")
            // 交回系统默认 handler，让进程按系统流程结束；不在这里 killProcess，
            // 避免抢在广播送达前把进程杀掉。
            previous?.uncaughtException(thread, throwable)
        }
        installed = true
        VLog.info(TAG, "installed in process=${app.applicationInfo.processName}")
    }

    fun markNormalExit() {
        normalExit = true
        VLog.info(TAG, "normal exit marked")
    }

    fun notifyUnexpectedExit(context: Context) {
        if (normalExit) {
            VLog.info(TAG, "GameService destroyed after normal exit, no broadcast")
            return
        }
        val detail = "GameService 在非正常退出路径被销毁（:game 进程可能被系统回收或异常终止）"
        VLog.error(TAG, detail)
        broadcast(
            context.applicationContext,
            GameCrashContract.REASON_UNEXPECTED_EXIT,
            detail,
            "",
        )
    }

    /**
     * JVM 已返回、退出码已知时的收尾判定（第三条触发路径，副审 round5 的 M1）。
     *
     * exitCode == 0：玩家正常退出游戏，标记 normalExit，后续 GameService.onDestroy 不发广播。
     * exitCode != 0：MC 自己崩了但进程还活着，没有任何未捕获异常冒到 ART 层，必须在这里主动发广播，
     *   否则用户只会看到画面一闪回到启动器，不知道发生了什么。
     *
     * 不返回布尔值：调用方（VMActivity）无论如何都要 finish()，区别只在有没有广播。
     */
    fun reportJvmExit(context: Context, exitCode: Int, versionId: String) {
        if (exitCode == 0) {
            markNormalExit()
            return
        }
        val detail = "游戏 JVM 以非 0 退出码结束：exitCode=$exitCode " +
            "（通常意味着 Minecraft 自己异常退出，详情见 :game 进程日志）"
        VLog.error(TAG, detail)
        broadcast(
            context.applicationContext,
            GameCrashContract.REASON_JVM_CRASH,
            detail,
            versionId,
        )
        // 注意：这里故意不 markNormalExit。让 GameService.onDestroy 的
        // notifyUnexpectedExit 保持沉默即可（同一进程里 normalExit 仍是 false，
        // 但 VMActivity.onDestroy 会补 markNormalExit，保证不重复广播）。
    }

    private fun buildDetail(thread: Thread, throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        pw.flush()
        return buildString {
            append("thread=").append(thread.name)
            append(" throwable=").append(throwable.javaClass.name)
            append(": ").append(throwable.message)
            append('\n')
            append(sw.toString())
        }.take(MAX_DETAIL_CHARS)
    }

    private fun broadcast(app: Context, reason: String, detail: String, versionId: String) {
        val intent = Intent(GameCrashContract.ACTION_GAME_PROCESS_CRASHED)
            .setClassName(app.packageName, GameCrashContract.RECEIVER_CLASS)
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            .putExtra(GameCrashContract.EXTRA_REASON, reason)
            .putExtra(GameCrashContract.EXTRA_DETAIL, detail)
            .putExtra(GameCrashContract.EXTRA_VERSION_ID, versionId)
        runCatching { app.sendBroadcast(intent) }
            .onFailure { t ->
                VLog.error(TAG, "崩溃广播发送失败：${t.javaClass.name}: ${t.message}", t)
            }
    }
}
