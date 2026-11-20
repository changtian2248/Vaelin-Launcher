package com.vaelin.launcher.ui

import androidx.compose.runtime.saveable.Saver

/**
 * 导航目标。
 *
 * 刻意不引入 navigation-compose：R1 只有「启动页 → 实例列表 → 实例详情」三段，
 * 用 sealed class + 一个 rememberSaveable 状态机就能表达，且不带来
 * NavHost/路由字符串/返回栈三套额外概念。等出现「设置 → 账户 → 下载 → 日志」
 * 这类需要深链与多级返回栈的场景再换。
 */
sealed interface VRoute {

    /** 启动页：规格书第 10 条要求的两行固定标注只在这里出现 */
    data object Splash : VRoute

    /** 实例列表 */
    data object VersionList : VRoute

    /** 实例详情，用 profile id 而不是整个对象做 key，避免删除后持有陈旧快照 */
    data class VersionDetail(val profileId: String) : VRoute

    /** 启动器全局设置（主题/共享或全版本隔离等）。 */
    data object Settings : VRoute

    /** 资源下载主页（搜索/分类/结果） */
    data object Download : VRoute

    /** 已安装的模组/光影/资源包管理，严格绑定某个实例及其目录模式。 */
    data class InstalledContent(val profileId: String) : VRoute

    /**
     * 下载详情全屏页（竖屏用；横屏双栏内嵌不走这里）。
     * 带 sourceId 为后续多源（CurseForge）留好路由参数。
     */
    data class DownloadDetail(val sourceId: String, val projectId: String) : VRoute
}

/**
 * 手写 Saver 而不是让 Route 实现 Serializable：
 * Kotlin object 反序列化会拿到新实例，`==` 的身份比较会失效。
 * 编码成字符串之后，进程重建也能恢复。
 */
val VRouteSaver: Saver<VRoute, String> = Saver(
    save = { route ->
        when (route) {
            VRoute.Splash -> "splash"
            VRoute.VersionList -> "list"
            is VRoute.VersionDetail -> "detail:${route.profileId}"
            VRoute.Settings -> "settings"
            VRoute.Download -> "download"
            is VRoute.InstalledContent -> "content:${route.profileId}"
            is VRoute.DownloadDetail -> "dl:${route.sourceId}:${route.projectId}"
        }
    },
    restore = { raw ->
        when {
            raw == "list" -> VRoute.VersionList
            raw.startsWith("detail:") -> VRoute.VersionDetail(raw.removePrefix("detail:"))
            raw == "settings" -> VRoute.Settings
            raw == "download" -> VRoute.Download
            raw.startsWith("content:") -> VRoute.InstalledContent(raw.removePrefix("content:"))
            raw.startsWith("dl:") -> {
                val parts = raw.split(":", limit = 3)
                if (parts.size == 3) VRoute.DownloadDetail(parts[1], parts[2]) else VRoute.Download
            }
            else -> VRoute.Splash
        }
    },
)
