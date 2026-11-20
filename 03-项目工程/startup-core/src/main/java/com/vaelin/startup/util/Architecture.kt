/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 * Copyright (C) 2025-2026 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * 取自 ZalithLauncher2 的 utils/device/Architecture.kt（本身又移植自 PojavLauncher 的
 * Architecture.java）。Vaelin 首版只出 arm64-v8a（规格书第 9 条），
 * 但这里保留完整映射，避免将来放开 ABI 时要回头改调用点。
 */

package com.vaelin.startup.util

import android.os.Build

object Architecture {
    const val UNSUPPORTED_ARCH = -1
    const val ARCH_ARM64 = 0x1
    const val ARCH_ARM = 0x2
    const val ARCH_X86 = 0x4
    const val ARCH_X86_64 = 0x8

    const val ADDRESS_SPACE_LIMIT_32_BIT: Long = 0xbfffffffL
    const val ADDRESS_SPACE_LIMIT_64_BIT: Long = 0x7fffffffffL

    fun getAddressSpaceLimit() =
        if (is64BitsDevice) ADDRESS_SPACE_LIMIT_64_BIT else ADDRESS_SPACE_LIMIT_32_BIT

    val is64BitsDevice: Boolean
        get() = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()

    val is32BitsDevice: Boolean
        get() = !is64BitsDevice

    fun getDeviceArchitecture(): Int {
        return if (isX86Device()) {
            if (is64BitsDevice) ARCH_X86_64 else ARCH_X86
        } else {
            if (is64BitsDevice) ARCH_ARM64 else ARCH_ARM
        }
    }

    private fun isX86Device(): Boolean {
        val abis = if (is64BitsDevice) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS
        val comparedArch = if (is64BitsDevice) ARCH_X86_64 else ARCH_X86
        return abis.any { archAsInt(it) == comparedArch }
    }

    fun archAsInt(arch: String?): Int {
        val normalized = arch?.lowercase()?.trim()?.replace(" ", "") ?: return UNSUPPORTED_ARCH
        return when {
            normalized.contains("arm64") || normalized == "aarch64" -> ARCH_ARM64
            normalized.contains("arm") || normalized == "aarch32" -> ARCH_ARM
            normalized.contains("x86_64") || normalized.contains("amd64") -> ARCH_X86_64
            normalized.contains("x86") ||
                (normalized.startsWith("i") && normalized.endsWith("86")) -> ARCH_X86
            else -> UNSUPPORTED_ARCH
        }
    }

    /** assets/runtimes/<jre>/ 下的 bin 包名，例如 bin-arm64.tar.xz */
    fun archAsString(arch: Int): String = when (arch) {
        ARCH_ARM64 -> "arm64"
        ARCH_ARM -> "arm"
        ARCH_X86_64 -> "x86_64"
        ARCH_X86 -> "x86"
        else -> "UNSUPPORTED_ARCH"
    }

    /** Android ABI 目录名 */
    fun archAsStringAndroid(arch: Int): String = when (arch) {
        ARCH_ARM64 -> "arm64-v8a"
        ARCH_ARM -> "armeabi-v7a"
        ARCH_X86_64 -> "x86_64"
        ARCH_X86 -> "x86"
        else -> "UNSUPPORTED_ARCH"
    }
}
