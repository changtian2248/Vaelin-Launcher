/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * R1 · T1.10 —— :jvm 独立进程里的安装器前台服务（ZL2 JvmService.kt 的移植）。
 *
 * 这个服务跑的是 Forge / NeoForge installer jar（下载/安装阶段），不是游戏进程；
 * 游戏 JVM 在 :game 由另一 Agent 负责，本文件不碰渲染与游戏启动链。
 *
 * 与 ZL2 的关键差异（任务书明确要求）：
 * - 不复用 ZL2 的 abstract class Launcher / JvmLauncher（它们拖进 account、
 *   version、download、plugin、setting 五个子系统），改用 Vaelin 已有的
 *   VaelinLauncher.launch() 精简启动链。
 * - 剥掉 GlobalContext / AllSettings / PathManager / R.* / Logger，
 *   改吃 VPaths / VLog / JreInstaller / VRuntime / Architecture。
 * - 跨进程只靠 UDP 字面量协议（JVMSocketServer.send + JVMHandler 编解码）回报
 *   PROGRESS / EXIT，不引入 Parcelable 进度对象。
 */

package com.vaelin.startup.jvm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.movtery.zalithlauncher.bridge.LoggerBridge
import com.vaelin.startup.components.VComponents
import com.vaelin.startup.launch.VaelinLauncher
import com.vaelin.startup.multirt.JreInstaller
import com.vaelin.startup.multirt.VRuntime
import com.vaelin.startup.profile.VersionProfile
import com.vaelin.startup.util.Architecture
import com.vaelin.startup.util.VLog
import com.vaelin.startup.util.VPaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

class JvmService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 前台服务必须在 startForegroundService 之后 5 秒内 startForeground
        postNotification()

        if (intent == null) {
            VLog.warning(TAG, "onStartCommand 收到 null intent，直接停止")
            stopSelf()
            return START_NOT_STICKY
        }

        val request = InstallJvmRequest(
            jvmArgs = intent.getStringExtra(SERVICE_JVM_ARGS).orEmpty(),
            jreName = intent.getStringExtra(SERVICE_JRE_NAME),
            userHome = intent.getStringExtra(SERVICE_USER_HOME),
            postSummary = intent.getStringExtra(SERVICE_POST_SUMMARY),
        )
        scope.launch(Dispatchers.Default) {
            // 跨进程协议必须有终止信号：任何失败都回报一个非 0 退出码，
            // 让主进程显式失败/重试，而不是留一个没人收码的僵尸服务。
            val code = try {
                runInstaller(request)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                logCrash(t)
                1
            }
            onExit(code)
        }
        return START_NOT_STICKY
    }

    /**
     * 跑一次 installer JVM，返回退出码。
     * 对齐 ZL2 JvmService.runJvm（LoggerBridge 日志 + launcher_profiles.json）与
     * JvmLauncher.launch（512M 内存、1920x1080、useLocalLanguage=false）。
     */
    private suspend fun runInstaller(request: InstallJvmRequest): Int {
        require(request.jvmArgs.isNotBlank()) { "The JVM parameters must be set." }

        // :jvm 是独立进程，有自己的 Application；VPaths / VLog 必须在本进程重新初始化
        VPaths.init(this)
        VLog.initialize(this)

        val jreName = resolveJreName(request.jreName)
        sendProgress("准备运行时 $jreName")
        val runtimeHome = JreInstaller.ensureInstalled(this, jreName) { msg ->
            sendProgress(msg)
        }
        val runtime = buildRuntime(jreName)
        val args = JVMHandler.parseServiceArgs(request.jvmArgs)
        val gameDirectory = resolveInstallerGameDirectory(args.gameArgs)

        // Forge/NeoForge installer 缺这个文件会失败（ZL2 JvmLauncher.kt:151-166）
        generateLauncherProfiles(request.userHome)

        val logFile = LATEST_PROCESS_LOG_FILE
        if (!logFile.exists() && !logFile.createNewFile()) {
            throw IOException("Failed to create process log file: ${logFile.absolutePath}")
        }
        // native 日志重定向；LoggerBridge 的 static 块会 loadPojavLib
        LoggerBridge.start(logFile.absolutePath)

        val extraJvmArgs = args.extraJvmArgs + userHomeOverride(request.userHome, args.extraJvmArgs)
        val profile = VersionProfile(id = "installer", minecraftVersion = "", memoryMB = INSTALL_RAM_MB)

        sendProgress("启动安装器 JVM: ${args.mainClass}")
        return VaelinLauncher.launch(
            context = this,
            gameDir = gameDirectory,
            runtimeHome = runtimeHome,
            runtime = runtime,
            profile = profile,
            classpath = args.classpath,
            mainClass = args.mainClass,
            gameArgs = args.gameArgs,
            extraJvmArgs = extraJvmArgs,
            // 安装器 JVM（Forge/NeoForge installer）不用 LWJGL；launch 只用它拼 LD 搜索路径，
            // 不校验目录存在性，传默认组件目录保持契约一致即可。
            lwjglComponentDir = VComponents.Component.LWJGL333.installName,
            screenWidth = SCREEN_WIDTH,
            screenHeight = SCREEN_HEIGHT,
            useLocalLanguage = false,
        )
    }

    /** 请求的名字必须真实存在于 assets，不存在显式报错，不静默替换成别的运行时。 */
    private suspend fun resolveJreName(requested: String?): String {
        val available = JreInstaller.listAvailable(this).map { it.name }
        if (requested != null && requested.isNotBlank()) {
            require(available.any { it == requested }) {
                "请求的运行时 $requested 不在 assets 可用列表: $available"
            }
            return requested
        }
        return available.firstOrNull()
            ?: throw IOException("assets 里没有与当前设备架构匹配的运行时")
    }

    /** VaelinLauncher.launch 只用 isJDK8 / arch；versionString 仅作诊断。 */
    private fun buildRuntime(jreName: String): VRuntime {
        val javaVersion = jreName.removePrefix("jre-").toIntOrNull() ?: 0
        return VRuntime(
            name = jreName,
            versionString = JreInstaller.installedVersion(jreName),
            arch = Architecture.archAsString(Architecture.getDeviceArchitecture()),
            javaVersion = javaVersion,
            isProvidedByLauncher = true,
            isJDK8 = javaVersion == 8,
        )
    }

    /**
     * 把 installer 的 userHome 透传成 -Duser.home。
     * VLaunchArgs.buildOverridableArgs 里 user.home 是可覆盖键，用户参数出现同键时
     * 跳过默认值，因此这里显式加一条即可覆盖 VPaths.filesExternal。
     * 值用引号包住，parseUserArguments 的 splitPreservingQuotes 会保留含空格路径。
     */
    private fun userHomeOverride(userHome: String?, extraArgs: List<String>): List<String> {
        if (userHome.isNullOrBlank()) return emptyList()
        if (extraArgs.any { it == "-Duser.home" || it.startsWith("-Duser.home=") }) return emptyList()
        return listOf("-Duser.home=\"$userHome\"")
    }

    /** Forge installer 的 `--installClient` 目标必须与对应 profile 的共享/隔离根目录一致。 */
    private fun resolveInstallerGameDirectory(gameArgs: List<String>): File {
        val installIndex = gameArgs.indexOf("--installClient")
        if (installIndex < 0) return VPaths.gameDir
        val rawPath = gameArgs.getOrNull(installIndex + 1)?.takeIf { it.isNotBlank() }
            ?: throw IOException("--installClient 缺少目标游戏目录")
        val target = File(rawPath).canonicalFile
        if (!target.mkdirs() && !target.isDirectory) {
            throw IOException("无法创建 installer 目标游戏目录: $target")
        }
        return target
    }

    private fun generateLauncherProfiles(userHome: String?) {
        val base = userHome?.let { File(it, ".minecraft") } ?: VPaths.gameDir
        val file = File(base, "launcher_profiles.json")
        // 对齐 ZL2：写失败仅告警不阻断（installer 自己会给出更明确的报错）
        runCatching {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                if (!file.createNewFile()) {
                    throw IOException("Failed to create launcher_profiles.json: ${file.absolutePath}")
                }
                file.writeText(DEFAULT_LAUNCHER_PROFILES)
                VLog.info(TAG, "已写入默认 launcher_profiles.json: ${file.absolutePath}")
            }
        }.onFailure {
            VLog.warning(TAG, "无法生成 launcher_profiles.json（Forge/NeoForge 安装可能失败）", it)
        }
    }

    private fun logCrash(t: Throwable) {
        VLog.error(TAG, "JVM Service Crash", t)
        runCatching {
            LoggerBridge.appendTitle("JVM Service Crash")
            LoggerBridge.append(t.stackTraceToString())
        }
    }

    private fun onExit(code: Int) {
        sendCode(code)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** 尽力而为：UDP 是同步 send，主进程侧有超时兜底，失败不阻断退出流程。 */
    private fun sendCode(code: Int) {
        runCatching { JVMSocketServer.send(JVMHandler.encodeExit(code)) }
            .onFailure { VLog.error(TAG, "回报退出码 $code 失败", it) }
    }

    private fun sendProgress(text: String) {
        val clean = JVMHandler.normalizeLogLine(text)
        runCatching { JVMSocketServer.send(JVMHandler.encodeProgress(clean)) }
            .onFailure { VLog.warning(TAG, "回报进度失败: $clean", it) }
    }

    /** Android 14 (targetSdk 34) 的 specialUse 前台服务：三参 startForeground + 通知渠道。 */
    private fun postNotification() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Installer JVM", NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)

        // 优先级由 NotificationChannel 的 IMPORTANCE_LOW 决定，不用已废弃的 setPriority
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Vaelin 安装器")
            .setContentText("正在运行安装器 JVM…")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        // :jvm 是独立进程，stopSelf 后强杀确保独占槽释放、不残留僵尸 JVM。
        // sendCode 是同步 UDP，onExit 已在其返回后发出，500ms 只给前台服务收尾留时间。
        runCatching { Thread.sleep(500) }
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    companion object {
        private const val TAG = "JvmService"
        private const val CHANNEL_ID = "vaelin_installer_jvm"
        private const val NOTIFICATION_ID = 0x4a56
        private const val INSTALL_RAM_MB = 512
        private const val SCREEN_WIDTH = 1920
        private const val SCREEN_HEIGHT = 1080
        private val DEFAULT_LAUNCHER_PROFILES =
            """{"profiles":{"default":{"lastVersionId":"latest-release"}},"selectedProfile":"default"}"""
    }
}
