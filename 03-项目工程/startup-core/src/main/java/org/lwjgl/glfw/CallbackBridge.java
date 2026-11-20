/*
 * Zalith Launcher 2
 * Copyright (C) 2025-2026 MovTery <movtery228@qq.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 适配说明（2026-11-08）：
 *  - 包名与类名是 JNI 静态绑定的硬约束，不可改：input_bridge_v3.c 的 JNI_OnLoad
 *    FindClass("org/lwjgl/glfw/CallbackBridge") 与
 *    Java_org_lwjgl_glfw_CallbackBridge_nativeNotifyLauncher 符号都钉在这个类上。
 *  - 与 SdlBridge.kt 同一决策：R1 不启用 SDL 渲染/手柄路径（APK 不带 libSDL2/SDL3.so），
 *    所有 SDLActivity/SDLSurface 分支已整体摘除；恢复 SDL 支持时从
 *    02-参考源码/ZalithLauncher2 的同名文件回移。
 *  - BuildKeys / ContextsKt（getGlobalContext）由 Vaelin 按原包名提供，见同包适配文件。
 */
package org.lwjgl.glfw;

import static com.movtery.zalithlauncher.bridge.ZLBridgeStatesKt.CURSOR_DISABLED;
import static com.movtery.zalithlauncher.bridge.ZLBridgeStatesKt.CURSOR_ENABLED;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

import com.movtery.inputmap.keycodes.LwjglGlfwKeycode;
import com.movtery.zalithlauncher.BuildKeys;
import com.movtery.zalithlauncher.bridge.CursorShape;
import com.movtery.zalithlauncher.bridge.LoggerBridge;
import com.movtery.zalithlauncher.bridge.NativeLibraryLoader;
import com.movtery.zalithlauncher.bridge.ZLBridgeStates;
import com.movtery.zalithlauncher.bridge.ZLNativeInvoker;
import com.movtery.zalithlauncher.context.ContextsKt;
import com.movtery.zalithlauncher.game.sdl.DirectGamepadEnableHandler;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import dalvik.annotation.optimization.CriticalNative;

@Keep
public class CallbackBridge {
    private static final int GLFW_IBEAM_CURSOR = 0x36002;
    private static final int GLFW_HAND_CURSOR = 0x36004;
    private static final int GLFW_CROSSHAIR_CURSOR = 0x36003;
    private static final int GLFW_RESIZE_NS_CURSOR = 0x36006;
    private static final int GLFW_RESIZE_EW_CURSOR = 0x36005;
    private static final int GLFW_RESIZE_ALL_CURSOR = 0x36009;
    private static final int GLFW_NOT_ALLOWED_CURSOR = 0x3600A;
    private static final int GLFW_ARROW_CURSOR = 0x36001;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static volatile ClipboardManager sClipboardManager;
    private static volatile boolean isGrabbing = false;
    private static final Consumer<Boolean> grabListener = isGrabbing ->
            ZLBridgeStates.changeCursorMode(isGrabbing ? CURSOR_DISABLED : CURSOR_ENABLED);

    private static int cursorShape = GLFW_ARROW_CURSOR;
    private static final Consumer<CursorShape> cursorShapeListener = ZLBridgeStates::changeCursorShape;

    private static void postFrameCallbackDelayed(Choreographer.FrameCallback callback, long delayMillis) {
        MAIN_HANDLER.post(() -> Choreographer.getInstance().postFrameCallbackDelayed(callback, delayMillis));
    }
    
    public static final int CLIPBOARD_COPY = 2000;
    public static final int CLIPBOARD_PASTE = 2001;
    public static final int CLIPBOARD_OPEN = 2002;

    // SDL launcher integration. See the AAMC reference implementation:
    // https://github.com/AngelAuraMC/Amethyst-Android
    // Notification types
    public static final int NOTIF_TYPE_SDL = 0;

    // Notification actions
    public static final int ACTION_INIT_LAUNCHER_INTEGRATION = 0;
    public static final int ACTION_SEND_TEXTBOX_RECT = 1;

    // org.lwjgl.sdl.SDLInit 通过这两个常量调用 nativeNotifyLauncher
    public static final int SDL = NOTIF_TYPE_SDL;
    public static final int INIT = ACTION_INIT_LAUNCHER_INTEGRATION;

    /**
     * 由 JRE 侧（sdl_hook JNI）调用的通知入口。
     * @return 通知是否处理成功
     */
    @SuppressWarnings("unused")
    @Keep
    public static boolean notifyLauncher(int type, int... action) {
        if (action == null || action.length == 0) {
            LoggerBridge.append("ZalithLauncher: SDL notification has no action");
            return false;
        }
        switch (type) {
            case NOTIF_TYPE_SDL:
                if (action[0] == ACTION_INIT_LAUNCHER_INTEGRATION) {
                    // Vaelin R1 不启用 SDL 渲染/手柄路径（决策见文件头与 SdlBridge.kt）：
                    // 显式记录并拒绝，不加载 libSDL2/SDL3，不做 surface 绑定。
                    LoggerBridge.append("ZalithLauncher: SDL launcher integration is disabled in Vaelin R1");
                    return false;
                }
                if (action[0] == ACTION_SEND_TEXTBOX_RECT) {
                    // TODO: 输入框位置同步（后续接入）
                }
        }
        return false;
    }

    /**
     * org.lwjgl.sdl.SDLInit（LWJGL 3.4.1 的 SDL Java 绑定）调用的入口，转发到 {@link #notifyLauncher}。
     * 注意：LWJGL 组件内声明为 native，运行时以本实现为准（避免依赖额外 C 符号）。
     */
    @SuppressWarnings("unused")
    @Keep
    public static void nativeNotifyLauncher(int type, int... action) {
        notifyLauncher(type, action);
    }
    
    public static volatile int windowWidth, windowHeight;
    public static volatile int physicalWidth, physicalHeight;
    public static float mouseX, mouseY, deltaX, deltaY;
    private static int sMouseButtonState = 0;
    public volatile static boolean holdingAlt, holdingCapslock, holdingCtrl,
            holdingNumlock, holdingShift;

    // GLFW direct gamepad 共享缓冲
    public static final ByteBuffer sGamepadButtonBuffer;
    public static final FloatBuffer sGamepadAxisBuffer;
    public static boolean sGamepadDirectInput = false;
    // Use a weak reference here to avoid possibly statically referencing a Context.
    private static @Nullable WeakReference<DirectGamepadEnableHandler> sDirectGamepadEnableHandler;

    public static void putMouseEventWithCoords(int button, float x, float y) {
        sendCursorPos(x, y);
        putMouseEvent(button);
    }

    public static void putMouseEvent(int button) {
        putMouseEvent(button, true);
        postFrameCallbackDelayed(l -> putMouseEvent(button, false), 33);
    }
    
    public static void putMouseEvent(int button, boolean isDown) {
        sendMouseKeycode(button, CallbackBridge.getCurrentMods(), isDown);
    }


    public static void sendCursorPos(float x, float y) {
        mouseX = x;
        mouseY = y;
        deltaX = 0f;
        deltaY = 0f;
        nativeSendCursorPos(mouseX, mouseY);
    }

    public static void sendCursorDelta(float x, float y) {
        deltaX = x;
        deltaY = y;
        mouseX += x;
        mouseY += y;
        nativeSendCursorPos(mouseX, mouseY);
    }

    public static void sendKeycode(int keycode, char keychar, int scancode, int modifiers, boolean isDown) {
        if (keycode > LwjglGlfwKeycode.GLFW_KEY_UNKNOWN && keycode <= LwjglGlfwKeycode.GLFW_KEY_LAST) {
            nativeSendKey(keycode, scancode, isDown ? 1 : 0, modifiers);
        }
        if (isDown && !Character.isISOControl(keychar)) {
            nativeSendCharMods(keychar, modifiers);
            nativeSendChar(keychar);
        }
    }

    private static boolean isTextEventChar(char keychar, int modifiers) {
        if (Character.isISOControl(keychar)) return false;
        return (modifiers & LwjglGlfwKeycode.GLFW_MOD_CONTROL) == 0;
    }

    public static void sendChar(char keychar, int modifiers){
        nativeSendCharMods(keychar, modifiers);
        nativeSendChar(keychar);
    }

    public static void sendKeyPress(int keyCode, int modifiers, boolean status) {
        sendKeyPress(keyCode, 0, modifiers, status);
    }

    public static void sendKeyPress(int keyCode, int scancode, int modifiers, boolean status) {
        sendKeyPress(keyCode, '\u0000', scancode, modifiers, status);
    }

    public static void sendKeyPress(int keyCode, char keyChar, int scancode, int modifiers, boolean status) {
        CallbackBridge.sendKeycode(keyCode, keyChar, scancode, modifiers, status);
    }

    public static void sendKeyPress(int keyCode) {
        sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), true);
        sendKeyPress(keyCode, CallbackBridge.getCurrentMods(), false);
    }

    public static void sendMouseButton(int button, boolean status) {
        CallbackBridge.sendMouseKeycode(button, CallbackBridge.getCurrentMods(), status);
    }

    public static void sendMouseKeycode(int button, int modifiers, boolean isDown) {
        // if (isGrabbing()) DEBUG_STRING.append("MouseGrabStrace: " + android.util.Log.getStackTraceString(new Throwable()) + "\n");
        nativeSendMouseButton(button, isDown ? 1 : 0, modifiers);
    }

    public static void sendMouseKeycode(int keycode) {
        sendMouseKeycode(keycode, CallbackBridge.getCurrentMods(), true);
        sendMouseKeycode(keycode, CallbackBridge.getCurrentMods(), false);
    }
    
    public static void sendScroll(double xoffset, double yoffset) {
        nativeSendScroll(xoffset, yoffset);
    }

    public static void sendUpdateWindowSize(int w, int h) {
        windowWidth = w;
        windowHeight = h;
        nativeSendScreenSize(w, h);
    }

    public static boolean isGrabbing() {
        // Avoid going through the JNI each time.
        return isGrabbing;
    }

    public static void resetInputState() {
        nativeResetInputState();
        deltaX = 0f;
        deltaY = 0f;
        sMouseButtonState = 0;
        holdingAlt = false;
        holdingCapslock = false;
        holdingCtrl = false;
        holdingNumlock = false;
        holdingShift = false;
    }

    // Called from JRE side
    @SuppressWarnings("unused")
    @Keep
    public static @Nullable String accessAndroidClipboard(int type, String copy) {
        String result = null;
        switch (type) {
            case CLIPBOARD_COPY: {
                ClipboardManager clipboard = getClipboardManager();
                if (clipboard != null) {
                    ClipData clip = ClipData.newPlainText(BuildKeys.INSTANCE.getLAUNCHER_IDENTIFIER(), copy);
                    clipboard.setPrimaryClip(clip);
                }
                break;
            }
            case CLIPBOARD_PASTE: {
                ClipboardManager clipboard = getClipboardManager();
                if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClipDescription().hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)) {
                    result = clipboard.getPrimaryClip().getItemAt(0).getText().toString();
                } else {
                    result = "";
                }
                break;
            }
            case CLIPBOARD_OPEN:
                ZLNativeInvoker.openLink(copy);
                break;
        }
        return result;
    }

    /**
     * On Android 8.x, the ClipboardManager constructor creates a Handler on the calling thread,
     * so obtaining the clipboard service from a looper-less thread (the game threads) throws
     * immediately. Build and cache the instance on the main thread once; afterwards calls from
     * any thread are safe, as they only post messages to the main looper.
     */
    private static @Nullable ClipboardManager getClipboardManager() {
        ClipboardManager clipboard = sClipboardManager;
        if (clipboard != null) return clipboard;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            clipboard = (ClipboardManager) ContextsKt.getGlobalContext().getSystemService(Context.CLIPBOARD_SERVICE);
            sClipboardManager = clipboard;
        } else {
            CountDownLatch latch = new CountDownLatch(1);
            MAIN_HANDLER.post(() -> {
                try {
                    sClipboardManager = (ClipboardManager) ContextsKt.getGlobalContext().getSystemService(Context.CLIPBOARD_SERVICE);
                } finally {
                    latch.countDown();
                }
            });
            try {
                latch.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            clipboard = sClipboardManager;
        }
        return clipboard;
    }

    public static int getCurrentMods() {
        int currMods = 0;
        if (holdingAlt) {
            currMods |= LwjglGlfwKeycode.GLFW_MOD_ALT;
        } if (holdingCapslock) {
            currMods |= LwjglGlfwKeycode.GLFW_MOD_CAPS_LOCK;
        } if (holdingCtrl) {
            currMods |= LwjglGlfwKeycode.GLFW_MOD_CONTROL;
        } if (holdingNumlock) {
            currMods |= LwjglGlfwKeycode.GLFW_MOD_NUM_LOCK;
        } if (holdingShift) {
            currMods |= LwjglGlfwKeycode.GLFW_MOD_SHIFT;
        }
        return currMods;
    }

    public static void setModifiers(int keyCode, boolean isDown){
        switch (keyCode){
            case LwjglGlfwKeycode.GLFW_KEY_LEFT_SHIFT:
                CallbackBridge.holdingShift = isDown;
                return;

            case LwjglGlfwKeycode.GLFW_KEY_LEFT_CONTROL:
                CallbackBridge.holdingCtrl = isDown;
                return;

            case LwjglGlfwKeycode.GLFW_KEY_LEFT_ALT:
                CallbackBridge.holdingAlt = isDown;
                return;

            case LwjglGlfwKeycode.GLFW_KEY_CAPS_LOCK:
                CallbackBridge.holdingCapslock = isDown;
                return;

            case LwjglGlfwKeycode.GLFW_KEY_NUM_LOCK:
                CallbackBridge.holdingNumlock = isDown;
        }
    }

    public static void setDirectGamepadEnableHandler(@Nullable DirectGamepadEnableHandler handler) {
        sDirectGamepadEnableHandler = new WeakReference<>(handler);
    }

    public static void clearSdlBridgeState() {
        sGamepadDirectInput = false;
        sDirectGamepadEnableHandler = null;
        sMouseButtonState = 0;
        deltaX = 0f;
        deltaY = 0f;
    }

    //Called from JRE side
    @SuppressWarnings("unused")
    @Keep
    private static void onDirectInputEnable() {
        LoggerBridge.append("ZalithLauncher: Direct gamepad input enabled");
        sGamepadDirectInput = true;
        DirectGamepadEnableHandler enableHandler =
                sDirectGamepadEnableHandler == null ? null : sDirectGamepadEnableHandler.get();
        if (enableHandler != null) {
            enableHandler.onDirectGamepadEnabled();
        }
    }

    //Called from JRE side
    @SuppressWarnings("unused")
    @Keep
    private static void onGrabStateChanged(final boolean grabbing) {
        System.out.println("Grab callback received: " + grabbing);
        isGrabbing = grabbing;
        deltaX = 0f;
        deltaY = 0f;
        postFrameCallbackDelayed((time) -> {
            // If the grab re-changed, skip notify process
            if(isGrabbing != grabbing) return;

            System.out.println("Grab changed : " + grabbing);
            synchronized (grabListener) {
                grabListener.accept(isGrabbing);
            }

        }, 16);
    }

    //Called from JRE side
    @SuppressWarnings("unused")
    @Keep
    private static void onCursorShapeChanged(final int shape) {
        cursorShape = shape;
        postFrameCallbackDelayed((time) -> {
            if (cursorShape != shape) return;

            synchronized (cursorShapeListener) {
                CursorShape shape1;
                switch (cursorShape) {
                    // IBeam
                    case GLFW_IBEAM_CURSOR:
                        shape1 = CursorShape.IBeam;
                        break;
                    // Hand
                    case GLFW_HAND_CURSOR:
                        shape1 = CursorShape.Hand;
                        break;
                    // Cross Hair
                    case GLFW_CROSSHAIR_CURSOR:
                        shape1 = CursorShape.CrossHair;
                        break;
                    // Resize NS
                    case GLFW_RESIZE_NS_CURSOR:
                        shape1 = CursorShape.ResizeNS;
                        break;
                    // Resize EW
                    case GLFW_RESIZE_EW_CURSOR:
                        shape1 = CursorShape.ResizeEW;
                        break;
                    // Resize All
                    case GLFW_RESIZE_ALL_CURSOR:
                        shape1 = CursorShape.ResizeAll;
                        break;
                    // Not Allowed
                    case GLFW_NOT_ALLOWED_CURSOR:
                        shape1 = CursorShape.NotAllowed;
                        break;
                    // Arrow
                    case GLFW_ARROW_CURSOR:
                    default:
                        shape1 = CursorShape.Arrow;
                }

                cursorShapeListener.accept(shape1);
            }
        }, 16);
    }

    private static GraphicOutputListener sGraphicOutputListener;
    public static void setGraphicOutputListener(GraphicOutputListener listener) {
        sGraphicOutputListener = listener;
    }

    //Called from JRE side
    @SuppressWarnings("unused")
    @Keep
    private static void onGraphicOutput() {
        if (sGraphicOutputListener != null) {
            sGraphicOutputListener.onGraphicOutput();
        }
    }

    @Keep @CriticalNative public static native void nativeSetUseInputStackQueue(boolean useInputStackQueue);

    @Keep @CriticalNative private static native boolean nativeSendChar(char codepoint);
    // GLFW: GLFWCharModsCallback deprecated, but is Minecraft still use?
    @Keep @CriticalNative private static native boolean nativeSendCharMods(char codepoint, int mods);
    @Keep @CriticalNative private static native void nativeSendKey(int key, int scancode, int action, int mods);
    // private static native void nativeSendCursorEnter(int entered);
    @Keep @CriticalNative private static native void nativeSendCursorPos(float x, float y);
    @Keep @CriticalNative private static native void nativeSendMouseButton(int button, int action, int mods);
    @Keep @CriticalNative private static native void nativeResetInputState();
    @Keep @CriticalNative private static native void nativeSendScroll(double xoffset, double yoffset);
    @Keep @CriticalNative private static native void nativeSendScreenSize(int width, int height);
    @Keep public static native void nativeSetWindowAttrib(int attrib, int value);
    @Keep public static native void nativeSetGrabbing(boolean grab);
    @Keep public static native int getCurrentFps();

    private static native ByteBuffer nativeCreateGamepadButtonBuffer();
    private static native ByteBuffer nativeCreateGamepadAxisBuffer();

    static {
        NativeLibraryLoader.loadPojavLib();
        sGamepadButtonBuffer = nativeCreateGamepadButtonBuffer();
        sGamepadAxisBuffer = nativeCreateGamepadAxisBuffer().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
    }
}

