package com.openandroidintelligence.mobile

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color as AndroidColor
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/**
 * 系统栏（状态栏 / 导航栏）图标明暗跟随**应用内主题**，而不是仅跟随系统夜间模式。
 *
 * 为什么需要这一层：`themes.xml` 里写死 `windowLightStatusBar=true` 时，
 * 用户把应用强制切成深色、但系统仍是浅色，状态栏图标依旧是深色 —— 压在深色
 * 画布上等于不可读。`enableEdgeToEdge()` 无参调用同样只看系统配置，解决不了这个组合。
 *
 * 因此这里把「当前是否深色」作为唯一输入：浅色主题 → 深色图标，深色主题 → 浅色图标，
 * 两条栏都保持透明（edge-to-edge 由 `enableEdgeToEdge` 负责），内容自己用 inset 避让。
 *
 * 只在主题变化时调用（`LaunchedEffect(isDark)`），避免每次重组都改一次窗口标志位。
 */
internal fun ComponentActivity.applySystemBarContrast(isDark: Boolean) {
    val style = SystemBarStyle.auto(
        lightScrim = AndroidColor.TRANSPARENT,
        darkScrim = AndroidColor.TRANSPARENT,
    ) { isDark }
    enableEdgeToEdge(
        statusBarStyle = style,
        navigationBarStyle = style,
    )
}

/**
 * 外观偏好 + 系统配置合成「当前是否深色」。
 *
 * 主界面与系统气泡窗口共用同一份判定，保证两个 Activity 的深浅与系统栏图标一致：
 * `LIGHT` / `DARK` 为用户的显式选择，`SYSTEM` 才回落到系统夜间模式。
 */
internal fun Context.appearanceIsDark(settings: AppearanceSettings): Boolean = when (settings.theme) {
    ThemePreference.LIGHT -> false
    ThemePreference.DARK -> true
    ThemePreference.SYSTEM ->
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}
