/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现。ZL2 的版本档案散落在 Version 对象 + MMKV + kotlinx.serialization
 * 三处，R1 不需要那套重量级结构：这里只保留启动一个实例必需的字段，
 * 用 Android 内置的 org.json 手写序列化，不引 kotlinx.serialization 插件。
 */

package com.vaelin.startup.profile

/**
 * 一个可启动的游戏实例。
 *
 * 字段命名与 ZL2 的区分：ZL2 的 `Version` 同时承担「版本清单项」和「已安装实例」
 * 两个角色，Vaelin 把它拆开——这个类只描述实例，清单由 `minecraft_versions.json`
 * 的原始 VMeta 负责，R1 之后再接。
 */
data class VersionProfile(
    /** 实例目录名，同时是唯一键。 */
    val id: String,
    /** 显示名，默认等于 id */
    val name: String = id,
    /** 对应的 Minecraft 原版版本号，如 "1.20.1" */
    val minecraftVersion: String,
    /** 加载器：vanilla / fabric / quilt / forge / neoforge */
    val modLoader: String = "vanilla",
    val modLoaderVersion: String? = null,
    /** 渲染器短 id，对应 RendererInterface.getRendererId()，如 "opengles2" */
    val rendererId: String = "opengles2",
    /** 渲染器唯一标识，对应 RendererInterface.getUniqueIdentifier() */
    val rendererUniqueId: String = "",
    /** 分配的堆内存，MB */
    val memoryMB: Int = 1024,
    /** 额外的 JVM 参数，用户手填的部分 */
    val extraJvmArgs: List<String> = emptyList(),
    /** 使用的 JRE 名，对应 games/runtimes/<name>；null 表示用内置默认 */
    val javaRuntime: String? = null,
    /** 上次启动时间，0 表示从未启动 */
    val lastPlayedAt: Long = 0L,
)

/**
 * 内存档位。4GB 以下设备自动降档（规格书第 48 条）的判定放在这里，
 * 让调用方不需要各自记「低端机该给多少」。
 */
object MemoryTiers {

    private const val LOW_RAM_THRESHOLD_MB = 4096

    /** 设备总内存低于该值时算低端机 */
    fun isLowEndDevice(totalRamMb: Long): Boolean = totalRamMb < LOW_RAM_THRESHOLD_MB

    /** 按设备总内存给一个自适应的默认堆大小 */
    fun defaultHeapMb(totalRamMb: Long): Int = when {
        totalRamMb <= 2048 -> 512
        totalRamMb <= 3072 -> 768
        totalRamMb <= 4096 -> 1024
        totalRamMb <= 6144 -> 2048
        totalRamMb <= 8192 -> 3072
        else -> 4096
    }
}
