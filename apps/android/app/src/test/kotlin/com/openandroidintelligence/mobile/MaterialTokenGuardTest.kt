package com.openandroidintelligence.mobile

import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.Dimensions
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 视觉令牌守卫。
 *
 * 1. 硬编码色值会让系统动态取色（Monet）和深浅主题被悄悄绕过，必须严格禁止；
 * 2. 圆角与尺寸守卫采用合理区间（Sanity Bounds）策略，既防止极端异常值或层级倒置，
 *    又避免微调视觉参数时因死板白名单产生误报阻塞。
 */
class MaterialTokenGuardTest {

    @Test fun appScreensNeverHardcodeColors() {
        val root = File("src/main/kotlin")
        assertTrue("找不到 UI 源码根目录", root.exists())

        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { HARDCODED_COLOR.containsMatchIn(it.readText()) }
            .map { it.path }
            .toList()

        assertTrue(
            "界面不得硬编码 Color(0x...)：请改用 MaterialTheme.colorScheme 的语义角色\n${offenders.joinToString()}",
            offenders.isEmpty(),
        )
    }

    @Test fun appScreensCornerRadiusWithinSanityBounds() {
        val root = File("src/main/kotlin")
        assertTrue("找不到 UI 源码根目录", root.exists())

        val dpBounds = 0..48
        val percentBounds = 0..100
        val offenders = mutableListOf<String>()

        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val text = file.readText()
                HARDCODED_DP_RADIUS.findAll(text)
                    .map { it.groupValues[1].toInt() }
                    .filter { it !in dpBounds }
                    .forEach { offenders += "${file.name}:${it}dp" }
                HARDCODED_PERCENT_RADIUS.findAll(text)
                    .map { it.groupValues[1].toInt() }
                    .filter { it !in percentBounds }
                    .forEach { offenders += "${file.name}:${it}%" }
            }

        assertTrue(
            "圆角字面量超出合理区间（dp: $dpBounds, percent: $percentBounds）：${offenders.joinToString()}",
            offenders.isEmpty(),
        )
    }

    @Test fun designTokenRadiusAndDimensionsWithinSanityBounds() {
        val radii = listOf(
            AppRadius.ExtraSmall,
            AppRadius.Small,
            AppRadius.Compact,
            AppRadius.Medium,
            AppRadius.Bubble,
            AppRadius.Large,
            AppRadius.ExtraLarge,
        )
        assertTrue("AppRadius 令牌必须处于合理圆角区间 0.dp..48.dp", radii.all { it in 0.dp..48.dp })
        assertTrue(
            "AppRadius 令牌应保持非递减层级顺序",
            radii.zipWithNext().all { (smaller, larger) -> smaller <= larger },
        )

        val spacings = listOf(
            Dimensions.SpaceTiny,
            Dimensions.SpaceSmall,
            Dimensions.SpaceCompact,
            Dimensions.SpaceMedium,
            Dimensions.ScreenHorizontal,
            Dimensions.ScreenVertical,
            Dimensions.SpaceLarge,
            Dimensions.SpaceXLarge,
        )
        assertTrue("间距令牌必须处于合理正区间 1.dp..64.dp", spacings.all { it in 1.dp..64.dp })
        assertTrue(
            "最小触控热区必须在无障碍安全区间 44.dp..64.dp",
            Dimensions.MinimumTouchTarget in 44.dp..64.dp,
        )

        val icons = listOf(
            Dimensions.SmallIcon,
            Dimensions.Icon,
            Dimensions.LeadingIconContainer,
            Dimensions.DockedBall,
            Dimensions.BrandMark,
            Dimensions.Progress,
        )
        assertTrue("图标与浮球尺寸必须在合理区间 12.dp..72.dp", icons.all { it in 12.dp..72.dp })
        assertTrue(
            "图标尺寸层级应满足 SmallIcon <= Icon <= LeadingIconContainer <= DockedBall",
            Dimensions.SmallIcon <= Dimensions.Icon &&
                Dimensions.Icon <= Dimensions.LeadingIconContainer &&
                Dimensions.LeadingIconContainer <= Dimensions.DockedBall,
        )

        assertTrue(
            "窗口断点必须在合理区间且满足 Medium < Expanded",
            Dimensions.MediumWindow in 480.dp..720.dp &&
                Dimensions.ExpandedWindow in 720.dp..1200.dp &&
                Dimensions.MediumWindow < Dimensions.ExpandedWindow,
        )
    }

    private val HARDCODED_COLOR = Regex("""Color\(\s*0[xX][0-9A-Fa-f]{6,8}""")
    private val HARDCODED_DP_RADIUS = Regex("""RoundedCornerShape\(\s*(\d+)\s*\.dp""")
    private val HARDCODED_PERCENT_RADIUS = Regex("""RoundedCornerShape\(\s*(?:percent\s*=\s*)?(\d+)\s*(?:,|\))""")
}
