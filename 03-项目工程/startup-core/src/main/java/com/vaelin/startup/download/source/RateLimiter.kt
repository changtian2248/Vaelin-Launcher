/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现。可调限速接口（用户拍板：留接口可以调）。
 */

package com.vaelin.startup.download.source

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 请求限速器。每次发起 HTTP 请求前调 [acquire]，返回即允许发请求。
 *
 * 用 provider 而不是固定值：间隔从设置里读，用户/开发者选项改了立即生效，
 * 不用重建源实例。Modrinth 官方口径约 250ms 一个请求，默认走保守档。
 */
fun interface RateLimiter {

    /** 阻塞到允许发下一次请求为止。 */
    fun acquire()

    companion object {
        /** Modrinth 官方 App 的同款保守档（毫秒）。 */
        const val DEFAULT_INTERVAL_MS = 250L
    }
}

/**
 * 固定最小间隔限速器。同一时刻只允许一个调用方在等（锁内排队），
 * 多线程共用同一个实例时不会把请求压进同一毫秒。
 */
class IntervalRateLimiter(
    private val intervalMsProvider: () -> Long = { RateLimiter.DEFAULT_INTERVAL_MS },
) : RateLimiter {

    private val lock = ReentrantLock()
    private val allowed = lock.newCondition()

    @Volatile
    private var lastRequestAt = 0L

    override fun acquire() {
        val interval = intervalMsProvider().coerceAtLeast(0L)
        lock.withLock {
            val now = System.currentTimeMillis()
            val waitMs = lastRequestAt + interval - now
            if (waitMs > 0L) {
                try {
                    allowed.awaitNanos(waitMs * 1_000_000L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
            lastRequestAt = System.currentTimeMillis()
        }
    }
}
