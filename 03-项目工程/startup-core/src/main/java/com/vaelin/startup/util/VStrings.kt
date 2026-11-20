/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * 移植自 ZalithLauncher2 的 utils/string/StringUtils.kt。
 */

package com.vaelin.startup.util

/**
 * 按分隔符切分命令行参数，但保留双引号内的内容。
 *
 * 存在的理由：JVM 参数里有 `-Duser.home="/sdcard/My Games"` 这种带空格的值，
 * 直接 [String.split] 会把一处值切成两个参数，JVM 启动即失败。
 * 转义引号（`\"`）不参与状态翻转。
 */
fun String.splitPreservingQuotes(delimiter: Char = ' '): List<String> {
    val result = mutableListOf<String>()
    val currentPart = StringBuilder()
    var inQuotes = false

    for ((index, c) in withIndex()) {
        when {
            c == '"' && (index == 0 || this[index - 1] != '\\') -> {
                inQuotes = !inQuotes
            }

            (if (delimiter == ' ') c.isWhitespace() else c == delimiter) && !inQuotes -> {
                if (currentPart.isNotEmpty()) {
                    result.add(currentPart.toString())
                    currentPart.clear()
                }
            }

            else -> currentPart.append(c)
        }
    }

    if (currentPart.isNotEmpty()) result.add(currentPart.toString())
    return result
}
