/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * R1 · T1.10 —— 主进程侧的 UDP 接收端（ZL2 JVMSocketServer.kt 的逻辑层移植）。
 *
 * 与 ZL2 的差异：
 * - 用 java.net + java.util.concurrent 取代 kotlinx.coroutines，纯 JVM 可编译；
 *   （协程依赖仍在 build.gradle.kts 里供别处用，但本文件不碰）
 * - 接收端实现 [JvmEventSource] 接口，直接挂到 InstallerJvmScheduler 上；
 * - 保留 ZL2 的三条关键语义：start 先清空上一轮 receiveMsg 防陈旧退出码被复用、
 *   每次 start 先 stop 旧的单例状态、close 无条件关 socket。
 *
 * 端口 / ip 与 :jvm 侧 JvmService 的 send 靠字面量对齐（跨进程无共享内存）。
 * 本文件只依赖 java.net，不 import android.*，可在 JVM 单测里被当作协议参考，
 * 但真实绑定会占用端口，故单测用 FakeEventSource，不调用此处 start()。
 */

package com.vaelin.startup.jvm

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException

object JVMSocketServer {

    @Volatile
    private var current: UdpEventSource? = null

    /** 最近收到的原始消息（诊断用；调度器取退出码走 JvmEvent.Exit，不读它） */
    @Volatile
    var receiveMsg: String? = null
        private set

    internal fun recordReceive(msg: String) {
        receiveMsg = msg
    }

    /**
     * 绑定回环 UDP 端口并返回接收端。必须先于 JvmService 启动调用。
     * 绑定失败抛 [JvmSocketBindException]，让调度器显式中止，不起僵尸服务。
     */
    @Synchronized
    fun start(ip: String = "127.0.0.1", port: Int = PROCESS_SERVICE_PORT): JvmEventSource {
        // 清空上一轮的接收结果，防止陈旧退出码被当作本轮结果消费
        stop()
        receiveMsg = null
        val source = try {
            UdpEventSource(ip, port)
        } catch (e: SocketException) {
            throw JvmSocketBindException("Failed to bind UDP receiver on $ip:$port", e)
        }
        current = source
        return source
    }

    /** 无论成败、取消还是超时都调用，收掉单例状态，避免跨轮残留毒化下一次运行。 */
    @Synchronized
    fun stop() {
        current?.close()
        current = null
    }

    /**
     * :jvm 进程侧发送一条消息（JvmService.sendCode 走这里）。
     * 用独立 DatagramSocket 而非接收端，因为发送方在另一个进程。
     */
    fun send(msg: String, ip: String = "127.0.0.1", port: Int = PROCESS_SERVICE_PORT) {
        val bytes = msg.toByteArray(Charsets.UTF_8)
        DatagramSocket().use { socket ->
            socket.connect(InetSocketAddress(ip, port))
            socket.send(DatagramPacket(bytes, bytes.size))
        }
    }
}

/** 一次绑定的 UDP 接收端；用 soTimeout 实现 [JvmEventSource.awaitEvent] 的窗口语义。 */
internal class UdpEventSource(ip: String, port: Int) : JvmEventSource {
    private val socket = DatagramSocket(port, InetAddress.getByName(ip))
    private val buffer = ByteArray(4096)

    @Volatile
    private var closed = false

    override fun awaitEvent(timeoutMillis: Long): JvmEvent? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!closed) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return null
            socket.soTimeout = minOf(remaining, Int.MAX_VALUE.toLong()).toInt()
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: SocketTimeoutException) {
                return null
            } catch (e: SocketException) {
                if (closed) return null else throw e
            }
            val msg = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
            JVMSocketServer.recordReceive(msg)
            // 无法识别的噪声消息跳过，继续在本窗口内等下一条，
            // 不让 awaitEvent 把噪声误报成「没有事件」。
            val event = JVMHandler.parse(msg)
            if (event != null) return event
        }
        return null
    }

    override fun close() {
        closed = true
        socket.close()
    }
}
