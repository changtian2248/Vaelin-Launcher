/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现。规格书第 38 条：资源源抽象可插拔。
 */

package com.vaelin.startup.download.source

/**
 * 资源源抽象。检索/详情/版本列表/下载地址解析四个动作。
 *
 * 实现约束：
 * - 全部是同步阻塞调用，线程编排交给调用方（与 FileDownloader 同一约定），
 *   这样单测不需要协程设施；
 * - 只返回 source 包里的统一模型，不允许把平台私有类型漏出到接口上；
 * - 网络错误一律抛 [SourceException]（或其子类），不吞异常、不返回 null 装成功。
 */
interface ContentSource {

    /** 稳定 id，如 "modrinth" / "curseforge"。跨版本不得改名（持久化引用按它查源）。 */
    val sourceId: String

    /** 用户可见的源名称 */
    val displayName: String

    /** 按条件搜索项目。 */
    fun search(query: AddonSearchQuery): AddonSearchPage

    /** 取项目详情。 */
    fun project(projectId: String): AddonProject

    /** 取项目的版本列表，可按游戏版本/加载器过滤（null 不过滤）。 */
    fun versions(projectId: String, gameVersion: String? = null, loader: String? = null): List<AddonVersion>

    /**
     * 把 [file] 解析为可直接 GET 的下载地址。
     *
     * 默认实现原样返回 [AddonFile.downloadUrl]。需要重定向解析或镜像替换的源
     * （如 CurseForge）覆写它。调用方在分发动作前会先过地区闸（见 SourceGate）。
     */
    fun resolveDownloadUrl(file: AddonFile): String = file.downloadUrl
}

/** 源调用失败的统一异常。message 给用户看时要带上源名与动作上下文。 */
open class SourceException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 项目/版本不存在（HTTP 404）。UI 上区别于一般网络错误。 */
class SourceNotFoundException(message: String) : SourceException(message)
