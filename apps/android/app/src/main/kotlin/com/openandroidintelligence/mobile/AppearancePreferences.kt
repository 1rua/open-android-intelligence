package com.openandroidintelligence.mobile

import android.content.Context
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

data class AppearanceSettings(
    val theme: ThemePreference = ThemePreference.SYSTEM,
    val dynamicColor: Boolean = false,
    val reduceMotion: Boolean = false,
)

class AppearancePreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppearanceSettings> = _settings.asStateFlow()

    fun setTheme(theme: ThemePreference) {
        persist(_settings.value.copy(theme = theme))
    }

    fun setDynamicColor(enabled: Boolean) {
        persist(_settings.value.copy(dynamicColor = enabled))
    }

    fun setReduceMotion(enabled: Boolean) {
        persist(_settings.value.copy(reduceMotion = enabled))
    }

    private fun read(): AppearanceSettings = AppearanceSettings(
        theme = preferences.getString(KEY_THEME, ThemePreference.SYSTEM.name)
            ?.let { value -> runCatching { ThemePreference.valueOf(value) }.getOrNull() }
            ?: ThemePreference.SYSTEM,
        dynamicColor = preferences.getBoolean(KEY_DYNAMIC_COLOR, false),
        reduceMotion = preferences.getBoolean(KEY_REDUCE_MOTION, false),
    )

    private fun persist(next: AppearanceSettings) {
        check(
            preferences.edit()
                .putString(KEY_THEME, next.theme.name)
                .putBoolean(KEY_DYNAMIC_COLOR, next.dynamicColor)
                .putBoolean(KEY_REDUCE_MOTION, next.reduceMotion)
                .commit(),
        ) { "APPEARANCE_PREFERENCE_PERSISTENCE_FAILED" }
        _settings.value = next
    }

    private companion object {
        const val PREFERENCES_NAME = "open_android_intelligence_appearance"
        const val KEY_THEME = "theme"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
        const val KEY_REDUCE_MOTION = "reduce_motion"
    }
}
