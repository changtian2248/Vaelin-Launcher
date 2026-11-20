/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.vaelin.startup.download

import java.io.File

/** 一个不再被当前版本引用的对象文件。 */
data class StaleAsset(
    val file: File,
    val hash: String,
)

/**
 * 资源清理：按「当前版本实际需要的 hash 集合」找出 objects 目录里的孤儿文件。
 *
 * **只列不删**。删除是不可逆的，而在本层我们并不知道上层是否还要兼容另一个版本的
 * 存档或资源包；由调用方拿到清单后再决定，也顺便让人能在日志里先看一眼。
 */
class AssetCleanup(private val objectsDir: File) {

    fun findStale(requiredHashes: Set<String>): List<StaleAsset> {
        if (!objectsDir.isDirectory) return emptyList()
        val required = HashSet<String>(requiredHashes.size)
        for (hash in requiredHashes) required += hash.trim().lowercase()

        val stale = ArrayList<StaleAsset>()
        val buckets = objectsDir.listFiles() ?: return emptyList()
        for (bucket in buckets) {
            if (!bucket.isDirectory) continue
            val files = bucket.listFiles() ?: continue
            for (file in files) {
                if (!file.isFile) continue
                val hash = file.name.lowercase()
                if (hash !in required) stale += StaleAsset(file, hash)
            }
        }
        stale.sortBy { it.file.absolutePath }
        return stale
    }
}
