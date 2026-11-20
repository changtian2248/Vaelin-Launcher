/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.vaelin.startup.download

/**
 * rules 求值时看到的环境。
 *
 * 刻意不用 `android.os.Build`：一是纯 JVM 单测用不了，二是 rules 求值本身是纯函数，
 * 把设备信息当参数传进来，同一个库在不同设备上的取舍才能在测试里被逐个钉住。
 */
data class RuleEnvironment(
    val osName: String,
    val osArch: String,
    val osVersion: String = "",
    val features: Map<String, Boolean> = emptyMap(),
) {
    companion object {
        const val OS_LINUX = "linux"
        const val OS_WINDOWS = "windows"
        const val OS_OSX = "osx"

        /** Android 设备对 Mojang 而言就是一台 linux。 */
        fun android(
            abi: String,
            osVersion: String = "",
            features: Map<String, Boolean> = emptyMap(),
        ): RuleEnvironment = RuleEnvironment(
            osName = OS_LINUX,
            osArch = NativeClassifiers.mojangArch(abi),
            osVersion = osVersion,
            features = features,
        )
    }
}

/** 这条规则是否适用于给定环境。任一显式条件不匹配就整条不适用。 */
fun Rule.matches(environment: RuleEnvironment): Boolean {
    if (osName != null && osName != environment.osName) return false
    if (osArch != null &&
        NativeClassifiers.normalizeArch(osArch) != NativeClassifiers.normalizeArch(environment.osArch)
    ) {
        return false
    }
    // Mojang 的 os.version 是精确匹配，且从未在真实 rules 里出现过；Android 也拿不到
    // 一个能对上的 Windows/macOS 版本号，因此带该条件的规则在这里自然不命中
    if (osVersion != null && osVersion != environment.osVersion) return false
    for ((key, expected) in features) {
        val actual = environment.features[key] ?: false
        if (actual != expected) return false
    }
    return true
}

/**
 * library rules 求值。
 *
 * 严格按 Mojang 的求值顺序，而不是「命中的 disallow 立刻排除」那种简化写法：
 * - 没有 rules 数组 → 默认放行；
 * - 有 rules → 初始状态是 disallow，然后**按文档顺序**逐条看，命中就按该条的
 *   action 覆盖当前状态，最后一条命中的规则说了算（last match wins）。
 *
 * 这个区别是实质性的：Mojang 的规则常常写成「先 disallow 掉某个 os，再 allow 回
 * 其它 os」，如果做成「disallow 命中即返回 false」，后面那条 allow 就永远没机会生效。
 * ZL2 里的 `Rule.checkRules` 是只判 `osx` 的简化版，不能照抄。
 */
object LibraryRules {

    fun isAllowed(rules: List<Rule>, environment: RuleEnvironment): Boolean {
        if (rules.isEmpty()) return true
        var allowed = false
        for (rule in rules) {
            if (rule.matches(environment)) {
                allowed = rule.isAllow
            }
        }
        return allowed
    }
}

/**
 * native 包的分类器选择。三件事都在这里：
 * 1. Android ABI → Mojang / LWJGL 词汇的 arch；
 * 2. `${arch}` 占位符替换（`natives` 字段的值可能是 `natives-linux-${arch}`）；
 * 3. 从 `downloads.classifiers` 里挑出真正要下的那一条。
 */
object NativeClassifiers {

    private const val ARCH_PLACEHOLDER = "\${arch}"

    /**
     * ABI → natives 模板里 `${arch}` 的替换值。
     *
     * 用 LWJGL 3 的分类器命名（arm64 / arm32 / x86 / x86_64）：1.19+ 的 lwjgl 包
     * 就按这套名字发构件；lwjgl 2 时代 Windows 上曾用 32/64，但 Android 只跑 linux，
     * 不需要兼容那套。
     */
    fun mojangArch(abi: String): String = when (abi.lowercase()) {
        "arm64-v8a" -> "arm64"
        "armeabi-v7a", "armeabi" -> "arm32"
        "x86" -> "x86"
        "x86_64" -> "x86_64"
        else -> abi.lowercase()
    }

    /** 把同一架构的各种叫法收敛成一个词，供 rules 比较使用。 */
    fun normalizeArch(value: String): String = when (value.lowercase()) {
        "i386", "i486", "i586", "i686", "x86", "x86-32", "x86_32", "x32" -> "x86"
        "x86_64", "x86-64", "amd64", "x64" -> "x86_64"
        "arm64", "aarch64", "armv8", "arm64-v8a" -> "arm64"
        "arm", "arm32", "aarch32", "armv7", "armv7l", "armeabi", "armeabi-v7a" -> "arm32"
        else -> value.lowercase()
    }

    /** 同一架构在 classifiers 里可能出现的后缀，按先精确后兼容排序。 */
    fun classifierAliases(abi: String): List<String> = when (normalizeArch(abi)) {
        "arm64" -> listOf("arm64", "aarch64")
        "arm32" -> listOf("arm32", "arm")
        "x86" -> listOf("x86", "i386")
        "x86_64" -> listOf("x86_64", "amd64")
        else -> emptyList()
    }

    /**
     * 取该 library 在当前设备上要下载的分类器键；没有可用 native 包时返回 null。
     *
     * 返回 null 不是异常：例如 lwjgl 3 之前的库只发 x64 的 linux 包，arm64 设备上
     * 装它没有意义，调用方应跳过并记一条日志，而不是把整轮下载判失败。
     */
    fun resolve(
        library: Library,
        abi: String,
        osName: String = RuleEnvironment.OS_LINUX,
    ): String? {
        val template = library.natives[osName] ?: return null
        val classifiers = library.downloads?.classifiers ?: return null
        if (classifiers.isEmpty()) return null

        val arch = mojangArch(abi)

        // 只有模板真的带占位符时才做替换后的精确匹配：模板不带占位符时 replace 是空操作，
        // 直接 containsKey 会把「无架构后缀的通用包」当成架构包放行，等于绕过了下面的平台判断
        if (template.contains(ARCH_PLACEHOLDER)) {
            val expanded = template.replace(ARCH_PLACEHOLDER, arch)
            if (classifiers.containsKey(expanded)) return expanded
        }

        // 模板里没写占位符的情况（1.19+ 的 lwjgl 就是 `"linux": "natives-linux"`）：
        // 库里按 `natives-linux-arm64` 这样的后缀发布架构包，补后缀再找一次
        if (!template.contains(ARCH_PLACEHOLDER)) {
            for (alias in classifierAliases(abi)) {
                val candidate = "$template-$alias"
                if (classifiers.containsKey(candidate)) return candidate
            }
        }

        // 无后缀的通用分类器只在该平台的默认架构上才成立；别的架构装上去就是
        // 一个永远不会被加载的 .so，白占体积还会在排查时误导人
        if (!template.contains(ARCH_PLACEHOLDER) &&
            classifiers.containsKey(template) &&
            normalizeArch(arch) == platformDefaultArch(osName)
        ) {
            return template
        }
        return null
    }

    private fun platformDefaultArch(osName: String): String = when (osName.lowercase()) {
        RuleEnvironment.OS_LINUX, RuleEnvironment.OS_WINDOWS, RuleEnvironment.OS_OSX -> "x86_64"
        else -> ""
    }
}
