/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 适配说明：ZL2 的 context/GlobalContext 是绑定 MMKV/设置初始化的全局上下文
 * （01-文档/05-启动基底复用清单.md §4.1），且**语义是"当前前台 Activity"**——
 * ZLApplication.onCreate 赋 Application，AbstractAppCompatActivity.onCreate(:30)
 * 每次建 Activity 时覆写为自身，复用区代码（CallbackBridge/ZLNativeInvoker）靠
 * `GlobalContext as? Activity` 拿到可 runOnUiThread 的宿主。
 * 本文件按原包名提供同形状的最小实现：存"传入者本身"，由进程入口的 Activity 传入。
 * 顶层的 getGlobalContext()/setGlobalContext() 编译为 ContextsKt 的静态方法，
 * 与 ZL2 里 `ContextsKt.getGlobalContext()` 的调用形状一致。
 */

package com.movtery.zalithlauncher.context

import android.content.Context

@Volatile
private var globalContext: Context? = null

/**
 * 由进程入口调用。**传 Activity 本身而不是 applicationContext**——
 * 复用区代码要把它 cast 回 Activity 用 runOnUiThread，传 Application 会让这些路径静默失效。
 * ZL2 的语义是每次建 Activity 覆写，这里保持一致。
 */
fun setGlobalContext(context: Context) {
    globalContext = context
}

/**
 * 取当前全局上下文。
 * 未初始化即显式报错——拿不到 Context 的剪贴板/openLink 静默失败比崩溃更难排查。
 */
fun getGlobalContext(): Context =
    globalContext ?: error("GlobalContext 未初始化：本进程入口必须先调用 setGlobalContext()")
