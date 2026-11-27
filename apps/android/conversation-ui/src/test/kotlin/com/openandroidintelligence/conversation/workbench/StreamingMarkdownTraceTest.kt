package com.openandroidintelligence.conversation.workbench

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 对照既有完整解析器，验证流式来源映射不会改变 Markdown 语义或错认旧文字。 */
class StreamingMarkdownTraceTest {
    private val colors = MarkdownColors(Color.Black, Color.Black, Color.Black, Color.Black, Color.White, Color.Blue)

    @Test fun 末尾显式换行只进入活跃文字布局而不改最终Markdown语义() {
        val document = StreamingMarkdownCache().parse("甲\n\n")
        val block = document.blocks.single()
        val source = requireNotNull(document.trace.blocks[block]).leaves.single()
        val laidOut = streamingInlineSource("甲", source, document, true)
        assertEquals("甲\n\n", laidOut.text)
        assertEquals(listOf(0, 1, 2), laidOut.offsets.toList())
        assertEquals("甲", streamingInlineSource("甲", source, document, false).text)
        assertEquals(MarkdownParser.parse("甲\n\n"), document.blocks)
    }

    @Test fun 每个流式前缀和非追加纠正均与完整解析相同() {
        val text = """
            # 标题

            普通 **强调** 与 [链接](https://example.com) 和 &amp;。

            > 引用
            > - 项目

            - [ ] 第一项
              - 子项
            - 第二项

            | 名称 | 值 |
            | --- | --- |
            | 甲 | `代码` |

            ```kotlin
            val a = "你好👩🏽‍💻"
            ```

            尾段 ${'$'}x^2${'$'} 与正文。
        """.trimIndent()
        val cache = StreamingMarkdownCache()
        for (offset in 0..text.length) {
            val prefix = text.substring(0, offset)
            assertEquals("原文偏移 $offset", MarkdownParser.parse(prefix), cache.parse(prefix).blocks)
        }
        assertEquals(MarkdownParser.parse("更正全文"), cache.parse("更正全文").blocks)
    }

    @Test fun 相同字词的列表和表格仍保留各自原文位置() {
        val text = "前段\n\n- 相同\n- 相同\n\n| 相同 | 相同 |\n| --- | --- |\n| 相同 | 相同 |"
        val document = StreamingMarkdownCache().parse(text)
        val list = document.blocks.filterIsInstance<TimelineBlock.ListBlock>().single()
        val table = document.blocks.filterIsInstance<TimelineBlock.Table>().single()
        val listSources = requireNotNull(document.trace.blocks[list]).leaves
        val tableSources = requireNotNull(document.trace.blocks[table]).leaves
        assertEquals(list.items.map { it.text }, listSources.map { it.text })
        assertEquals(table.headers + table.rows.flatten(), tableSources.map { it.text })
        assertTrue(listSources.zipWithNext().all { (left, right) -> left.end < right.start })
        assertTrue(tableSources.zipWithNext().all { (left, right) -> left.end < right.start })
        assertEquals("相同", text.substring(listSources.last().start, listSources.last().end))
    }

    @Test fun 行内公式替代文本与来源数组逐单元对应且后续正文不偏移() {
        val text = "前 ${'$'}x^2+y${'$'} 后 **新字**"
        val inline = buildMarkdownInline(text, colors)
        assertEquals(inline.annotated.length, inline.sourceOffsets.size)
        assertEquals("x^2+y", inline.annotated.substring(inline.inlineMath.single().start, inline.inlineMath.single().end))
        val after = inline.annotated.text.indexOf('后')
        assertEquals(text.indexOf('后'), inline.sourceOffsets[after])
        assertEquals(text.indexOf('新'), inline.sourceOffsets[inline.annotated.text.indexOf('新')])
    }

    @Test fun 闭合强调链接和实体不把旧文字变成新范围() {
        listOf("**你好**", "[你好](https://example.com)", "你好 &amp;").forEach { text ->
            val inline = buildMarkdownInline(text, colors)
            val offsets = sourceOffsetsFor(inline, MarkdownSourceText.original(text))
            val closeStart = when {
                text.startsWith("**") -> text.length - 2
                text.startsWith("[") -> text.indexOf("]")
                else -> text.length - 1
            }
            assertTrue(visibleSourceRanges(inline.annotated.text, offsets, closeStart, text.length).isEmpty())
        }
    }

    @Test fun 最终回答始终由同一次解析的最后执行块后节点组成() {
        val text = "<think>已有过程</think>\n\n最终 **回复**"
        val document = StreamingMarkdownCache().parse(text)
        val finalBlocks = document.blocks.drop(document.blocks.indexOfLast(::isExecutionBlock) + 1)
        assertEquals(listOf(TimelineBlock.Paragraph("最终 **回复**")), finalBlocks)
        assertTrue(requireNotNull(document.trace.blocks[finalBlocks.single()]).source.start > text.indexOf("</think>"))
    }
}
