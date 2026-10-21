package com.openandroidintelligence.conversation.workbench

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * KaTeX HTML 构建、尺寸估算与缓存的纯 JVM 单测。
 *
 * 这一部分不依赖 Android 运行时，也不启动 WebView：只验证「喂给 WebView 的 HTML 是否
 * 安全、离线、颜色/字号/displayMode 正确」，以及缓存与估算这两个纯数据逻辑。
 */
class MarkdownMathTest {

    @Before
    fun clearCache() {
        MarkdownMathSizeCache.clear()
    }

    @Test
    fun buildKaTeXHtmlUsesOnlyRelativeOfflineAssets() {
        val html = buildKaTeXHtml(
            latex = "x^2",
            displayMode = false,
            fontSizePx = 16f,
            textColorArgb = 0xFF112233.toInt(),
            errorColorArgb = 0xFFCC0000.toInt(),
        )

        assertTrue("必须引用相对路径的本地 CSS", html.contains("href=\"katex.min.css\""))
        assertTrue("必须引用相对路径的本地 JS", html.contains("src=\"katex.min.js\""))
        assertFalse("HTML 内不得出现 http 资源", html.contains("http://"))
        assertFalse("HTML 内不得出现 https 资源", html.contains("https://"))
        assertFalse("HTML 内不得出现绝对 file 路径", html.contains("file:///"))
        assertTrue("必须显式声明透明背景", html.contains("background:transparent"))
    }

    @Test
    fun buildKaTeXHtmlEncodesDisplayModeAndFontSize() {
        val inlineHtml = buildKaTeXHtml(
            latex = "x",
            displayMode = false,
            fontSizePx = 16f,
            textColorArgb = 0xFF000000.toInt(),
            errorColorArgb = 0xFFCC0000.toInt(),
        )
        val blockHtml = buildKaTeXHtml(
            latex = "x",
            displayMode = true,
            fontSizePx = 18.5f,
            textColorArgb = 0xFF000000.toInt(),
            errorColorArgb = 0xFFCC0000.toInt(),
        )

        assertTrue(inlineHtml.contains("displayMode:false"))
        assertFalse(inlineHtml.contains("displayMode:true"))
        assertTrue(inlineHtml.contains("font-size:16px"))

        assertTrue(blockHtml.contains("displayMode:true"))
        assertFalse(blockHtml.contains("displayMode:false"))
        assertTrue("小数像素字号不能被抹成整数", blockHtml.contains("font-size:18.5px"))
        assertTrue("块级公式用 min-width 撑满以便居中", blockHtml.contains("min-width:100%"))
        assertFalse("行内公式不应撑满整行", inlineHtml.contains("min-width:100%"))
    }

    @Test
    fun buildKaTeXHtmlAppliesTextAndErrorColors() {
        val html = buildKaTeXHtml(
            latex = "\\frac{1}{2}",
            displayMode = true,
            fontSizePx = 16f,
            textColorArgb = 0xFF112233.toInt(),
            errorColorArgb = 0x80FF0000.toInt(),
        )

        assertTrue("前景色必须来自调用方参数", html.contains("#112233"))
        assertTrue("错误色必须来自调用方参数且保留透明度", html.contains("#FF000080"))
        assertTrue("KaTeX 必须收到 errorColor 选项", html.contains("errorColor:\"#FF000080\""))
        assertTrue("KaTeX 必须关闭 throwOnError", html.contains("throwOnError:false"))
    }

    @Test
    fun buildKaTeXHtmlEscapesScriptInjection() {
        val latex = "\\frac{1}{2}</script>&amp;<script>alert('x')</script>"
        val html = buildKaTeXHtml(
            latex = latex,
            displayMode = false,
            fontSizePx = 16f,
            textColorArgb = 0xFF000000.toInt(),
            errorColorArgb = 0xFFCC0000.toInt(),
        )

        // 页面自身有两个 script 标签（KaTeX + 引导脚本），LaTeX 里的注入不能新增任何标签。
        assertEquals("注入的 </script> 不能截断页面脚本", 2, Regex("</script>").findAll(html).count())
        assertEquals("注入的 <script> 不能新增脚本", 2, Regex("<script").findAll(html).count())
        assertFalse("注入的原始标签不得出现在 HTML 里", html.contains("<script>alert"))
        assertTrue("尖括号必须转成 JS 的 \\uXXXX 转义", html.contains("\\u003c/script\\u003e"))
        assertTrue("ampersand 也应转义", html.contains("\\u0026"))
    }

    @Test
    fun buildKaTeXHtmlEscapesQuotesBackslashesAndLineBreaks() {
        val latex = "\\text{\"a\"}\n\\\\"
        val html = buildKaTeXHtml(
            latex = latex,
            displayMode = false,
            fontSizePx = 16f,
            textColorArgb = 0xFF000000.toInt(),
            errorColorArgb = 0xFFCC0000.toInt(),
        )

        assertTrue("反斜杠必须成对转义", html.contains("\\\\text{"))
        assertTrue("双引号必须转义", html.contains("\\\"a\\\""))
        assertTrue("换行必须转成 \\n 转义而不是真实换行", html.contains("\\n"))
    }

    @Test
    fun escapeJsStringLiteralHandlesControlAndLineSeparatorChars() {
        assertEquals("\"\\u003c/script\\u003e\"", escapeJsStringLiteral("</script>"))
        assertEquals("\"a\\\\b\"", escapeJsStringLiteral("a\\b"))
        assertEquals("\"line1\\nline2\"", escapeJsStringLiteral("line1\nline2"))
        assertEquals("\"q\\\"q\"", escapeJsStringLiteral("q\"q"))
        assertEquals("\"\\u2028\\u2029\"", escapeJsStringLiteral("\u2028\u2029"))
    }

    @Test
    fun argbToCssColorKeepsAlphaOnlyWhenNeeded() {
        assertEquals("#112233", argbToCssColor(0xFF112233.toInt()))
        assertEquals("#11223380", argbToCssColor(0x80112233.toInt()))
        assertEquals("#00000000", argbToCssColor(0x00000000))
    }

    @Test
    fun formatCssPxTrimsZerosAndRejectsIllegalValues() {
        assertEquals("16px", formatCssPx(16f))
        assertEquals("18.5px", formatCssPx(18.5f))
        assertEquals("非法字号回落到 16px", "16px", formatCssPx(Float.NaN))
        assertEquals("非正字号回落到 16px", "16px", formatCssPx(0f))
        assertEquals("超大字号要夹到上限", "512px", formatCssPx(4096f))
    }

    @Test
    fun cacheReturnsRecordedSizeAndIgnoresNonPositiveSizes() {
        val key = MarkdownMathKey(latex = "\\sqrt{2}", displayMode = false, fontSizePx = 32)
        assertNull(MarkdownMathSizeCache.size(key))

        MarkdownMathSizeCache.record(key, IntSize(120, 48))
        assertEquals(IntSize(120, 48), MarkdownMathSizeCache.size(key))

        // 非正尺寸是脏数据：既不能覆盖已有值，也不能在空缓存里留下 0 尺寸。
        MarkdownMathSizeCache.record(key, IntSize(0, 48))
        MarkdownMathSizeCache.record(key, IntSize(120, -1))
        assertEquals(IntSize(120, 48), MarkdownMathSizeCache.size(key))

        val untouched = MarkdownMathKey(latex = "x", displayMode = true, fontSizePx = 16)
        MarkdownMathSizeCache.record(untouched, IntSize(0, 0))
        assertNull(MarkdownMathSizeCache.size(untouched))
    }

    @Test
    fun cacheSeparatesKeysByLatexDisplayModeAndFontSize() {
        val inline = MarkdownMathKey(latex = "x^2", displayMode = false, fontSizePx = 32)
        val block = MarkdownMathKey(latex = "x^2", displayMode = true, fontSizePx = 32)
        val bigger = MarkdownMathKey(latex = "x^2", displayMode = false, fontSizePx = 48)
        val otherLatex = MarkdownMathKey(latex = "y^2", displayMode = false, fontSizePx = 32)

        MarkdownMathSizeCache.record(inline, IntSize(10, 20))
        MarkdownMathSizeCache.record(block, IntSize(30, 40))
        MarkdownMathSizeCache.record(bigger, IntSize(50, 60))
        MarkdownMathSizeCache.record(otherLatex, IntSize(70, 80))

        assertEquals(IntSize(10, 20), MarkdownMathSizeCache.size(inline))
        assertEquals(IntSize(30, 40), MarkdownMathSizeCache.size(block))
        assertEquals(IntSize(50, 60), MarkdownMathSizeCache.size(bigger))
        assertEquals(IntSize(70, 80), MarkdownMathSizeCache.size(otherLatex))
    }

    @Test
    fun cacheEvictsOldEntriesBeyondCapacity() {
        repeat(300) { index ->
            MarkdownMathSizeCache.record(
                MarkdownMathKey(latex = "x_$index", displayMode = false, fontSizePx = 32),
                IntSize(index + 1, index + 2),
            )
        }
        // 容量 256：最早的 44 条被淘汰，最新的必须还在。
        val newest = MarkdownMathKey(latex = "x_299", displayMode = false, fontSizePx = 32)
        assertEquals(IntSize(300, 301), MarkdownMathSizeCache.size(newest))
        assertNull(MarkdownMathSizeCache.size(MarkdownMathKey(latex = "x_0", displayMode = false, fontSizePx = 32)))
    }

    @Test
    fun estimateInlineSizeIsPositiveAndGrowsWithContentAndFontSize() {
        val singleGlyph = MarkdownMathSizeCache.estimateInlineSize("x", 16)
        val manyGlyphs = MarkdownMathSizeCache.estimateInlineSize("x+y+z+w", 16)
        val largeFont = MarkdownMathSizeCache.estimateInlineSize("x", 32)

        assertTrue("估算宽度必须为正", singleGlyph.width > 0)
        assertTrue("估算高度必须为正", singleGlyph.height > 0)
        assertTrue("字符越多估算越宽", manyGlyphs.width > singleGlyph.width)
        assertTrue("字号越大估算越高", largeFont.height > singleGlyph.height)
        assertTrue("字号越大估算越宽", largeFont.width > singleGlyph.width)
        assertNotEquals(singleGlyph, manyGlyphs)

        val empty = MarkdownMathSizeCache.estimateInlineSize("", 16)
        assertTrue("空公式也不能塌成 0 宽", empty.width > 0)
        assertTrue("空公式也不能塌成 0 高", empty.height > 0)

        val degenerate = MarkdownMathSizeCache.estimateInlineSize("x", 0)
        assertTrue("非法字号也不能产生 0 尺寸", degenerate.width > 0 && degenerate.height > 0)

        // 首帧估算不能超过 16sp 正文的 26sp 行高，否则会先撑高再回缩、产生可见跳动。
        val simple = MarkdownMathSizeCache.estimateInlineSize("E=mc^2", 16)
        assertTrue("简单行内公式的首帧高度不应超过 1.6em", simple.height <= 26)
    }

    @Test
    fun parseMathMeasurementReadsJavascriptPayload() {
        val measurement = parseMathMeasurement("\"120,48,200,60,0\"")
        assertEquals(IntSize(120, 48), measurement?.natural)
        assertEquals(IntSize(200, 60), measurement?.document)
        assertEquals(false, measurement?.failed)

        val failed = parseMathMeasurement("\"0,0,0,0,1\"")
        assertEquals(true, failed?.failed)

        assertNull("页面还没就绪时返回空串", parseMathMeasurement(""))
        assertNull("null 结果视为未测量", parseMathMeasurement("null"))
        assertNull("字段不足视为未测量", parseMathMeasurement("\"1,2\""))
        assertNull("非数字视为未测量", parseMathMeasurement("\"a,2,3,4,0\""))
        assertNull("null 输入", parseMathMeasurement(null))
    }
    @Test
    fun cacheEvictsLeastRecentlyUsedEntryNotOldestInserted() {
        MarkdownMathSizeCache.clear()
        fun key(index: Int) = MarkdownMathKey("x$index", displayMode = false, fontSizePx = 16)

        repeat(256) { index ->
            MarkdownMathSizeCache.record(key(index), IntSize(10 + index, 20))
        }
        // 读取最旧的条目，使其成为“最近使用”
        assertEquals(IntSize(10, 20), MarkdownMathSizeCache.size(key(0)))

        MarkdownMathSizeCache.record(key(256), IntSize(999, 999))

        assertEquals("被读取刷新过的旧条目应保留", IntSize(10, 20), MarkdownMathSizeCache.size(key(0)))
        assertNull("真正最久未使用的条目应被淘汰", MarkdownMathSizeCache.size(key(1)))
        assertEquals(IntSize(999, 999), MarkdownMathSizeCache.size(key(256)))
        MarkdownMathSizeCache.clear()
    }

}

/**
 * Robolectric Compose 冒烟测试：只证明公式组件在真实 Compose 组合/布局下不崩溃，
 * 且失败兜底与容器语义可被测试树检索到。
 *
 * 注意：Robolectric 的 ShadowWebView 不执行 JavaScript，也不会回调 onPageFinished，
 * 所以这里无法验证真实排版尺寸；尺寸/颜色由上面的纯 JVM HTML 测试与设备验证覆盖。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MarkdownMathComposeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun mathComponentsRenderContainerOrHonestFallbackWithoutCrash() {
        MarkdownMathSizeCache.clear()

        compose.setContent {
            MaterialTheme {
                Column {
                    MarkdownMathBlock(latex = "\\frac{a}{b}", fontSize = 16.sp)
                    MarkdownMathInline(latex = "x^2", fontSize = 16.sp, onMeasured = {})
                    MarkdownMathFallback(latex = "\\sqrt{2}", displayMode = true, fontSize = 16.sp)
                }
            }
        }

        // 容器一直都在：要么装着 WebView，要么装着兜底，不允许「什么都不渲染」。
        compose.onNodeWithTag(MARKDOWN_MATH_BLOCK_TAG).assertExists()
        compose.onNodeWithTag(MARKDOWN_MATH_INLINE_TAG).assertExists()

        // 显式渲染的兜底：等宽原始 LaTeX + 无障碍描述都要在语义树里。
        assertTrue(
            "兜底必须带「公式渲染不可用」的描述",
            compose.onAllNodesWithContentDescription(MARKDOWN_MATH_UNAVAILABLE_DESCRIPTION)
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )
        assertTrue(
            "兜底必须显示原始 LaTeX",
            compose.onAllNodesWithText("\\sqrt{2}", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )
        assertTrue(
            "兜底容器必须可被测试树找到",
            compose.onAllNodesWithTag(MARKDOWN_MATH_FALLBACK_TAG)
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )
    }
}
