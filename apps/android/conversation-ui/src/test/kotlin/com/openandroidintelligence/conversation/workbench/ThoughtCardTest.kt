package com.openandroidintelligence.conversation.workbench

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
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
        val paragraphBlock = blocks[1] as TimelineBlock.Paragraph
        assertEquals("这是最终答案。", paragraphBlock.text)
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

        // 验证正在思考提示展示
        compose.onNodeWithText("AI 正在思考", substring = true).assertIsDisplayed()
        // 未完成状态默认展开，显示思考文本
        compose.onNodeWithText("正在推演量子算法与经典比特的区别...", substring = true).assertIsDisplayed()
    }

    @Test
    fun completedThoughtDefaultsToCollapsedAndShowsWordCount() {
        val thoughtText = "这里是已完成的思考过程"
        val block = TimelineBlock.ThoughtBlock(
            thought = thoughtText,
            isComplete = true,
        )

        compose.setContent {
            MaterialTheme {
                MarkdownThoughtBlockView(block = block, isStreaming = false)
            }
        }

        // 已完成状态默认折叠，显示折叠标题与字数
        compose.onNodeWithText("已折叠思考过程", substring = true).assertIsDisplayed()
        compose.onNodeWithText("共 ${thoughtText.length} 字", substring = true).assertIsDisplayed()
        compose.onNodeWithText("点击展开", substring = true).assertIsDisplayed()

        // 正文未展开时不显示
        compose.onNodeWithText("这里是已完成的思考过程").assertDoesNotExist()
    }

    @Test
    fun clickingCollapsedThoughtHeaderTogglesExpandAndCollapse() {
        val thoughtText = "深度思考：校验设备策略与指纹契约"
        val block = TimelineBlock.ThoughtBlock(
            thought = thoughtText,
            isComplete = true,
        )

        compose.setContent {
            MaterialTheme {
                MarkdownThoughtBlockView(block = block, isStreaming = false)
            }
        }

        // 初始状态：折叠
        compose.onNodeWithText("点击展开", substring = true).assertIsDisplayed()
        compose.onNodeWithText(thoughtText).assertDoesNotExist()

        // 点击展开
        compose.onNodeWithText("已折叠思考过程", substring = true).performClick()

        // 展开后状态：显示思考正文与点击收起
        compose.onNodeWithText(thoughtText, substring = true).assertIsDisplayed()
        compose.onNodeWithText("点击收起", substring = true).assertIsDisplayed()

        // 再次点击收起
        compose.onNodeWithText("点击收起", substring = true).performClick()

        // 收起后状态
        compose.onNodeWithText("点击展开", substring = true).assertIsDisplayed()
        compose.onNodeWithText(thoughtText).assertDoesNotExist()
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

