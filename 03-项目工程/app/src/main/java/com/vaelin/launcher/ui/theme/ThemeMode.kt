package com.vaelin.launcher.ui.theme

import android.content.Context
import androidx.compose.runtime.saveable.Saver

/**
 * 三态主题。OLED 与 DARK 是两个独立档位：
 * OLED 把背景与容器全部压到纯黑（#000000），DARK 用 Material 的标准深灰。
 */
enum class ThemeMode {
    LIGHT,
    DARK,
    OLED;

    fun displayName(): String = when (this) {
        LIGHT -> "浅色"
        DARK -> "深色"
        OLED -> "纯黑"
    }

    fun next(): ThemeMode = when (this) {
        LIGHT -> DARK
        DARK -> OLED
        OLED -> LIGHT
    }
}

/**
 * 主题持久化。刻意做成「普通类 + 构造时拿 Context」，不是全局可变单例：
 * 主题状态的唯一真相是 ViewModel 里的 mutableStateOf，这里只管存取。
 *
 * 用 SharedPreferences 而不是 startup-core 的 settings.json：
 * 主题是纯 UI 偏好，随卸载丢失无所谓，且必须在 Compose 首帧之前就能同步读到。
 */
class ThemeModeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): ThemeMode {
        val saved = prefs.getString(KEY_THEME_MODE, null) ?: return DEFAULT
        return ThemeMode.entries.firstOrNull { it.name == saved } ?: DEFAULT
    }

    fun save(mode: ThemeMode) {
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    companion object {
        private const val PREFS_NAME = "vaelin_ui"
        private const val KEY_THEME_MODE = "theme_mode"

        /** 默认深色：游戏启动器的主色场景是竖屏外的暗色环境，浅色作为可选项 */
        val DEFAULT: ThemeMode = ThemeMode.DARK
    }
}

/** 让路由/主题这类状态能在进程重建后恢复，存枚举名而不是 ordinal（顺序会漂移） */
val ThemeModeSaver: Saver<ThemeMode, String> = Saver(
    save = { mode -> mode.name },
    restore = { raw -> ThemeMode.entries.firstOrNull { it.name == raw } ?: ThemeModeStore.DEFAULT },
)
