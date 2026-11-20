/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * 注意：包名与类名是 JNI 静态导出符号的硬绑定，不可改成 com.vaelin.startup.*。
 * 依据：input_bridge_v3.c:434/486/492/517 四个 JNIEXPORT 符号
 *       Java_com_movtery_zalithlauncher_game_sdl_SdlBridge_*
 *       以及 01-文档/04-分轮计划与任务分解.md:215 的硬约束。
 */

package com.movtery.zalithlauncher.game.sdl

import androidx.annotation.Keep

/**
 * libpojavexec.so 里 4 个 SDL 相关 JNI 静态导出符号的 Java 宿主。
 *
 * 只保留 external 声明，不照搬 ZL2 的 SdlBridge.kt（188 行）：后者依赖
 * org.libsdl.app.* / AllSettings / kotlinx.coroutines，这些在 Vaelin 全不存在，
 * 整文件复用会把依赖泄漏进 startup-core（团队准则：按逻辑层复用，禁止整文件式复用）。
 * R1 不启用 SDL 渲染路径，故这些 native 方法默认不被调用；类存在的意义是让
 * 静态导出符号有可解析的宿主，并在 VMActivity 里用 isSdlRenderActive() 做一次安全探测。
 *
 * 符号实现均在 input_bridge_v3.c：
 *  - :434 initializeControllerSubsystems（dlopen libSDL3.so 初始化手柄子系统）
 *  - :486 isSdlRenderActive（仅检查静态窗口指针 sSdlPrimaryWindow，不 dlopen，可安全探测）
 *  - :492 setNativeTextInputActive
 *  - :517 isNativeTextInputActive
 */
@Keep
object SdlBridge {

    @JvmStatic
    external fun initializeControllerSubsystems()

    @JvmStatic
    external fun isSdlRenderActive(): Boolean

    @JvmStatic
    external fun setNativeTextInputActive(active: Boolean): Boolean

    @JvmStatic
    external fun isNativeTextInputActive(): Boolean
}
