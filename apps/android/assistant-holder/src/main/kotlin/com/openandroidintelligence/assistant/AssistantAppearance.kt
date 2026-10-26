package com.openandroidintelligence.assistant

import android.content.Context
import android.content.res.Configuration
import com.openandroidintelligence.conversation.theme.ThemePalette

/**
 * 助理进程使用的外观偏好。
 *
 * **主路径是主进程经既有状态通道下发的字段**（见 `AssistantBridgeService.publish`
 * 的 `appearance` 对象）：跨进程读 SharedPreferences 不可靠 —— 对方进程写完后，
 * 本进程内存里的缓存不会失效，助理会一直沿用旧主题直到进程被杀。
 *
 * [readFallbackAppearance] 只在拿不到桥接状态时（尚未 connect、旧版主进程不带该字段）
 * 兜底读一次本地偏好；一旦桥接状态到达就以桥接字段为准。
 */
internal data class AssistantAppearance(
    /** 主进程保存的模式名：SYSTEM / LIGHT / DARK。 */
    val theme: String,
    val dynamicColor: Boolean,
    /** 预设色板名（ThemePalette 枚举名），非法或缺失时回落到靛蓝。 */
    val palette: String,
    val reduceMotion: Boolean,
) {
    fun darkTheme(context: Context): Boolean = when (theme) {
        THEME_LIGHT -> false
        THEME_DARK -> true
        else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
    }

    fun resolvedPalette(): ThemePalette =
        runCatching { ThemePalette.valueOf(palette) }.getOrNull() ?: ThemePalette.EMBER
}

/** 兜底读取：只在没有桥接状态时使用，语义是"上次保存的偏好"而不是实时值。 */
internal fun readFallbackAppearance(context: Context): AssistantAppearance {
    val prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    return AssistantAppearance(
        theme = prefs.getString(KEY_THEME, THEME_SYSTEM) ?: THEME_SYSTEM,
        dynamicColor = prefs.getBoolean(KEY_DYNAMIC_COLOR, false),
        palette = prefs.getString(KEY_PALETTE, ThemePalette.EMBER.name) ?: ThemePalette.EMBER.name,
        reduceMotion = prefs.getBoolean(KEY_REDUCE_MOTION, false),
    )
}

internal const val THEME_SYSTEM = "SYSTEM"
internal const val THEME_LIGHT = "LIGHT"
internal const val THEME_DARK = "DARK"

private const val PREFERENCES_NAME = "open_android_intelligence_appearance"
private const val KEY_THEME = "theme"
private const val KEY_DYNAMIC_COLOR = "dynamic_color"
private const val KEY_REDUCE_MOTION = "reduce_motion"
private const val KEY_PALETTE = "palette"
