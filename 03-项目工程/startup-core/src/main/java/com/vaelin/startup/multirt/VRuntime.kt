/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * 移植自 ZalithLauncher2 的 game/multirt/Runtime.kt。
 * 去掉了对 ZLApplication.DEVICE_ARCHITECTURE 全局单例的依赖，
 * 改为把设备架构作为参数传进来，便于单测。
 */

package com.vaelin.startup.multirt

/**
 * 一个已解压的 JRE 运行时。
 *
 * 与 ZL2 的差别：`isCompatible()` 不再读全局设备架构，而是接收一个由
 * `Architecture.getDeviceArchitecture()` 算出的值——这样这个判据可以在 JVM 单测里跑。
 */
data class VRuntime(
    val name: String,
    val versionString: String?,
    val arch: String?,
    val javaVersion: Int,
    val isProvidedByLauncher: Boolean,
    val isJDK8: Boolean,
) {
    constructor(name: String) : this(
        name = name,
        versionString = null,
        arch = null,
        javaVersion = 0,
        isProvidedByLauncher = false,
        isJDK8 = false,
    )

    fun isCompatible(deviceArchitecture: Int): Boolean =
        versionString != null && deviceArchitecture == com.vaelin.startup.util.Architecture.archAsInt(arch)
}
