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

/**
 * maven 坐标（`group:artifact:version[:classifier][@ext]`）。
 *
 * version json 里 library 的 `name` 就是这个格式。绝大多数库同时也带了
 * `downloads.artifact.path`，但老版本（以及很多加载器自己加的库）没有，得靠坐标
 * 现拼路径，所以解析要稳。
 */
data class MavenCoordinate(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val classifier: String?,
    val extension: String,
) {

    val fileName: String
        get() = buildString {
            append(artifactId).append('-').append(version)
            if (!classifier.isNullOrBlank()) append('-').append(classifier)
            append('.').append(extension)
        }

    val relativePath: String
        get() = "${groupId.replace('.', '/')}/$artifactId/$version/$fileName"

    companion object {

        fun parse(name: String): MavenCoordinate? {
            var raw = name.trim()
            if (raw.isEmpty()) return null

            // Forge 等会写成 group:artifact:version@zip，扩展名要单独摘出来
            var extension = "jar"
            val at = raw.indexOf('@')
            if (at >= 0) {
                extension = raw.substring(at + 1).ifBlank { "jar" }
                raw = raw.substring(0, at)
            }

            val parts = raw.split(':')
            if (parts.size < 3) return null
            val groupId = parts[0]
            val artifactId = parts[1]
            val version = parts[2]
            if (groupId.isBlank() || artifactId.isBlank() || version.isBlank()) return null

            val classifier = parts.getOrNull(3)?.takeIf { it.isNotBlank() }
            return MavenCoordinate(groupId, artifactId, version, classifier, extension)
        }
    }
}
