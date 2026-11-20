/*
 * Vaelin Launcher
 * Copyright (C) 2026 Vaelin contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Vaelin 自有实现：只复用 ZL2 的 Surface 生命周期「时序」，UI 与崩溃链路自写。
 */

package com.vaelin.launcher.game

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import com.movtery.zalithlauncher.bridge.NativeLibraryLoader
import com.movtery.zalithlauncher.bridge.ZLBridge
import com.movtery.zalithlauncher.bridge.ZLNativeInvoker
import com.movtery.zalithlauncher.context.setGlobalContext
import com.movtery.zalithlauncher.game.input.LWJGLCharSender
import com.movtery.zalithlauncher.game.sdl.SdlBridge
import org.lwjgl.glfw.CallbackBridge
import com.vaelin.startup.input.TouchGestureDispatcher
import com.vaelin.startup.launch.LaunchCoordinator
import com.vaelin.startup.util.VLog
import com.vaelin.startup.util.VPaths

/**
 * :game 进程的游戏宿主 Activity（T1.10b A.2 + T1.9 B.3）。
 *
 * 进程归属：app/AndroidManifest.xml 对本 Activity 声明 android:process=":game"，
 * 系统据此在独立进程创建它；与 MainActivity（主进程）形成两个 pid。
 *
 * 复用边界（按逻辑层，不整文件照搬 ZL2 VMActivity.kt 的 1182 行）：
 * 只继承两件底座时序——
 *  ① Surface 生命周期与 EGL 窗口绑定：onCreate 绝不启动游戏，
 *     onSurfaceTextureAvailable 之后才触发；Surface 重建走 setupBridgeWindow 复用分支；
 *  ② 崩溃自恢复：交给 GameCrashWatchdog / GameCrashReceiver / GameProcessRebirth。
 * 触控按用户裁定（2026-11-08）采用 ZL2 的方式：手势语义 = ZL2 CLICK 指针模式
 * （TouchInput.kt CLICK 分支 + GameScreen MouseControlLayout 接线）移植到
 * startup-core 的 TouchGestureDispatcher（可单测）；输入事件经 CallbackBridge 的
 * GLFW 管道送达 MC（input_bridge_v3.c 注册）；键盘/鼠标按键映射复用 ZL2
 * game/input/ 六件（LWJGLCharSender 等，05 文档 §3.6「直接复用」）。
 * ZL2 的 Compose GameScreen、控制布局、插件、SDL Java 层、ViewModel 全部不复用
 * （依赖在 Vaelin 不存在，且 app 无 kotlinx-coroutines / AppCompat / ProcessPhoenix 依赖）。
 *
 * 载体用 TextureView + SurfaceTextureListener（与 ZL2 VMActivity.kt:1006-1022 一致），
 * 而非字面意义的 SurfaceView：ZL2 真正依赖的是 SurfaceTexture 的
 * available/sizeChanged/destroyed 回调与 setDefaultBufferSize，TextureView 才能提供。
 * 任务书里的「SurfaceView」按「承载 Surface 的 View」语义满足，报告里会说明。
 *
 * 时序等价：app 不能直接用 ZL2 的 lifecycleScope.launch(Dispatchers.Default) + awaitData，
 * 这里用后台 Thread + mainHandler.post 复刻「后台等待尺寸 → 主线程执行启动」的形状。
 *
 * R1 边界（显式声明，不伪装）：真正拉起 JVM 是 VaelinLauncher.launch（T1.11）。
 * 本 Activity 完成的是进程隔离 + Surface/EGL 时序骨架 + 输入注入闭环；
 * 启动序列到达 execute() 时只落日志与绑定窗口，不调用 VaelinLauncher.launch。
 */
class VMActivity : ComponentActivity(), TextureView.SurfaceTextureListener {

    companion object {
        private const val TAG = "VMActivity"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var gameTextureView: TextureView? = null
    private var gameSurface: Surface? = null

    /** 对应 ZL2 vmViewModel.isRunning：区分首次启动与 Surface 重建复用分支 */
    private var isRunning = false

    /** 对应 ZL2 mIsSurfaceDestroyed：销毁后阻塞尺寸回调，避免错位状态 */
    private var isSurfaceDestroyed = false

    /** 游戏渲染坐标系尺寸（R1 与视图 1:1；触控坐标按此比例从视图系换算到游戏系） */
    private var gameWidth = 0
    private var gameHeight = 0

    /**
     * 触控手势状态机（ZL2 CLICK 指针模式语义，见 TouchGestureDispatcher 注释）。
     * Sender 接线到 GLFW 管道：MC 经 input_bridge_v3.c 消费 CallbackBridge 的事件。
     */
    private val touchDispatcher = TouchGestureDispatcher(
        object : TouchGestureDispatcher.Sender {
            override fun cursorPos(x: Float, y: Float) {
                CallbackBridge.sendCursorPos(x, y)
            }

            override fun mouseButton(button: Int, pressed: Boolean) {
                CallbackBridge.sendMouseButton(button, pressed)
            }

            override fun tapAt(button: Int, x: Float, y: Float) {
                // ZL2 GameScreen onTap 同款：位置 + 按下 + 33ms 自动释放（CallbackBridge 内部）
                CallbackBridge.putMouseEventWithCoords(button, x, y)
            }
        },
    )

    private lateinit var params: GameLaunchContract.GameLaunchParams

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // :game 是独立进程，没有自定义 Application，必须自己初始化 VPaths/VLog，
        // 否则 VLog.kt:44-48 的 writer 为 null，日志不落盘（多进程各有一份 lateinit）。
        VPaths.init(applicationContext)
        VLog.initialize(applicationContext)
        GameCrashWatchdog.install(applicationContext)

        // 显式加载 native：JNI 符号是静态导出硬绑定，分属两个 .so ——
        //   libpojavexec.so     : input_bridge_v3.c 的 CallbackBridge JNI 符号（触控管道）
        //   libpojavexec_awt.so : awt_bridge.c:53 的 ZLBridge.sendInputData（cacio 备用管道）
        //   libexithook.so      : exit hook，ZLBridge.java:95-99 的 static 块也按此顺序加载
        // ZLBridge 类首次被引用时其 static 块会重放同一组 loadLibrary，JNI 层对已加载库是
        // no-op，不会重复加载。这里提前加载，保证 execute() 与触控路径在符号层面一定可用。
        // 加载失败不 catch：这是关键依赖缺失，显式抛出，由上面的 watchdog 广播崩溃提示
        // （团队准则：禁止静默降级）；VLog 已初始化，最后一行能落盘。
        VLog.info(TAG, "loading native libs for :game process")
        // 复用区（CallbackBridge 剪贴板 / ZLNativeInvoker）依赖的全局上下文：
        // 传 Activity 本身而不是 applicationContext，它们的 `as? Activity` 转换才能成立
        // （ZL2 语义：AbstractAppCompatActivity.onCreate 每次覆写为当前 Activity）。
        setGlobalContext(this)
        NativeLibraryLoader.loadExitHookLib()
        NativeLibraryLoader.loadPojavLib()
        NativeLibraryLoader.loadPojavAWTLib()
        VLog.info(TAG, "native libs loaded")

        // 契约缺失显式抛异常，由已安装的 watchdog 捕获并广播崩溃提示，禁止静默降级。
        params = GameLaunchContract.read(intent)
        VLog.info(
            TAG,
            "onCreate in process=${applicationInfo.processName} pid=${android.os.Process.myPid()} " +
                "version=${params.versionId} mc=${params.minecraftVersion} " +
                "renderer=${params.rendererId}/${params.rendererUniqueId} heap=${params.memoryMB}MB " +
                "extraJvmArgs=${params.extraJvmArgs} javaRuntime=${params.javaRuntime}",
        )

        // 前台服务：ZL2 VMActivity.kt:452-455 onCreate 也再拉一次，后台受限时忽略。
        runCatching { startService(Intent(this, GameService::class.java)) }
            .onFailure { VLog.warning(TAG, "startService(GameService) 被拒：${it.message}") }

        // 强制横屏（ZL2 VMActivity.kt:657-659 重写 setRequestedOrientation 的等价约束）。
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        window.apply {
            setBackgroundDrawable(ColorDrawable(Color.BLACK))
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        val texture = TextureView(this).apply {
            isOpaque = true
            alpha = 1.0f
            surfaceTextureListener = this@VMActivity
            setOnTouchListener { _, event -> handleTouch(event) }
        }.also { gameTextureView = it }

        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(
                    texture,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            },
        )
        VLog.info(TAG, "onCreate 完成：仅建 TextureView，未触发启动")
    }

    // ---------------------------------------------------------------- Surface 时序

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        val nativeSurface = Surface(surface)
        gameSurface = nativeSurface
        isSurfaceDestroyed = false
        VLog.info(TAG, "onSurfaceTextureAvailable ${width}x${height} isRunning=$isRunning")

        if (isRunning) {
            // ZL2 VMActivity.kt:911-917 窗口重建复用分支：重新绑定 EGL 窗口并刷新尺寸。
            // setupBridgeWindow 的 native 实现在 egl_bridge.c:84，pojav_environ 由
            // environ.c:11 的 constructor 初始化，故此处调用安全；失败显式记录不静默。
            runCatching { ZLBridge.setupBridgeWindow(nativeSurface) }
                .onFailure { t ->
                    VLog.error(TAG, "setupBridgeWindow 失败：${t.javaClass.name}: ${t.message}", t)
                }
            refreshWindowSize(width, height)
            return
        }

        isRunning = true
        // ZL2 VMActivity.kt:919-932：后台等尺寸 → 主线程 refreshWindowSize → execute。
        // R1 没有 Compose Box 测量，直接用回调给出的视图尺寸作为 screenSize。
        Thread {
            val size = awaitScreenSize(width, height)
            mainHandler.post {
                val finalSize = refreshWindowSize(size.first, size.second)
                execute(nativeSurface, finalSize.first, finalSize.second)
            }
        }.start()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        // ZL2 VMActivity.kt:935-938：销毁后不处理尺寸回调。
        if (isSurfaceDestroyed) return
        VLog.info(TAG, "onSurfaceTextureSizeChanged ${width}x${height}")
        refreshWindowSize(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        // ZL2 VMActivity.kt:940-951：释放 Surface、标记销毁。
        VLog.info(TAG, "onSurfaceTextureDestroyed")
        gameSurface?.release()
        gameSurface = null
        isSurfaceDestroyed = true
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        // ZL2 在此做放大镜取帧，R1 不需要。
    }

    /** ZL2 用 DataBridge.awaitData 等 Compose 测量；R1 用 TextureView 尺寸，保留扩展点。 */
    private fun awaitScreenSize(fallbackWidth: Int, fallbackHeight: Int): Pair<Int, Int> {
        val w = gameTextureView?.width?.takeIf { it > 0 } ?: fallbackWidth
        val h = gameTextureView?.height?.takeIf { it > 0 } ?: fallbackHeight
        return w to h
    }

    /** 对应 ZL2 VMActivity.kt:694-719 refreshWindowSize 的最小骨架。 */
    private fun refreshWindowSize(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width to height
        val size = computeGameRenderSize(width, height)
        gameWidth = size.first
        gameHeight = size.second
        gameTextureView?.surfaceTexture?.setDefaultBufferSize(size.first, size.second)
        // ZL2 VMActivity.kt:710 同款：GLFW 侧窗口尺寸的唯一来源（nativeSendScreenSize →
        // pojav_environ->savedWidth/savedHeight，影响 MC 的 GUI 缩放与光标坐标空间）。
        // ZL2 的 ZLBridgeStates.onWindowChange() 是其 Compose 层的刷新通知，Vaelin 无对应层，不需要。
        CallbackBridge.sendUpdateWindowSize(size.first, size.second)
        VLog.info(
            TAG,
            "refreshWindowSize view=${width}x${height} game=${size.first}x${size.second}",
        )
        return size
    }

    /** R1 做 1:1，不引入 ZL2 的自定义分辨率/letterbox；触控坐标换算用 handleTouch 里的比例式。 */
    private fun computeGameRenderSize(width: Int, height: Int): Pair<Int, Int> = width to height

    /**
     * 启动序列到达点（对应 ZL2 AbstractHandler.execute）。
     *
     * T1.11：窗口尺寸已就绪、参数已读、输入闭环已挂，这里在 :game 进程真正拉起 JVM。
     * VaelinLauncher.launch 会阻塞到游戏结束，所以必须另起后台线程（execute 由 mainHandler.post
     * 在主线程调用，直接调用会 ANR）。读主进程落盘的 LaunchPlan，失败则显式抛出，
     * 由 GameCrashWatchdog 捕获并弹崩溃提示，绝不静默回退。
     */
    private fun execute(surface: Surface, width: Int, height: Int) {
        VLog.info(
            TAG,
            "execute reached: game=${width}x${height} surface=${surface} " +
                "version=${params.versionId} renderer=${params.rendererId} " +
                "— 准备在 :game 进程拉起 JVM",
        )
        // ZL2 GameHandler.execute 的第一步（GameHandler.kt:94）：把 Surface 绑到 EGL 桥的
        // 全局窗口。缺了它 MC 的 GLFW 拿到 NULL 窗口，ANativeWindow_acquire 直接
        // SIGSEGV（真机实测，hs_err_pid996）。首启分支原来只在重建分支调用，已修正。
        runCatching { ZLBridge.setupBridgeWindow(surface) }
            .onSuccess { VLog.info(TAG, "setupBridgeWindow OK") }
            .onFailure { t ->
                VLog.error(TAG, "setupBridgeWindow 失败：${t.javaClass.name}: ${t.message}", t)
            }
        runCatching { SdlBridge.isSdlRenderActive() }
            .onSuccess { active ->
                VLog.info(TAG, "SdlBridge JNI 宿主可解析，isSdlRenderActive=$active（R1 不启用 SDL 渲染路径）")
            }
            .onFailure { t ->
                VLog.warning(TAG, "SdlBridge 探测失败（不影响输入闭环）：${t.javaClass.name}: ${t.message}")
            }
        // JVM 经 exit() 退出时进程随即被 System.exit 杀死，launchJVM 调用线程来不及
        // 返回，下面的「游戏退出」日志与 reportJvmExit 都不会执行——必须在拉起前挂
        // onJvmExit 钩子（ZL2 的 staticLauncher/onExit 同款模式），从 exit 链路里把
        // 退出码送回主进程。
        ZLNativeInvoker.onJvmExit = { exitCode, _ ->
            GameCrashWatchdog.reportJvmExit(this@VMActivity, exitCode, params.versionId)
        }
        Thread {
            val exitCode = LaunchCoordinator.launchFromGameProcess(
                context = this@VMActivity,
                versionId = params.versionId,
                screenWidth = width,
                screenHeight = height,
            )
            VLog.info(TAG, "游戏退出，JVM exitCode=$exitCode")
            mainHandler.post {
                // 副审 round5 M1：不能不看 exitCode 就 finish。非 0 说明 MC 自己崩了，
                // 必须先发崩溃广播让主进程弹提示，否则玩家只会看到画面一闪回到启动器。
                GameCrashWatchdog.reportJvmExit(this@VMActivity, exitCode, params.versionId)
                this@VMActivity.finish()
            }
        }.apply {
            name = "vaelin-jvm-${params.versionId}"
            start()
        }
    }

    // ---------------------------------------------------------------- 输入注入闭环

    /**
     * TextureView 触摸 → 视图坐标换算游戏坐标 → TouchGestureDispatcher（ZL2 CLICK 语义）。
     *
     * 用户裁定（2026-11-08）：触控直接采用 ZL2 的方式，不要自己写。本方法只做两件事：
     * MotionEvent 取值 + 坐标系换算（等价 ZL2 的 mapToGame；R1 视图与游戏 1:1，
     * 换算为恒等，但保留比例式以便未来引入自定义分辨率）；
     * 手势语义全部在 startup-core 的 TouchGestureDispatcher（可单测），
     * 事件经 Sender 落到 CallbackBridge 的 GLFW 管道（input_bridge_v3.c 注册）。
     *
     * 旧的自写 AWT 路径（ZLBridge.sendInputData，awt_bridge.c 管道）已弃用——
     * 它是 cacio/AWT 的输入管道，对 GLFW 游戏无效（交接文档 §4.5 根因）。
     * NativeInput.kt 保留在 startup-core 作为 cacio 备用路径，不再被本 Activity 引用。
     *
     * 多点触控：R1 只跟踪第一根指针（actionIndex 0），与 ZL2 的 activePointer 模型一致；
     * 其余指针事件交由系统（ZL2 中由控制布局等其它层消费）。
     */
    private fun handleTouch(event: MotionEvent): Boolean {
        // 物理鼠标点击走按键映射（ZL2 GameScreen onMouseButton 同款），
        // 否则右键/中键会被当成触屏 tap 处理成左键
        if (event.source and InputDevice.SOURCE_TOUCHSCREEN == 0 &&
            event.source and InputDevice.SOURCE_MOUSE != 0
        ) {
            return handlePhysicalMouseClick(event)
        }

        val viewWidth = gameTextureView?.width ?: 0
        val viewHeight = gameTextureView?.height ?: 0
        if (viewWidth <= 0 || viewHeight <= 0 || gameWidth <= 0 || gameHeight <= 0) {
            VLog.warning(TAG, "touch 到达但尺寸未就绪，跳过本次事件")
            return true
        }

        // 视图坐标 → 游戏坐标（ZL2 currentGameDisplayLayout.mapToGame 的比例式等价）
        val gameX = event.x * gameWidth / viewWidth
        val gameY = event.y * gameHeight / viewHeight

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> touchDispatcher.onDown(gameX, gameY)
            MotionEvent.ACTION_MOVE -> touchDispatcher.onMove(gameX, gameY)
            MotionEvent.ACTION_UP -> touchDispatcher.onUp(gameX, gameY)
            MotionEvent.ACTION_CANCEL -> touchDispatcher.onCancel()
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                // 多余指针：忽略（R1 单指针语义）
            }
            else -> return false
        }

        VLog.info(
            TAG,
            "touch action=${event.actionMasked} view=(${event.x}, ${event.y}) " +
                "-> game=($gameX, $gameY) viewSize=${viewWidth}x${viewHeight} " +
                "gameSize=${gameWidth}x${gameHeight}",
        )
        return true
    }

    /**
     * 物理鼠标（OTG/蓝牙）：悬停移动更新指针位置；滚轮透传 AXIS 值（ZL2 TouchInput.kt:676-684
     * 同款符号约定）；按键经 LWJGLCharSender.getMouseButton 映射
     * （左/右/中键 → GLFW_MOUSE_BUTTON_*，ZL2 GameScreen onMouseButton 同款）。
     * 触屏事件（SOURCE_TOUCHSCREEN）走 handleTouch，不进这条路径。
     * TextureView 全屏铺满，窗口相对坐标 ≈ 视图相对坐标。
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.source and InputDevice.SOURCE_CLASS_POINTER != 0 &&
            event.source and InputDevice.SOURCE_TOUCHSCREEN == 0
        ) {
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE -> {
                    val gameX = mapPointerX(event.x)
                    val gameY = mapPointerY(event.y)
                    if (gameX != null && gameY != null) {
                        CallbackBridge.sendCursorPos(gameX, gameY)
                        return true
                    }
                }
                MotionEvent.ACTION_SCROLL -> {
                    CallbackBridge.sendScroll(
                        event.getAxisValue(MotionEvent.AXIS_HSCROLL).toDouble(),
                        event.getAxisValue(MotionEvent.AXIS_VSCROLL).toDouble(),
                    )
                    return true
                }
            }
        }
        return super.onGenericMotionEvent(event)
    }

    /** 物理鼠标按下/抬起：actionButton → GLFW 按键号（LWJGLCharSender 映射表） */
    private fun handlePhysicalMouseClick(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN &&
            event.actionMasked != MotionEvent.ACTION_UP
        ) {
            return false
        }
        val button = LWJGLCharSender.getMouseButton(event.actionButton) ?: return true
        CallbackBridge.sendMouseButton(
            button.toInt(),
            event.actionMasked == MotionEvent.ACTION_DOWN,
        )
        return true
    }

    private fun mapPointerX(x: Float): Float? {
        val viewWidth = gameTextureView?.width ?: 0
        return if (viewWidth > 0 && gameWidth > 0) x * gameWidth / viewWidth else null
    }

    private fun mapPointerY(y: Float): Float? {
        val viewHeight = gameTextureView?.height ?: 0
        return if (viewHeight > 0 && gameHeight > 0) y * gameHeight / viewHeight else null
    }

    /**
     * 物理键盘基础路径：全部按键（除 BACK）交给 sendOther →
     * EfficientAndroidLWJGLKeycode.execKey，内部按 KeyEvent.action 发按下/抬起
     * （方向键/Tab/Enter/Backspace 都在其映射表内）。
     * BACK 不映射：保持系统返回语义（R1 退出游戏的路径）。
     * ZL2 的完整键盘栈（IME 文本注入、手柄、SDL 直通）依赖其控制布局与设置系统，R1 不引入。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isRunning && event.repeatCount == 0) {
            when (event.action) {
                KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP ->
                    if (event.keyCode != KeyEvent.KEYCODE_BACK) {
                        LWJGLCharSender.sendOther(event)
                        return true
                    }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        // 正常退出：标记后 GameService.onDestroy 不会发崩溃广播。
        GameCrashWatchdog.markNormalExit()
        ZLNativeInvoker.onJvmExit = null
        stopService(Intent(this, GameService::class.java))
        gameSurface?.release()
        gameSurface = null
        VLog.info(TAG, "onDestroy")
        super.onDestroy()
    }
}
