package com.openandroidintelligence.conversation.theme

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.rememberSystemMotionPolicy

/** 系统动态取色（Monet）自 Android 12（API 31）起提供。 */
fun supportsDynamicColor(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * 主题桥接层。
 *
 * 配色来源有两条，优先级从高到低：
 * 1. **系统动态取色（默认）**：`dynamicLightColorScheme` / `dynamicDarkColorScheme`，
 *    由壁纸推导，这是 Material You 的原生形态；不自行计算壁纸颜色。
 * 2. **预设主题色**：用户在「外观」里关闭动态取色后，从 [ThemePalette] 里选一套。
 *    每套都是按 M3 色调映射从种子色推导的完整角色表，不是手写散值。
 *
 * 深浅切换时逐角色插值，避免"白闪一下"或颜色硬切；开启减少动态时直接落到目标值。
 */
@Composable
fun OpenAndroidIntelligenceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = supportsDynamicColor(),
    palette: ThemePalette = ThemePalette.INDIGO,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dynamicAvailable = dynamicColor && supportsDynamicColor()
    val target = when {
        dynamicAvailable && darkTheme -> dynamicDarkColorScheme(context)
        dynamicAvailable -> dynamicLightColorScheme(context)
        darkTheme -> palette.darkScheme()
        else -> palette.lightScheme()
    }
    val policy = rememberSystemMotionPolicy(reduceMotion)
    val colorSpec: AnimationSpec<Color> =
        if (policy.reduceMotion || policy.durationScale == 0f) {
            snap()
        } else {
            tween(durationMillis = MotionSpecs.Standard, easing = MotionSpecs.StandardEasing)
        }
    val scheme = animatedColorScheme(target, colorSpec)
    val stateColors = if (darkTheme) DarkAppStateColors else LightAppStateColors
    CompositionLocalProvider(
        LocalMotionPolicy provides policy,
        LocalAppStateColors provides stateColors,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

/** M3 形状令牌：容器越大圆角越大。 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(AppRadius.ExtraSmall),
    small = RoundedCornerShape(AppRadius.Small),
    medium = RoundedCornerShape(AppRadius.Medium),
    large = RoundedCornerShape(AppRadius.Large),
    extraLarge = RoundedCornerShape(AppRadius.ExtraLarge),
)

@Composable
private fun role(target: Color, spec: AnimationSpec<Color>): Color =
    animateColorAsState(targetValue = target, animationSpec = spec, label = "color-role").value

/**
 * 逐角色插值当前配色。
 *
 * 使用 [animateColorAsState] 而不是整棵子树 Crossfade：后者会让两个版本的界面同时挂载
 * （重复的组合、重复的副作用、重复的无障碍节点），而这里只是在同一棵树上推进颜色值，
 * 布局与状态完全不受影响。
 */
@Composable
private fun animatedColorScheme(target: ColorScheme, spec: AnimationSpec<Color>): ColorScheme =
    target.copy(
        primary = role(target.primary, spec),
        onPrimary = role(target.onPrimary, spec),
        primaryContainer = role(target.primaryContainer, spec),
        onPrimaryContainer = role(target.onPrimaryContainer, spec),
        inversePrimary = role(target.inversePrimary, spec),
        secondary = role(target.secondary, spec),
        onSecondary = role(target.onSecondary, spec),
        secondaryContainer = role(target.secondaryContainer, spec),
        onSecondaryContainer = role(target.onSecondaryContainer, spec),
        tertiary = role(target.tertiary, spec),
        onTertiary = role(target.onTertiary, spec),
        tertiaryContainer = role(target.tertiaryContainer, spec),
        onTertiaryContainer = role(target.onTertiaryContainer, spec),
        background = role(target.background, spec),
        onBackground = role(target.onBackground, spec),
        surface = role(target.surface, spec),
        onSurface = role(target.onSurface, spec),
        surfaceVariant = role(target.surfaceVariant, spec),
        onSurfaceVariant = role(target.onSurfaceVariant, spec),
        surfaceTint = role(target.surfaceTint, spec),
        inverseSurface = role(target.inverseSurface, spec),
        inverseOnSurface = role(target.inverseOnSurface, spec),
        error = role(target.error, spec),
        onError = role(target.onError, spec),
        errorContainer = role(target.errorContainer, spec),
        onErrorContainer = role(target.onErrorContainer, spec),
        outline = role(target.outline, spec),
        outlineVariant = role(target.outlineVariant, spec),
        scrim = role(target.scrim, spec),
        surfaceBright = role(target.surfaceBright, spec),
        surfaceContainer = role(target.surfaceContainer, spec),
        surfaceContainerHigh = role(target.surfaceContainerHigh, spec),
        surfaceContainerHighest = role(target.surfaceContainerHighest, spec),
        surfaceContainerLow = role(target.surfaceContainerLow, spec),
        surfaceContainerLowest = role(target.surfaceContainerLowest, spec),
        surfaceDim = role(target.surfaceDim, spec),
    )
