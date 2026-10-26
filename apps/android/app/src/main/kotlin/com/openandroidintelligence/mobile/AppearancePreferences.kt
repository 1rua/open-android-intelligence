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
 * 首次启动默认关闭动态取色（Monet），默认启用应用图标同款配色（EMBER 炽橙）。
 * 用户可以在「外观」设置或引导页中开启动态取色以跟随系统壁纸；也可以
 * 直接从预设色板中选择一套。
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
    val dynamicColor: Boolean = false,
    val dynamicIconColor: Boolean = false,
    val palette: ThemePalette = ThemePalette.EMBER,
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
        // 首次启动默认不打开动态取色，默认使用图标同款配色（EMBER）；用户显式更改过则读回配置。
        dynamicColor = preferences.getBoolean(KEY_DYNAMIC_COLOR, false),
        dynamicIconColor = preferences.getBoolean(KEY_DYNAMIC_ICON_COLOR, false),
        palette = preferences.getString(KEY_PALETTE, ThemePalette.EMBER.name)
            ?.let { value -> runCatching { ThemePalette.valueOf(value) }.getOrNull() }
            ?: ThemePalette.EMBER,
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
