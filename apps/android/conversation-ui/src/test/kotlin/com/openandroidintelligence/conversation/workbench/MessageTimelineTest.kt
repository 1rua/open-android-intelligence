package com.openandroidintelligence.conversation.workbench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageTimelineTest {

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
}

