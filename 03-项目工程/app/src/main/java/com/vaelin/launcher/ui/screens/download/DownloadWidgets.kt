/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现。下载页共享小组件：项目卡片、版本行、徽标、占位头像。
 * 刻意不引入图片加载库（Coil 等）：首版图标用稳定色块 + 首字母占位，
 * 网络图片留作后续打磨项。
 */

package com.vaelin.launcher.ui.screens.download

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vaelin.startup.download.source.AddonProject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 资源大类显示名。 */
fun addonKindName(kind: com.vaelin.startup.download.source.AddonKind): String = when (kind) {
    com.vaelin.startup.download.source.AddonKind.MOD -> "模组"
    com.vaelin.startup.download.source.AddonKind.SHADER -> "光影"
    com.vaelin.startup.download.source.AddonKind.RESOURCE_PACK -> "资源包"
    com.vaelin.startup.download.source.AddonKind.DATA_PACK -> "数据包"
    com.vaelin.startup.download.source.AddonKind.MODPACK -> "整合包"
    com.vaelin.startup.download.source.AddonKind.WORLD -> "世界"
}

/** 项目图标占位：id 哈希出稳定底色，放首字母大写。 */
@Composable
fun ProjectAvatar(project: AddonProject, size: Int = 48) {
    val palette = listOf(
        MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer,
        MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer,
        MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer,
        MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer,
        MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val (color, contentColor) = palette[Math.floorMod(project.projectId.hashCode(), palette.size)]
    Box(
        modifier = Modifier
            .size(size.dp)
            .background(color, RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = project.name.take(1).uppercase(Locale.ROOT),
            color = contentColor,
            fontWeight = FontWeight.Bold,
            fontSize = (size / 2.4).sp,
        )
    }
}

/** 发布类型徽标：release 绿 / beta 琥珀 / alpha 红。 */
@Composable
fun ReleaseTypeBadge(releaseType: String) {
    val (label, container, content) = when (releaseType.lowercase(Locale.ROOT)) {
        "release" -> Triple(
            "正式版",
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.onPrimaryContainer,
        )
        "beta" -> Triple(
            "测试版",
            MaterialTheme.colorScheme.tertiaryContainer,
            MaterialTheme.colorScheme.onTertiaryContainer,
        )
        else -> Triple(
            "预览版",
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
        )
    }
    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** 搜索结果卡片（Axolotl 信息架构：图标/名称/作者/统计/简介一行布局）。 */
@Composable
fun ProjectCard(
    project: AddonProject,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outlineVariant,
        ),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProjectAvatar(project, size = 48)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = formatCount(project.downloads),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = buildString {
                        if (project.author.isNotEmpty()) {
                            append(project.author); append(" · ")
                        }
                        append(formatCount(project.follows)); append(" 关注 · ")
                        append(project.gameVersions.take(2).joinToString("/"))
                        if (project.gameVersions.size > 2) append("…")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = project.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** 计数格式化：过万显示 x.x万。 */
fun formatCount(value: Long): String = when {
    value >= 100_000_000L -> String.format(Locale.CHINA, "%.1f亿", value / 100_000_000.0)
    value >= 10_000L -> String.format(Locale.CHINA, "%.1f万", value / 10_000.0)
    else -> value.toString()
}

/** 字节数格式化。 */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1_048_576L -> String.format(Locale.CHINA, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024L -> String.format(Locale.CHINA, "%.1f KB", bytes / 1_024.0)
    else -> "$bytes B"
}

/** epoch 毫秒 → yyyy-MM-dd。 */
fun formatDate(millis: Long): String {
    if (millis <= 0L) return "—"
    return SimpleDateFormat("yyyy-MM-dd", Locale.CHINA).format(Date(millis))
}
