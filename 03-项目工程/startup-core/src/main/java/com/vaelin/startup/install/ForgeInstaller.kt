/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * T1.5.5 —— Forge 1.20.1 安装链编排（Android 侧，:jvm 进程组件的第一个生产调用方）。
 *
 * 全链：promotions 解析 → installer 下载（BMCLAPI 优先官方兜底）→ 读 MANIFEST
 * Main-Class → 经 [GameJVMRunner]（内含 [com.vaelin.startup.jvm.InstallerJvmScheduler]
 * 的互斥清场 / 服务重试 / 15 分钟超时 / JRE 17→21 重试 / UDP 53151 回传）起 :jvm
 * 跑 installer → 产物校验 → ProfileStore 更新 minecraftVersion/modLoader。
 *
 * 与 ZL2 的路线差异（有意为之）：ZL2 走 HMCL 式 processor 手动安装
 * （Install.ForgeLike.kt 的 installNewForgeHMCLWay），Vaelin 直接跑 installer 本体
 * （`--installClient`），产物一致性交给 Forge 官方安装器保证；ZL2 注释里
 * 「launcher_profiles.json 缺失会导致安装失败」的防御由 JvmService 与本文件双重兜底。
 */

package com.vaelin.startup.install

import android.content.Context
import com.vaelin.startup.download.HttpTransport
import com.vaelin.startup.download.UrlConnectionTransport
import com.vaelin.startup.jvm.GameJVMRunner
import com.vaelin.startup.jvm.LATEST_PROCESS_LOG_FILE
import com.vaelin.startup.jvm.LogLineSink
import com.vaelin.startup.jvm.ProcessLogTailer
import com.vaelin.startup.profile.ProfileStore
import com.vaelin.startup.profile.VersionProfile
import com.vaelin.startup.util.GameDirectoryOperationLock
import com.vaelin.startup.util.VLog
import com.vaelin.startup.util.VPaths
import kotlinx.coroutines.runBlocking
import java.io.File

/** 安装阶段；UI 侧状态机（idle/downloading/installing/success/error）按它映射。 */
enum class ForgeInstallStage { RESOLVE, DOWNLOAD, PREPARE, RUN, VERIFY }

object ForgeInstaller {

    private const val TAG = "ForgeInstaller"

    /** 失败时附加到错误信息里的 installer 日志尾部行数 */
    private const val LOG_TAIL_LINES = 40

    private const val DEFAULT_LAUNCHER_PROFILES =
        """{"profiles":{"default":{"lastVersionId":"latest-release"}},"selectedProfile":"default"}"""

    /**
     * 仅解析 recommended 版本号（UI 确认对话框预览用），不做任何下载/安装动作。
     * 抛 [ForgeInstallException] 表示 promotions 拉取或解析失败。
     */
    fun resolveRecommended(
        mcVersion: String,
        transport: HttpTransport = UrlConnectionTransport(),
    ): ForgeBuild =
        ForgePromotions.parse(fetchText(transport, ForgePromotions.PROMOTIONS_URL)).resolve(mcVersion)

    /**
     * Forge 安装全链。后台线程执行（回调风格与 [com.vaelin.startup.launch.LaunchCoordinator.prepareAsync]
     * 一致，app 模块不直接碰协程）；回调都在安装线程上发生，UI 侧自行切主线程。
     *
     * @param onProgress 阶段 + 明细文本（UDP PROGRESS 与调度器状态都汇到这里）
     * @param onSuccess 安装成功，返回更新后的 profile（minecraftVersion 已改为 forge 版本 id）
     * @param onError 任何一步失败；异常 message 带 installer 日志尾部（[ProcessLogTailer] 增量读）
     */
    fun installAsync(
        context: Context,
        profile: VersionProfile,
        transport: HttpTransport = UrlConnectionTransport(),
        gameDir: File? = null,
        onProgress: (ForgeInstallStage, String) -> Unit = { _, _ -> },
        onSuccess: (VersionProfile) -> Unit = {},
        onError: (Throwable) -> Unit = {},
    ): Thread = Thread {
        // VPaths 幂等 init：日志落点（launcherLogsDir）与缓存目录都要先就位
        VPaths.init(context.applicationContext)
        // Loader manifests/libraries are launcher metadata and remain in shared gameHome even in isolation mode.
        val resolvedGameDir = gameDir ?: VPaths.gameDir
        try {
            GameDirectoryOperationLock.withLock(VPaths.gameDirectoryLockFile) {
                val follower = InstallerLogFollower(defaultFollowFiles())
                try {
            // 1. 解析版本（promotions 官方源）
            onProgress(ForgeInstallStage.RESOLVE, "拉取 Forge promotions")
            val build = resolveRecommended(profile.minecraftVersion, transport)
            onProgress(ForgeInstallStage.RESOLVE, "选定 Forge ${build.forgeVersion}（${build.channel}）")

            // 2. 下载 installer jar（sha1 侧车优先，Content-Length 兜底）
            onProgress(ForgeInstallStage.DOWNLOAD, "下载 forge-${build.fullVersion}-installer.jar")
            val installerJar = File(
                File(VPaths.cache, "forge/${build.fullVersion}"),
                "forge-${build.fullVersion}-installer.jar",
            )
            InstallerJar.download(transport, build.installerUrls(), build.installerSha1Urls(), installerJar)
            onProgress(ForgeInstallStage.DOWNLOAD, "installer 已就绪: ${installerJar.name}")

            // 3. 读 MANIFEST Main-Class（读不到显式抛错）
            onProgress(ForgeInstallStage.PREPARE, "读取 installer Main-Class")
            val mainClass = InstallerJar.readInstallerMainClass(installerJar)

            // 4. 补 launcher_profiles.json（installer 在 --installClient 目标目录找它；
            //    :jvm 侧 JvmService 还会在 userHome/.minecraft 写一份，双保险）
            ensureLauncherProfiles(resolvedGameDir)

            // 5. 起 :jvm 安装器 JVM。命令串形态对齐 Install.ForgeLike.kt:355-364：
            //    `-cp <classpath> <mainClass> <args...>`；--installClient 与路径放 mainClass
            //    之后，JVMHandler.parseServiceArgs 会把它们归入 gameArgs（'-' 开头 token
            //    只有在 mainClass 之前才归 extraJvmArgs，现有 JVMHandlerTest 语义不变）。
            follower.start()
            onProgress(ForgeInstallStage.RUN, "启动安装器 JVM（$mainClass）")
            val jvmArgs = "-cp \"${installerJar.absolutePath}\" $mainClass" +
                " --installClient \"${resolvedGameDir.absolutePath}\""
            val exitCode = runBlocking {
                GameJVMRunner.runInstaller(
                    context = context,
                    jvmArgs = jvmArgs,
                    userHome = VPaths.filesExternal.absolutePath,
                    postSummary = "Forge ${build.forgeVersion}",
                    onProgress = { text -> onProgress(ForgeInstallStage.RUN, text) },
                    onStatus = { text -> onProgress(ForgeInstallStage.RUN, text) },
                )
            }
            follower.close()
            if (exitCode != 0) {
                // InstallerJvmScheduler 非零码已在链内重试并抛 JvmCrashException，
                // 这里是契约性兜底：EXIT 非 0 一律视为失败并带日志尾部
                throw ForgeInstallException("安装器 JVM 退出码 $exitCode（非 0 视为失败）")
            }
            onProgress(ForgeInstallStage.RUN, "安装器 JVM 正常退出（0）")

            // 6. 产物校验 + profile 收尾
            onProgress(ForgeInstallStage.VERIFY, "校验安装产物")
            val artifact = ForgeArtifactVerifier.verify(resolvedGameDir, profile.minecraftVersion, build.forgeVersion)
            val updated = profile.copy(
                // 语义说明：minecraftVersion 从此 = 启动分支要找的本地版本目录名（forge id），
                // 原版版本号由产物 JSON 的 inheritsFrom 承载（LaunchCoordinator 合并链读它）
                minecraftVersion = artifact.forgeId,
                modLoader = "forge",
                modLoaderVersion = build.forgeVersion,
            )
            ProfileStore.upsert(updated)
            onProgress(ForgeInstallStage.VERIFY, "Forge ${build.forgeVersion} 安装完成（${artifact.forgeId}）")
            onSuccess(updated)
                } catch (t: Throwable) {
                    follower.close()
                    onError(attachLogTail(t, follower))
                }
            }
        } catch (t: Throwable) {
            onError(t)
        }
    }.apply {
        name = "vaelin-forge-install-${profile.id}"
        start()
    }

    /** installer 进程日志的两个落点：JvmService 的 latest_process.log 与 VaelinLauncher 的 latestlog.txt */
    private fun defaultFollowFiles(): List<File> = listOf(
        LATEST_PROCESS_LOG_FILE,
        File(VPaths.launcherLogsDir, "latestlog.txt"),
    ).distinct()

    /** 在 gameDir 根补一份默认 launcher_profiles.json；写失败仅告警（:jvm 侧还有一份兜底）。 */
    private fun ensureLauncherProfiles(gameDir: File) {
        runCatching {
            val file = File(gameDir, "launcher_profiles.json")
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                if (!file.createNewFile()) {
                    throw java.io.IOException("Failed to create launcher_profiles.json: ${file.absolutePath}")
                }
                file.writeText(DEFAULT_LAUNCHER_PROFILES)
                VLog.info(TAG, "已写入 launcher_profiles.json: ${file.absolutePath}")
            }
        }.onFailure {
            VLog.warning(TAG, "写入 launcher_profiles.json 失败（Forge 安装可能失败）", it)
        }
    }

    /** 把 installer 日志尾部附加到失败原因上，UI 与日志都能直接看到安装器最后说了什么。 */
    private fun attachLogTail(t: Throwable, follower: InstallerLogFollower): Throwable {
        val tail = follower.recentLines(LOG_TAIL_LINES)
        if (tail.isEmpty()) return t
        return ForgeInstallException(
            "${t.message ?: t.javaClass.simpleName}\n" +
                "—— installer 日志尾部 ——\n" +
                tail.joinToString("\n"),
            t,
        )
    }

    private fun fetchText(transport: HttpTransport, url: String): String {
        val response = try {
            transport.open(url, "GET")
        } catch (e: Exception) {
            throw ForgeInstallException("请求失败: $url（${e.message}）", e)
        }
        response.use { resp ->
            if (!resp.isSuccess) {
                throw ForgeInstallException("HTTP ${resp.code}: $url")
            }
            val body = resp.body ?: throw ForgeInstallException("响应没有正文: $url")
            return body.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }
}

/**
 * installer 进程日志的增量跟随（复用纯 JVM 的 [ProcessLogTailer]）。
 * - 运行期间：新行转发给 [onLine]（UI 进度明细用 installer 的滚动输出，比
 *   UDP PROGRESS 的里程碑消息更细）；
 * - 失败时：[recentLines] 给出缓冲的日志尾部，附加到错误信息里。
 */
internal class InstallerLogFollower(
    files: List<File>,
    private val onLine: (String) -> Unit = {},
) : AutoCloseable {

    companion object {
        private const val MAX_BUFFERED_LINES = 400
        private const val POLL_INTERVAL_MS = 500L
    }

    private val lock = Any()
    private val buffer = ArrayDeque<String>()

    private val sink = LogLineSink { lines ->
        var last: String? = null
        synchronized(lock) {
            lines.forEach { line ->
                if (line.isNotBlank()) {
                    buffer.addLast(line)
                    while (buffer.size > MAX_BUFFERED_LINES) buffer.removeFirst()
                    last = line
                }
            }
        }
        last?.let(onLine)
    }

    private val tailers = files.map { ProcessLogTailer(it, sink) }

    @Volatile
    private var closed = false

    private val thread = Thread {
        while (!closed) {
            runCatching { Thread.sleep(POLL_INTERVAL_MS) }
            tailers.forEach { tailer -> runCatching { tailer.poll() } }
        }
    }

    fun start() {
        thread.isDaemon = true
        thread.name = "vaelin-forge-log-follower"
        thread.start()
    }

    fun recentLines(max: Int = 40): List<String> = synchronized(lock) {
        buffer.toList().takeLast(max)
    }

    override fun close() {
        closed = true
        runCatching { thread.join(1000L) }
    }
}
