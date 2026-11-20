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

/**
 * Android 触摸事件 → `ZLBridge.sendInputData` 期望的 AWT 事件序列。
 *
 * 刻意放在 startup-core 且完全不引用 android.*：app 模块没有 testImplementation，
 * 也不许改构建脚本，只有 startup-core 能跑 JVM 单测。把编码规则抽成纯函数后，
 * VMActivity（:game 进程）只负责从 MotionEvent 取值并投递，坐标换算可独立验证。
 *
 * 契约来源（均为本地源码实测）：
 *  - ZLBridge.java:29  EVENT_TYPE_CURSOR_POS = 1003
 *  - ZLBridge.java:31  EVENT_TYPE_MOUSE_BUTTON = 1006
 *  - ZLBridge.java:57-59 sendMousePos(x, y) -> sendInputData(1003, x, y, 0, 0)
 *  - ZLBridge.java:48-50 sendMousePress(awtButtons, isDown) -> sendInputData(1006, awtButtons, 1/0, 0, 0)
 *  - AWTInputEvent.java:109/115/121 BUTTON1/2/3_DOWN_MASK = 1<<10 / 1<<11 / 1<<12
 *  - awt_bridge.c:53 sendInputData 的 native 实现（转发 CTCAndroidInput.receiveData）
 *  - ZL2 AWTCharSender.kt:99-101 把 BUTTON_PRIMARY/SECONDARY/TERTIARY 映射到上述掩码
 *
 * R1 边界：此时 :game 进程尚未 dlopen HotSpot JVM，awt_bridge.c:54-56 在
 * runtimeJavaVMPtr == NULL 时直接 return，故投递是安全的 no-op；真实生效要等
 * T1.11 串起 VaelinLauncher.launch。本层不对此做静默伪装，编码结果照实返回。
 */
object NativeInput {

    const val EVENT_TYPE_CURSOR_POS = 1003
    const val EVENT_TYPE_MOUSE_BUTTON = 1006

    const val BUTTON1_DOWN_MASK = 1 shl 10
    const val BUTTON2_DOWN_MASK = 1 shl 11
    const val BUTTON3_DOWN_MASK = 1 shl 12

    /** 触摸动作，与 android.view.MotionEvent.ACTION_* 一一对应，由 app 侧转换后传入。 */
    enum class TouchAction { DOWN, UP, MOVE, CANCEL }

    /** 一条 sendInputData 调用的参数。 */
    data class InputEvent(
        val type: Int,
        val i1: Int,
        val i2: Int,
        val i3: Int,
        val i4: Int,
    )

    /** 游戏渲染坐标系里的整数点。 */
    data class GamePoint(val x: Int, val y: Int)

    /** 编码结果：换算后的坐标 + 需要按顺序投递的事件序列。 */
    data class EncodedTouch(
        val point: GamePoint,
        val events: List<InputEvent>,
    )

    /**
     * 视图局部坐标 → 游戏渲染坐标。
     *
     * 按视图/渲染尺寸的比例缩放后取整，并钳制到 [0, gameWidth-1] / [0, gameHeight-1]，
     * 防止厂商窗口缩放或 ACTION_MOVE 越界时把负坐标喂给 AWT。
     * 尺寸非正属于契约破坏，直接抛异常而不是回退到 1:1（禁止静默降级）。
     */
    fun mapToGame(
        viewX: Float,
        viewY: Float,
        viewWidth: Int,
        viewHeight: Int,
        gameWidth: Int,
        gameHeight: Int,
    ): GamePoint {
        require(viewWidth > 0 && viewHeight > 0) {
            "view size must be positive: ${viewWidth}x${viewHeight}"
        }
        require(gameWidth > 0 && gameHeight > 0) {
            "game size must be positive: ${gameWidth}x${gameHeight}"
        }
        val safeX = if (viewX.isFinite()) viewX else 0f
        val safeY = if (viewY.isFinite()) viewY else 0f
        val gx = ((safeX / viewWidth) * gameWidth).toInt().coerceIn(0, gameWidth - 1)
        val gy = ((safeY / viewHeight) * gameHeight).toInt().coerceIn(0, gameHeight - 1)
        return GamePoint(gx, gy)
    }

    /**
     * 把一次触摸编码成事件序列：先移动光标（CURSOR_POS），DOWN/UP 再补按键事件。
     * MOVE 只带坐标；CANCEL 视为抬起前的一次坐标同步，不补按键，避免卡住按下态。
     *
     * @param awtButtonMask 由 app 侧按 MotionEvent.buttonState 选好的 AWT 掩码，
     *                      纯函数不引用 android.view.MotionEvent 常量。
     */
    fun encode(
        action: TouchAction,
        viewX: Float,
        viewY: Float,
        viewWidth: Int,
        viewHeight: Int,
        gameWidth: Int,
        gameHeight: Int,
        awtButtonMask: Int = BUTTON1_DOWN_MASK,
    ): EncodedTouch {
        val point = mapToGame(viewX, viewY, viewWidth, viewHeight, gameWidth, gameHeight)
        val events = ArrayList<InputEvent>(2)
        events += InputEvent(EVENT_TYPE_CURSOR_POS, point.x, point.y, 0, 0)
        when (action) {
            TouchAction.DOWN -> events += InputEvent(EVENT_TYPE_MOUSE_BUTTON, awtButtonMask, 1, 0, 0)
            TouchAction.UP -> events += InputEvent(EVENT_TYPE_MOUSE_BUTTON, awtButtonMask, 0, 0, 0)
            TouchAction.MOVE, TouchAction.CANCEL -> Unit
        }
        return EncodedTouch(point, events)
    }
}
