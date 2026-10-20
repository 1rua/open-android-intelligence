package com.openandroidintelligence.conversation.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * M3 ColorScheme 之外的语义状态色。
 *
 * Material 3 的角色表只有 error，没有 success / warning。界面里「已完成」「等待中」
 * 这类状态如果借用 primary / tertiary，就会和主题色混在一起：用户无法只凭颜色判断
 * 这是"强调"还是"结果"。这里补齐两个角色，浅色与深色共享同一套语义。
 *
 * 状态色**不跟随主题预设**：换一套主题色不应该改变"成功是绿色、等待是琥珀色"的
 * 约定，否则同一份数据在不同主题下含义会漂移。两套值都保证正文级对比度（≥ 4.5:1）。
 *
 * 约束：
 * 1. 颜色不是唯一信号。任何使用状态色的地方都必须同时给出文字或图标。
 * 2. 只允许通过 [LocalAppStateColors] 读取，禁止在界面里写死色值。
 */
@Immutable
data class AppStateColors(
    /** 成功 / 已完成：正文级对比度。 */
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    /** 等待 / 需要确认 / 结果未知：正文级对比度。 */
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
)

val LightAppStateColors = AppStateColors(
    success = Color(0xFF2E6B4F),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFD3E7D9),
    onSuccessContainer = Color(0xFF14361F),
    warning = Color(0xFF8A5A16),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFF5E3C3),
    onWarningContainer = Color(0xFF3B2A08),
)

val DarkAppStateColors = AppStateColors(
    success = Color(0xFF8FD3AE),
    onSuccess = Color(0xFF0A2A19),
    successContainer = Color(0xFF1E3A2C),
    onSuccessContainer = Color(0xFFC7EBD8),
    warning = Color(0xFFE7BE7E),
    onWarning = Color(0xFF3B2A08),
    warningContainer = Color(0xFF453314),
    onWarningContainer = Color(0xFFF7E4C2),
)

/**
 * 当前主题下的状态色。
 *
 * 默认值取浅色：Composable 预览与独立的界面测试可以在没有主题包裹时仍然取到合法色值，
 * 而不是崩溃或拿到透明色。
 */
val LocalAppStateColors = staticCompositionLocalOf { LightAppStateColors }
