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

import android.content.Intent
import com.vaelin.startup.profile.VersionProfile

/**
 * 主进程 → :game 的启动参数契约（T1.10b A.4）。
 *
 * VersionProfile 是普通 data class（VersionProfile.kt:38-50），不是 Parcelable/Serializable，
 * 且 extraJvmArgs 可能较大，因此 Intent 只传拆开的原始类型，绝不塞整个对象。
 * :game 侧 read() 对缺失字段显式抛 IllegalStateException，不使用默认值掩盖
 * （团队准则：关键契约缺失必须显式报错并中止，禁止静默降级）。
 */
object GameLaunchContract {

    const val EXTRA_VERSION_ID = "com.vaelin.launcher.game.VERSION_ID"
    const val EXTRA_MINECRAFT_VERSION = "com.vaelin.launcher.game.MINECRAFT_VERSION"
    const val EXTRA_MOD_LOADER = "com.vaelin.launcher.game.MOD_LOADER"
    const val EXTRA_MOD_LOADER_VERSION = "com.vaelin.launcher.game.MOD_LOADER_VERSION"
    const val EXTRA_RENDERER_ID = "com.vaelin.launcher.game.RENDERER_ID"
    const val EXTRA_RENDERER_UNIQUE_ID = "com.vaelin.launcher.game.RENDERER_UNIQUE_ID"
    const val EXTRA_MEMORY_MB = "com.vaelin.launcher.game.MEMORY_MB"
    const val EXTRA_EXTRA_JVM_ARGS = "com.vaelin.launcher.game.EXTRA_JVM_ARGS"
    const val EXTRA_JAVA_RUNTIME = "com.vaelin.launcher.game.JAVA_RUNTIME"

    /** 从 :game 的 Intent 读回的启动参数，全部为基本类型/字符串。 */
    data class GameLaunchParams(
        val versionId: String,
        val minecraftVersion: String,
        val modLoader: String,
        val modLoaderVersion: String?,
        val rendererId: String,
        val rendererUniqueId: String,
        val memoryMB: Int,
        val extraJvmArgs: List<String>,
        val javaRuntime: String?,
    )

    /** 把 profile 拆成原始类型写进 Intent（主进程调用）。 */
    fun write(intent: Intent, profile: VersionProfile): Intent = intent.apply {
        putExtra(EXTRA_VERSION_ID, profile.id)
        putExtra(EXTRA_MINECRAFT_VERSION, profile.minecraftVersion)
        putExtra(EXTRA_MOD_LOADER, profile.modLoader)
        putExtra(EXTRA_MOD_LOADER_VERSION, profile.modLoaderVersion)
        putExtra(EXTRA_RENDERER_ID, profile.rendererId)
        putExtra(EXTRA_RENDERER_UNIQUE_ID, profile.rendererUniqueId)
        putExtra(EXTRA_MEMORY_MB, profile.memoryMB)
        putExtra(EXTRA_EXTRA_JVM_ARGS, profile.extraJvmArgs.toTypedArray())
        putExtra(EXTRA_JAVA_RUNTIME, profile.javaRuntime)
    }

    /** 从 Intent 读回；任何必需字段缺失都视为契约破坏，显式抛异常。 */
    fun read(intent: Intent): GameLaunchParams {
        fun required(key: String): String = intent.getStringExtra(key)
            ?: throw IllegalStateException("Missing $key in VMActivity intent")

        val versionId = required(EXTRA_VERSION_ID)
        if (versionId.isBlank()) {
            throw IllegalStateException("$EXTRA_VERSION_ID is blank")
        }
        val memoryMB = intent.getIntExtra(EXTRA_MEMORY_MB, 0)
        if (memoryMB <= 0) {
            throw IllegalStateException("Missing or invalid $EXTRA_MEMORY_MB")
        }
        return GameLaunchParams(
            versionId = versionId,
            minecraftVersion = required(EXTRA_MINECRAFT_VERSION),
            modLoader = required(EXTRA_MOD_LOADER),
            modLoaderVersion = intent.getStringExtra(EXTRA_MOD_LOADER_VERSION),
            rendererId = required(EXTRA_RENDERER_ID),
            rendererUniqueId = required(EXTRA_RENDERER_UNIQUE_ID),
            memoryMB = memoryMB,
            extraJvmArgs = intent.getStringArrayExtra(EXTRA_EXTRA_JVM_ARGS)?.toList() ?: emptyList(),
            javaRuntime = intent.getStringExtra(EXTRA_JAVA_RUNTIME),
        )
    }
}
