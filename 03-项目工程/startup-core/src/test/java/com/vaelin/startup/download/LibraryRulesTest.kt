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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("NonAsciiCharacters")
class LibraryRulesTest {

    private val androidArm64 = RuleEnvironment.android("arm64-v8a")

    private fun allow(
        osName: String? = null,
        osArch: String? = null,
        features: Map<String, Boolean> = emptyMap(),
    ) = Rule(
        action = Rule.ACTION_ALLOW,
        osName = osName,
        osArch = osArch,
        osVersion = null,
        features = features,
    )

    private fun disallow(
        osName: String? = null,
        osArch: String? = null,
        features: Map<String, Boolean> = emptyMap(),
    ) = Rule(
        action = Rule.ACTION_DISALLOW,
        osName = osName,
        osArch = osArch,
        osVersion = null,
        features = features,
    )

    @Test
    fun `没有 rules 的库默认放行`() {
        assertTrue(LibraryRules.isAllowed(emptyList(), androidArm64))
    }

    @Test
    fun `linux 规则在 Android 上命中`() {
        assertTrue(LibraryRules.isAllowed(listOf(allow(osName = "linux")), androidArm64))
    }

    @Test
    fun `windows 规则在 Android 上不命中且默认拒绝`() {
        // 只有一条 allow-windows：既不命中，也没有别的规则把它放行，初始状态是 disallow
        assertFalse(LibraryRules.isAllowed(listOf(allow(osName = "windows")), androidArm64))
    }

    @Test
    fun `先 disallow linux 再 allow linux 时以最后一条为准`() {
        val rules = listOf(disallow(osName = "linux"), allow(osName = "linux"))

        // 「disallow 命中即排除」的简化写法会在这里答错，Mojang 的真实文档里这种写法很常见
        assertTrue(LibraryRules.isAllowed(rules, androidArm64))
    }

    @Test
    fun `先 allow linux 再 disallow linux 时库被排除`() {
        val rules = listOf(allow(osName = "linux"), disallow(osName = "linux"))

        assertFalse(LibraryRules.isAllowed(rules, androidArm64))
    }

    @Test
    fun `未命中的 disallow 不影响其它 allow`() {
        val rules = listOf(allow(osName = "linux"), disallow(osName = "osx"))

        assertTrue(LibraryRules.isAllowed(rules, androidArm64))
    }

    @Test
    fun `带 features 的 allow 规则在功能未开启时不命中`() {
        // R1 一律按 false 求值；这里环境里没有这个 feature，等价于 false
        val rules = listOf(allow(features = mapOf("is_demo_user" to true)))

        assertFalse(LibraryRules.isAllowed(rules, androidArm64))
    }

    @Test
    fun `features 显式声明为 false 时规则命中`() {
        val rules = listOf(allow(features = mapOf("is_demo_user" to false)))

        assertTrue(LibraryRules.isAllowed(rules, androidArm64))
    }

    @Test
    fun `os arch 规则按架构别名收敛比较`() {
        // 文档里可能写 aarch64，设备上报的是 arm64-v8a，两者必须视为同一架构
        assertTrue(LibraryRules.isAllowed(listOf(allow(osName = "linux", osArch = "aarch64")), androidArm64))
        assertFalse(LibraryRules.isAllowed(listOf(allow(osName = "linux", osArch = "x86_64")), androidArm64))
    }

    @Test
    fun `applicableLibraries 过滤掉被规则排除的库`() {
        val kept = Library("com.example:kept:1.0", null, emptyMap(), listOf(allow(osName = "linux")), null)
        val dropped = Library("com.example:dropped:1.0", null, emptyMap(), listOf(allow(osName = "windows")), null)
        val version = VersionJson(
            id = "1.20.1",
            type = "release",
            mainClass = "net.minecraft.client.main.Main",
            assets = "5",
            assetIndex = null,
            client = null,
            libraries = listOf(kept, dropped),
            javaVersion = null,
        )

        val applicable = version.applicableLibraries(androidArm64)

        assertEquals(1, applicable.size)
        assertEquals("com.example:kept:1.0", applicable.single().name)
    }
}
