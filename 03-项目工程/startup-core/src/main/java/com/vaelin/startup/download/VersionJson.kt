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
 * 一个可下载文件。
 *
 * `sha1` / `size` 允许缺失（部分加载器版本、以及个别第三方仓库的 json 不带校验信息），
 * 缺失时下载器只做「文件存在且非空」的弱校验，不假装自己验过。
 */
data class Artifact(
    val path: String?,
    val url: String?,
    val sha1: String?,
    val size: Long,
) {
    val hasUrl: Boolean get() = !url.isNullOrBlank()

    val hasSha1: Boolean get() = !sha1.isNullOrBlank()

    companion object {
        fun parse(value: JsonValue?): Artifact? {
            val url = value.stringField("url") ?: return null
            if (url.isBlank()) return null
            return Artifact(
                path = value.stringField("path"),
                url = url,
                sha1 = value.stringField("sha1"),
                size = value.longField("size") ?: -1L,
            )
        }
    }
}

/** 资源索引（assets index）描述文件本身。索引里的 objects 清单要另下一份 json 才有。 */
data class AssetIndex(
    val id: String,
    val url: String,
    val sha1: String,
    val size: Long,
    val totalSize: Long?,
)

/** 该版本要求的 Java 主版本。`majorVersion` 是启动时挑 JRE 的依据。 */
data class JavaVersion(
    val component: String?,
    val majorVersion: Int,
    val version: String?,
)

/**
 * library 的 rules 条目。
 *
 * 字段语义按 Mojang 的 `Rule` 模型：`os` 与 `features` 都是可选条件，
 * 出现即要求匹配；`action` 只有 "allow" / "disallow" 两个合法值。
 */
data class Rule(
    val action: String,
    val osName: String?,
    val osArch: String?,
    val osVersion: String?,
    val features: Map<String, Boolean>,
) {
    val isAllow: Boolean get() = action == ACTION_ALLOW

    companion object {
        const val ACTION_ALLOW = "allow"
        const val ACTION_DISALLOW = "disallow"

        fun parse(value: JsonValue?): Rule? {
            // action 是必需字段：没有 action 的规则无法求值，宁可丢掉也不猜成 allow
            val action = value.stringField("action")?.lowercase() ?: return null
            if (action != ACTION_ALLOW && action != ACTION_DISALLOW) return null

            val os = value.field("os")
            val features = LinkedHashMap<String, Boolean>()
            value.field("features").asObject()?.forEach { (key, flag) ->
                flag.asBoolean()?.let { features[key] = it }
            }

            return Rule(
                action = action,
                osName = os.stringField("name")?.lowercase(),
                osArch = os.stringField("arch")?.lowercase(),
                osVersion = os.stringField("version"),
                features = features,
            )
        }
    }
}

/**
 * 一个 library 依赖。
 *
 * `natives` 是「平台 → classifiers 键模板」的映射，模板里可能带 `${arch}` 占位符；
 * `downloads.classifiers` 才是真正的 native 包。普通 jar 走 `downloads.artifact`，
 * 极老版本没有 `downloads` 块，退回到 `url` 根路径 + maven 坐标拼路径。
 */
data class Library(
    val name: String,
    val downloads: LibraryDownloads?,
    val natives: Map<String, String>,
    val rules: List<Rule>,
    val url: String?,
) {
    val hasNatives: Boolean get() = natives.isNotEmpty()

    val coordinate: MavenCoordinate? get() = MavenCoordinate.parse(name)

    companion object {
        fun parse(value: JsonValue?): Library? {
            val name = value.stringField("name") ?: return null

            val downloadsValue = value.field("downloads")
            val artifact = Artifact.parse(downloadsValue.field("artifact"))
            val classifiers = LinkedHashMap<String, Artifact>()
            downloadsValue.field("classifiers").asObject()?.forEach { (key, entry) ->
                Artifact.parse(entry)?.let { classifiers[key] = it }
            }

            val natives = LinkedHashMap<String, String>()
            value.field("natives").asObject()?.forEach { (platform, classifier) ->
                classifier.asString()?.let { natives[platform.lowercase()] = it }
            }

            val rules = value.arrayField("rules").orEmpty().mapNotNull { Rule.parse(it) }

            return Library(
                name = name,
                downloads = if (artifact == null && classifiers.isEmpty()) {
                    null
                } else {
                    LibraryDownloads(artifact = artifact, classifiers = classifiers)
                },
                natives = natives,
                rules = rules,
                url = value.stringField("url"),
            )
        }
    }
}

data class LibraryDownloads(
    val artifact: Artifact?,
    val classifiers: Map<String, Artifact>,
)

/**
 * 版本元数据（`<version>.json`）。只保留 R1 下载与启动真正要用的字段。
 *
 * 没解析 `arguments` / `logging` 顶层块：前者属于 JVM 启动参数拼装（按原文文本交给
 * GameArgumentExpander），后者是 Mojang 的启动日志配置，与下载链路无关。
 *
 * [mainClass] 是「条件必需」：模组加载器（Forge/Fabric）安装产物的 JSON 没有自己的
 * mainClass，靠 `inheritsFrom` 从父版本继承，不能在解析层卡死；但既没有 mainClass
 * 也没有 inheritsFrom 的 JSON 一定没法启动，仍然早失败。
 * [jar] 是「client jar 文件名覆写」（少数加载器声明），决定从哪个 `<versions>/<名字>/<名字>.jar`
 * 取客户端 jar（ZL2 LaunchArgs 的 clientJar=版本自身 jar 同语义）。
 */
data class VersionJson(
    val id: String,
    val type: String?,
    val mainClass: String?,
    val assets: String?,
    val assetIndex: AssetIndex?,
    val client: Artifact?,
    val libraries: List<Library>,
    val javaVersion: JavaVersion?,
    val inheritsFrom: String? = null,
    val jar: String? = null,
) {

    /** 规则求值后真正属于当前设备的 library。顺序保持文档原序。 */
    fun applicableLibraries(environment: RuleEnvironment): List<Library> =
        libraries.filter { LibraryRules.isAllowed(it.rules, environment) }

    companion object {

        /**
         * 解析 version json。
         *
         * `id` 必需；`mainClass` 在没有 `inheritsFrom` 时必需——缺了它们下载得以完成也没法
         * 启动，早失败比下完几万个资源再报错划算。带 `inheritsFrom` 的模组版 JSON 允许
         * 缺 mainClass（继承链合并后由父版本提供），合并结果再解析时若仍缺会在这里被拦下。
         * 其余字段缺了就置空，由调用方决定兜底策略。
         */
        fun parse(text: String): VersionJson {
            val root = Json.parse(text)

            val id = root.stringField("id")
                ?: throw IllegalArgumentException("version json 缺少必需字段 'id'")
            val inheritsFrom = root.stringField("inheritsFrom")?.takeIf { it.isNotBlank() }
            val mainClass = root.stringField("mainClass")?.takeIf { it.isNotBlank() }
            if (mainClass == null && inheritsFrom == null) {
                throw IllegalArgumentException(
                    "version json ($id) 缺少必需字段 'mainClass'（也没有 inheritsFrom 可从父版本继承）",
                )
            }

            val assetIndexValue = root.field("assetIndex")
            val assetIndexUrl = assetIndexValue.stringField("url")
            val assetIndex = if (assetIndexUrl.isNullOrBlank()) {
                null
            } else {
                AssetIndex(
                    id = assetIndexValue.stringField("id").orEmpty(),
                    url = assetIndexUrl,
                    sha1 = assetIndexValue.stringField("sha1").orEmpty(),
                    size = assetIndexValue.longField("size") ?: -1L,
                    totalSize = assetIndexValue.longField("totalSize"),
                )
            }

            val javaVersionValue = root.field("javaVersion")
            val majorVersion = javaVersionValue.intField("majorVersion")
            val javaVersion = if (majorVersion == null) {
                null
            } else {
                JavaVersion(
                    component = javaVersionValue.stringField("component"),
                    majorVersion = majorVersion,
                    version = javaVersionValue.stringField("version"),
                )
            }

            val libraries = root.arrayField("libraries").orEmpty().mapNotNull { Library.parse(it) }

            return VersionJson(
                id = id,
                type = root.stringField("type"),
                mainClass = mainClass,
                assets = root.stringField("assets"),
                assetIndex = assetIndex,
                client = Artifact.parse(root.field("downloads").field("client")),
                libraries = libraries,
                javaVersion = javaVersion,
                inheritsFrom = inheritsFrom,
                jar = root.stringField("jar")?.takeIf { it.isNotBlank() },
            )
        }
    }
}
