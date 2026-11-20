/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现。规格书第十部分：闸建在源框架层，新源自动继承合规；
 * 只拦截「文件分发动作」（下载/安装），检索与展示信息全球可用。
 */

package com.vaelin.startup.download.source

/** 地区闸拦截时抛出的异常。message 面向用户，说明是合规限制而不是网络故障。 */
class SourceGateException(message: String) : Exception(message)

/**
 * 地区闸。统一拦截文件分发动作。
 *
 * 特意只做一件事：判定 + 抛错。检索不过闸（规格书原文「检索与展示信息
 * 全球可用」），所以 search/project/versions 都不从这里走；只有下载/安装
 * 编排（以及将来的整合包导入）在动手前调 [assertDownloadAllowed]。
 *
 * 不可被模块系统覆盖：规格书第 25 条，合规开关位于核心层、不进 FeatureRegistry。
 */
class SourceGate(private val detector: RegionDetector) {

    /** 当前是否处于「文件分发被禁止」的地区。UI 用它隐藏下载按钮。 */
    fun isDistributionBlocked(): Boolean = detector.isMainland()

    /**
     * 分发动作前的强制检查。被拦时抛 [SourceGateException]，调用方必须显式
     * 捕获并展示，不允许静默跳过或降级重试。
     */
    fun assertDownloadAllowed() {
        if (isDistributionBlocked()) {
            throw SourceGateException("当前地区不开放文件下载（合规限制）")
        }
    }
}
