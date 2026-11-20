/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * 移植自 ZalithLauncher2 的 utils/network/AndroidUsingConnectivity.kt，
 * 唯一改动是把 GlobalContext 单例换成显式传 Context。
 */

package com.vaelin.startup.util

import android.content.Context
import android.net.ConnectivityManager

/**
 * 取设备当前网络的真实 DNS 服务器。
 *
 * 用途：JVM 侧的 `-Dsun.net.spi.nameservice...` 与 `ext.net.resolvPath` 需要一份
 * resolv.conf。系统那份在 Android 上不可读，只能自己按 ConnectivityManager 生成。
 * 拿不到时返回 null，调用方回退到不注入任何 DNS 配置。
 */
fun getSystemDnsServerAddresses(context: Context): List<String>? = runCatching {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
    val network = cm.activeNetwork ?: return null
    val linkProperties = cm.getLinkProperties(network) ?: return null

    linkProperties.dnsServers
        .mapNotNull { it.hostAddress?.takeIf(String::isNotEmpty) }
        .takeIf(List<String>::isNotEmpty)
}.getOrNull()
