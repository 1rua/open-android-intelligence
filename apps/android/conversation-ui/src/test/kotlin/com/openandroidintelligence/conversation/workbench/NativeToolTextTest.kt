package com.openandroidintelligence.conversation.workbench

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Native tool attribution requires host metadata; prose is never execution evidence. */
class NativeToolTextTest {
    @Test
    fun toolNamesInOrdinaryRepliesRemainProse() {
        listOf("Tool call: execute_code", "调用工具：read_file", "Calling tool: browser").forEach { text ->
            val blocks = MarkdownParser.parse(text)
            assertFalse("Ordinary reply was turned into a tool: $text", blocks.any { it is TimelineBlock.ToolCallBlock })
            assertTrue(blocks.any { it is TimelineBlock.Paragraph && it.text == text })
        }
    }

    @Test
    fun activityWordsDoNotInventToolCalls() {
        val text = "Running code examples\nReading documentation\nLooking at the image"
        val blocks = MarkdownParser.parse(text)
        assertFalse("Activity words cannot establish tool execution", blocks.any { it is TimelineBlock.ToolCallBlock })
        assertTrue(blocks.any { it is TimelineBlock.Paragraph && it.text == text })
    }

    @Test
    fun explicitToolMarkupRemainsSupported() {
        assertTrue(MarkdownParser.parse("<tool_call name=\"read_file\"><command>config</command></tool_call>")
            .any { it is TimelineBlock.ToolCallBlock })
        assertTrue(MarkdownParser.parse("```tool:read_file\nconfig\n```")
            .any { it is TimelineBlock.ToolCallBlock })
    }
}
