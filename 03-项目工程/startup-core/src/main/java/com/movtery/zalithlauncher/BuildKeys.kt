/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 适配说明：ZL2 的 BuildKeys 是 JitPack 插件编译期生成的类
 * （01-文档/05-启动基底复用清单.md §10.3），按原包名提供同形状的常量，
 * 让复用区文件（CallbackBridge / ZLNativeInvoker）的 import 保持不动。
 * 值来自 Vaelin 自己的产品标识。
 */

package com.movtery.zalithlauncher

/**
 * 编译期常量（ZL2 BuildKeys 的 Vaelin 等价物）。
 * 注意用普通 val 而不是 const val：复用区 Java 代码（CallbackBridge）按
 * `BuildKeys.INSTANCE.getLAUNCHER_IDENTIFIER()` 调用，const val 只会生成静态字段、
 * 不生成 getter，会直接编译失败。
 */
object BuildKeys {
    /** 启动器标识（剪贴板来源标签等场景用） */
    val LAUNCHER_IDENTIFIER: String = "com.vaelin.launcher"

    /** 启动器名 */
    val LAUNCHER_NAME: String = "Vaelin Launcher"
}
