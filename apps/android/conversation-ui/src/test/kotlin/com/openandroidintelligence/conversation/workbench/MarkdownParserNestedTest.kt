package com.openandroidintelligence.conversation.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 块级解析的新能力：GFM 任务列表、嵌套缩进、多级引用与数学块。
 * 这些用例同时保证流式文本解析不抛异常。
 */
class MarkdownParserNestedTest {

    @Test
    fun parseNestedTaskListKeepsLevelsAndStates() {
        val input = """
            - [x] 已完成项
              - [ ] 子任务
                - 孙任务
            - [ ] 未完成项
        """.trimIndent()

        val blocks = parseMarkdownBlocks(input)
        assertEquals(1, blocks.size)
        val list = blocks.single() as TimelineBlock.ListBlock
        assertEquals(listOf(0, 1, 2, 0), list.items.map { it.level })
        assertEquals(
            listOf(TaskState.CHECKED, TaskState.UNCHECKED, TaskState.NONE, TaskState.UNCHECKED),
            list.items.map { it.taskState },
        )
        assertEquals(listOf("已完成项", "子任务", "孙任务", "未完成项"), list.items.map { it.text })
    }

    @Test
    fun parseFourSpaceIndentedListUsesDetectedStep() {
        val input = """
            - 一级
                - 二级
                    - 三级
        """.trimIndent()

        val list = parseMarkdownBlocks(input).single() as TimelineBlock.ListBlock
        assertEquals(listOf(0, 1, 2), list.items.map { it.level })
        assertEquals(listOf("一级", "二级", "三级"), list.items.map { it.text })
    }

    @Test
    fun parseOrderedListRestartsNumberingUnderEachParentItem() {
        val input = """
            1. 第一步
               1. 子步骤 A
               2. 子步骤 B
            2. 第二步
               1. 子步骤 C
        """.trimIndent()

        val list = parseMarkdownBlocks(input).single() as TimelineBlock.ListBlock
        assertEquals(listOf(0, 1, 1, 0, 1), list.items.map { it.level })
        assertEquals(listOf(1, 1, 2, 2, 1), list.items.map { it.number })
    }

    @Test
    fun parseMultiLevelBlockquoteRecursively() {
        val input = """
            > 一级引用
            >
            >> 二级引用
            >>> 三级引用
        """.trimIndent()

        val blocks = parseMarkdownBlocks(input)
        assertEquals(1, blocks.size)

        val levelOne = blocks[0] as TimelineBlock.Blockquote
        assertEquals(TimelineBlock.Paragraph("一级引用"), levelOne.blocks[0])

        val levelTwo = levelOne.blocks[1] as TimelineBlock.Blockquote
        assertEquals(TimelineBlock.Paragraph("二级引用"), levelTwo.blocks[0])

        val levelThree = levelTwo.blocks[1] as TimelineBlock.Blockquote
        assertEquals(TimelineBlock.Paragraph("三级引用"), levelThree.blocks[0])
    }

    @Test
    fun parseBlockquoteContainingListAndCodeFence() {
        val input = """
            > 引用里的列表：
            > - 第一项
            > - 第二项
            >
            > ```bash
            > adb devices
            > ```
        """.trimIndent()

        val quote = parseMarkdownBlocks(input).single() as TimelineBlock.Blockquote
        assertEquals(TimelineBlock.Paragraph("引用里的列表："), quote.blocks[0])
        assertEquals(2, (quote.blocks[1] as TimelineBlock.ListBlock).items.size)
        val code = quote.blocks[2] as TimelineBlock.CodeBlock
        assertEquals("bash", code.language)
        assertEquals("adb devices", code.code)
    }

    @Test
    fun parseBlockMathKeepsLatexWithoutDollarSigns() {
        val input = """
            质能方程：
            ${'$'}${'$'}
            E = mc^2
            ${'$'}${'$'}
            行内公式 ${'$'}${'$'}a^2+b^2=c^2${'$'}${'$'} 结束
        """.trimIndent()

        val blocks = parseMarkdownBlocks(input)
        assertEquals(3, blocks.size)
        assertEquals(TimelineBlock.Paragraph("质能方程："), blocks[0])
        assertEquals(TimelineBlock.MathBlock("E = mc^2"), blocks[1])
        assertEquals(
            TimelineBlock.Paragraph("行内公式 ${'$'}${'$'}a^2+b^2=c^2${'$'}${'$'} 结束"),
            blocks[2],
        )
    }

    @Test
    fun parseUnclosedBlockMathDuringStreamingDoesNotExposeDollarMarkers() {
        val input = """
            推导如下：
            ${'$'}${'$'}
            \int_0^\infty e^{-x^2} dx
        """.trimIndent()

        val blocks = parseMarkdownBlocks(input)
        assertEquals(2, blocks.size)
        val math = blocks[1] as TimelineBlock.MathBlock
        assertEquals("\\int_0^\\infty e^{-x^2} dx", math.latex)
    }

    @Test
    fun parseStreamingNestedListPrefixDoesNotThrow() {
        val input = "- [x] 已完成\n  - [ ] 写了一半"
        val list = parseMarkdownBlocks(input).single() as TimelineBlock.ListBlock
        assertEquals(2, list.items.size)
        assertEquals(1, list.items[1].level)
        assertTrue(list.items[1].text.isNotBlank())
    }
    @Test(timeout = 2_000)
    fun emptyMathDelimitersNeverHangTheParser() {
        // 流式输出刚收到 `$$`、公式体还没到时必然经过这些输入
        listOf("$$", "$$\n", "$$\n\n", "$$\n$$", "$$$$").forEach { input ->
            val blocks = parseMarkdownBlocks(input)
            assertTrue("空公式定界符不能吞掉文本：$input", blocks.isNotEmpty())
        }
    }

    @Test(timeout = 2_000)
    fun mathBlockWithTrailingTextDoesNotSwallowFollowingBlocks() {
        val blocks = parseMarkdownBlocks("${'$'}${'$'}x${'$'}${'$'} tail\n# 标题")

        val paragraph = blocks.firstOrNull() as? TimelineBlock.Paragraph
        assertTrue("闭合后还有正文时应降级为段落", paragraph?.text?.contains("tail") == true)
        assertTrue("后续标题不能被公式吞掉", blocks.any { it is TimelineBlock.Heading })
    }

    @Test(timeout = 5_000)
    fun deepBlockquoteNestingDegradesInsteadOfOverflowing() {
        val input = ">".repeat(2_000) + " 深层文本"

        val blocks = parseMarkdownBlocks(input)

        var depth = 0
        var current: List<TimelineBlock> = blocks
        while (true) {
            val quote = current.firstOrNull() as? TimelineBlock.Blockquote ?: break
            depth++
            current = quote.blocks
        }
        assertTrue("引用递归必须有深度上限", depth <= 8)
        assertTrue("超限内容仍要保留", blocks.toString().contains("深层文本"))
    }

    @Test
    fun listBaseIndentUsesMinimumInsteadOfFirstItem() {
        val blocks = parseMarkdownBlocks("  - a\n- b\n  - c")

        val items = (blocks.single() as TimelineBlock.ListBlock).items
        assertEquals(1, items[0].level)
        assertEquals(0, items[1].level)
        assertEquals(1, items[2].level)
    }

}
