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

/** 需要解压进 natives 目录的 native jar。解压本身属于启动侧，这里只负责把它挑出来。 */
data class NativeLibrary(
    val libraryName: String,
    val classifier: String,
    val jarFile: File,
    val extractDir: File,
)

/** 被 rules 排除、或没有本机架构 native 包而没生成任务的 library。 */
data class SkippedLibrary(val name: String, val reason: String)

/**
 * 一次版本安装要下的全部文件。
 *
 * `versionJsonFile` 不在 `requests` 里：调用方通常已经拿到了 json 文本（要解析才知道
 * 下什么），直接写盘比再下一遍省一次往返。
 */
data class DownloadPlan(
    val versionId: String,
    val versionDir: File,
    val versionJsonFile: File,
    val clientJarFile: File?,
    val assetIndexFile: File?,
    val librariesDir: File,
    val assetsDir: File,
    val nativesDir: File,
    val requests: List<DownloadRequest>,
    val natives: List<NativeLibrary>,
    val skippedLibraries: List<SkippedLibrary>,
) {
    val totalExpectedBytes: Long
        get() = requests.sumOf { if (it.expectedSize > 0) it.expectedSize else 0L }
}

/**
 * 把一份 version json 翻译成「下哪些文件、放到哪」。
 *
 * 目录布局沿用原版启动器的约定（`versions/<id>/`、`libraries/`、`assets/indexes/`、
 * `assets/objects/`），这样以后换任何启动核心都还能直接读到这套文件。
 */
class VersionDownloadPlanner(
    private val gameDir: File,
    private val abi: String,
    private val environment: RuleEnvironment = RuleEnvironment.android(abi),
) {

    fun plan(version: VersionJson): DownloadPlan {
        val versionDir = File(File(gameDir, VERSIONS_DIR), version.id)
        val librariesDir = File(gameDir, LIBRARIES_DIR)
        val assetsDir = File(gameDir, ASSETS_DIR)
        val nativesDir = File(versionDir, NATIVES_DIR)

        val tasks = LinkedHashMap<String, DownloadRequest>()
        val natives = ArrayList<NativeLibrary>()
        val skipped = ArrayList<SkippedLibrary>()

        val clientUrl = version.client?.url
        val clientJarFile = if (!clientUrl.isNullOrBlank()) {
            val client = version.client
            val target = File(versionDir, "${version.id}.jar")
            addTask(tasks, DownloadRequest(clientUrl, target, client.sha1, client.size))
            target
        } else {
            null
        }

        val assetIndex = version.assetIndex
        val assetIndexFile = if (assetIndex != null && assetIndex.url.isNotBlank()) {
            val target = File(File(assetsDir, INDEXES_DIR), "${assetIndex.id}.json")
            addTask(tasks, DownloadRequest(assetIndex.url, target, assetIndex.sha1, assetIndex.size))
            target
        } else {
            null
        }

        for (library in version.applicableLibraries(environment)) {
            addLibraryArtifact(tasks, skipped, librariesDir, library)
            addLibraryNative(tasks, natives, skipped, librariesDir, nativesDir, library)
        }

        return DownloadPlan(
            versionId = version.id,
            versionDir = versionDir,
            versionJsonFile = File(versionDir, "${version.id}.json"),
            clientJarFile = clientJarFile,
            assetIndexFile = assetIndexFile,
            librariesDir = librariesDir,
            assetsDir = assetsDir,
            nativesDir = nativesDir,
            requests = tasks.values.toList(),
            natives = natives,
            skippedLibraries = skipped,
        )
    }

    /** 普通 jar（进 classpath）。只有 native 包、没有 artifact 的库在这里跳过。 */
    private fun addLibraryArtifact(
        tasks: MutableMap<String, DownloadRequest>,
        skipped: MutableList<SkippedLibrary>,
        librariesDir: File,
        library: Library,
    ) {
        val artifact = library.downloads?.artifact
        if (artifact == null && library.hasNatives) return

        val url = artifact?.url?.takeIf { it.isNotBlank() } ?: mavenUrl(library)
        val relativePath = artifact?.path ?: library.coordinate?.relativePath
        if (url.isNullOrBlank() || relativePath.isNullOrBlank()) {
            skipped += SkippedLibrary(library.name, "缺少下载地址或 maven 路径")
            return
        }
        addTask(
            tasks,
            DownloadRequest(
                url = url,
                target = File(librariesDir, relativePath),
                expectedSha1 = artifact?.sha1,
                expectedSize = artifact?.size ?: -1L,
            ),
        )
    }

    /** native 包：挑出本机架构的 classifier，登记成待解压项。 */
    private fun addLibraryNative(
        tasks: MutableMap<String, DownloadRequest>,
        natives: MutableList<NativeLibrary>,
        skipped: MutableList<SkippedLibrary>,
        librariesDir: File,
        nativesDir: File,
        library: Library,
    ) {
        if (!library.hasNatives) return

        val classifier = NativeClassifiers.resolve(library, abi, environment.osName)
        if (classifier == null) {
            skipped += SkippedLibrary(
                library.name,
                "没有适用于 ${environment.osName}/${environment.osArch} 的 native 包",
            )
            return
        }

        val artifact = library.downloads?.classifiers?.get(classifier)
        val url = artifact?.url
        if (url.isNullOrBlank()) {
            skipped += SkippedLibrary(library.name, "classifier '$classifier' 缺少下载地址")
            return
        }

        val relativePath = artifact.path
            ?: library.coordinate?.copy(classifier = classifier)?.relativePath
        if (relativePath.isNullOrBlank()) {
            skipped += SkippedLibrary(library.name, "classifier '$classifier' 无法推出 maven 路径")
            return
        }

        val target = File(librariesDir, relativePath)
        addTask(
            tasks,
            DownloadRequest(
                url = url,
                target = target,
                expectedSha1 = artifact.sha1,
                expectedSize = artifact.size,
            ),
        )
        natives += NativeLibrary(
            libraryName = library.name,
            classifier = classifier,
            jarFile = target,
            extractDir = nativesDir,
        )
    }

    /** 没有 downloads 块的老格式库：用坐标拼 maven 路径，仓库根缺省取 Mojang 官方。 */
    private fun mavenUrl(library: Library): String? {
        val coordinate = library.coordinate ?: return null
        val base = library.url?.takeIf { it.isNotBlank() } ?: MojangEndpoints.LIBRARIES_BASE
        return base.trimEnd('/') + "/" + coordinate.relativePath
    }

    /**
     * 同一目标只保留一个任务：不同 library 条目指向同一个 jar 的情况确实存在
     * （加载器经常重复声明），重复排队只会让同一文件被并发写两遍。
     */
    private fun addTask(tasks: MutableMap<String, DownloadRequest>, request: DownloadRequest) {
        tasks.putIfAbsent(request.target.absolutePath, request)
    }

    companion object {
        const val VERSIONS_DIR = "versions"
        const val LIBRARIES_DIR = "libraries"
        const val ASSETS_DIR = "assets"
        const val INDEXES_DIR = "indexes"
        const val OBJECTS_DIR = "objects"
        const val NATIVES_DIR = "natives"
    }
}
