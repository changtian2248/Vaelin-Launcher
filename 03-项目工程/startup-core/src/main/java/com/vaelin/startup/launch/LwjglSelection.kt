/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.vaelin.startup.launch

import com.vaelin.startup.components.VComponents
import com.vaelin.startup.download.VersionJson
import com.vaelin.startup.util.VPaths
import java.io.File

/**
 * LWJGL 组件选择，对齐 ZL2 `LaunchArgs.kt` 的 detectLwjglVersion / lwjglVersionDir /
 * getLWJGL3ClassPath 三件套：从 version json 探测要求的 LWJGL 版本 → 映射到随 APK 分发的
 * `app_runtime/lwjgl` 组件目录 → 按上游顺序拼出组件 jar。
 *
 * 组件 jar 必须排在 Mojang 下载的库之前：ZL2 的补丁版 org.lwjgl 类（GLFW → CallbackBridge
 * 输入桥等）要赢过 Mojang 原版同名类，否则游戏会在 Android 上调到不存在的 GLFW JNI。
 * jar 顺序本身就是类加载优先级，见 [classpathJars]。
 *
 * 纯 JVM，可独立单测。
 */
object LwjglSelection {

    /** 一次组件选择的结果。[componentDir] 是 [VComponents.Component.installName]，如 `lwjgl/3.3.3`。 */
    data class Choice(val componentDir: String, val classpathJars: List<File>)

    private const val PREFIX_LWJGL = "org.lwjgl:lwjgl:"
    private const val PREFIX_LWJGL_LWJGL = "org.lwjgl.lwjgl:lwjgl:"

    /**
     * 从库清单探测要求的 LWJGL 版本整数（3.3.1→331、3.4.1→341、2.9.9→299）。
     * 探测不到返回 0，按 LWJGL3 处理（与 ZL2 detectLwjglVersion 一致，不猜版本）。
     */
    fun detectVersion(version: VersionJson): Int {
        for (library in version.libraries) {
            val prefix = when {
                library.name.startsWith(PREFIX_LWJGL_LWJGL) -> PREFIX_LWJGL_LWJGL
                library.name.startsWith(PREFIX_LWJGL) -> PREFIX_LWJGL
                else -> continue
            }
            val intVersion = library.name.removePrefix(prefix)
                .takeWhile { it.isDigit() || it == '.' }
                .filter { it != '.' }
                .toIntOrNull()
            if (intVersion != null && intVersion in 200..999) return intVersion
        }
        return 0
    }

    /**
     * 版本整数 → 组件目录：>= 341 用 3.4.1 组件，否则（含探测不到与 LWJGL2 桥接场景）用 3.3.3 组件。
     * 目录名取自 [VComponents.Component]，与 assets 布局单一来源对齐。
     */
    fun versionDir(detected: Int): String =
        if (detected >= 341) {
            VComponents.Component.LWJGL341.installName
        } else {
            VComponents.Component.LWJGL333.installName
        }

    /**
     * 组件目录里参与 classpath 的 jar。顺序是硬契约（同名类谁在前谁赢）：
     * `lwjgl.jar`(0) → `lwjgl-<版本>-merged-modules.jar`(1) → 其余模块(2) → `lwjgl-lwjglx.jar`(3，
     * 仅 LWJGL2 桥接保留，LWJGL3 一律剔除)。目录为空或不存在即抛 [LaunchException]，不做静默回退。
     */
    fun classpathJars(detected: Int, componentsDir: File): List<File> {
        val dir = File(componentsDir, versionDir(detected))
        val isLwjgl2 = detected in 1..299
        // listFiles 返回平台类型 Array<File!>?，必须先 toList() 再 elvis，否则 emptyList() 推导不出元素类型
        val jars = dir.listFiles { file -> file.isFile && file.name.endsWith(".jar") }
            ?.toList()
            ?: emptyList()
        if (jars.isEmpty()) {
            throw LaunchException(
                "LWJGL 组件目录没有 jar：${dir.absolutePath}（组件未解出或 assets 缺失，拒绝无 LWJGL 启动）",
            )
        }
        return jars
            .filter { isLwjgl2 || it.name != "lwjgl-lwjglx.jar" }
            .sortedBy { jarOrder(it.name, versionDir(detected)) }
    }

    /** 一步到位：探测 + 选目录 + 拼 jar。 */
    fun choose(version: VersionJson, componentsDir: File = VPaths.componentsDir): Choice {
        val detected = detectVersion(version)
        return Choice(componentDir = versionDir(detected), classpathJars = classpathJars(detected, componentsDir))
    }

    private fun jarOrder(name: String, dir: String): Int = when (name) {
        "lwjgl.jar" -> 0
        "lwjgl-$dir-merged-modules.jar" -> 1
        "lwjgl-lwjglx.jar" -> 3
        else -> 2
    }
}
