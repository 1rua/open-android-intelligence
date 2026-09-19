package com.openandroidintelligence.conversation.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.rememberSystemMotionPolicy

/** 系统动态取色（Monet）自 Android 12（API 31）起提供。 */
fun supportsDynamicColor(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * 品牌降级配色（浅色）。
 *
 * 只有在设备拿不到系统动态取色时才会用到它。这里必须把 M3 的色角色一次性定义完整，
 * 尤其是 5 级 surface container 层级 —— 界面靠这些层级表达容器深浅，
 * 缺一个角色就会退化成「大家都用同一个灰」，或者逼业务代码回去写死颜色。
 */
val BrandLightColorScheme = lightColorScheme(
    primary = AppColors.LightPrimary,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = AppColors.LightSurfaceHigh,
    onPrimaryContainer = Color(0xFF174C44),
    secondary = Color(0xFF50645A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = AppColors.LightSurface,
    onSecondaryContainer = AppColors.LightText,
    tertiary = AppColors.LightAccent,
    onTertiary = Color(0xFF201505),
    tertiaryContainer = Color(0xFFF0DFC8),
    onTertiaryContainer = Color(0xFF35240E),
    background = AppColors.LightCanvas,
    onBackground = AppColors.LightText,
    surface = AppColors.LightCanvas,
    onSurface = AppColors.LightText,
    surfaceVariant = AppColors.LightSurfaceHigh,
    onSurfaceVariant = AppColors.LightMuted,
    surfaceDim = Color(0xFFD5DDD6),
    surfaceBright = AppColors.LightCanvas,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEBF0EB),
    surfaceContainer = AppColors.LightSurface,
    surfaceContainerHigh = AppColors.LightSurfaceHigh,
    surfaceContainerHighest = Color(0xFFC9D8D0),
    outline = Color(0xFF6E7C75),
    outlineVariant = Color(0xFFBDC9C1),
    scrim = Color(0xFF000000),
    error = AppColors.LightError,
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD3),
    onErrorContainer = Color(0xFF3D0E08),
    inverseSurface = Color(0xFF2C3630),
    inverseOnSurface = AppColors.DarkText,
    inversePrimary = AppColors.DarkPrimary,
    surfaceTint = AppColors.LightPrimary,
)

/** 品牌降级配色（深色），与浅色共享完全相同的语义角色。 */
val BrandDarkColorScheme = darkColorScheme(
    primary = AppColors.DarkPrimary,
    onPrimary = Color(0xFF0A332B),
    primaryContainer = Color(0xFF204C42),
    onPrimaryContainer = Color(0xFFBAE7D9),
    secondary = Color(0xFFB2CBBE),
    onSecondary = Color(0xFF233A30),
    secondaryContainer = AppColors.DarkSurfaceHigh,
    onSecondaryContainer = AppColors.DarkText,
    tertiary = AppColors.DarkAccent,
    onTertiary = Color(0xFF35240E),
    tertiaryContainer = Color(0xFF554025),
    onTertiaryContainer = Color(0xFFF0DFC8),
    background = AppColors.DarkCanvas,
    onBackground = AppColors.DarkText,
    surface = AppColors.DarkCanvas,
    onSurface = AppColors.DarkText,
    surfaceVariant = AppColors.DarkSurfaceHigh,
    onSurfaceVariant = AppColors.DarkMuted,
    surfaceDim = AppColors.DarkCanvas,
    surfaceBright = Color(0xFF35413A),
    surfaceContainerLowest = Color(0xFF0B110E),
    surfaceContainerLow = Color(0xFF151D19),
    surfaceContainer = AppColors.DarkSurface,
    surfaceContainerHigh = AppColors.DarkSurfaceHigh,
    surfaceContainerHighest = Color(0xFF324039),
    outline = Color(0xFF889A90),
    outlineVariant = Color(0xFF45574C),
    scrim = Color(0xFF000000),
    error = AppColors.DarkError,
    onError = Color(0xFF3D0E08),
    errorContainer = Color(0xFF64382F),
    onErrorContainer = Color(0xFFFFDAD3),
    inverseSurface = AppColors.LightSurface,
    inverseOnSurface = AppColors.LightText,
    inversePrimary = AppColors.LightPrimary,
    surfaceTint = AppColors.DarkPrimary,
)

/** M3 形状令牌：容器越大圆角越大。 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(AppRadius.ExtraSmall),
    small = RoundedCornerShape(AppRadius.Small),
    medium = RoundedCornerShape(AppRadius.Medium),
    large = RoundedCornerShape(AppRadius.Large),
    extraLarge = RoundedCornerShape(AppRadius.ExtraLarge),
)

/**
 * 主题桥接层。
 *
 * Android 12+ 默认走系统动态取色（[dynamicLightColorScheme] / [dynamicDarkColorScheme]）；
 * 拿不到动态取色时降级到 [BrandLightColorScheme] / [BrandDarkColorScheme]。
 * 无论走哪条路，业务组件看到的都只是一套完整的 M3 语义角色。
 */
@Composable
fun OpenAndroidIntelligenceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = supportsDynamicColor(),
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val dynamicAvailable = dynamicColor && supportsDynamicColor()
    val scheme = when {
        dynamicAvailable && darkTheme -> dynamicDarkColorScheme(context)
        dynamicAvailable -> dynamicLightColorScheme(context)
        darkTheme -> BrandDarkColorScheme
        else -> BrandLightColorScheme
    }
    val policy = rememberSystemMotionPolicy(reduceMotion)
    CompositionLocalProvider(LocalMotionPolicy provides policy) {
        MaterialTheme(
            colorScheme = scheme,
            typography = Typography(),
            shapes = AppShapes,
            content = content,
        )
    }
}
