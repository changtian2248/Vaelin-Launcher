/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现。整合包导入弹层：读文件 → 确认信息 → 进度 → 结果。
 * 居中大卡片覆盖层，不引 ModalBottomSheet（避免 material3 版本 API 不确定）。
 */

package com.vaelin.launcher.ui.screens.download

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vaelin.launcher.ui.DownloadViewModel.ModpackImportState
import com.vaelin.startup.profile.GameDirectoryMode

@Composable
fun ModpackImportSheet(
    state: ModpackImportState,
    gameDirectoryMode: GameDirectoryMode,
    gateBlocked: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (state.stage == ModpackImportState.Stage.IDLE) return
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Text(
                    text = "导入整合包",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))

                when (state.stage) {
                    ModpackImportState.Stage.READING -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("正在识别格式与解析清单…", style = MaterialTheme.typography.bodyMedium)
                    }

                    ModpackImportState.Stage.READY -> {
                        val info = state.inspected?.info
                        if (info != null) {
                            InfoRow("名称", info.name)
                            InfoRow("版本", info.version.ifEmpty { "—" })
                            InfoRow("格式", info.format.displayName)
                            InfoRow("MC 版本", info.gameVersion)
                            InfoRow("全局目录", gameDirectoryMode.displayName)
                            InfoRow("加载器", info.loader?.let { "$it ${info.loaderVersion ?: ""}" } ?: "原版")
                            InfoRow("待下载", "${info.files.size} 个文件")
                            InfoRow("覆盖文件", "${state.inspected?.overrides?.size ?: 0} 个")
                        }
                        if (gameDirectoryMode == GameDirectoryMode.SHARED) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "共享模式会把 overrides 与模组写入共享根目录，其他共享实例也会看到它们。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        if (gateBlocked) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "当前地区不开放文件下载（合规限制），无法安装",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = onDismiss) { Text("取消") }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = onConfirm, enabled = !gateBlocked) { Text("开始安装") }
                        }
                    }

                    ModpackImportState.Stage.INSTALLING -> {
                        val progress = state.progress
                        val total = state.inspected?.info?.files?.size ?: 0
                        Text(
                            "正在下载文件 ${progress?.first ?: 0}/$total",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        if (total > 0) {
                            LinearProgressIndicator(
                                progress = { (progress?.first ?: 0).toFloat() / total.coerceAtLeast(1) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "加载器与 overrides 将在下载完成后落盘",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    ModpackImportState.Stage.DONE -> {
                        val result = state.result
                        if (result != null) {
                            InfoRow("下载成功", "${result.succeeded} 个（跳过已存在 ${result.skippedAlreadyPresent}）")
                            InfoRow("覆盖文件", "已落盘 ${result.overridesExtracted} 个")
                            InfoRow("实例", state.createdProfileId ?: "—")
                        }
                        state.loaderNote?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Button(onClick = onDismiss) { Text("完成") }
                        }
                    }

                    ModpackImportState.Stage.FAILED -> {
                        Text(
                            state.error ?: "未知错误",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                        )
                        state.result?.let { r ->
                            if (r.failedRequired.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "失败文件示例：${r.failedRequired.first().request.url}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Button(onClick = onDismiss) { Text("关闭") }
                        }
                    }

                    ModpackImportState.Stage.IDLE -> Unit
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
