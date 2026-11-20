/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现。整合包的统一领域模型（规格书任务范围 B）。
 * 解析层产出 ModpackInfo，安装层消费它 + zip 实体落盘，两者解耦便于单测。
 */

package com.vaelin.startup.download.modpack

/** 支持的整合包格式（用户裁定首版三格式）。 */
enum class ModpackFormat(val displayName: String) {
    /** Modrinth .mrpack：zip 根含 modrinth.index.json */
    MRPACK("Modrinth (.mrpack)"),

    /** CurseForge zip：zip 根含 manifest.json（manifestType=minecraftModpack） */
    CURSEFORGE_ZIP("CurseForge (zip)"),

    /** MCBBS v2 国内格式——规范文档待用户/社区确认后接（见交接文档） */
    MCBBS_V2("MCBBS v2 (zip)"),

    /** 认不出的格式 */
    UNKNOWN("未知"),
}

/**
 * 一个待下载的整合包文件。
 *
 * @param relativePath 相对实例根的路径（如 mods/xxx.jar、config/yyy.cfg）
 * @param urls 候选下载地址（首个可用优先；FileDownloader 失败会重试但不会跨镜像切换，
 *   多镜像切换留给下载编排层做——ZL2 的 mapMCIMMirrorUrls 同理后置）
 * @param sha1 期望 sha1，可空（CF 清单直给的 fileName 场景拿不到哈希，退弱校验）
 * @param required 是否必需；非必需文件下载失败不判整包失败
 */
data class ModpackFile(
    val relativePath: String,
    val urls: List<String>,
    val sha1: String?,
    val required: Boolean,
)

/** 解析后的整合包清单。 */
data class ModpackInfo(
    val format: ModpackFormat,
    val name: String,
    val version: String,
    val summary: String,
    /** Minecraft 版本号，如 "1.20.1" */
    val gameVersion: String,
    /** 加载器短名：forge / neoforge / fabric / quilt / optifine；纯原版为 null */
    val loader: String?,
    val loaderVersion: String?,
    val files: List<ModpackFile>,
)

/**
 * 检查阶段的完整产物：清单 + zip 内 overrides 实体清单（安装期据此落盘，
 * 解析期不落盘，避免「解析一下就写一堆文件」的副作用）。
 *
 * @param overrides 相对路径列表（前缀已剥离，如 config/xxx.cfg）
 */
data class InspectedPack(
    val format: ModpackFormat,
    val info: ModpackInfo,
    val overrides: List<String>,
)

/** 解析失败统一异常。 */
class ModpackParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 安装失败统一异常。 */
class ModpackInstallException(message: String, cause: Throwable? = null) : Exception(message, cause)
