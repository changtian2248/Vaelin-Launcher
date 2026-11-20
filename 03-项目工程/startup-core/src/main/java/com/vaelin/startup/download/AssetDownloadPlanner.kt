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

import java.io.File

/** 被规划器跳过的资源，带原因，方便上层统计「为什么没下这一个」。 */
data class SkippedAsset(
    val path: String,
    val hash: String,
    val reason: String,
)

/** 一次资源下载的完整计划。 */
data class AssetPlan(
    val objectsDir: File,
    val requests: List<DownloadRequest>,
    val skipped: List<SkippedAsset>,
) {
    /** 规划阶段已知的总字节数；size 未知（0）的对象不计入。 */
    val totalExpectedBytes: Long
        get() = requests.sumOf { if (it.expectedSize > 0L) it.expectedSize else 0L }

    val nothingToDo: Boolean get() = requests.isEmpty()
}

/**
 * 把 asset index 的对象列表展开成可交给 `FileDownloader` / `BatchDownloader` 的任务。
 *
 * 这里刻意不做任何 IO（不探网络、不读文件内容）：只 stat 本地文件决定「跳过还是重下」，
 * 所以计划本身是纯函数，单测不需要假传输层。真正的 Range 续传与 sha1 校验全部复用
 * `FileDownloader`，规划层不另写一套下载逻辑。
 */
class AssetDownloadPlanner(
    private val gameDir: File,
    private val resourcesBase: String = MojangEndpoints.RESOURCES_BASE,
) {

    fun plan(objects: List<AssetObject>): AssetPlan {
        val objectsDir = File(File(gameDir, ASSETS_DIR), OBJECTS_DIR)
        val requests = ArrayList<DownloadRequest>(objects.size)
        val skipped = ArrayList<SkippedAsset>()
        val seenHashes = HashSet<String>()

        for (obj in objects) {
            if (!AssetIndexParser.isSha1Hex(obj.hash)) {
                skipped += SkippedAsset(obj.path, obj.hash, "hash 不是 40 位 sha1，无法定位对象文件")
                continue
            }
            // 同一个 hash 可以对应多个游戏内路径（尤其声音与语言文件），对象文件只该下一份
            if (!seenHashes.add(obj.hash)) {
                skipped += SkippedAsset(obj.path, obj.hash, "同一 hash 已由其它路径登记，对象文件共用")
                continue
            }

            val target = File(File(objectsDir, obj.hash.take(MojangAssetSource.OBJECT_PREFIX_LENGTH)), obj.hash)
            // 大小一致就当作已有完整对象：这是最重要的断点续，重开启动器时不必重下全部资源。
            // size 为 0 说明索引没给大小，不能只凭「文件存在」跳过，交给 sha1 校验。
            if (obj.size > 0L && target.isFile && target.length() == obj.size) {
                skipped += SkippedAsset(obj.path, obj.hash, "对象文件已存在且大小一致")
                continue
            }

            requests += DownloadRequest(
                url = MojangAssetSource.objectUrl(obj.hash, resourcesBase),
                target = target,
                expectedSha1 = obj.hash,
                expectedSize = obj.size,
                // 大小不一致但文件还在时保留它，让 FileDownloader 用 Range 续传；
                // 如果其实是坏内容，末尾的 sha1 校验会删掉它并在重试里整份重下。
                resume = target.isFile,
            )
        }

        return AssetPlan(objectsDir, requests, skipped)
    }

    companion object {
        const val ASSETS_DIR = "assets"
        const val OBJECTS_DIR = "objects"
    }
}
