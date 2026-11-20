/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.vaelin.startup.profile

import android.util.AtomicFile
import java.io.File

/** Launcher-wide Minecraft working-directory mode, following ZalithLauncher2's global switch. */
enum class GameDirectoryMode(val storageValue: String, val displayName: String) {
    SHARED("shared", "共享游戏目录"),
    ISOLATED("isolated", "全局版本隔离");

    companion object {
        fun fromStorageValue(value: String?): GameDirectoryMode? =
            entries.firstOrNull { it.storageValue == value }
    }
}

/**
 * Cross-process atomic storage. SharedPreferences caches are not reliable for the long-lived :game process,
 * so the mode is read from disk each time a game directory is resolved.
 */
class GameDirectoryModeStore(file: File) {
    private val atomicFile = AtomicFile(file)
    private val baseFile = file

    fun hasStoredValue(): Boolean = baseFile.isFile || File(baseFile.path + ".bak").isFile

    fun load(defaultMode: GameDirectoryMode): GameDirectoryMode {
        if (!hasStoredValue()) return defaultMode
        return runCatching {
            atomicFile.openRead().bufferedReader(Charsets.UTF_8).use { it.readText().trim() }
        }.getOrNull()?.let(GameDirectoryMode::fromStorageValue) ?: defaultMode
    }

    @Synchronized
    fun save(mode: GameDirectoryMode): Boolean {
        val parent = baseFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.isDirectory) return false
        val stream = atomicFile.startWrite()
        return try {
            stream.write(mode.storageValue.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
            true
        } catch (t: Throwable) {
            atomicFile.failWrite(stream)
            false
        }
    }
}
