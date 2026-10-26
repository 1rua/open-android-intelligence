package com.openandroidintelligence.mobile

import android.content.Context
import com.openandroidintelligence.conversation.theme.ThemePalette
import com.openandroidintelligence.conversation.theme.supportsDynamicColor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User controlled appearance settings for the host shell.
 *
 * These values are deliberately kept separate from Gateway credentials and
 * pairing state.  A preference is published only after the durable write has
 * succeeded, so a recreated Activity observes the same value that the next
 * process would read.
 */
enum class ThemePreference {
    SYSTEM,
    LIGHT,
    DARK,
}

/**
 * 本机外观偏好。
 *
 * 动态取色（Monet）**默认开启**（Android 12+）：默认跟随系统壁纸取色，与 Material 3
 * 的平台默认形态一致。用户可以在「外观与动效」里关掉它，回落到本机预设色板；也可以
 * 直接从预设色板中选择一套（色板选择入口由主题层统一提供，本文件只负责偏好持久化）。
 * 代码不自行计算壁纸色，只调用 Android 官方的 `dynamicLightColorScheme` /
 * `dynamicDarkColorScheme`。
 *
 * 注意 [AppearancePreferences.read] 只在用户从未保存过动态取色偏好时才使用这里的默认值，
 * 已经落盘的显式选择（无论 true/false）一律保持原样。
 *
 * [palette] 只在动态取色关闭时生效：用户关掉 Monet 后，界面回落到选中的预设色板。
 * 选择预设会同时关闭动态取色（见 [selectPalette]），避免"选了却看不到"。
 */
data class AppearanceSettings(
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val dynamicColor: Boolean = supportsDynamicColor(),
    val dynamicIconColor: Boolean = supportsDynamicColor(),
    val palette: ThemePalette = ThemePalette.INDIGO,
    val reduceMotion: Boolean = false,
)

class AppearancePreferences(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppearanceSettings> = _settings.asStateFlow()

    init {
        LauncherIconManager.applyDynamicIconPreference(appContext, _settings.value.dynamicIconColor)
    }

    fun setTheme(theme: ThemePreference) {
        persist(_settings.value.copy(theme = theme))
    }

    fun setDynamicColor(enabled: Boolean) {
        persist(_settings.value.copy(dynamicColor = enabled))
    }

    fun setDynamicIconColor(enabled: Boolean) {
        persist(_settings.value.copy(dynamicIconColor = enabled))
        LauncherIconManager.applyDynamicIconPreference(appContext, enabled)
    }

    /** 选择一套预设色板：同时关闭动态取色，选中的方案必须立刻可见。 */
    fun selectPalette(palette: ThemePalette) {
        persist(_settings.value.copy(palette = palette, dynamicColor = false))
    }

    /** 只保存色板（不改变动态取色开关）；设置页的预置行与测试使用。 */
    fun setPalette(palette: ThemePalette) {
        persist(_settings.value.copy(palette = palette))
    }

    fun setReduceMotion(enabled: Boolean) {
        persist(_settings.value.copy(reduceMotion = enabled))
    }

    private fun read(): AppearanceSettings = AppearanceSettings(
        theme = preferences.getString(KEY_THEME, ThemePreference.SYSTEM.name)
            ?.let { value -> runCatching { ThemePreference.valueOf(value) }.getOrNull() }
            ?: ThemePreference.SYSTEM,
        // 默认跟随系统动态取色（Android 12+）；用户显式关闭过则读回 false。
        dynamicColor = preferences.getBoolean(KEY_DYNAMIC_COLOR, supportsDynamicColor()),
        dynamicIconColor = preferences.getBoolean(KEY_DYNAMIC_ICON_COLOR, supportsDynamicColor()),
        palette = preferences.getString(KEY_PALETTE, ThemePalette.INDIGO.name)
            ?.let { value -> runCatching { ThemePalette.valueOf(value) }.getOrNull() }
            ?: ThemePalette.INDIGO,
        reduceMotion = preferences.getBoolean(KEY_REDUCE_MOTION, false),
    )

    private fun persist(next: AppearanceSettings) {
        check(
            preferences.edit()
                .putString(KEY_THEME, next.theme.name)
                .putBoolean(KEY_DYNAMIC_COLOR, next.dynamicColor)
                .putBoolean(KEY_DYNAMIC_ICON_COLOR, next.dynamicIconColor)
                .putBoolean(KEY_REDUCE_MOTION, next.reduceMotion)
                .putString(KEY_PALETTE, next.palette.name)
                .commit(),
        ) { "APPEARANCE_PREFERENCE_PERSISTENCE_FAILED" }
        _settings.value = next
    }

    private companion object {
        const val PREFERENCES_NAME = "open_android_intelligence_appearance"
        const val KEY_THEME = "theme"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val KEY_DYNAMIC_ICON_COLOR = "dynamic_icon_color"
        const val KEY_REDUCE_MOTION = "reduce_motion"
        const val KEY_PALETTE = "palette"
    }
}
