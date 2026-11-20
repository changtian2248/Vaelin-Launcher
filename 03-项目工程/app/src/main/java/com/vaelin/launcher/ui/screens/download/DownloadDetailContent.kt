/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现。下载详情内容：项目头 + 版本列表 + 下载动作。
 * 横屏双栏右栏与竖屏全屏详情共用这一份。
 */

package com.vaelin.launcher.ui.screens.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vaelin.launcher.ui.DownloadDetailState
import com.vaelin.launcher.ui.DownloadJob
import com.vaelin.startup.download.source.AddonVersion

/**
 * 详情内容。
 *
 * @param gateBlocked 地区闸状态：true 时下载按钮隐藏并显示合规提示（规格书第 8 条
 *   「入口隐藏 + 动作拒绝」；Beta 期由模拟开关驱动测试）
 */
@Composable
fun DownloadDetailContent(
    state: DownloadDetailState,
    gateBlocked: Boolean,
    downloadJob: DownloadJob?,
    onDownload: (AddonVersion) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val project = state.project
    when {
        state.loading && project == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        state.error != null && project == null -> ErrorPane(
            message = state.error,
            onRetry = onRetry,
            modifier = modifier.fillMaxSize(),
        )
        project != null -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") {
                DetailHeader(project)
            }
            if (gateBlocked) {
                item(key = "gate") {
                    GateNotice()
                }
            }
            item(key = "versions-title") {
                Text(
                    text = "版本（${state.versions.size}）",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(items = state.versions, key = { it.versionId }) { version ->
                VersionRow(
                    version = version,
                    downloadEnabled = !gateBlocked,
                    downloadJob = downloadJob,
                    onDownload = { onDownload(version) },
                )
            }
        }
        else -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("选择左侧项目查看详情", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DetailHeader(project: com.vaelin.startup.download.source.AddonProject) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProjectAvatar(project, size = 56)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append(addonKindName(project.kind))
                            if (project.author.isNotEmpty()) {
                                append(" · "); append(project.author)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = project.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                StatColumn(formatCount(project.downloads), "下载")
                StatColumn(formatCount(project.follows), "关注")
                StatColumn(formatDate(project.updatedMillis), "更新")
            }
        }
    }
}

@Composable
private fun StatColumn(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun VersionRow(
    version: AddonVersion,
    downloadEnabled: Boolean,
    downloadJob: DownloadJob?,
    onDownload: () -> Unit,
) {
    val isCurrentJob = downloadJob?.fileName == version.primaryFile()?.filename
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ReleaseTypeBadge(version.releaseType)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = version.versionNumber.ifEmpty { version.name },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                version.primaryFile()?.let { file ->
                    Text(
                        text = formatBytes(file.sizeBytes),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = buildString {
                    append(version.gameVersions.take(4).joinToString("/"))
                    if (version.gameVersions.size > 4) append("…")
                    if (version.loaders.isNotEmpty()) {
                        append(" · "); append(version.loaders.joinToString("/"))
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = version.primaryFile()?.filename ?: "无文件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                when {
                    isCurrentJob && downloadJob != null -> JobStatusText(downloadJob)
                    downloadEnabled -> TextButton(onClick = onDownload) { Text("下载") }
                    else -> Text(
                        text = "不可用",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
    }
}

@Composable
private fun JobStatusText(job: DownloadJob) {
    when (job.status) {
        DownloadJob.Status.RUNNING -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(6.dp))
            Text("下载中…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        DownloadJob.Status.DONE -> Text(
            "已下载",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        DownloadJob.Status.FAILED -> Text(
            "失败：${job.message}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 地区闸提示条（规格书第 8 条文案口径：合规限制，不甩锅网络）。 */
@Composable
private fun GateNotice() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "当前地区不开放文件下载（合规限制），仅可浏览",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
fun ErrorPane(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("加载失败", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            Button(onClick = onRetry) { Text("重试") }
        }
    }
}
