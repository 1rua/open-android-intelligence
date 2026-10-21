package com.openandroidintelligence.conversation.workbench

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行内解析修复：真实可点击的链接、自动链接、白名单 HTML、行内公式与实体转义。
 * 这里的测试全部在纯 JVM 运行，不依赖 WebView，公式只校验占位与 LaTeX 是否传递正确。
 */
class MarkdownInlineTest {

    private val colors = MarkdownColors(
        textColor = Color.White,
        boldColor = Color.White,
        italicColor = Color.Gray,
        codeColor = Color.Green,
        codeBackground = Color.Black,
        linkColor = Color.Blue,
    )

    @Test
    fun markdownLinkCreatesClickableAnnotationAndInvokesHandler() {
        var opened: String? = null
        val result = buildMarkdownInline(
            "访问 [OpenAI](https://openai.com) 官网",
            colors,
        ) { opened = it }

        assertEquals("访问 OpenAI 官网", result.annotated.text)
        val links = result.annotated.getLinkAnnotations(0, result.annotated.length)
        assertEquals(1, links.size)

        val clickable = links[0].item as LinkAnnotation.Clickable
        assertEquals("https://openai.com", clickable.tag)
        clickable.linkInteractionListener?.onClick(clickable)
        assertEquals("https://openai.com", opened)
    }

    @Test
    fun autolinkAngleBracketsAndBareUrlsAreRecognized() {
        val result = buildMarkdownInline(
            "<https://example.com> 与 https://kotlinlang.org 以及纯文本",
            colors,
        )

        assertEquals("https://example.com 与 https://kotlinlang.org 以及纯文本", result.annotated.text)
        val tags = result.annotated
            .getLinkAnnotations(0, result.annotated.length)
            .map { (it.item as LinkAnnotation.Clickable).tag }
        assertEquals(listOf("https://example.com", "https://kotlinlang.org"), tags)
    }

    @Test
    fun angleEmailBecomesMailtoLink() {
        val result = buildMarkdownInline("联系 <user@example.com>", colors)
        assertEquals("联系 user@example.com", result.annotated.text)
        val tag = (result.annotated.getLinkAnnotations(0, result.annotated.length).single().item as LinkAnnotation.Clickable).tag
        assertEquals("mailto:user@example.com", tag)
    }

    @Test
    fun bareUrlDropsTrailingChinesePunctuation() {
        val result = buildMarkdownInline("访问 https://example.com。", colors)
        assertEquals("访问 https://example.com。", result.annotated.text)
        val tag = (result.annotated.getLinkAnnotations(0, result.annotated.length).single().item as LinkAnnotation.Clickable).tag
        assertEquals("https://example.com", tag)
    }

    @Test
    fun unsupportedSchemeKeepsReadableTextWithoutFakeLink() {
        val result = buildMarkdownInline("[本地文件](file:///data/local/tmp/a.txt)", colors)
        assertEquals("本地文件", result.annotated.text)
        assertTrue(result.annotated.getLinkAnnotations(0, result.annotated.length).isEmpty())
    }

    @Test
    fun linkTitleIsConsumedWithoutLeakingIntoText() {
        val result = buildMarkdownInline("[官网](https://example.com \"标题\")", colors)
        assertEquals("官网", result.annotated.text)
        val tag = (result.annotated.getLinkAnnotations(0, result.annotated.length).single().item as LinkAnnotation.Clickable).tag
        assertEquals("https://example.com", tag)
    }

    @Test
    fun inlineMathIsExtractedAndCurrencyIsNotFormula() {
        val raw = "质能方程 ${'$'}E=mc^2${'$'} 与价格 ${'$'}5 和 ${'$'}10"
        val result = buildMarkdownInline(raw, colors)

        assertEquals(1, result.inlineMath.size)
        assertEquals("E=mc^2", result.inlineMath.single().latex)
        assertEquals("质能方程 E=mc^2 与价格 ${'$'}5 和 ${'$'}10", result.annotated.text)
    }

    @Test
    fun inlineMathPlaceholdersGetUniqueIds() {
        val result = buildMarkdownInline("${'$'}a${'$'} 与 ${'$'}b${'$'}", colors)
        assertEquals(listOf("md_math_0", "md_math_1"), result.inlineMath.map { it.id })
        assertEquals(listOf("a", "b"), result.inlineMath.map { it.latex })
    }

    @Test
    fun inlineHtmlAllowlistIsRenderedAndStripped() {
        val raw = "按 <kbd>Ctrl</kbd> + <kbd>C</kbd> 复制 H<sub>2</sub>O 与 x<sup>2</sup>，<mark>高亮</mark>，<b>粗</b>"
        val result = buildMarkdownInline(raw, colors)

        assertEquals("按 Ctrl + C 复制 H2O 与 x2，高亮，粗", result.annotated.text)
        assertTrue(
            "kbd/mark 必须带背景色",
            result.annotated.spanStyles.any { it.item.background != Color.Unspecified },
        )
        assertTrue(
            "sub 必须有下标偏移",
            result.annotated.spanStyles.any { it.item.baselineShift == BaselineShift.Subscript },
        )
        assertTrue(
            "sup 必须有上标偏移",
            result.annotated.spanStyles.any { it.item.baselineShift == BaselineShift.Superscript },
        )
        assertTrue(
            "mark 必须带背景色",
            result.annotated.spanStyles.any { it.item.background == colors.resolvedMarkBackground },
        )
    }

    @Test
    fun htmlAnchorCreatesClickableLink() {
        val result = buildMarkdownInline("<a href=\"https://example.com\">官网</a>", colors)
        assertEquals("官网", result.annotated.text)
        val tag = (result.annotated.getLinkAnnotations(0, result.annotated.length).single().item as LinkAnnotation.Clickable).tag
        assertEquals("https://example.com", tag)
    }

    @Test
    fun dangerousHtmlStaysLiteral() {
        val result = buildMarkdownInline("<script>alert(1)</script>", colors)
        assertEquals("<script>alert(1)</script>", result.annotated.text)
    }

    @Test
    fun htmlEntitiesAreDecoded() {
        val result = buildMarkdownInline("A &amp; B &lt; C &gt; D &#65;", colors)
        assertEquals("A & B < C > D A", result.annotated.text)
    }

    @Test
    fun strongEmphasisNestsWithoutLeakingMarkers() {
        val result = buildMarkdownInline("***粗斜体***", colors)
        assertEquals("粗斜体", result.annotated.text)
        assertTrue(result.annotated.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(result.annotated.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }

    @Test
    fun strikethroughUsesLineThrough() {
        val result = buildMarkdownInline("~~删除~~", colors)
        assertEquals("删除", result.annotated.text)
        assertTrue(result.annotated.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough })
    }

    @Test
    fun escapedDollarStaysLiteral() {
        val result = buildMarkdownInline("价格 \\${'$'}5", colors)
        assertEquals("价格 ${'$'}5", result.annotated.text)
        assertTrue(result.inlineMath.isEmpty())
    }

    @Test
    fun supportedSchemesAreLimitedToBrowserMailAndTelephone() {
        assertTrue(isSupportedLinkUrl("https://example.com"))
        assertTrue(isSupportedLinkUrl("http://example.com"))
        assertTrue(isSupportedLinkUrl("mailto:a@b.com"))
        assertTrue(isSupportedLinkUrl("tel:+8613800000000"))
        assertFalse(isSupportedLinkUrl("file:///etc/passwd"))
        assertFalse(isSupportedLinkUrl("javascript:alert(1)"))
        assertFalse(isSupportedLinkUrl("data:text/html,hi"))
        assertFalse(isSupportedLinkUrl("https:"))
    }
    @Test
    fun emphasisDoesNotCloseInsideInlineCode() {
        val result = buildMarkdownInline("*a `b*c` d*", colors)

        assertEquals("a b*c d", result.annotated.text)
        val italic = result.annotated.spanStyles.filter { it.item.fontStyle == FontStyle.Italic }
        assertTrue("整体应是斜体", italic.any { it.start == 0 && it.end == result.annotated.length })
    }

    @Test
    fun bareUrlInsideEmphasisStopsAtTheMarkerBoundary() {
        val result = buildMarkdownInline("**https://a**x", colors)

        assertEquals("https://ax", result.annotated.text)
        assertEquals(1, result.annotated.getLinkAnnotations(0, result.annotated.length).size)
        val link = result.annotated.getLinkAnnotations(0, result.annotated.length).single()
        assertEquals("https://a", ((link.item as LinkAnnotation.Clickable).tag))
    }

    @Test
    fun hrefAttributeRequiresRealAttributeBoundary() {
        val result = buildMarkdownInline("<a data-href=\"https://example.com\">x</a>", colors)

        assertEquals("x", result.annotated.text)
        assertTrue("data-href 不能被当成 href", result.annotated.getLinkAnnotations(0, result.annotated.length).isEmpty())
    }

    @Test
    fun doubleDollarWithoutClosingDelimiterIsPlainText() {
        val result = buildMarkdownInline("a ${'$'}${'$'}x${'$'}", colors)

        assertEquals("a ${'$'}${'$'}x${'$'}", result.annotated.text)
        assertTrue("未闭合的 $$ 不能把第二个 $ 当公式起点", result.inlineMath.isEmpty())
    }

    @Test
    fun linkUrlRequiresNonEmptyHostForHttp() {
        assertFalse(isSupportedLinkUrl("https://"))
        assertFalse(isSupportedLinkUrl("https://..."))
        assertTrue(isSupportedLinkUrl("HTTPS://example.com/path?q=1"))
        assertFalse(isSupportedLinkUrl("mailto:"))
    }

    @Test(timeout = 2_000)
    fun malformedHtmlTagDoesNotTriggerRegexBacktracking() {
        // 未闭合的标签即使带大量空格，也必须在线性时间内按普通文本处理
        val input = "<a " + " ".repeat(32_000) + "!"

        val result = buildMarkdownInline(input, colors)

        assertTrue(result.annotated.text.endsWith("!"))
        assertTrue(result.annotated.getLinkAnnotations(0, result.annotated.length).isEmpty())
    }

    @Test
    fun bareUrlStopsBeforeInlineHtmlTag() {
        val result = buildMarkdownInline("https://example.com<b>粗</b>", colors)

        assertEquals("https://example.com粗", result.annotated.text)
        val link = result.annotated.getLinkAnnotations(0, result.annotated.length).single()
        assertEquals("https://example.com", (link.item as LinkAnnotation.Clickable).tag)
        assertTrue("粗体标签仍要生效", result.annotated.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun malformedNestedOpenTagStaysLiteralInsteadOfSwallowingMarkup() {
        val result = buildMarkdownInline("<b <i>x</i>", colors)

        assertEquals("<b x", result.annotated.text)
        assertTrue("内层 <i> 仍应正常生效", result.annotated.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
    }

}
