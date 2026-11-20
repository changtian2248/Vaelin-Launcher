/*
 * Zalith Launcher 2
 * Copyright (C) 2025-2026 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/gpl-3.0.txt>.
 *
 * Vaelin 适配说明（2026-11-08）：
 *  - 包名与类名是 JNI 静态绑定的硬约束，不可改：
 *    stdio_is.c:176（ZLBridge.setupExitMethod）FindClass 本类并取 jvmExit(IZ)V，
 *    awt_bridge.c:41（libpojavexec_awt JNI_OnLoad）取 openLink/querySystemClipboard/putClipboardData。
 *  - jvmExit：ZL2 原实现回调 staticLauncher 并 killProgress()。Vaelin 的退出链路是
 *    VMActivity 等待 VMLauncher.launchJVM 返回退出码后走 GameCrashWatchdog.reportJvmExit；
 *    且 nominal_exit（stdio_is.c:140-152）在调用本方法后必然 System.exit() 收尾，
 *    所以这里只留日志，不杀进程、不重复回调。
 *  - openLink：ZL2 的 file: 分支走 shareFile/openPath（文件管理器能力，Vaelin R1 没有），
 *    显式记日志拒绝；http(s) 链接用 ACTION_VIEW 打开。
 *  - GlobalContext → ContextsKt.getGlobalContext()（见 context/Contexts.kt）。
 */

package com.movtery.zalithlauncher.bridge

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context.CLIPBOARD_SERVICE
import android.content.Intent
import android.net.Uri
import androidx.annotation.Keep
import com.movtery.zalithlauncher.BuildKeys
import com.movtery.zalithlauncher.context.getGlobalContext

private const val TAG = "ZLNativeInvoker"

@Keep
object ZLNativeInvoker {

    /**
     * JVM 退出钩子（对齐 ZL2 的 staticLauncher/onExit 模式）。
     * exit_hook.c 在 JVM 调 exit() 时经 nominal_exit 调 [jvmExit]，随即 System.exit
     * 杀死整个 :game 进程——launchJVM 的调用线程来不及返回，VaelinLauncher 侧的
     * 「游戏退出」日志永远打不出来。宿主（VMActivity）在拉起前设置本钩子，
     * 才能把退出码通知回主进程（GameCrashWatchdog.reportJvmExit）。
     */
    @Volatile
    @JvmStatic
    var onJvmExit: ((exitCode: Int, isSignal: Boolean) -> Unit)? = null

    @Keep
    @JvmStatic
    fun openLink(link: String) {
        val activity = getGlobalContext() as? Activity ?: return
        activity.runOnUiThread {
            if (link.startsWith("file:")) {
                // R1 没有文件管理器/分享能力，显式记录而不是静默吞掉
                LoggerBridge.append("$TAG: file link not supported in R1: $link")
                return@runOnUiThread
            }
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        }
    }

    @Keep
    @JvmStatic
    fun querySystemClipboard() {
        val activity = getGlobalContext() as? Activity ?: run {
            ZLBridge.clipboardReceived(null, null)
            return
        }
        activity.runOnUiThread {
            val clipData = (activity.getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager)?.primaryClip ?: run {
                ZLBridge.clipboardReceived(null, null)
                return@runOnUiThread
            }
            val clipItemText = clipData.getItemAt(0).text ?: run {
                ZLBridge.clipboardReceived(null, null)
                return@runOnUiThread
            }
            ZLBridge.clipboardReceived(clipItemText.toString(), "plain")
        }
    }

    @Keep
    @JvmStatic
    fun putClipboardData(data: String, mimeType: String) {
        val activity = getGlobalContext() as? Activity ?: return
        activity.runOnUiThread {
            val clipData = when (mimeType) {
                "text/plain" -> ClipData.newPlainText(BuildKeys.LAUNCHER_IDENTIFIER, data)
                "text/html" -> ClipData.newHtmlText(BuildKeys.LAUNCHER_IDENTIFIER, data, data)
                else -> null
            }
            clipData?.let {
                (activity.getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager)?.setPrimaryClip(it)
            }
        }
    }

    @Keep
    @JvmStatic
    fun jvmExit(exitCode: Int, isSignal: Boolean) {
        // 见文件头适配说明：进程退出由 nominal_exit 的 System.exit() 兜底。
        // 先尽力把退出码送回主进程，再留一行到游戏日志（start 已在 VaelinLauncher 打开）。
        runCatching { onJvmExit?.invoke(exitCode, isSignal) }
            .onFailure { LoggerBridge.append("$TAG: onJvmExit hook failed: $it") }
        LoggerBridge.append("$TAG: jvmExit code=$exitCode signal=$isSignal")
    }
}
