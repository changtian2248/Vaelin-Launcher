/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现。地区闸判定逻辑：规格书第 8 条 + 第十部分。
 * 判定信号 = 系统地区 + SIM 国家码；判定失败默认按大陆处理。
 */

package com.vaelin.startup.download.source

/** 地区判定结论。UNKNOWN 只出现在信号冲突时，按规格书保守落在大陆侧。 */
enum class RegionVerdict {
    MAINLAND,
    OVERSEAS,
}

/**
 * 判定信号来源。抽成接口是为了：
 * 1. Android 侧实现去读 Locale / TelephonyManager（需要 Context）；
 * 2. 单测能注入任意信号组合，不用 shadow android.jar。
 *
 * 两个信号都返回 ISO 3166-1 alpha-2 两位大写国别码（如 "CN" / "US"），
 * 拿不到返回 null。MCC → 国别的换算由 Android 侧适配器做（460/461 → CN），
 * 核心层只认国别码，保持纯 JVM 可测。
 */
interface RegionSignals {

    /** 系统地区（Locale.getDefault().country 一类） */
    fun systemCountry(): String?

    /** SIM 国家码（TelephonyManager.getSimCountryIso 一类） */
    fun simCountry(): String?
}

/**
 * 地区判定器。
 *
 * 规则（按规格书「判定失败默认按大陆处理」展开）：
 * - 任一信号是 CN → MAINLAND；
 * - 两个信号都能拿到且都不是 CN → OVERSEAS；
 * - 两个信号都拿不到（判定失败）→ MAINLAND；
 * - 信号冲突（一个 CN 一个非 CN）→ MAINLAND（保守侧，宁可误伤不可漏放）。
 */
class RegionDetector(private val signals: RegionSignals) {

    fun verdict(): RegionVerdict {
        val system = normalize(signals.systemCountry())
        val sim = normalize(signals.simCountry())
        if (isChina(system) || isChina(sim)) return RegionVerdict.MAINLAND
        if (system != null || sim != null) return RegionVerdict.OVERSEAS
        return RegionVerdict.MAINLAND
    }

    fun isMainland(): Boolean = verdict() == RegionVerdict.MAINLAND

    private fun normalize(raw: String?): String? =
        raw?.trim()?.uppercase()?.takeIf { it.length == 2 && it.all { ch -> ch in 'A'..'Z' } }

    private fun isChina(country: String?): Boolean = country == CHINA

    companion object {
        const val CHINA = "CN"
    }
}
