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

package com.vaelin.startup.profile

import com.vaelin.startup.util.VLog
import com.vaelin.startup.util.VPaths
import com.vaelin.startup.util.ensureParentDirectory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val TAG = "ProfileStore"
private const val SCHEMA_VERSION = 1

/**
 * 实例清单的持久化。
 *
 * 存储格式刻意保持「一个 JSON 数组 + 版本号字段」，而不是 ZL2 的
 * MMKV + kotlinx.serialization 双层结构：R1 只需要读写正确，
 * 出问题时能直接 cat 出来看。
 *
 * 写入采用「先写 .tmp 再 rename」的原子替换，避免进程在写一半时被杀
 * 导致清单文件损坏——这个文件一坏，用户所有实例都看不见。
 */
object ProfileStore {

    fun load(): List<VersionProfile> {
        val file = VPaths.minecraftVersionsFile
        if (!file.exists()) return emptyList()

        return runCatching {
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("profiles") ?: JSONArray()
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    runCatching { parse(obj) }
                        .onSuccess { add(it) }
                        .onFailure { VLog.warning(TAG, "skip malformed profile at index $i", it) }
                }
            }
        }.onFailure {
            VLog.error(TAG, "failed to read ${file.absolutePath}", it)
        }.getOrDefault(emptyList())
    }

    /**
     * 全量写入。调用方负责给出完整列表。
     */
    fun save(profiles: List<VersionProfile>): Boolean {
        val file = VPaths.minecraftVersionsFile
        val tmp = File(file.parentFile, file.name + ".tmp")

        return runCatching {
            val arr = JSONArray()
            profiles.forEach { arr.put(serialize(it)) }
            val root = JSONObject().apply {
                put("schemaVersion", SCHEMA_VERSION)
                put("profiles", arr)
            }

            tmp.ensureParentDirectory()
            tmp.writeText(root.toString(2))

            // File.renameTo 在目标已存在时的行为依赖文件系统，
            // 这里先删再 rename，接受极短窗口内「没有清单」的风险，
            // 换来「永远不会出现半个文件」。
            if (file.exists() && !file.delete()) {
                VLog.warning(TAG, "failed to delete stale ${file.absolutePath}")
            }
            if (!tmp.renameTo(file)) {
                // rename 失败时退化为直接写，仍然保证最终有可用文件
                file.writeText(root.toString(2))
                tmp.delete()
            }
            VLog.info(TAG, "saved ${profiles.size} profile(s) to ${file.absolutePath}")
            true
        }.onFailure {
            VLog.error(TAG, "failed to write ${file.absolutePath}", it)
        }.getOrDefault(false)
    }

    fun upsert(profile: VersionProfile): List<VersionProfile> {
        val current = load().toMutableList()
        val idx = current.indexOfFirst { it.id == profile.id }
        if (idx >= 0) current[idx] = profile else current.add(profile)
        save(current)
        return current
    }

    fun find(id: String): VersionProfile? = load().firstOrNull { it.id == id }

    fun delete(id: String): List<VersionProfile> {
        val next = load().filterNot { it.id == id }
        save(next)
        return next
    }

    // ---- 序列化 ----

    private fun serialize(p: VersionProfile): JSONObject = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("minecraftVersion", p.minecraftVersion)
        put("modLoader", p.modLoader)
        p.modLoaderVersion?.let { put("modLoaderVersion", it) }
        put("rendererId", p.rendererId)
        put("rendererUniqueId", p.rendererUniqueId)
        put("memoryMB", p.memoryMB)
        put("extraJvmArgs", JSONArray(p.extraJvmArgs))
        p.javaRuntime?.let { put("javaRuntime", it) }
        put("lastPlayedAt", p.lastPlayedAt)
    }

    private fun parse(o: JSONObject): VersionProfile {
        val id = o.getString("id")
        return VersionProfile(
            id = id,
            name = o.optString("name", id),
            minecraftVersion = o.getString("minecraftVersion"),
            modLoader = o.optString("modLoader", "vanilla"),
            modLoaderVersion = o.optString("modLoaderVersion").takeIf { it.isNotEmpty() && it != "null" },
            rendererId = o.optString("rendererId", "opengles2"),
            rendererUniqueId = o.optString("rendererUniqueId", ""),
            memoryMB = o.optInt("memoryMB", 1024),
            extraJvmArgs = o.optJSONArray("extraJvmArgs")?.let { arr ->
                buildList { for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotEmpty() }?.let { add(it) } }
            } ?: emptyList(),
            javaRuntime = o.optString("javaRuntime").takeIf { it.isNotEmpty() && it != "null" },
            lastPlayedAt = o.optLong("lastPlayedAt", 0L)
        )
    }
}
