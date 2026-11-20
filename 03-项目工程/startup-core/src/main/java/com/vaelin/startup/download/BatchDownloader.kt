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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 一次批量下载的进度快照，回调里只会看到递增的完成数。 */
data class BatchProgress(
    val completed: Int,
    val total: Int,
    val downloadedBytes: Long,
    val currentUrl: String,
)

data class DownloadFailure(
    val request: DownloadRequest,
    val reason: String,
)

data class BatchResult(
    val succeeded: List<DownloadResult.Success>,
    val failed: List<DownloadFailure>,
    val skipped: Int,
    val downloadedBytes: Long,
) {
    val isFullSuccess: Boolean get() = failed.isEmpty()
}

/**
 * 批量下载：信号量限流，默认 4 个并发。
 *
 * 为什么是 4 而不是 ZL2 的 64：手机上同时开几十条 TLS 连接换不来多少吞吐，
 * 反而会抢掉启动器自己的带宽、更容易被 CDN 判成异常流量；而版本安装的文件量
 * 本来就集中在几百个资源上，4 路已经足够把链路跑满。
 */
class BatchDownloader(
    private val downloader: FileDownloader,
    private val parallelism: Int = DEFAULT_PARALLELISM,
) {

    /**
     * 下载全部请求。单个文件失败不会中断其它文件（失败项收集到 [BatchResult.failed]），
     * 由调用方决定是整体回滚还是跳过。
     */
    suspend fun downloadAll(
        requests: List<DownloadRequest>,
        onProgress: ((BatchProgress) -> Unit)? = null,
    ): BatchResult = coroutineScope {
        val gate = Semaphore(parallelism.coerceAtLeast(1))
        val completed = AtomicInteger(0)
        val bytes = AtomicLong(0)
        val total = requests.size

        val results = requests.map { request ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val result = downloader.download(request)
                    if (result is DownloadResult.Success) {
                        bytes.addAndGet(result.bytes)
                    }
                    onProgress?.invoke(
                        BatchProgress(
                            completed = completed.incrementAndGet(),
                            total = total,
                            downloadedBytes = bytes.get(),
                            currentUrl = request.url,
                        )
                    )
                    request to result
                }
            }
        }.awaitAll()

        BatchResult(
            succeeded = results.mapNotNull { (_, result) -> result as? DownloadResult.Success },
            failed = results.mapNotNull { (request, result) ->
                (result as? DownloadResult.Failure)?.let { DownloadFailure(request, it.reason) }
            },
            skipped = results.count { (_, result) ->
                result is DownloadResult.Success && result.skipped
            },
            downloadedBytes = bytes.get(),
        )
    }

    companion object {
        const val DEFAULT_PARALLELISM = 4
    }
}
