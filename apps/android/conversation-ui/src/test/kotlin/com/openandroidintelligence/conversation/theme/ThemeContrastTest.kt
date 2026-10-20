package com.openandroidintelligence.conversation.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * 主题守卫测试。
 *
 * 默认外观走系统动态取色（Monet），但用户随时可能关掉它去选一套预设，所以**每一套预设**
 * 都必须独立满足可读性要求：不能只有"默认那套"经过验证。
 */
class ThemeContrastTest {

    private val allSchemes = ThemePalette.entries.flatMap { palette ->
        listOf(palette.label to palette.lightScheme(), palette.label + "-dark" to palette.darkScheme())
    }

    @Test
    fun everyPresetKeepsReadableTextOnAllUsedTonalSurfaces() {
        allSchemes.forEach { (name, scheme) ->
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
                scheme.primaryContainer,
                scheme.tertiaryContainer,
            )
            surfaces.forEach { background ->
                assertContrast("$name onSurface", scheme.onSurface, background)
                assertContrast("$name onSurfaceVariant", scheme.onSurfaceVariant, background)
            }
            listOf(
                "onPrimary" to (scheme.onPrimary to scheme.primary),
                "onPrimaryContainer" to (scheme.onPrimaryContainer to scheme.primaryContainer),
                "onSecondary" to (scheme.onSecondary to scheme.secondary),
                "onSecondaryContainer" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
                "onTertiary" to (scheme.onTertiary to scheme.tertiary),
                "onTertiaryContainer" to (scheme.onTertiaryContainer to scheme.tertiaryContainer),
                "onError" to (scheme.onError to scheme.error),
                "onErrorContainer" to (scheme.onErrorContainer to scheme.errorContainer),
                "inverseOnSurface" to (scheme.inverseOnSurface to scheme.inverseSurface),
            ).forEach { (role, pair) ->
                assertContrast("$name $role", pair.first, pair.second)
            }
        }
    }

    /**
     * 预设的色调映射必须真的落在 Material 3 的档位上。
     *
     * 光"对比度够"不够：主色是 tone 40 还是 tone 70，决定了界面看起来像不像 Material。
     * 这里把生成出来的颜色转回 CIELAB 的 L*，允许 ±2 的取整误差。
     */
    @Test
    fun presetTonesFollowMaterialToneLadder() {
        ThemePalette.entries.forEach { palette ->
            val light = palette.lightScheme()
            val dark = palette.darkScheme()
            assertTone("${palette.label} light primary", light.primary, 40.0)
            assertTone("${palette.label} light primaryContainer", light.primaryContainer, 90.0)
            assertTone("${palette.label} light surface", light.surface, 98.5)
            assertTone("${palette.label} light containerHighest", light.surfaceContainerHighest, 90.0)
            assertTone("${palette.label} dark primary", dark.primary, 80.0)
            assertTone("${palette.label} dark surface", dark.surface, 6.0)
            assertTone("${palette.label} dark containerHighest", dark.surfaceContainerHighest, 22.0)
        }
    }

    @Test
    fun everyPresetCoversEveryContainerRoleDistinctly() {
        allSchemes.forEach { (name, scheme) ->
            val containers = listOf(
                scheme.surface,
                scheme.surfaceContainerLowest,
                scheme.surfaceContainerLow,
                scheme.surfaceContainer,
                scheme.surfaceContainerHigh,
                scheme.surfaceContainerHighest,
            )
            assertTrue(
                "$name: surface container 层级必须逐级变化，否则容器深浅无法表达: $containers",
                containers.zipWithNext().all { (a, b) -> a != b },
            )
            assertTrue("$name: surfaceTint 必须跟随 primary", scheme.surfaceTint == scheme.primary)
            assertTrue("$name: scrim 必须是纯黑遮罩", scheme.scrim.luminance() == 0f)
        }
    }

    /** 状态色不随主题漂移，但同样要满足正文级对比度。 */
    @Test
    fun stateColorsKeepReadableContrast() {
        listOf(LightAppStateColors, DarkAppStateColors).forEach { state ->
            assertContrast("success", state.success, state.onSuccess)
            assertContrast("successContainer", state.onSuccessContainer, state.successContainer)
            assertContrast("warning", state.warning, state.onWarning)
            assertContrast("warningContainer", state.onWarningContainer, state.warningContainer)
        }
        assertContrast("light success on light surface", LightAppStateColors.success, Color(0xFFF7F9F7))
        assertContrast("dark success on dark surface", DarkAppStateColors.success, Color(0xFF101416))
    }

    /**
     * 硬编码色值守卫：视觉令牌之外的 UI 代码不允许出现 `Color(0x...)`。
     * 一旦放宽，系统动态取色与深浅主题都会被绕开。
     */
    @Test
    fun uiComposablesNeverHardcodeColors() {
        val root = listOf(File("."), File("../conversation-ui"), File("../../.."))
            .firstOrNull { File(it, "src/main/kotlin").exists() }
        checkNotNull(root) { "找不到 UI 源码根目录" }

        val allowed = setOf(
            "com/openandroidintelligence/conversation/theme/AppPalettes.kt",
            "com/openandroidintelligence/conversation/theme/AppStateColors.kt",
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

    private fun assertContrast(label: String, foreground: Color, background: Color) {
        val a = foreground.luminance()
        val b = background.luminance()
        val ratio = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
        assertTrue("$label 文字对比度不足: $foreground / $background = $ratio", ratio >= 4.5)
    }

    private fun assertTone(label: String, color: Color, expected: Double) {
        val (lightness, _, _) = colorToTone(color)
        assertTrue(
            "$label 的感知明度应接近 $expected，实际 ${"%.1f".format(lightness)}",
            abs(lightness - expected) <= 2.5,
        )
    }
}
