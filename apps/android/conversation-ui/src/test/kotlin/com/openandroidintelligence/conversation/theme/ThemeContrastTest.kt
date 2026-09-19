package com.openandroidintelligence.conversation.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ThemeContrastTest {

    @Test fun bothBrandSchemesKeepReadableTextOnAllUsedTonalSurfaces() {
        listOf(BrandLightColorScheme, BrandDarkColorScheme).forEach { scheme ->
            val surfaces = listOf(
                scheme.surface,
                scheme.surfaceContainerLowest,
                scheme.surfaceContainerLow,
                scheme.surfaceContainer,
                scheme.surfaceContainerHigh,
                scheme.surfaceContainerHighest,
                scheme.surfaceBright,
                scheme.surfaceDim,
                scheme.secondaryContainer,
            )
            surfaces.forEach { background ->
                assertContrast(scheme.onSurface, background)
                assertContrast(scheme.onSurfaceVariant, background)
            }
            listOf(
                scheme.onPrimary to scheme.primary,
                scheme.onPrimaryContainer to scheme.primaryContainer,
                scheme.onSecondary to scheme.secondary,
                scheme.onSecondaryContainer to scheme.secondaryContainer,
                scheme.onTertiary to scheme.tertiary,
                scheme.onTertiaryContainer to scheme.tertiaryContainer,
                scheme.onError to scheme.error,
                scheme.onErrorContainer to scheme.errorContainer,
                scheme.inverseOnSurface to scheme.inverseSurface,
            ).forEach { (foreground, background) -> assertContrast(foreground, background) }
        }
    }

    /**
     * 降级配色的守卫：所有 [AppColors] 种子最终都必须落到某个 M3 语义角色上，
     * 否则界面为了好看又会回去直接引用种子色，动态取色就此失效。
     */
    @Test fun fallbackSchemeCoversEveryContainerRoleDistinctly() {
        listOf(BrandLightColorScheme, BrandDarkColorScheme).forEach { scheme ->
            val containers = listOf(
                scheme.surface,
                scheme.surfaceContainerLowest,
                scheme.surfaceContainerLow,
                scheme.surfaceContainer,
                scheme.surfaceContainerHigh,
                scheme.surfaceContainerHighest,
            )
            assertTrue(
                "surface container 层级必须逐级变化，否则容器深浅无法表达: $containers",
                containers.zipWithNext().all { (a, b) -> a != b },
            )
            assertTrue("surfaceTint 必须跟随 primary", scheme.surfaceTint == scheme.primary)
            assertTrue("scrim 必须是纯黑遮罩", scheme.scrim.luminance() == 0f)
        }
    }

    /**
     * 硬编码色值守卫：视觉令牌之外的 UI 代码不允许出现 `Color(0x...)`。
     * 一旦放宽，系统动态取色与深浅主题都会被绕开。
     */
    @Test fun uiComposablesNeverHardcodeColors() {
        val root = listOf(File("."), File("../conversation-ui"), File("../../.."))
            .firstOrNull { File(it, "src/main/kotlin").exists() }
        checkNotNull(root) { "找不到 UI 源码根目录" }

        val allowed = setOf(
            "com/openandroidintelligence/conversation/theme/AppColors.kt",
            "com/openandroidintelligence/conversation/theme/Theme.kt",
        )
        val offenders = File(root, "src/main/kotlin")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it to it.relativeToOrSelf(File(root, "src/main/kotlin")).path }
            .filter { (_, relative) -> relative !in allowed }
            .filter { (file, _) -> HARDCODED_COLOR.containsMatchIn(file.readText()) }
            .map { (_, relative) -> relative }
            .toList()

        assertTrue(
            "界面不得硬编码 Color(0x...)：请改用 MaterialTheme.colorScheme 的语义角色\n${offenders.joinToString()}",
            offenders.isEmpty(),
        )
    }

    private val HARDCODED_COLOR = Regex("""Color\(\s*0[xX][0-9A-Fa-f]{6,8}""")

    private fun assertContrast(foreground: Color, background: Color) {
        val a = foreground.luminance()
        val b = background.luminance()
        val ratio = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
        assertTrue("文字对比度不足: $foreground / $background = $ratio", ratio >= 4.5)
    }
}
