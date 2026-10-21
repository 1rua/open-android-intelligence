package com.openandroidintelligence.conversation.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 可选主题色预设。
 *
 * 产品的默认外观是**系统动态取色（Monet）**：壁纸决定配色，这是 Material You 的原生形态。
 * 当用户不想跟随壁纸时，可以从这里的几套预设里挑一个 —— 每套都是按 Material 3 的
 * 色调映射（tone 40/90/10、tone 80/30/90）从同一个种子色推导出来的完整角色表，
 * 而不是手写几十个散值。
 *
 * 命名只描述颜色本身，不绑定任何"品牌绿"：预设由色相与色度定义，界面里只出现
 * `MaterialTheme.colorScheme` 的语义角色。
 *
 * @param hue 主色色相（CIE LCh，0..360）。
 * @param chroma 主色色度（0..约 60，越大越鲜艳）。
 * @param tertiaryHue 第三个强调色的色相；默认在主色基础上错开 60°。
 * @param neutralChroma 中性表面带的色度（很小，让灰面略微倾向主色）。
 */
enum class ThemePalette(
    val label: String,
    val description: String,
    val hue: Double,
    val chroma: Double,
    val neutralChroma: Double = 2.4,
    val tertiaryHue: Double = (hue + 60.0) % 360.0,
) {
    /** 靛蓝：沉稳的蓝紫，接近 Material 3 基准蓝。 */
    INDIGO("靛蓝", "沉稳的蓝紫，适合长时间阅读", hue = 265.0, chroma = 52.0),

    /** 青碧：清透的青蓝，比绿色更中性。 */
    AZURE("青碧", "清透的青蓝，界面更轻盈", hue = 205.0, chroma = 44.0),

    /** 紫罗兰：更有表现力的紫，适合内容型界面。 */
    VIOLET("紫罗兰", "鲜明的紫罗兰，强调动作更醒目", hue = 312.0, chroma = 48.0),

    /** 暖橙：温暖的琥珀橙，媒体与附件场景更亲切。 */
    CORAL("暖橙", "温暖的琥珀橙，消息氛围更亲切", hue = 40.0, chroma = 58.0, tertiaryHue = 15.0),

    /** 石墨：近乎中性的单色方案，最安静。 */
    GRAPHITE("石墨", "近乎中性的单色，最安静克制", hue = 250.0, chroma = 6.0, neutralChroma = 1.2),
    ;

    /**
     * 生成浅色配色。
     *
     * 角色映射遵循 Material 3 的色调约定：
     * 主色 tone 40 / 容器 tone 90 / 容器文字 tone 10；表面用中性色 tone 98.5 起，
     * 容器层级 100 / 96 / 94 / 92 / 90 逐级加深；描边 tone 50 / 80。
     */
    fun lightScheme(): ColorScheme = buildScheme(dark = false)

    /** 生成深色配色，与浅色共享完全相同的语义角色。 */
    fun darkScheme(): ColorScheme = buildScheme(dark = true)

    private fun buildScheme(dark: Boolean): ColorScheme {
        val primaryHue = hue
        val secondaryHue = (hue + 8.0) % 360.0
        val tertiaryHueValue = tertiaryHue
        val neutral = neutralChroma
        return if (!dark) {
            val primary = tone(40.0, chroma, primaryHue)
            lightColorScheme(
                primary = primary,
                onPrimary = tone(100.0, 0.0, primaryHue),
                primaryContainer = tone(90.0, chroma * 0.62, primaryHue),
                onPrimaryContainer = tone(10.0, chroma * 0.55, primaryHue),
                inversePrimary = tone(80.0, chroma * 0.75, primaryHue),
                secondary = tone(40.0, chroma * 0.34, secondaryHue),
                onSecondary = tone(100.0, 0.0, secondaryHue),
                secondaryContainer = tone(90.0, chroma * 0.22, secondaryHue),
                onSecondaryContainer = tone(10.0, chroma * 0.28, secondaryHue),
                tertiary = tone(40.0, chroma * 0.55, tertiaryHueValue),
                onTertiary = tone(100.0, 0.0, tertiaryHueValue),
                tertiaryContainer = tone(90.0, chroma * 0.3, tertiaryHueValue),
                onTertiaryContainer = tone(10.0, chroma * 0.34, tertiaryHueValue),
                background = tone(98.5, neutral, primaryHue),
                onBackground = tone(10.0, neutral * 0.8, primaryHue),
                surface = tone(98.5, neutral, primaryHue),
                onSurface = tone(10.0, neutral * 0.8, primaryHue),
                surfaceVariant = tone(92.0, neutral * 1.1, primaryHue),
                onSurfaceVariant = tone(30.0, neutral * 1.4, primaryHue),
                surfaceTint = primary,
                inverseSurface = tone(20.0, neutral, primaryHue),
                inverseOnSurface = tone(95.0, neutral * 0.6, primaryHue),
                error = ErrorLight,
                onError = Color(0xFFFFFFFF),
                errorContainer = ErrorContainerLight,
                onErrorContainer = ErrorOnContainerLight,
                outline = tone(50.0, neutral * 1.6, primaryHue),
                outlineVariant = tone(80.0, neutral * 1.2, primaryHue),
                scrim = Color(0xFF000000),
                surfaceBright = tone(100.0, neutral * 0.6, primaryHue),
                surfaceDim = tone(87.0, neutral, primaryHue),
                surfaceContainerLowest = tone(100.0, neutral * 0.4, primaryHue),
                surfaceContainerLow = tone(96.0, neutral, primaryHue),
                surfaceContainer = tone(94.0, neutral, primaryHue),
                surfaceContainerHigh = tone(92.0, neutral * 1.1, primaryHue),
                surfaceContainerHighest = tone(90.0, neutral * 1.2, primaryHue),
            )
        } else {
            val primary = tone(80.0, chroma * 0.78, primaryHue)
            darkColorScheme(
                primary = primary,
                onPrimary = tone(20.0, chroma * 0.6, primaryHue),
                primaryContainer = tone(30.0, chroma * 0.7, primaryHue),
                onPrimaryContainer = tone(90.0, chroma * 0.4, primaryHue),
                inversePrimary = tone(40.0, chroma, primaryHue),
                secondary = tone(80.0, chroma * 0.34, secondaryHue),
                onSecondary = tone(20.0, chroma * 0.3, secondaryHue),
                secondaryContainer = tone(30.0, chroma * 0.28, secondaryHue),
                onSecondaryContainer = tone(90.0, chroma * 0.24, secondaryHue),
                tertiary = tone(80.0, chroma * 0.5, tertiaryHueValue),
                onTertiary = tone(20.0, chroma * 0.4, tertiaryHueValue),
                tertiaryContainer = tone(30.0, chroma * 0.42, tertiaryHueValue),
                onTertiaryContainer = tone(90.0, chroma * 0.3, tertiaryHueValue),
                background = tone(6.0, neutral, primaryHue),
                onBackground = tone(90.0, neutral * 1.1, primaryHue),
                surface = tone(6.0, neutral, primaryHue),
                onSurface = tone(90.0, neutral * 1.1, primaryHue),
                surfaceVariant = tone(30.0, neutral * 1.1, primaryHue),
                onSurfaceVariant = tone(80.0, neutral * 1.3, primaryHue),
                surfaceTint = primary,
                inverseSurface = tone(90.0, neutral * 1.1, primaryHue),
                inverseOnSurface = tone(20.0, neutral, primaryHue),
                error = ErrorDark,
                onError = Color(0xFF3D0E08),
                errorContainer = ErrorContainerDark,
                onErrorContainer = ErrorOnContainerDark,
                outline = tone(60.0, neutral * 1.5, primaryHue),
                outlineVariant = tone(30.0, neutral * 1.2, primaryHue),
                scrim = Color(0xFF000000),
                surfaceBright = tone(24.0, neutral * 1.2, primaryHue),
                surfaceDim = tone(6.0, neutral, primaryHue),
                surfaceContainerLowest = tone(4.0, neutral * 0.8, primaryHue),
                surfaceContainerLow = tone(10.0, neutral, primaryHue),
                surfaceContainer = tone(12.0, neutral * 1.05, primaryHue),
                surfaceContainerHigh = tone(17.0, neutral * 1.15, primaryHue),
                surfaceContainerHighest = tone(22.0, neutral * 1.25, primaryHue),
            )
        }
    }

    private companion object {
        val ErrorLight = Color(0xFFB3261E)
        val ErrorContainerLight = Color(0xFFF9DEDC)
        val ErrorOnContainerLight = Color(0xFF410E0B)
        val ErrorDark = Color(0xFFF2B8B5)
        val ErrorContainerDark = Color(0xFF8C1D18)
        val ErrorOnContainerDark = Color(0xFFF9DEDC)
    }
}

/**
 * CIE LCh 取色：把「明度 / 色度 / 色相」映射成 sRGB 颜色。
 *
 * 之所以不用简单的 HSL：HSL 的"亮度"与人眼感知不一致，同一 tone 在黄色和蓝色上会
 * 一个刺眼一个发灰，Material 3 的色调体系因此建立在 CIELAB 的 L* 上。这里用同一套
 * 感知明度取色，才能保证 40/90/10 这类档位在不同色相下对比度一致。
 *
 * 超出 sRGB 色域时逐级降低色度（而不是裁剪 RGB 分量），避免颜色被压偏色相。
 */
internal fun tone(lightness: Double, chroma: Double, hueDegrees: Double): Color {
    val clampedLightness = lightness.coerceIn(0.0, 100.0)
    val safeChroma = chroma.coerceIn(0.0, 140.0)
    var low = 0.0
    var high = safeChroma
    var best = labToColor(clampedLightness, 0.0, 0.0)
    repeat(24) {
        val mid = (low + high) / 2.0
        val candidate = lchToRgb(clampedLightness, mid, hueDegrees)
        val inGamut = candidate.all { it in -0.0005..1.0005 } || mid < 0.01
        if (inGamut) {
            best = rgbToColor(candidate)
            low = mid
        } else {
            high = mid
        }
    }
    return best
}

private fun labToColor(l: Double, a: Double, b: Double): Color = rgbToColor(labToRgb(l, a, b))

private fun rgbToColor(rgb: DoubleArray): Color = Color(
    red = rgb[0].coerceIn(0.0, 1.0).toFloat(),
    green = rgb[1].coerceIn(0.0, 1.0).toFloat(),
    blue = rgb[2].coerceIn(0.0, 1.0).toFloat(),
)

private fun lchToRgb(lightness: Double, chroma: Double, hueDegrees: Double): DoubleArray {
    val radians = Math.toRadians(hueDegrees)
    return labToRgb(lightness, chroma * cos(radians), chroma * sin(radians))
}

// D65 白点
private const val WHITE_X = 0.95047
private const val WHITE_Y = 1.0
private const val WHITE_Z = 1.08883

private fun labToRgb(l: Double, a: Double, b: Double): DoubleArray {
    val fy = (l + 16.0) / 116.0
    val fx = fy + a / 500.0
    val fz = fy - b / 200.0
    val x = WHITE_X * inverseF(fx)
    val y = WHITE_Y * inverseF(fy)
    val z = WHITE_Z * inverseF(fz)
    val r = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z
    val g = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z
    val blue = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z
    return doubleArrayOf(linearToSrgb(r), linearToSrgb(g), linearToSrgb(blue))
}

private fun inverseF(value: Double): Double {
    val t = value.pow(3.0)
    return if (t > 0.008856) t else (value * 116.0 - 16.0) / 903.3
}

private fun linearToSrgb(value: Double): Double =
    if (value <= 0.0031308) value * 12.92 else 1.055 * value.pow(1.0 / 2.4) - 0.055

/** 供测试使用：把任意颜色转回 LCh，校验 tone 映射没有跑偏。 */
internal fun colorToTone(color: Color): Triple<Double, Double, Double> {
    fun toLinear(channel: Float): Double {
        val v = channel.toDouble()
        return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
    val r = toLinear(color.red)
    val g = toLinear(color.green)
    val b = toLinear(color.blue)
    val x = 0.4124564 * r + 0.3575761 * g + 0.1804375 * b
    val y = 0.2126729 * r + 0.7151522 * g + 0.0721750 * b
    val z = 0.0193339 * r + 0.1191920 * g + 0.9503041 * b
    val fx = forwardF(x / WHITE_X)
    val fy = forwardF(y / WHITE_Y)
    val fz = forwardF(z / WHITE_Z)
    val l = 116.0 * fy - 16.0
    val a = 500.0 * (fx - fy)
    val bStar = 200.0 * (fy - fz)
    val chroma = sqrt(a * a + bStar * bStar)
    val hue = Math.toDegrees(kotlin.math.atan2(bStar, a)).let { if (it < 0) it + 360.0 else it }
    return Triple(l, chroma, hue)
}

private fun forwardF(value: Double): Double =
    if (value > 0.008856) cbrt(value) else (903.3 * value + 16.0) / 116.0
