package com.vaelin.launcher.ui

/** Mutable Minecraft data classes offered explicitly in the shared-to-isolated migration preview. */
enum class MigrationCategory(
    val title: String,
    val riskNote: String,
) {
    MODS("模组", "模组可能与不同 MC 版本/加载器不兼容。"),
    CONFIG("配置与脚本", "配置、默认配置、KubeJS 与脚本可能依赖特定版本/加载器。"),
    WORLDS("存档", "每个目标得到独立副本，之后的游玩进度不会跨实例同步。"),
    RESOURCE_PACKS("资源包", "会复制到每个勾选实例。"),
    SHADERS("光影", "会复制到每个勾选实例。"),
    SCREENSHOTS("截图", "会复制到每个勾选实例，不会合并后续截图。"),
    GAME_SETTINGS("游戏选项", "将游戏选项与服务器列表复制到各实例。"),
    OTHER("其他用户文件", "请检查文件路径后再选择迁移。");

    companion object {
        private val rootCategory = mapOf(
            "mods" to MODS,
            "config" to CONFIG,
            "defaultconfigs" to CONFIG,
            "kubejs" to CONFIG,
            "scripts" to CONFIG,
            "saves" to WORLDS,
            "resourcepacks" to RESOURCE_PACKS,
            "shaderpacks" to SHADERS,
            "screenshots" to SCREENSHOTS,
        )
        private val settingFiles = setOf(
            "options.txt",
            "optionsof.txt",
            "optionsshaders.txt",
            "servers.dat",
            "servers.dat_old",
        )

        fun fromRelativePath(relativePath: String): MigrationCategory {
            val normalized = relativePath.replace('\\', '/').lowercase()
            val root = normalized.substringBefore('/')
            return rootCategory[root] ?: if ('/' !in normalized && root in settingFiles) GAME_SETTINGS else OTHER
        }
    }
}
