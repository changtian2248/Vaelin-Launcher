package com.vaelin.startup.launch

import com.vaelin.startup.download.RuleEnvironment
import com.vaelin.startup.download.VersionJson
import java.io.File

/**
 * 拼 classpath：LibraryRules 过滤 → 按 Maven 坐标去重 → 按坐标排序 → 末尾追加 client jar。
 *
 * 与 VersionDownloadPlanner.addLibraryArtifact 一致的取舍：只有 natives、没有 artifact 的库
 * 不产生 classpath jar。纯 JVM，可独立单测。
 */
object ClasspathBuilder {
    data class Result(val classpath: List<File>, val duplicateCount: Int)

    fun buildWithStats(
        version: VersionJson,
        librariesDir: File,
        clientJarFile: File?,
        environment: RuleEnvironment,
    ): Result {
        val seen = HashSet<String>()
        val entries = ArrayList<Pair<String, File>>()
        var duplicates = 0
        for (library in version.applicableLibraries(environment)) {
            val artifact = library.downloads?.artifact
            if (artifact == null && library.hasNatives) continue
            val coordinate = library.coordinate
            val key = coordinate?.let {
                "${it.groupId}:${it.artifactId}:${it.version}:${it.classifier ?: ""}:${it.extension}"
            } ?: library.name
            if (!seen.add(key)) {
                duplicates++
                continue
            }
            val relativePath = artifact?.path
                ?: coordinate?.relativePath
                ?: throw LaunchException(
                    "库 ${library.name} 既无 downloads.artifact.path 也无合法 Maven 坐标，无法确定 jar 路径",
                )
            entries.add(key to File(librariesDir, relativePath))
        }
        entries.sortBy { it.first }
        val files = entries.map { it.second }.toMutableList()
        if (clientJarFile != null) files.add(clientJarFile)
        return Result(files.toList(), duplicates)
    }

    fun build(
        version: VersionJson,
        librariesDir: File,
        clientJarFile: File?,
        environment: RuleEnvironment,
    ): List<File> = buildWithStats(version, librariesDir, clientJarFile, environment).classpath
}
