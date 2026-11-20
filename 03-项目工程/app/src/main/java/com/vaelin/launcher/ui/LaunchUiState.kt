package com.vaelin.launcher.ui

import com.vaelin.startup.install.ForgeChannel

/**
 * T1.11 启动链在 UI 上的可见状态。
 *
 * 由 [LauncherViewModel] 持有，VersionDetailScreen 只读显示。
 * [isPreparing] 表示主进程正在跑 prepare（下载/装 JRE/拼参），[launched] 表示已交棒给 :game 进程。
 * [error] 非空即 prepare 失败并显式中止（不含静默回退）。
 */
data class LaunchUiState(
    val profileId: String? = null,
    val isPreparing: Boolean = false,
    val stageLabel: String = "",
    val percent: Int = 0,
    val detail: String = "",
    val error: String? = null,
    val launched: Boolean = false,
)

/**
 * T1.5.5 Forge 安装链在 UI 上的状态机（任务书：idle/downloading/installing/success/error）。
 *
 * 由 [LauncherViewModel] 持有，VersionDetailScreen 只读显示；进度文本来自
 * UDP PROGRESS 事件与 installer 日志滚动行（ForgeInstaller 汇聚），不伪造。
 */
enum class ForgeInstallPhase { IDLE, DOWNLOADING, INSTALLING, SUCCESS, ERROR }

data class ForgeInstallState(
    val phase: ForgeInstallPhase = ForgeInstallPhase.IDLE,
    /** 当前阶段明细（promotions 解析结果 / 下载 URL / UDP PROGRESS 文本 / 安装器日志行） */
    val detail: String = "",
    /** ERROR 时的完整原因（含 installer 日志尾部） */
    val error: String? = null,
)

/**
 * 详情页「安装 Forge」确认对话框打开时的 recommended 版本预解析结果。
 * 只读网络小请求，不落盘；确认后 installForge 会重新解析一次（数据源幂等）。
 */
data class ForgePreviewState(
    val loading: Boolean = false,
    val forgeVersion: String? = null,
    val channel: ForgeChannel? = null,
    val error: String? = null,
) {
    /** 对话框主文案：recommended / latest 档位要显示出来，用户知道装的是哪一档 */
    val channelLabel: String
        get() = when (channel) {
            ForgeChannel.RECOMMENDED -> "官方推荐版"
            ForgeChannel.LATEST -> "最新构建"
            null -> ""
        }
}
