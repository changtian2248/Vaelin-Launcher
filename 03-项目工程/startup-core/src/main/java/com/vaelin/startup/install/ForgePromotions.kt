/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * T1.5.5 —— Forge 版本解析与 installer 下载 URL 构造（纯 JVM，可单测）。
 *
 * ZL2 对照点（game/addons/modloader/forgelike/forge/ForgeVersions.kt +
 * game/addons/mirror/BMCLAPI.kt + game/download/game/forge/Download.ForgeLike.kt:42-72）：
 * - ZL2 从 files.minecraftforge.net 的 index_<mc>.html 抓 HTML 或 BMCLAPI 的
 *   /forge/minecraft/<mc> 接口取全量版本列表；Vaelin R1 只需要装 recommended 一档，
 *   官方 promotions_slim.json 一个小 JSON 就够，不需要解析 HTML。
 * - ZL2 的 installer 下载 URL（ForgeVersions.getDownloadUrl）是
 *   `files.minecraftforge.net/maven/net/minecraftforge/forge/<ver>/forge-<ver>-installer.jar`，
 *   BMCLAPI.kt:47-48 把 `files.minecraftforge.net/maven` 与 `maven.minecraftforge.net`
 *   都映射到 `bmclapi2.bangbang93.com/maven`。Vaelin 按任务书固定
 *   BMCLAPI 镜像优先、官方 maven 兜底的双候选列表（路径形态与 ZL2 一致）。
 * - promotions_slim.json 本体 BMCLAPI 不镜像，只走官方源（实测 2026-11-08）。
 */

package com.vaelin.startup.install

import com.vaelin.startup.download.Json
import com.vaelin.startup.download.JsonParseException
import com.vaelin.startup.download.asObject
import com.vaelin.startup.download.asString
import com.vaelin.startup.download.field

/** Forge 的 maven 坐标路径（installer 与 .sha1 侧车共用） */
private const val FORGE_COORDINATES = "net/minecraftforge/forge"

object ForgePromotions {

    /** 官方 promotions 文档（任务书指定 URL；实测顶层键是 "promos"） */
    const val PROMOTIONS_URL =
        "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json"

    /** BMCLAPI maven 镜像根（ZL2 BMCLAPI.kt:30 的 MAVEN 同款根路径） */
    const val BMCLAPI_MAVEN_ROOT = "https://bmclapi2.bangbang93.com/maven"

    /** Forge 官方 maven（ZL2 ForgeVersions FORGE_FILE_URL 的 files.minecraftforge.net/maven 与此同域别名） */
    const val FORGE_MAVEN_ROOT = "https://maven.minecraftforge.net"

    /**
     * 解析 promotions_slim.json。
     *
     * 真实结构：顶层 "promos" 对象，键形如 "1.20.1-recommended"（任务书写作
     * promotions["1.20.1-recommended"] 是口语化表述；实现按真实文档解析，
     * 顶层没有 "promos" 直接抛错，不猜 "promotions" 之类的别名键）。
     */
    fun parse(text: String): ForgePromotionsMap {
        val root = try {
            Json.parse(text)
        } catch (e: JsonParseException) {
            throw ForgeInstallException("promotions_slim.json 解析失败: ${e.message}", e)
        }
        val promos = root.field("promos").asObject()
            ?: throw ForgeInstallException("promotions_slim.json 缺少顶层 'promos' 对象，无法解析 Forge 版本表")
        val values = LinkedHashMap<String, String>()
        promos.forEach { (key, value) -> value.asString()?.let { values[key] = it } }
        if (values.isEmpty()) {
            throw ForgeInstallException("promotions_slim.json 的 'promos' 对象为空，无法解析 Forge 版本表")
        }
        return ForgePromotionsMap(values)
    }
}

/** promotions 推荐档：官方标记的稳定版 / 最新构建。 */
enum class ForgeChannel { RECOMMENDED, LATEST }

/** 一次安装选定的 Forge 构建版本。 */
data class ForgeBuild(
    val mcVersion: String,
    val forgeVersion: String,
    val channel: ForgeChannel,
) {
    /** maven 路径用的完整版本串，如 "1.20.1-47.2.0"；promotions 值带前缀时原样保留 */
    val fullVersion: String =
        if (forgeVersion.startsWith("$mcVersion-")) forgeVersion else "$mcVersion-$forgeVersion"

    /** installer 下载候选（BMCLAPI 镜像优先，官方 maven 兜底；FileDownloader 逐个尝试） */
    fun installerUrls(): List<String> = listOf(
        "${ForgePromotions.BMCLAPI_MAVEN_ROOT}/$FORGE_COORDINATES/$fullVersion/forge-$fullVersion-installer.jar",
        "${ForgePromotions.FORGE_MAVEN_ROOT}/$FORGE_COORDINATES/$fullVersion/forge-$fullVersion-installer.jar",
    )

    /** .sha1 侧车候选（与 installer URL 同序；都拿不到时下载层退回 Content-Length 校验） */
    fun installerSha1Urls(): List<String> = installerUrls().map { "$it.sha1" }
}

/** 已解析的 promotions 键值表；键形如 "1.20.1-recommended"。 */
class ForgePromotionsMap(private val promos: Map<String, String>) {

    fun recommended(mcVersion: String): String? = lookup(mcVersion, "-recommended")

    fun latest(mcVersion: String): String? = lookup(mcVersion, "-latest")

    /**
     * 选出要安装的 Forge 版本：recommended 优先；recommended 缺条目时显式降级 latest
     * （返回值带 [ForgeChannel.LATEST] 标注，UI 与日志都能看到降级）；两者都缺抛错。
     */
    fun resolve(mcVersion: String): ForgeBuild {
        recommended(mcVersion)?.let { return ForgeBuild(mcVersion, it, ForgeChannel.RECOMMENDED) }
        latest(mcVersion)?.let { return ForgeBuild(mcVersion, it, ForgeChannel.LATEST) }
        throw ForgeInstallException(
            "promotions 里没有 Minecraft $mcVersion 的 recommended/latest 条目" +
                "（已知条目示例: ${promos.keys.take(8).joinToString()}…）",
        )
    }

    /**
     * 条目键查找：先用原始 mcVersion，再试 '-' 换 '_' 的变体
     * （promotions 对 1.7.10-pre4 这类预发布版本的键用下划线；1.20.1 两种相同）。
     */
    private fun lookup(mcVersion: String, suffix: String): String? =
        promos["$mcVersion$suffix"]
            ?: promos["${mcVersion.replace('-', '_')}$suffix"]
}
