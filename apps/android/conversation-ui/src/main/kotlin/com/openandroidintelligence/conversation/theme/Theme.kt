package com.openandroidintelligence.conversation.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.rememberSystemMotionPolicy

val MossLightColorScheme = lightColorScheme(
    primary = AppColors.LightPrimary, onPrimary = Color.White,
    primaryContainer = AppColors.LightSurfaceHigh, onPrimaryContainer = Color(0xFF174C44),
    secondary = Color(0xFF50645A), onSecondary = Color.White,
    secondaryContainer = AppColors.LightSurface, onSecondaryContainer = AppColors.LightText,
    tertiary = Color(0xFF75552E), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF0DFC8), onTertiaryContainer = Color(0xFF35240E),
    background = AppColors.LightCanvas, onBackground = AppColors.LightText,
    surface = AppColors.LightCanvas, onSurface = AppColors.LightText,
    surfaceDim = Color(0xFFD5DDD6), surfaceBright = AppColors.LightCanvas,
    surfaceContainerLowest = Color(0xFFF8FAF7), surfaceContainerLow = Color(0xFFEBF0EB),
    surfaceContainer = AppColors.LightSurface, surfaceContainerHigh = AppColors.LightSurfaceHigh,
    surfaceContainerHighest = Color(0xFFC9D8D0),
    surfaceVariant = AppColors.LightSurfaceHigh, onSurfaceVariant = AppColors.LightMuted,
    outline = Color(0xFF6E7C75), outlineVariant = Color(0xFFBDC9C1),
    error = AppColors.LightError, onError = Color.White,
    errorContainer = Color(0xFFFFDAD3), onErrorContainer = Color(0xFF3D0E08),
    inverseSurface = Color(0xFF2C3630), inverseOnSurface = AppColors.DarkText,
    inversePrimary = AppColors.DarkPrimary, surfaceTint = AppColors.LightPrimary,
)

val MossDarkColorScheme = darkColorScheme(
    primary = AppColors.DarkPrimary, onPrimary = Color(0xFF0A332B),
    primaryContainer = Color(0xFF204C42), onPrimaryContainer = Color(0xFFBAE7D9),
    secondary = Color(0xFFB2CBBE), onSecondary = Color(0xFF233A30),
    secondaryContainer = AppColors.DarkSurfaceHigh, onSecondaryContainer = AppColors.DarkText,
    tertiary = AppColors.DarkAccent, onTertiary = Color(0xFF35240E),
    tertiaryContainer = Color(0xFF554025), onTertiaryContainer = Color(0xFFF0DFC8),
    background = AppColors.DarkCanvas, onBackground = AppColors.DarkText,
    surface = AppColors.DarkCanvas, onSurface = AppColors.DarkText,
    surfaceDim = AppColors.DarkCanvas, surfaceBright = Color(0xFF35413A),
    surfaceContainerLowest = Color(0xFF0B110E), surfaceContainerLow = Color(0xFF151D19),
    surfaceContainer = AppColors.DarkSurface, surfaceContainerHigh = AppColors.DarkSurfaceHigh,
    surfaceContainerHighest = Color(0xFF324039),
    surfaceVariant = AppColors.DarkSurfaceHigh, onSurfaceVariant = AppColors.DarkMuted,
    outline = Color(0xFF889A90), outlineVariant = Color(0xFF45574C),
    error = AppColors.DarkError, onError = Color(0xFF3D0E08),
    errorContainer = Color(0xFF64382F), onErrorContainer = Color(0xFFFFDAD3),
    inverseSurface = AppColors.LightSurface, inverseOnSurface = AppColors.LightText,
    inversePrimary = AppColors.LightPrimary, surfaceTint = AppColors.DarkPrimary,
)

private val MossShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** 品牌默认值跟随深浅模式；动态取色是明确选择的官方平台变体。 */
@Composable
fun OpenAndroidIntelligenceTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    reduceMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor -> dynamicLightColorScheme(context)
        darkTheme -> MossDarkColorScheme
        else -> MossLightColorScheme
    }
    val policy = rememberSystemMotionPolicy(reduceMotion)
    CompositionLocalProvider(LocalMotionPolicy provides policy) {
        MaterialTheme(colorScheme = scheme, typography = Typography(), shapes = MossShapes, content = content)
    }
}
