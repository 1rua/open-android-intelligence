package com.openandroidintelligence.conversation.workbench

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.CompositionLocalProvider
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.MotionPolicy
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThoughtCardTest {

    @get:Rule
    val compose = createComposeRule()

    private class FakeClock(var now: Long = 0L)

    // ==========================================
    // 1. MarkdownParser AST 解析测试
    // ==========================================

    @Test
    fun closedThoughtTagParsesAsCompleteThoughtBlock() {
        val markdown = "<think>思考完成，正在组织回答</think>这是最终答案。"
        val blocks = MarkdownParser.parse(markdown)

        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is TimelineBlock.ThoughtBlock)
        val thoughtBlock = blocks[0] as TimelineBlock.ThoughtBlock
        assertEquals("思考完成，正在组织回答", thoughtBlock.thought)
        assertTrue(thoughtBlock.isComplete)

        assertTrue(blocks[1] is TimelineBlock.Paragraph)
        assertEquals("这是最终答案。", (blocks[1] as TimelineBlock.Paragraph).text)
    }

    @Test
    fun unclosedThoughtTagParsesAsIncompleteThoughtBlockStreaming() {
        val markdown = "<think>正在分析系统状态，尚未生成闭合标签..."
        val blocks = MarkdownParser.parse(markdown)

        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is TimelineBlock.ThoughtBlock)
        val thoughtBlock = blocks[0] as TimelineBlock.ThoughtBlock
        assertEquals("正在分析系统状态，尚未生成闭合标签...", thoughtBlock.thought)
        assertFalse(thoughtBlock.isComplete)
    }

    @Test
    fun thoughtWithTextBeforeAndAfterParsesCorrectly() {
        val markdown = """
            这是开头的说明文字。
            <think>
            正在思考第一步...
            正在思考第二步...
            </think>
            这是思考后的结论。
        """.trimIndent()

        val blocks = MarkdownParser.parse(markdown)
        assertEquals(3, blocks.size)

        assertTrue(blocks[0] is TimelineBlock.Paragraph)
        assertEquals("这是开头的说明文字。", (blocks[0] as TimelineBlock.Paragraph).text)

        assertTrue(blocks[1] is TimelineBlock.ThoughtBlock)
        val thoughtBlock = blocks[1] as TimelineBlock.ThoughtBlock
        assertTrue(thoughtBlock.thought.contains("正在思考第一步..."))
        assertTrue(thoughtBlock.thought.contains("正在思考第二步..."))
        assertTrue(thoughtBlock.isComplete)

        assertTrue(blocks[2] is TimelineBlock.Paragraph)
        assertEquals("这是思考后的结论。", (blocks[2] as TimelineBlock.Paragraph).text)
    }

    @Test
    fun multipleThoughtBlocksParseInOrder() {
        val markdown = "<think>步骤一</think>过渡文字<think>步骤二</think>总结"
        val blocks = MarkdownParser.parse(markdown)

        assertEquals(4, blocks.size)
        assertTrue(blocks[0] is TimelineBlock.ThoughtBlock)
        assertEquals("步骤一", (blocks[0] as TimelineBlock.ThoughtBlock).thought)

        assertTrue(blocks[1] is TimelineBlock.Paragraph)
        assertEquals("过渡文字", (blocks[1] as TimelineBlock.Paragraph).text)

        assertTrue(blocks[2] is TimelineBlock.ThoughtBlock)
        assertEquals("步骤二", (blocks[2] as TimelineBlock.ThoughtBlock).thought)

        assertTrue(blocks[3] is TimelineBlock.Paragraph)
        assertEquals("总结", (blocks[3] as TimelineBlock.Paragraph).text)
    }

    @Test
    fun emptyThoughtTagsParseGracefully() {
        val closedEmpty = MarkdownParser.parse("<think></think>")
        assertEquals(1, closedEmpty.size)
        assertTrue(closedEmpty[0] is TimelineBlock.ThoughtBlock)
        assertEquals("", (closedEmpty[0] as TimelineBlock.ThoughtBlock).thought)
        assertTrue((closedEmpty[0] as TimelineBlock.ThoughtBlock).isComplete)

        val unclosedEmpty = MarkdownParser.parse("<think>")
        assertEquals(1, unclosedEmpty.size)
        assertTrue(unclosedEmpty[0] is TimelineBlock.ThoughtBlock)
        assertEquals("", (unclosedEmpty[0] as TimelineBlock.ThoughtBlock).thought)
        assertFalse((unclosedEmpty[0] as TimelineBlock.ThoughtBlock).isComplete)
    }

    @Test
    fun parseNarrativeDoesNotRecognizeExecutionBlocks() {
        val text = "<think>内部思考</think><tool_call name=\"adb_shell\"><command>ls</command></tool_call>"

        val blocks = MarkdownParser.parseNarrative(text)

        assertTrue(blocks.isNotEmpty())
        assertFalse(blocks.any { it is TimelineBlock.ThoughtBlock })
        assertFalse(blocks.any { it is TimelineBlock.ToolCallBlock })
        assertTrue((blocks.first() as TimelineBlock.Paragraph).text.contains("<think>"))
    }

    @Test
    fun parseNarrativeKeepsToolCallFenceAsCodeBlock() {
        val blocks = MarkdownParser.parseNarrative("```tool_call\n<command>ls</command>\n```")

        assertEquals(1, blocks.size)
        val codeBlock = blocks.single() as TimelineBlock.CodeBlock
        assertEquals("tool_call", codeBlock.language)
        assertTrue(codeBlock.code.contains("<command>ls</command>"))
    }

    @Test
    fun parseNarrativeStillParsesOrdinaryMarkdown() {
        val blocks = MarkdownParser.parseNarrative("# 标题\n\n- 第一项\n- 第二项\n\n**重点**")

        assertTrue(blocks.any { it is TimelineBlock.Heading })
        assertTrue(blocks.any { it is TimelineBlock.ListBlock })
        assertTrue(blocks.any { it is TimelineBlock.Paragraph })
    }

    @Test
    fun parseNarrativeOfEmptyTextReturnsSingleParagraph() {
        val blocks = MarkdownParser.parseNarrative("")

        assertEquals(1, blocks.size)
        assertTrue(blocks.single() is TimelineBlock.Paragraph)
    }

    // ==========================================
    // 2. Compose UI 呈现与交互测试
    // ==========================================

    @Test
    fun incompleteThoughtDefaultsToExpandedAndShowsThinkingHeader() {
        val block = TimelineBlock.ThoughtBlock(
            thought = "正在推演量子算法与经典比特的区别...",
            isComplete = false,
        )

        compose.setContent {
            MaterialTheme {
                MarkdownThoughtBlockView(block = block, isStreaming = true)
            }
        }

        compose.onNodeWithText("AI 正在思考", substring = true).assertIsDisplayed()
        compose.onNodeWithText("正在推演量子算法与经典比特的区别...", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun streamingThoughtRendersMarkdownBodyAndKeepsExecutionTagsLiteral() {
        val block = TimelineBlock.ThoughtBlock(
            thought = "**加粗结论**\n\n<tool_call name=\"adb_shell\"><command>ls</command></tool_call>",
            isComplete = false,
        )

        compose.setContent {
            MaterialTheme {
                MarkdownThoughtBlockView(block = block, isStreaming = true)
            }
        }

        // Markdown 粗体被渲染成样式文本；<tool_call> 保持普通文本，不再生成工具卡。
        compose.onNodeWithText("加粗结论", substring = true).assertIsDisplayed()
        compose.onNodeWithText("[adb_shell]").assertDoesNotExist()
    }

    @Test
    fun completedThoughtDefaultsToCollapsedAndShowsDeepThoughtHeader() {
        val thoughtText = "这里是已完成的思考过程"
        val block = TimelineBlock.ThoughtBlock(thought = thoughtText, isComplete = true)

        compose.setContent {
            MaterialTheme {
                MarkdownThoughtBlockView(block = block, isStreaming = false)
            }
        }

        compose.onNodeWithText("已深度思考", substring = true).assertIsDisplayed()
        compose.onNodeWithText("${thoughtText.length} 字", substring = true).assertIsDisplayed()
        compose.onNodeWithText("点击展开", substring = true).assertIsDisplayed()

        // 完成态默认收起，正文不参与组合。
        compose.onNodeWithText(thoughtText).assertDoesNotExist()
    }

    @Test
    fun clickingCollapsedThoughtHeaderTogglesExpandAndCollapse() {
        val thoughtText = "深度思考：校验设备策略与指纹契约"
        val block = TimelineBlock.ThoughtBlock(thought = thoughtText, isComplete = true)

        compose.setContent {
            MaterialTheme {
                MarkdownThoughtBlockView(block = block, isStreaming = false)
            }
        }

        compose.onNodeWithText("点击展开", substring = true).assertIsDisplayed()
        compose.onNodeWithText(thoughtText).assertDoesNotExist()

        compose.onNodeWithText("已深度思考", substring = true).performClick()

        compose.onNodeWithText(thoughtText, substring = true).assertIsDisplayed()
        compose.onNodeWithText("点击收起", substring = true).assertIsDisplayed()

        compose.onNodeWithText("点击收起", substring = true).performClick()

        compose.onNodeWithText("点击展开", substring = true).assertIsDisplayed()
        compose.onNodeWithText(thoughtText).assertDoesNotExist()
    }

    @Test
    fun interruptedThoughtShowsWarningHeaderAndKeepsBodyCollapsed() {
        val thoughtText = "流式分析到一半，连接被中断"
        val block = TimelineBlock.ThoughtBlock(thought = thoughtText, isComplete = false)

        compose.setContent {
            MaterialTheme {
                MarkdownThoughtBlockView(block = block, isStreaming = false)
            }
        }

        compose.onNodeWithText("思考已中断", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("思考已中断").assertIsDisplayed()
        compose.onNodeWithText("点击展开", substring = true).assertIsDisplayed()
        compose.onNodeWithText(thoughtText).assertDoesNotExist()

        compose.onNodeWithText("思考已中断", substring = true).performClick()

        compose.onNodeWithText(thoughtText, substring = true).assertIsDisplayed()
        compose.onNodeWithText("点击收起", substring = true).assertIsDisplayed()
    }

    @Test
    fun emptyCompletedThoughtShowsNoContentState() {
        val block = TimelineBlock.ThoughtBlock(thought = "", isComplete = true)

        compose.setContent {
            MaterialTheme {
                MarkdownThoughtBlockView(block = block, isStreaming = false)
            }
        }

        compose.onNodeWithText("无思考内容", substring = true).assertIsDisplayed()
        compose.onNodeWithText("已深度思考", substring = true).assertDoesNotExist()
    }

    @Test
    fun emptyStreamingThoughtOnlyShowsThinkingHeader() {
        val block = TimelineBlock.ThoughtBlock(thought = "", isComplete = false)

        compose.setContent {
            MaterialTheme {
                MarkdownThoughtBlockView(block = block, isStreaming = true)
            }
        }

        compose.onNodeWithText("AI 正在思考", substring = true).assertIsDisplayed()
        compose.onNodeWithText("无思考内容", substring = true).assertDoesNotExist()
    }

    @Test
    fun completedThoughtShowsObservedDuration() {
        val clock = FakeClock()
        val store = ExecutionTimingStore { clock.now }
        store.observe("a1#0", running = true)
        clock.now = 2_400L
        store.observe("a1#0", running = false)

        val block = TimelineBlock.ThoughtBlock(thought = "耗时展示", isComplete = true)

        compose.setContent {
            CompositionLocalProvider(LocalExecutionTiming provides store) {
                MaterialTheme {
                    MarkdownThoughtBlockView(
                        block = block,
                        isStreaming = false,
                        timingKey = "a1#0",
                    )
                }
            }
        }

        compose.onNodeWithText("2.4 秒", substring = true).assertIsDisplayed()
    }

    @Test
    fun completedThoughtWithoutObservedIntervalOmitsDuration() {
        val block = TimelineBlock.ThoughtBlock(thought = "没有观察到耗时", isComplete = true)

        compose.setContent {
            CompositionLocalProvider(LocalExecutionTiming provides ExecutionTimingStore { 0L }) {
                MaterialTheme {
                    MarkdownThoughtBlockView(
                        block = block,
                        isStreaming = false,
                        timingKey = "a1#0",
                    )
                }
            }
        }

        compose.onNodeWithText("秒", substring = true).assertDoesNotExist()
    }

    @Test
    fun reducedMotionModeRendersCorrectly() {
        val block = TimelineBlock.ThoughtBlock(
            thought = "无动效模式测试内容",
            isComplete = false,
        )

        compose.setContent {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = true)) {
                MaterialTheme {
                    MarkdownThoughtBlockView(block = block, isStreaming = true)
                }
            }
        }

        compose.onNodeWithText("AI 正在思考", substring = true).assertIsDisplayed()
        compose.onNodeWithText("无动效模式测试内容", substring = true).assertIsDisplayed()
    }
}
