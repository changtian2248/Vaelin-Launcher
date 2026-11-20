/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.vaelin.startup.download.source

import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {

    @Test
    fun `enforces minimum interval`() {
        val limiter = IntervalRateLimiter { 200L }
        val start = System.currentTimeMillis()
        limiter.acquire()
        limiter.acquire()
        limiter.acquire()
        val elapsed = System.currentTimeMillis() - start
        // 三次请求至少间隔 2 × 200ms；留 50ms 调度余量防抖动
        assertTrue("elapsed=$elapsed", elapsed >= 350L)
    }

    @Test
    fun `zero interval does not block`() {
        val limiter = IntervalRateLimiter { 0L }
        val start = System.currentTimeMillis()
        repeat(20) { limiter.acquire() }
        assertTrue(System.currentTimeMillis() - start < 1_000L)
    }

    @Test
    fun `interval is tunable at call time`() {
        var intervalMs = 0L
        val limiter = IntervalRateLimiter { intervalMs }
        limiter.acquire()
        intervalMs = 150L
        val start = System.currentTimeMillis()
        limiter.acquire()
        limiter.acquire()
        assertTrue(System.currentTimeMillis() - start >= 250L)
    }
}
