package com.openandroidintelligence.mobile

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 视觉令牌守卫。
 *
 * 硬编码色值会让系统动态取色（Monet）和深浅主题被悄悄绕过，而且这类倒退在
 * code review 里几乎看不出来 —— 所以交给编译器之外的这层测试来兜底。
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

    @Test fun appScreensNeverHardcodeOffGridCornerRadius() {
        val root = File("src/main/kotlin")
        assertTrue("找不到 UI 源码根目录", root.exists())

        // 允许的几何值 = AppRadius 令牌：4 / 8 / 12 / 16 / 24 / 28dp。
        val allowed = setOf(4, 8, 12, 16, 24, 28)
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                HARDCODED_RADIUS.findAll(file.readText())
                    .map { it.groupValues[1].toInt() }
                    .filter { it !in allowed }
                    .map { "${file.name}:${it}dp" }
            }
            .toList()

        assertTrue(
            "圆角必须取自 AppRadius 令牌（4/8/12/16/24/28dp）：${offenders.joinToString()}",
            offenders.isEmpty(),
        )
    }

    private val HARDCODED_COLOR = Regex("""Color\(\s*0[xX][0-9A-Fa-f]{6,8}""")
    private val HARDCODED_RADIUS = Regex("""RoundedCornerShape\(\s*(\d+)""")
}
