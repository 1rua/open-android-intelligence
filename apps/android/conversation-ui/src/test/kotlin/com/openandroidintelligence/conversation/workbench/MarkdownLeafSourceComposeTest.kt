package com.openandroidintelligence.conversation.workbench

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 真实 Markdown 组件只渲染当前叶节点正文，来源图只负责定位流式装饰。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MarkdownLeafSourceComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun 历史短表格行的补齐单元格为空且不泄露整表原文() {
        renderDocument(ShortTable, active = false)

        assertEquals(listOf("A", "B", "value", ""), renderedTexts())
        compose.onNodeWithText(ShortTable, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun 活动流式短表格行的补齐单元格为空且不泄露整表原文() {
        renderDocument(ShortTable, active = true)

        assertEquals(listOf("A", "B", "value", ""), renderedTexts())
        compose.onNodeWithText(ShortTable, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun 无来源的历史标题段落列表仍渲染各自正文() {
        val blocks = MarkdownParser.parse("# 标题正文\n\n段落 **加粗**\n\n- 列表正文")
        compose.setContent {
            MaterialTheme {
                MarkdownDocumentView(blocks, isStreaming = false)
            }
        }

        assertEquals(listOf("标题正文", "段落 加粗", "列表正文"), renderedTexts())
    }

    @Test fun 错配的叶来源和整块来源不会替换标题段落列表正文() {
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalMarkdownLeafSource provides MarkdownSourceText.original("错误叶正文"),
                    LocalMarkdownBlockSource provides MarkdownSourceText.original("错误整块正文"),
                ) {
                    val colors = defaultMarkdownColors()
                    Column {
                        MarkdownHeadingView(TimelineBlock.Heading(1, "标题正文"), false, colors)
                        MarkdownParagraphView(TimelineBlock.Paragraph("段落 **加粗**"), false, colors)
                        MarkdownListView(
                            TimelineBlock.ListBlock(listOf(MarkdownListItem(text = "列表正文"))),
                            false,
                            colors,
                        )
                    }
                }
            }
        }

        assertEquals(listOf("标题正文", "段落 加粗", "列表正文"), renderedTexts())
    }

    @Test fun 匹配叶来源保留Markdown格式链接及显式尾换行的真实布局() {
        val markdown = "正文 **加粗** 与 [链接](https://example.com)\n\n"
        renderDocument(markdown, active = true)

        val layout = textLayout("正文 加粗 与 链接\n\n")
        assertEquals("显式尾换行必须进入真实文字布局", 3, layout.lineCount)
        assertTrue(layout.layoutInput.text.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        val links = layout.layoutInput.text.getLinkAnnotations(0, layout.layoutInput.text.length)
        assertEquals("https://example.com", (links.single().item as LinkAnnotation.Clickable).tag)
    }

    @Test fun 缺少叶来源时匹配整块来源仍保留显式尾换行() {
        val markdown = "正文\n\n"
        val document = StreamingMarkdownCache().parse(markdown)
        compose.setContent {
            MaterialTheme {
                StreamingMarkdownContainer(markdown, presentation(markdown, true), document = document) {
                    CompositionLocalProvider(
                        LocalMarkdownLeafSource provides null,
                        LocalMarkdownBlockSource provides MarkdownSourceText.original("正文"),
                    ) {
                        MarkdownInlineText("正文", defaultMarkdownColors(), isStreaming = true)
                    }
                }
            }
        }

        assertEquals("匹配整块来源仍可定位末尾空行", 3, textLayout("正文\n\n").lineCount)
    }

    private fun renderDocument(markdown: String, active: Boolean) {
        val document = StreamingMarkdownCache().parse(markdown)
        compose.setContent {
            MaterialTheme {
                StreamingMarkdownContainer(
                    markdown,
                    presentation(markdown, active),
                    modifier = Modifier.width(320.dp),
                    document = document,
                ) {
                    MarkdownDocumentView(document.blocks, isStreaming = active)
                }
            }
        }
    }

    private fun presentation(markdown: String, active: Boolean) = StreamingTextPresentation(
        batchId = 1,
        sourceRevealStart = 0,
        sourceRevealEnd = markdown.length,
        active = active,
        reduceMotion = true,
    )

    private fun renderedTexts(): List<String> = compose.onAllNodes(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().flatMap { node -> node.config[SemanticsProperties.Text].map { it.text } }

    private fun textLayout(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
        return layouts.single()
    }

    private companion object {
        const val ShortTable = "| A | B |\n| --- | --- |\n| value |"
    }
}
