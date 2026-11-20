/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现。.mrpack（Modrinth 整合包 v1 规范）清单解析。
 * 字段对齐官方规范与 ZL2 ModrinthManifest。
 */

package com.vaelin.startup.download.modpack

import com.vaelin.startup.download.Json
import com.vaelin.startup.download.JsonValue
import com.vaelin.startup.download.arrayField
import com.vaelin.startup.download.field
import com.vaelin.startup.download.longField
import com.vaelin.startup.download.stringField

object MrpackParser {

    /**
     * 解析 modrinth.index.json 文本。
     *
     * 语义（对齐 ZL2 ModrinthPack.readModrinth + modrinth-code install_from）：
     * - files[].env.client == "unsupported" 的文件跳过（客户端不装）；
     * - dependencies 键：minecraft→游戏版本；forge/neoforge/fabric-loader/quilt-loader→加载器；
     * - 每个文件至少留一个下载地址，否则该项丢弃并记数（不整包失败——个别坏文件不应
     *   废掉整个包，交给安装层按 required 聚合）。
     */
    fun parse(text: String): ModpackInfo {
        val root = try {
            Json.parse(text)
        } catch (e: Exception) {
            throw ModpackParseException("modrinth.index.json 解析失败: ${e.message}", e)
        }
        if (root.stringField("game") != "minecraft") {
            throw ModpackParseException("不是 Minecraft 整合包清单（game=${root.stringField("game")}）")
        }
        val formatVersion = root.longField("formatVersion")?.toInt() ?: 1
        if (formatVersion != 1) {
            // 规范目前只有 1；未来出现 2 时这里显式拒绝，避免按 v1 语义瞎解
            throw ModpackParseException("不支持的 mrpack formatVersion=$formatVersion（当前只支持 1）")
        }

        val files = root.arrayField("files").orEmpty().mapNotNull { parseFile(it) }

        var gameVersion = ""
        var loader: String? = null
        var loaderVersion: String? = null
        root.field("dependencies")?.let { deps ->
            gameVersion = deps.stringField("minecraft").orEmpty()
            val loaders = listOf("forge", "neoforge", "fabric-loader", "quilt-loader")
            for (key in loaders) {
                val value = deps.stringField(key)
                if (value != null) {
                    loader = when (key) {
                        "fabric-loader" -> "fabric"
                        "quilt-loader" -> "quilt"
                        else -> key
                    }
                    loaderVersion = value
                    break
                }
            }
        }
        if (gameVersion.isEmpty()) {
            throw ModpackParseException("mrpack 缺少 dependencies.minecraft（游戏版本）")
        }

        return ModpackInfo(
            format = ModpackFormat.MRPACK,
            name = root.stringField("name").orEmpty(),
            version = root.stringField("versionId").orEmpty(),
            summary = root.stringField("summary").orEmpty(),
            gameVersion = gameVersion,
            loader = loader,
            loaderVersion = loaderVersion,
            files = files,
        )
    }

    private fun parseFile(obj: JsonValue): ModpackFile? {
        val path = obj.stringField("path") ?: return null
        if (!ModpackDetect.isSafeRelativePath(path)) return null
        // env.client == "unsupported"：客户端不安装（server-only 文件）
        if (obj.field("env")?.stringField("client") == "unsupported") return null
        val urls = obj.arrayField("downloads").orEmpty().mapNotNull { it.asDownloadUrl() }
        if (urls.isEmpty()) return null
        val sha1 = obj.field("hashes")?.stringField("sha1")?.lowercase()
        return ModpackFile(
            relativePath = path,
            urls = urls,
            sha1 = sha1,
            required = true,
        )
    }

    private fun JsonValue?.asDownloadUrl(): String? = when (this) {
        is JsonValue.Str -> value.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        else -> null
    }
}
