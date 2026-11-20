/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * 移植自 ZL2 game/download/jvm_server/JvmCrashException.kt。
 * 语义不变：JRE 逐级重试（8 → 17 → 21）全部非零退出后，以可见异常结束，
 * 禁止把安装失败静默当作成功。
 */

package com.vaelin.startup.jvm

/** 安装器 JVM 在所有候选 JRE 上都以非零码退出 */
class JvmCrashException(val code: Int) :
    JvmSchedulerException("During installation, the JVM exited with exception code $code.")
