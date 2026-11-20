/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现。竖屏全屏下载详情：Scaffold + 返回，内容区复用
 * DownloadDetailContent（与横屏双栏右栏同一份）。
 */

package com.vaelin.launcher.ui.screens.download

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.vaelin.launcher.ui.DownloadDetailState
import com.vaelin.launcher.ui.DownloadJob
import com.vaelin.startup.download.source.AddonVersion

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadDetailScreen(
    state: DownloadDetailState,
    downloadJob: DownloadJob?,
    gateBlocked: Boolean,
    onDownload: (AddonVersion) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.project?.name ?: "资源详情",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            DownloadDetailContent(
                state = state,
                gateBlocked = gateBlocked,
                downloadJob = downloadJob,
                onDownload = onDownload,
                onRetry = onRetry,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
