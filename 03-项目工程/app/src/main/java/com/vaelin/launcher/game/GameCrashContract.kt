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

/**
 * :game 崩溃 → 主进程提示的跨进程契约（T1.10b A.2 / T1.13 衔接）。
 *
 * 广播用 setClassName 显式定向到主进程的 GameCrashReceiver，避免隐式广播的后台限制；
 * 发送方与接收方同 UID、同包名，因此 receiver 可以 exported=false。
 */
object GameCrashContract {

    const val ACTION_GAME_PROCESS_CRASHED =
        "com.vaelin.launcher.game.GAME_PROCESS_CRASHED"
    const val RECEIVER_CLASS = "com.vaelin.launcher.game.GameCrashReceiver"

    const val EXTRA_REASON = "com.vaelin.launcher.game.CRASH_REASON"
    const val EXTRA_DETAIL = "com.vaelin.launcher.game.CRASH_DETAIL"
    const val EXTRA_VERSION_ID = "com.vaelin.launcher.game.CRASH_VERSION_ID"

    const val REASON_UNCAUGHT_EXCEPTION = "uncaught_exception"
    const val REASON_UNEXPECTED_EXIT = "unexpected_exit"

    /**
     * JVM 体面退出但退出码非 0：MC 自己崩了（例如 mod 抛异常后主循环结束、OOM 后被兜住），
     * 进程没被系统杀、也没有未捕获异常冒到 ART 层，前两条路径都感知不到。
     * 副审 round5 的 M1：VMActivity 原来不看 exitCode 就 finish，这条会被静默吞掉。
     */
    const val REASON_JVM_CRASH = "jvm_crash"
}
