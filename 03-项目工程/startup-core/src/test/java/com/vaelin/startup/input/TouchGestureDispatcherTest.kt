/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现：TouchGestureDispatcher 的纯 JVM 单测。
 * 断言语义 = ZL2 CLICK 指针模式（TouchInput.kt CLICK 分支 + GameScreen 接线）。
 */

package com.vaelin.startup.input

import org.junit.Assert.assertEquals
import org.junit.Test

class TouchGestureDispatcherTest {

    /** 记录 Sender 调用序列的假实现 */
    private class RecordingSender : TouchGestureDispatcher.Sender {
        val calls = mutableListOf<String>()

        override fun cursorPos(x: Float, y: Float) {
            calls += "cursor($x,$y)"
        }

        override fun mouseButton(button: Int, pressed: Boolean) {
            calls += "button($button,$pressed)"
        }

        override fun tapAt(button: Int, x: Float, y: Float) {
            calls += "tap($button,$x,$y)"
        }
    }

    private fun dispatcher(sender: RecordingSender) = TouchGestureDispatcher(sender)

    @Test
    fun `干净点击 DOWN 无移动 UP = tap 含位置`() {
        val sender = RecordingSender()
        val d = dispatcher(sender)
        d.onDown(118f, 90f)
        d.onUp(118f, 90f)
        assertEquals(listOf("cursor(118.0,90.0)", "tap(0,118.0,90.0)"), sender.calls)
    }

    @Test
    fun `按下即位移 = 左键按住拖拽 抬起释放`() {
        val sender = RecordingSender()
        val d = dispatcher(sender)
        d.onDown(10f, 10f)
        d.onMove(11f, 12f)
        d.onMove(20f, 30f)
        d.onUp(25f, 35f)
        assertEquals(
            listOf(
                "cursor(10.0,10.0)",
                "button(0,true)",
                "cursor(11.0,12.0)",
                "cursor(20.0,30.0)",
                "button(0,false)",
            ),
            sender.calls,
        )
    }

    @Test
    fun `拖拽后抬起不再发 tap`() {
        val sender = RecordingSender()
        val d = dispatcher(sender)
        d.onDown(5f, 5f)
        d.onMove(8f, 8f)
        d.onUp(8f, 8f)
        assertEquals(false, sender.calls.any { it.startsWith("tap(") })
    }

    @Test
    fun `CANCEL 释放按住的左键`() {
        val sender = RecordingSender()
        val d = dispatcher(sender)
        d.onDown(1f, 1f)
        d.onMove(2f, 2f)
        d.onCancel()
        assertEquals(listOf("cursor(1.0,1.0)", "button(0,true)", "cursor(2.0,2.0)", "button(0,false)"), sender.calls)
    }

    @Test
    fun `CANCEL 未进入拖拽不发按键`() {
        val sender = RecordingSender()
        val d = dispatcher(sender)
        d.onDown(1f, 1f)
        d.onCancel()
        assertEquals(listOf("cursor(1.0,1.0)"), sender.calls)
    }

    @Test
    fun `无 DOWN 时 MOVE 与 UP 被忽略`() {
        val sender = RecordingSender()
        val d = dispatcher(sender)
        d.onMove(3f, 4f)
        d.onUp(3f, 4f)
        assertEquals(emptyList<String>(), sender.calls)
    }

    @Test
    fun `UP 后新的一次 DOWN 重新开始生命周期`() {
        val sender = RecordingSender()
        val d = dispatcher(sender)
        d.onDown(1f, 1f)
        d.onUp(1f, 1f)
        d.onDown(2f, 2f)
        d.onMove(5f, 5f)
        d.onUp(6f, 6f)
        assertEquals(
            listOf(
                "cursor(1.0,1.0)",
                "tap(0,1.0,1.0)",
                "cursor(2.0,2.0)",
                "button(0,true)",
                "cursor(5.0,5.0)",
                "button(0,false)",
            ),
            sender.calls,
        )
    }

    @Test
    fun `按住期间的重复 DOWN 被忽略`() {
        val sender = RecordingSender()
        val d = dispatcher(sender)
        d.onDown(1f, 1f)
        d.onDown(9f, 9f)
        assertEquals(listOf("cursor(1.0,1.0)"), sender.calls)
    }
}
