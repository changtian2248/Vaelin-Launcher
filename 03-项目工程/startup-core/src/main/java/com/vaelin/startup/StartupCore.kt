package com.vaelin.startup

/**
 * 启动基底的版本标记。
 *
 * 本模块承载从 ZalithLauncher2（GPL-3.0）复用的启动链，
 * 被复用文件保留原版权与许可声明，整体随 Vaelin 以 GPL-3.0 分发。
 *
 * 实际内容按 R1 任务逐步接入：
 *   T1.2  native 层（jni/ 16 个 C + 9 个子目录）
 *   T1.3  JRE 分发与解压
 *   T1.4  bridge 层（ZLBridge / ZLNativeInvoker / LoggerBridge）
 *   T1.8  渲染器协议
 *   T1.10 安装器 JVM 进程
 *   T1.10b 游戏进程隔离（:game）
 */
object StartupCore {
    const val VERSION: String = "0.1.0-r1"
}
