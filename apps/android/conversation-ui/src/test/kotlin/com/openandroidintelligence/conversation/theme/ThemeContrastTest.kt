package com.openandroidintelligence.conversation.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeContrastTest {
    @Test fun bothBrandSchemesKeepReadableTextOnAllUsedTonalSurfaces() {
        listOf(MossLightColorScheme, MossDarkColorScheme).forEach { scheme ->
            val surfaces = listOf(scheme.surface, scheme.surfaceContainerLow, scheme.surfaceContainer,
                scheme.surfaceContainerHigh, scheme.surfaceContainerHighest, scheme.secondaryContainer)
            surfaces.forEach { background ->
                assertContrast(scheme.onSurface, background)
                assertContrast(scheme.onSurfaceVariant, background)
            }
            listOf(scheme.onPrimary to scheme.primary, scheme.onPrimaryContainer to scheme.primaryContainer,
                scheme.onError to scheme.error, scheme.onErrorContainer to scheme.errorContainer,
                scheme.onTertiary to scheme.tertiary).forEach { (foreground, background) -> assertContrast(foreground, background) }
        }
    }
    private fun assertContrast(foreground: Color, background: Color) {
        val a = foreground.luminance()
        val b = background.luminance()
        val ratio = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
        assertTrue("文字对比度不足: $foreground / $background = $ratio", ratio >= 4.5)
    }
}
