/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现。Android 版地区信号源（规格书第 8 条：系统地区 + SIM 国家码），
 * 附 Beta 调试用的「模拟地区」覆盖开关（用户裁定：测试期保留；发布前按合规
 * 隐藏——届时此开关迁进开发者选项，R7 的活）。
 */

package com.vaelin.launcher.ui

import android.content.Context
import android.telephony.TelephonyManager
import com.vaelin.startup.download.source.RegionSignals
import java.util.Locale

/** 模拟地区档位。AUTO = 真实信号判定。 */
enum class SimulatedRegion(val prefValue: String) {
    AUTO("auto"),
    OVERSEAS("overseas"),
    MAINLAND("mainland"),
}

/**
 * 模拟地区开关的持久化。放 SharedPreferences 而不是 settings.json：
 * 它是调试设施不是用户设置，发布版隐藏后这里自然冷掉。
 */
class RegionSimStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): SimulatedRegion {
        val raw = prefs.getString(KEY_SIM, SimulatedRegion.AUTO.prefValue)
        return SimulatedRegion.entries.firstOrNull { it.prefValue == raw } ?: SimulatedRegion.AUTO
    }

    /** 循环切换 AUTO → OVERSEAS → MAINLAND → AUTO，返回新档位 */
    fun cycle(): SimulatedRegion {
        val next = when (load()) {
            SimulatedRegion.AUTO -> SimulatedRegion.OVERSEAS
            SimulatedRegion.OVERSEAS -> SimulatedRegion.MAINLAND
            SimulatedRegion.MAINLAND -> SimulatedRegion.AUTO
        }
        prefs.edit().putString(KEY_SIM, next.prefValue).apply()
        return next
    }

    companion object {
        private const val PREFS_NAME = "region_debug"
        private const val KEY_SIM = "sim_region"
    }
}

/**
 * Android 版 [RegionSignals]。
 *
 * 信号优先级：模拟开关（Beta 调试）> 真实信号。模拟档同时覆盖两个信号——
 * 只盖 SIM 的话，系统地区是 CN 的测试机模拟海外会被另一路信号打回大陆，
 * 调试开关就失效了。
 *
 * 真实信号取 `Locale.getDefault().country` 与 `TelephonyManager.getSimCountryIso()`
 * （后者直接给 ISO 国别码，MCC 换算是系统做的；双卡机返回默认数据卡国别，
 * 符合规格书「SIM 国家码」口径）。
 */
class AndroidRegionSignals(context: Context) : RegionSignals {

    private val appContext = context.applicationContext
    private val simStore = RegionSimStore(appContext)

    override fun systemCountry(): String? = when (simStore.load()) {
        SimulatedRegion.OVERSEAS -> "US"
        SimulatedRegion.MAINLAND -> "CN"
        SimulatedRegion.AUTO -> Locale.getDefault().country
    }

    override fun simCountry(): String? = when (simStore.load()) {
        SimulatedRegion.OVERSEAS -> "US"
        SimulatedRegion.MAINLAND -> "CN"
        SimulatedRegion.AUTO -> readRealSimCountry()
    }

    private fun readRealSimCountry(): String? {
        return runCatching {
            val manager = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            manager?.simCountryIso?.uppercase(Locale.ROOT)?.takeIf { it.length == 2 }
        }.getOrNull()
    }
}
