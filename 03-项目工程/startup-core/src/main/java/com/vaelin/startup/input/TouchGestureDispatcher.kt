/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现：语义移植自 ZL2 的 CLICK 指针模式（用户 2026-11-08 裁定「触控直接
 * 采用 ZL2 的方式」）。语义出处（ZL2 源码）：
 *  - ui/control/mouse/TouchInput.kt TouchpadLayout pointerInput 块 CLICK 分支：
 *      · 按下 → 仅更新指针位置（onPointerMove，GameScreen 侧 = CallbackBridge.sendCursorPos）
 *      · 任意移动 → 立即触发长按（longPressTriggered）= 左键按住，之后指针跟随
 *      · 抬起 → 未进入过长按 = 一次点击（onTap = putMouseEventWithCoords，含 33ms 自动释放）；
 *               进入过长按 = 左键释放（onLongPressEnd）
 *  - ui/screens/game/GameScreen.kt MouseControlLayout 的回调接线（tap/onLongPress/onPointerMove）。
 * 状态机与 CallbackBridge 解耦（Sender 注入），保证纯 JVM 可单测；坐标一律为
 * 调用方换算后的游戏坐标系。
 */
package com.vaelin.startup.input

class TouchGestureDispatcher(private val sender: Sender) {

    /** CallbackBridge 的最小投影；VMActivity 接线到 org.lwjgl.glfw.CallbackBridge */
    interface Sender {
        /** 指针绝对位置更新 = CallbackBridge.sendCursorPos */
        fun cursorPos(x: Float, y: Float)

        /** 按键状态 = CallbackBridge.sendMouseButton */
        fun mouseButton(button: Int, pressed: Boolean)

        /** 点击 = CallbackBridge.putMouseEventWithCoords（位置 + 按下 + 延时自动释放） */
        fun tapAt(button: Int, x: Float, y: Float)
    }

    /** 当前是否有手指按住（ZL2 的 activePointer 概念；R1 只跟踪第一根指针） */
    private var active = false

    /** 本次按住期间是否已进入拖拽（ZL2 的 longPressTriggered） */
    private var dragging = false

    fun onDown(x: Float, y: Float) {
        if (active) return
        active = true
        dragging = false
        // CLICK 模式按下只更新指针位置，不按左键（ZL2 TouchInput.kt:298-301）
        sender.cursorPos(x, y)
    }

    fun onMove(x: Float, y: Float) {
        if (!active) return
        if (!dragging) {
            // CLICK 模式任意位移即进入拖拽：左键按住（ZL2 TouchInput.kt:393-398）
            dragging = true
            sender.mouseButton(LEFT_BUTTON, true)
        }
        sender.cursorPos(x, y)
    }

    fun onUp(x: Float, y: Float) {
        if (!active) return
        active = false
        if (dragging) {
            // 拖拽结束 = 左键释放（ZL2 TouchInput.kt:442-443 onLongPressEnd）
            dragging = false
            sender.mouseButton(LEFT_BUTTON, false)
        } else {
            // 未移动 = 一次点击：位置 + 按下 + 自动释放（ZL2 GameScreen.kt:1022-1025 onTap）
            dragging = false
            sender.tapAt(LEFT_BUTTON, x, y)
        }
    }

    fun onCancel() {
        if (!active) return
        active = false
        if (dragging) {
            dragging = false
            sender.mouseButton(LEFT_BUTTON, false)
        }
    }

    companion object {
        const val LEFT_BUTTON = 0 // LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT
    }
}
