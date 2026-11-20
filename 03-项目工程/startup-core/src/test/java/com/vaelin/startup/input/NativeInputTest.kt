/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现。
 */

package com.vaelin.startup.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * NativeInput 的纯 JVM 单测：坐标换算的正常/边界/失败路径，以及 AWT 事件编码。
 * 不引入任何 Android stub，符合「可测试性优先、零新增构建依赖」。
 */
class NativeInputTest {

    @Test
    fun `1比1 原点到左上角`() {
        val p = NativeInput.mapToGame(0f, 0f, 100, 100, 100, 100)
        assertEquals(0, p.x)
        assertEquals(0, p.y)
    }

    @Test
    fun `1比1 中心点`() {
        val p = NativeInput.mapToGame(50f, 50f, 100, 100, 100, 100)
        assertEquals(50, p.x)
        assertEquals(50, p.y)
    }

    @Test
    fun `右下角钳到 gameWidth-1`() {
        val p = NativeInput.mapToGame(100f, 100f, 100, 100, 100, 100)
        assertEquals(99, p.x)
        assertEquals(99, p.y)
    }

    @Test
    fun `负坐标钳到 0`() {
        val p = NativeInput.mapToGame(-10f, -10f, 100, 100, 100, 100)
        assertEquals(0, p.x)
        assertEquals(0, p.y)
    }

    @Test
    fun `超出右下角也钳到 gameWidth-1`() {
        val p = NativeInput.mapToGame(9999f, 9999f, 100, 100, 100, 100)
        assertEquals(99, p.x)
        assertEquals(99, p.y)
    }

    @Test
    fun `按比例缩放 1080x2400 到 720x1280`() {
        val p = NativeInput.mapToGame(540f, 1200f, 1080, 2400, 720, 1280)
        assertEquals(360, p.x)
        assertEquals(640, p.y)
    }

    @Test
    fun `NaN 坐标回退到原点而不抛`() {
        val p = NativeInput.mapToGame(Float.NaN, Float.NaN, 100, 100, 100, 100)
        assertEquals(0, p.x)
        assertEquals(0, p.y)
    }

    @Test
    fun `view 尺寸为 0 抛 IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            NativeInput.mapToGame(1f, 1f, 0, 100, 100, 100)
        }
    }

    @Test
    fun `game 尺寸为 0 抛 IllegalArgumentException`() {
        assertThrows(IllegalArgumentException::class.java) {
            NativeInput.mapToGame(1f, 1f, 100, 100, 0, 100)
        }
    }

    @Test
    fun `DOWN 编码为先光标后按下两个事件`() {
        val e = NativeInput.encode(NativeInput.TouchAction.DOWN, 25f, 50f, 100, 100, 100, 100)
        assertEquals(NativeInput.GamePoint(25, 50), e.point)
        assertEquals(2, e.events.size)
        assertEquals(
            NativeInput.InputEvent(NativeInput.EVENT_TYPE_CURSOR_POS, 25, 50, 0, 0),
            e.events[0],
        )
        assertEquals(
            NativeInput.InputEvent(
                NativeInput.EVENT_TYPE_MOUSE_BUTTON,
                NativeInput.BUTTON1_DOWN_MASK, 1, 0, 0,
            ),
            e.events[1],
        )
    }

    @Test
    fun `UP 编码为抬起`() {
        val e = NativeInput.encode(NativeInput.TouchAction.UP, 10f, 10f, 100, 100, 100, 100)
        assertEquals(2, e.events.size)
        assertEquals(0, e.events[1].i2)
        assertEquals(NativeInput.EVENT_TYPE_MOUSE_BUTTON, e.events[1].type)
    }

    @Test
    fun `MOVE 只编码光标事件`() {
        val e = NativeInput.encode(NativeInput.TouchAction.MOVE, 10f, 10f, 100, 100, 100, 100)
        assertEquals(1, e.events.size)
        assertEquals(NativeInput.EVENT_TYPE_CURSOR_POS, e.events[0].type)
    }

    @Test
    fun `CANCEL 不补按键事件`() {
        val e = NativeInput.encode(NativeInput.TouchAction.CANCEL, 10f, 10f, 100, 100, 100, 100)
        assertEquals(1, e.events.size)
        assertEquals(NativeInput.EVENT_TYPE_CURSOR_POS, e.events[0].type)
    }

    @Test
    fun `右键掩码透传`() {
        val e = NativeInput.encode(
            NativeInput.TouchAction.DOWN, 10f, 10f, 100, 100, 100, 100,
            NativeInput.BUTTON3_DOWN_MASK,
        )
        assertEquals(NativeInput.BUTTON3_DOWN_MASK, e.events[1].i1)
    }

    @Test
    fun `常量与 ZLBridge 及 AWTInputEvent 对齐`() {
        assertEquals(1003, NativeInput.EVENT_TYPE_CURSOR_POS)
        assertEquals(1006, NativeInput.EVENT_TYPE_MOUSE_BUTTON)
        assertEquals(1024, NativeInput.BUTTON1_DOWN_MASK)
        assertEquals(2048, NativeInput.BUTTON2_DOWN_MASK)
        assertEquals(4096, NativeInput.BUTTON3_DOWN_MASK)
    }
}
