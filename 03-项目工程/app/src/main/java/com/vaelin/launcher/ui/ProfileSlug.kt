/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现。实例 id 的 slug 化与去重。两份 ViewModel 都用，
 * 集中一处避免各自复制 slugify 规则后 drift。
 */

package com.vaelin.launcher.ui

/** 只保留 [a-z0-9._-]，其余压成 '-'；id 直接当目录名用。 */
fun slugifyProfileId(raw: String): String {
    val builder = StringBuilder(raw.length)
    raw.lowercase().forEach { ch ->
        builder.append(
            when {
                ch in 'a'..'z' -> ch
                ch in '0'..'9' -> ch
                ch == '.' || ch == '_' || ch == '-' -> ch
                else -> '-'
            }
        )
    }
    return builder.toString().trim('-', '.', '_').ifEmpty { "instance" }
}

/** 在既有 id 集合和磁盘目录内取唯一 id：base、base-2、base-3… */
fun uniqueProfileId(
    taken: Set<String>,
    base: String,
    diskPathExists: (String) -> Boolean = { false },
): String {
    if (base !in taken && !diskPathExists(base)) return base
    var suffix = 2
    while ("$base-$suffix" in taken || diskPathExists("$base-$suffix")) suffix++
    return "$base-$suffix"
}
