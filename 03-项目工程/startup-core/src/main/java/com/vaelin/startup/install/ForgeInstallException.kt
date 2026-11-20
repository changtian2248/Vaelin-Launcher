/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * T1.5.5 Forge 安装链 —— 显式失败类型。
 */

package com.vaelin.startup.install

/**
 * Forge 安装链（解析版本 / 下载 installer / 起 :jvm 安装 / 产物校验 / 更新 profile）
 * 的显式失败。纪律：任何一步失败都必须带可读原因冒泡到 UI，禁止静默降级。
 */
class ForgeInstallException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
