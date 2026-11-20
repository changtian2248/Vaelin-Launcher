/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * T1.5.5 —— installer 产物校验（纯 JVM，可单测）。
 *
 * Forge installer `--installClient <gameDir>` 的产物形态（对照 ZL2 Install.ForgeLike
 * 对产物目录的假设与 ZL2 GameJsonMerger 的本地版本合并输入）：
 *   <gameDir>/versions/<mcVersion>-forge-<forgeVersion>/<同名>.json   —— 版本 JSON（含 inheritsFrom）
 *   <gameDir>/versions/<mcVersion>-forge-<forgeVersion>/<同名>.jar    —— 桩 jar（仅 manifest，供 classpath）
 *   <gameDir>/libraries/...                                           —— installer 下载的依赖库
 */

package com.vaelin.startup.install

import com.vaelin.startup.download.VersionJson
import java.io.File

/** 校验通过的安装产物。 */
data class ForgeArtifact(
    /** 版本目录名，即启动链 `versions/<id>/<id>.json` 的 id（启动分支按它找本地 JSON） */
    val forgeId: String,
    val versionDir: File,
    val versionJsonFile: File,
    /** 桩 jar；LaunchCoordinator.ensureLocalClientJar 的输入 */
    val stubJarFile: File,
)

object ForgeArtifactVerifier {

    /** installer 的预期产物目录名：Forge 产物 id 形如 "1.20.1-forge-47.2.0" */
    fun expectedDirName(mcVersion: String, forgeVersion: String): String =
        "$mcVersion-forge-$forgeVersion"

    /**
     * 校验安装产物，全部硬性条件（任一不满足即抛 [ForgeInstallException]，绝不静默放行）：
     * 1. 产物 JSON 真实存在且能被 [VersionJson.parse] 解析；
     * 2. JSON 声明 `inheritsFrom=<mcVersion>`（指向别的版本或缺失都算失败）；
     * 3. 桩 jar（与目录同名 .jar）存在。
     *
     * 定位顺序：先按预期目录名精确找；找不到再扫描 versions/ 下「JSON 的 inheritsFrom
     * 匹配且 id 以 -forge-<forgeVersion> 结尾」的目录（兜底安装器命名差异）。
     * 扫描同样是显式匹配规则，不满足照样报错，不属于静默降级。
     */
    fun verify(gameDir: File, mcVersion: String, forgeVersion: String): ForgeArtifact {
        val versionsDir = File(gameDir, "versions")
        val expectedName = expectedDirName(mcVersion, forgeVersion)
        val directories = versionsDir.listFiles { f -> f.isDirectory }?.toList().orEmpty()
            .sortedBy { it.name != expectedName } // 预期目录优先尝试
        val errors = mutableListOf<String>()

        for (dir in directories) {
            val jsonFile = File(dir, "${dir.name}.json")
            if (!jsonFile.isFile) {
                errors += "${dir.name}: 缺少 ${jsonFile.name}"
                continue
            }
            val version = try {
                VersionJson.parse(jsonFile.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                errors += "${dir.name}: 版本 JSON 解析失败（${e.message}）"
                continue
            }
            if (version.inheritsFrom != mcVersion) {
                errors += "${dir.name}: inheritsFrom='${version.inheritsFrom}' != '$mcVersion'"
                continue
            }
            val idMatches = version.id == expectedName ||
                version.id.endsWith("-forge-$forgeVersion") ||
                dir.name.endsWith("-forge-$forgeVersion")
            if (!idMatches) {
                errors += "${dir.name}: 版本 id='${version.id}' 与 forge-$forgeVersion 不符"
                continue
            }
            val stubJar = File(dir, "${dir.name}.jar")
            if (!stubJar.isFile) {
                errors += "${dir.name}: 缺少桩 jar ${stubJar.name}（启动链的 client jar 将无从取用）"
                continue
            }
            return ForgeArtifact(
                forgeId = dir.name,
                versionDir = dir,
                versionJsonFile = jsonFile,
                stubJarFile = stubJar,
            )
        }

        throw ForgeInstallException(
            "Forge 安装产物校验失败：期望 $versionsDir/$expectedName（json + inheritsFrom=$mcVersion + 桩 jar）。\n" +
                (if (errors.isEmpty()) {
                    "versions 目录为空或不存在"
                } else {
                    "候选目录检查结果:\n" + errors.joinToString("\n")
                }),
        )
    }
}
