/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * 本文件是「移植改写」产物，不是 ZalithLauncher2 的逐字副本：
 * 目录布局与 ZL2 的 com.movtery.zalithlauncher.path.PathManager 保持一致
 * （这是复用其启动链的前提条件），但去掉了 Vaelin 不复用的部分
 * —— Terracotta 联机目录、赞助商缓存、壁纸/样式目录、各类导出器临时目录，
 * 并去掉了对 AllSettings 配置系统的依赖。
 */

package com.vaelin.startup.util

import android.content.Context
import android.os.Environment
import com.vaelin.startup.profile.GameDirectoryMode
import com.vaelin.startup.profile.GameDirectoryModeStore
import com.vaelin.startup.profile.GameDirectoryResolver
import com.vaelin.startup.profile.VersionProfile
import java.io.File

/**
 * 启动基底的目录与文件布局。
 *
 * 路径名不是随便起的：ZL2 的启动链（Launcher / RuntimesManager / UnpackJreTask）
 * 会按这些名字找 JRE 运行时、游戏目录和版本清单，改名字就要同步改那些代码。
 */
object VPaths {

    lateinit var filesPrivate: File
        private set
    lateinit var filesExternal: File
        private set
    lateinit var cache: File
        private set

    /** APK 里解出来的 .so 所在目录，JRE 侧的 dlopen 会用到 */
    lateinit var nativeLibDir: String
        private set

    /** 运行时模块（JVM 侧的 lib 搜索路径沙盒） */
    lateinit var runtimeModDir: File
        private set

    /** 兼容旧版与共享模式的游戏根目录：<files>/games */
    lateinit var gameDir: File
        private set

    /** 唯一全局目录策略。其 getter 每次从原子文件刷新，避免 Android 多进程 SharedPreferences 缓存过期。 */
    val gameDirectoryMode: GameDirectoryMode
        get() {
            if (!initialized) return gameDirectoryModeSnapshot
            val loaded = gameDirectoryModeStore.load(defaultGameDirectoryMode)
            gameDirectoryModeSnapshot = loaded
            return loaded
        }

    private lateinit var gameDirectoryModeStore: GameDirectoryModeStore
    private var defaultGameDirectoryMode: GameDirectoryMode = GameDirectoryMode.SHARED
    private var gameDirectoryModeSnapshot: GameDirectoryMode = GameDirectoryMode.SHARED

    /** Shared lock path used by the main, :game and :jvm processes. */
    lateinit var gameDirectoryLockFile: File
        private set

    /** JRE 运行时集合:<files>/games/runtimes（启动器共享，不随游戏目录模式复制）。 */
    lateinit var multirtDir: File
        private set

    /** JNA 解压目录：<files>/games/jna_dir */
    lateinit var jnaDir: File
        private set

    /** 组件目录（LWJGL、AWT 补丁等）：<files>/components */
    lateinit var componentsDir: File
        private set

    lateinit var launcherLogsDir: File
        private set
    lateinit var nativeLogsDir: File
        private set

    /**
     * MobileGlues 内置渲染器的配置目录：<filesExternal>/mobileglues。
     *
     * 与 ZL2 的差异（刻意）：ZL2 把它放在内部私有目录
     * （MobileGluesRenderer.kt:251 用 PathManager.DIR_FILES_PRIVATE），
     * Vaelin 按任务决定放外部私有目录，便于排查时直接 adb pull 出 config.json。
     * 该目录只被 MobileGluesRenderer 的惰性 env 求值读写，其他渲染器不碰。
     */
    lateinit var mobilegluesDir: File
        private set

    /** 版本清单（GLFW/版本 JSON 缓存） */
    lateinit var minecraftVersionsFile: File
        private set

    /** Vaelin 自己的配置文件 */
    lateinit var settingsFile: File
        private set

    private var initialized = false

    /** 使用当前全局设置解析一个 profile 的 Minecraft 工作目录。 */
    fun resolveGameDir(profile: VersionProfile): File {
        check(initialized) { "VPaths.init(context) must be called before resolving profile game directories" }
        return GameDirectoryResolver.resolve(gameDir, profile, gameDirectoryMode)
    }

    /**
     * 解析跨进程可重复计算的实例游戏工作目录：共享模式返回 gameHome；
     * 隔离模式返回 `<gameHome>/versions/<profileId>`，与 ZL2 的版本目录语义一致。
     */
    fun resolveGameDir(profileId: String): File {
        check(initialized) { "VPaths.init(context) must be called before resolving profile game directories" }
        return GameDirectoryResolver.resolve(gameDir, profileId, gameDirectoryMode)
    }

    /** Settings 唯一可调用的全局设置写入入口；同步提交后更新当前进程快照。 */
    @Synchronized
    fun setGameDirectoryMode(mode: GameDirectoryMode): Boolean {
        check(initialized) { "VPaths.init(context) must be called before changing game directory mode" }
        if (!gameDirectoryModeStore.save(mode)) return false
        gameDirectoryModeSnapshot = mode
        return true
    }

    /**
     * 解析全部路径并创建目录。幂等：重复调用只刷新外部存储路径。
     */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return

        filesPrivate = context.filesDir
        filesExternal = context.getExternalFilesDir(null) ?: fallbackExternalDir(context)
        cache = context.cacheDir
        nativeLibDir = context.applicationInfo.nativeLibraryDir
        runtimeModDir = context.getDir("runtime_mod", Context.MODE_PRIVATE)

        gameDir = File(filesPrivate, "games")
        gameDirectoryLockFile = File(filesPrivate, "game-directory.lock")
        minecraftVersionsFile = File(gameDir, "minecraft_versions.json")
        gameDirectoryModeStore = GameDirectoryModeStore(File(filesPrivate, "storage-settings/game-directory-mode"))
        GameDirectoryOperationLock.withLock(gameDirectoryLockFile) {
            defaultGameDirectoryMode = if (hasLegacySharedGameData(gameDir, minecraftVersionsFile)) {
                GameDirectoryMode.SHARED
            } else {
                // ZalithLauncher2's global versionIsolation default is enabled for clean installs.
                GameDirectoryMode.ISOLATED
            }
            if (!gameDirectoryModeStore.hasStoredValue() && !gameDirectoryModeStore.save(defaultGameDirectoryMode)) {
                throw IllegalStateException("无法持久化全局游戏目录默认设置")
            }
            gameDirectoryModeSnapshot = gameDirectoryModeStore.load(defaultGameDirectoryMode)
        }
        multirtDir = File(gameDir, "runtimes")
        jnaDir = File(gameDir, "jna_dir")
        componentsDir = File(filesPrivate, "components")

        launcherLogsDir = File(filesExternal, "logs")
        nativeLogsDir = File(launcherLogsDir, "native")
        mobilegluesDir = File(filesExternal, "mobileglues")

        settingsFile = File(filesPrivate, "settings.json")

        listOf(
            gameDir,
            File(gameDir, "versions"),
            multirtDir,
            jnaDir,
            componentsDir,
            launcherLogsDir,
            nativeLogsDir,
            mobilegluesDir,
        ).forEach { it.mkdirs() }

        initialized = true
    }

    private fun hasLegacySharedGameData(gameHome: File, profilesFile: File): Boolean {
        if (profilesFile.isFile) return true
        return listOf(
            "versions",
            "libraries",
            "assets",
            "mods",
            "config",
            "saves",
            "resourcepacks",
            "shaderpacks",
            "screenshots",
        ).any { File(gameHome, it).exists() }
    }

    /**
     * 外部私有目录拿不到时的兜底。与 ZL2 一致：
     * 先尝试 Android/data/<pkg>/files，再退回内部存储。
     */
    private fun fallbackExternalDir(context: Context): File {
        val candidate = File(
            Environment.getExternalStorageDirectory(),
            "Android/data/${context.packageName}/files"
        )
        return if (candidate.mkdirs() || candidate.isDirectory) {
            candidate
        } else {
            File(context.filesDir, "data_files").apply { mkdirs() }
        }
    }
}
