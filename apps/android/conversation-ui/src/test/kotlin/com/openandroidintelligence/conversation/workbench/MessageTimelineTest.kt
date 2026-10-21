package com.openandroidintelligence.conversation.workbench

import androidx.compose.ui.text.LinkAnnotation
import com.openandroidintelligence.conversation.ports.AgentMessageErrorCode
import com.openandroidintelligence.conversation.ports.AgentMessageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTimelineTest {

    @Test
    fun messageStatusTextDoesNotTreatGatewayAcceptanceAsAgentCompletion() {
        assertEquals("等待 Agent 接收", messageStatusLabel(AgentMessageStatus.QUEUED, null))
        assertEquals("Agent 已接收", messageStatusLabel(AgentMessageStatus.DELIVERED, null))
        assertEquals("Agent 处理完成", messageStatusLabel(AgentMessageStatus.COMPLETED, null))
        assertEquals(
            "Agent 处理失败：Agent 无法处理此媒体",
            messageStatusLabel(AgentMessageStatus.FAILED, AgentMessageErrorCode.AGENT_MEDIA_REJECTED),
        )
    }

    @Test
    fun parsePlainTextReturnsSingleParagraph() {
        val input = "Hello, how can I help you today?"
        val blocks = parseMarkdownBlocks(input)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is TimelineBlock.Paragraph)
        assertEquals(input, (blocks[0] as TimelineBlock.Paragraph).text)
    }

    @Test
    fun parseEmptyTextReturnsSingleParagraph() {
        val blocks = parseMarkdownBlocks("")
        assertEquals(1, blocks.size)
        assertEquals("", (blocks[0] as TimelineBlock.Paragraph).text)
    }

    @Test
    fun parseSingleCodeBlock() {
        val input = """
            Here is a script:
            ```bash
            adb shell pm list packages
            ```
            Run it to see packages.
        """.trimIndent()

        val blocks = parseMarkdownBlocks(input)
        assertEquals(3, blocks.size)

        assertEquals("Here is a script:", (blocks[0] as TimelineBlock.Paragraph).text)
        val codeBlock = blocks[1] as TimelineBlock.CodeBlock
        assertEquals("bash", codeBlock.language)
        assertEquals("adb shell pm list packages", codeBlock.code)
        assertEquals("Run it to see packages.", (blocks[2] as TimelineBlock.Paragraph).text)
    }

    @Test
    fun parseCodeBlockWithoutLanguageTagDefaultsToChineseLabel() {
        val input = """
            ```
            val a = 123
            val b = 456
            ```
        """.trimIndent()

        val blocks = parseMarkdownBlocks(input)
        assertEquals(1, blocks.size)
        val codeBlock = blocks[0] as TimelineBlock.CodeBlock
        assertEquals("代码", codeBlock.language)
        assertEquals("val a = 123\nval b = 456", codeBlock.code)
    }

    @Test
    fun parseIncompleteCodeBlockDoesNotThrow() {
        val input = "```incomplete code without closing ticks"
        val blocks = parseMarkdownBlocks(input)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is TimelineBlock.Paragraph)
        assertEquals(input, (blocks[0] as TimelineBlock.Paragraph).text)
    }

    @Test
    fun parseMultipleCodeBlocksSequentially() {
        val input = """
            First block:
            ```json
            {"status": "ok"}
            ```
            Second block:
            ```kotlin
            fun main() {}
            ```
            Done.
        """.trimIndent()

        val blocks = parseMarkdownBlocks(input)
        assertEquals(5, blocks.size)
        assertEquals("First block:", (blocks[0] as TimelineBlock.Paragraph).text)
        assertEquals("json", (blocks[1] as TimelineBlock.CodeBlock).language)
        assertEquals("Second block:", (blocks[2] as TimelineBlock.Paragraph).text)
        assertEquals("kotlin", (blocks[3] as TimelineBlock.CodeBlock).language)
        assertEquals("Done.", (blocks[4] as TimelineBlock.Paragraph).text)
    }

    @Test
    fun parseHeadingsFromLevelOneToThree() {
        val input = """
            # 标题一
            正文段落
            ## 标题二
            ### 标题三
        """.trimIndent()

        val blocks = parseMarkdownBlocks(input)
        assertEquals(4, blocks.size)
        assertEquals(TimelineBlock.Heading(1, "标题一"), blocks[0])
        assertEquals(TimelineBlock.Paragraph("正文段落"), blocks[1])
        assertEquals(TimelineBlock.Heading(2, "标题二"), blocks[2])
        assertEquals(TimelineBlock.Heading(3, "标题三"), blocks[3])
    }

    @Test
    fun parseBlockquoteSingleAndMultiLine() {
        val input = """
            > 提示：点击下方模型卡片可立即切换。
            > 另外，支持快捷命令。
        """.trimIndent()

        val blocks = parseMarkdownBlocks(input)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is TimelineBlock.Blockquote)
        val quote = blocks[0] as TimelineBlock.Blockquote
        // 引用内容现在是递归解析后的块，不再是一整段纯文本
        assertEquals(1, quote.blocks.size)
        assertEquals(
            TimelineBlock.Paragraph("提示：点击下方模型卡片可立即切换。\n另外，支持快捷命令。"),
            quote.blocks[0],
        )
    }

    @Test
    fun parseUnorderedAndOrderedLists() {
        val input = """
            - 跨 App 覆盖
            - 连续收缩为停靠球
            - Live 屏幕共享

            1. 第一步
            2. 第二步
        """.trimIndent()

        val blocks = parseMarkdownBlocks(input)
        assertEquals(2, blocks.size)
        val ul = blocks[0] as TimelineBlock.ListBlock
        assertEquals(3, ul.items.size)
        assertEquals("跨 App 覆盖", ul.items[0].text)
        assertEquals("连续收缩为停靠球", ul.items[1].text)
        assertEquals("Live 屏幕共享", ul.items[2].text)
        assertTrue("无序项不应带序号", ul.items.all { it.number == null })
        assertTrue("普通列表项不应带任务状态", ul.items.all { it.taskState == TaskState.NONE })

        val ol = blocks[1] as TimelineBlock.ListBlock
        assertEquals(2, ol.items.size)
        assertEquals(listOf(1, 2), ol.items.map { it.number })
        assertEquals(listOf("第一步", "第二步"), ol.items.map { it.text })
    }

    @Test
    fun parseMarkdownTableWithAlignments() {
        val input = """
            | 指标项 | 状态 | 详情 |
            | :--- | :---: | ---: |
            | **网关链路** | ✅ 在线 | `tsnet-node` (12ms) |
            | **设计系统** | 🟢 就绪 | `MD3` 动态取色 |
        """.trimIndent()

        val blocks = parseMarkdownBlocks(input)
        assertEquals(1, blocks.size)
        val table = blocks[0] as TimelineBlock.Table
        assertEquals(listOf("指标项", "状态", "详情"), table.headers)
        assertEquals(listOf(TableAlignment.LEFT, TableAlignment.CENTER, TableAlignment.RIGHT), table.alignments)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("**网关链路**", "✅ 在线", "`tsnet-node` (12ms)"), table.rows[0])
        assertEquals(listOf("**设计系统**", "🟢 就绪", "`MD3` 动态取色"), table.rows[1])
    }

    @Test
    fun parseThematicBreakDivider() {
        val input = """
            上方内容
            ---
            下方内容
        """.trimIndent()

        val blocks = parseMarkdownBlocks(input)
        assertEquals(3, blocks.size)
        assertEquals(TimelineBlock.Paragraph("上方内容"), blocks[0])
        assertEquals(TimelineBlock.ThematicBreak, blocks[1])
        assertEquals(TimelineBlock.Paragraph("下方内容"), blocks[2])
    }

    @Test
    fun parseInlineMarkdownElements() {
        val colors = MarkdownColors(
            textColor = androidx.compose.ui.graphics.Color.White,
            boldColor = androidx.compose.ui.graphics.Color.White,
            italicColor = androidx.compose.ui.graphics.Color.Gray,
            codeColor = androidx.compose.ui.graphics.Color.Green,
            codeBackground = androidx.compose.ui.graphics.Color.Black,
            linkColor = androidx.compose.ui.graphics.Color.Blue,
        )
        val raw = "这是 **粗体** 和 *斜体* 以及 `行内代码` 和 [链接](https://example.com) 还有 ~~删除线~~"
        val result = buildMarkdownInline(raw, colors)

        // 文本已剥除 markdown 标记
        assertEquals("这是 粗体 和 斜体 以及 行内代码 和 链接 还有 删除线", result.annotated.text)

        // 链接走 LinkAnnotation.Clickable，不再是裸字符串注解
        val links = result.annotated.getLinkAnnotations(0, result.annotated.length)
        assertEquals(1, links.size)
        val clickable = links[0].item as LinkAnnotation.Clickable
        assertEquals("https://example.com", clickable.tag)
        assertEquals("链接", result.annotated.text.substring(links[0].start, links[0].end))
    }

    @Test
    fun parseMotionPreviewFullReportSample() {
        val input = """
            ### 🔍 代码审查与架构规范验证报告

            已严格对照 **Material Design 3 (MD3)** 规范进行核验：

            - **跨 App 临时覆盖**：具备独立浮层。
            - **连续收缩为停靠球**：返回时平滑转换为 56dp 圆形停靠球。

            ```kotlin
            AssistantFloatingBar(
                onVoice = { startStreaming() }
            )
            ```

            > 系统处于全模态就绪状态，未发现协议异常。

            ✅ **审查结论**：架构合规。
        """.trimIndent()

        val blocks = parseMarkdownBlocks(input)
        assertEquals(6, blocks.size)
        assertEquals(TimelineBlock.Heading(3, "🔍 代码审查与架构规范验证报告"), blocks[0])
        assertTrue(blocks[1] is TimelineBlock.Paragraph)
        assertTrue(blocks[2] is TimelineBlock.ListBlock)
        assertEquals(2, (blocks[2] as TimelineBlock.ListBlock).items.size)
        val codeBlock = blocks[3] as TimelineBlock.CodeBlock
        assertEquals("kotlin", codeBlock.language)
        assertTrue(codeBlock.code.contains("AssistantFloatingBar"))
        val quote = blocks[4] as TimelineBlock.Blockquote
        assertEquals(
            TimelineBlock.Paragraph("系统处于全模态就绪状态，未发现协议异常。"),
            quote.blocks.single(),
        )
        assertTrue(blocks[5] is TimelineBlock.Paragraph)
    }
}

