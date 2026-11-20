/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vaelin.startup.diag

import com.vaelin.startup.renderer.RendererLoadException
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T1.13 三类分类各一条，全部用假异常 / 假退出码驱动，不依赖 Android。
 */
class FailureClassifierTest {

    @Test
    fun `JVM 启动失败 非0退出码`() {
        val category = FailureClassifier.classify(
            LaunchFailureEvent(stage = LaunchStage.JVM_BOOT, exitCode = 1)
        )
        assertEquals(FailureCategory.JVM_START_FAILED, category)
    }

    @Test
    fun `JVM 启动失败 ZLBridge 异常`() {
        val category = FailureClassifier.classify(
            LaunchFailureEvent(
                stage = LaunchStage.JVM_BOOT,
                throwable = RuntimeException("dlopen java runtime failed")
            )
        )
        assertEquals(FailureCategory.JVM_START_FAILED, category)
    }

    @Test
    fun `渲染器 dlopen 失败 由异常类型判定`() {
        val error = RendererLoadException(
            "opengles2", "libgl4es_114.so", listOf("/a/libgl4es_114.so")
        )
        val category = FailureClassifier.classify(
            LaunchFailureEvent(stage = LaunchStage.JVM_BOOT, throwable = error)
        )
        // 即便阶段写成 JVM_BOOT，异常类型也优先判成 RENDERER_DLOPEN_FAILED
        assertEquals(FailureCategory.RENDERER_DLOPEN_FAILED, category)
    }

    @Test
    fun `渲染器 dlopen 失败 由阶段判定`() {
        val category = FailureClassifier.classify(
            LaunchFailureEvent(stage = LaunchStage.RENDERER_LOAD)
        )
        assertEquals(FailureCategory.RENDERER_DLOPEN_FAILED, category)
    }

    @Test
    fun `游戏进程非0退出 MC_CRASHED`() {
        val category = FailureClassifier.classify(
            LaunchFailureEvent(stage = LaunchStage.GAME_RUNNING, exitCode = 139)
        )
        assertEquals(FailureCategory.MC_CRASHED, category)
    }

    @Test
    fun `游戏进程被杀 MC_CRASHED`() {
        val category = FailureClassifier.classify(
            LaunchFailureEvent(
                stage = LaunchStage.GAME_RUNNING,
                exitCode = 0,
                gameProcessKilled = true
            )
        )
        assertEquals(FailureCategory.MC_CRASHED, category)
    }

    @Test
    fun `无有效信号兜底按启动失败处理 不静默判成功`() {
        val category = FailureClassifier.classify(
            LaunchFailureEvent(stage = LaunchStage.GAME_RUNNING, exitCode = 0)
        )
        assertEquals(FailureCategory.JVM_START_FAILED, category)
    }
}
