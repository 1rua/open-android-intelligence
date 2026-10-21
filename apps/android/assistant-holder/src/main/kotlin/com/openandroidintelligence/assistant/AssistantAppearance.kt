package com.openandroidintelligence.assistant

import android.content.Context
import android.content.res.Configuration
import com.openandroidintelligence.conversation.theme.ThemePalette
import com.openandroidintelligence.conversation.theme.supportsDynamicColor

/**
 * 助理进程读到的外观偏好。
 *
 * 助理会话与 ASSIST 兜底页跑在 `:assistant_session` 进程，和主进程是同一个应用、
 * 同一份数据目录，所以直接读主进程写的 SharedPreferences（只读，不参与跨进程写入
 * 协商）。以前这里硬编码 `isSystemInDarkTheme()` + 动态取色，结果用户在 App 里
 * 强制浅色/深色、换预设色板、或打开「减弱动态效果」后，助理界面完全不跟随。
 *
 * 读不到（首次安装、旧版本没有这些键）时回落到与 App 默认值一致的策略。
 */
internal data class AssistantAppearance(
    val darkTheme: Boolean,
    val dynamicColor: Boolean,
    val palette: ThemePalette,
    val reduceMotion: Boolean,
)

internal fun readAssistantAppearance(context: Context): AssistantAppearance {
    val prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    val systemNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    val darkTheme = when (prefs.getString(KEY_THEME, THEME_SYSTEM)) {
        THEME_LIGHT -> false
        THEME_DARK -> true
        else -> systemNight
    }
    val palette = prefs.getString(KEY_PALETTE, null)
        ?.let { name -> runCatching { ThemePalette.valueOf(name) }.getOrNull() }
        ?: ThemePalette.INDIGO
    return AssistantAppearance(
        darkTheme = darkTheme,
        dynamicColor = prefs.getBoolean(KEY_DYNAMIC_COLOR, supportsDynamicColor()),
        palette = palette,
        reduceMotion = prefs.getBoolean(KEY_REDUCE_MOTION, false),
    )
}

// 与 app 模块 AppearancePreferences 的存储键保持一致；两边同时改才能生效，
// 但助理进程只读不写，缺失时不会破坏主进程的偏好。
private const val PREFERENCES_NAME = "open_android_intelligence_appearance"
private const val KEY_THEME = "theme"
private const val KEY_DYNAMIC_COLOR = "dynamic_color"
private const val KEY_REDUCE_MOTION = "reduce_motion"
private const val KEY_PALETTE = "palette"
private const val THEME_SYSTEM = "SYSTEM"
private const val THEME_LIGHT = "LIGHT"
private const val THEME_DARK = "DARK"
