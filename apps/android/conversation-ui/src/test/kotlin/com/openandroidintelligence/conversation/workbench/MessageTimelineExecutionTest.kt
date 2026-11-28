package com.openandroidintelligence.conversation.workbench

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.openandroidintelligence.conversation.state.TimelineEntry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * 回合级执行步骤在真实 [MessageTimeline] 中的界面契约（R3）：
 * - 不使用集中式“执行过程 / 已执行 N 步”折叠框，思考与工具卡片按发生先后顺序直接平铺呈现；
 * - 完成后的单步思考/工具卡默认收起，点击各自独立展开/收起；
 * - 没有执行块的普通多条助手消息保持逐条；
 * - 中断、只有工具没有最终回答时也不伪造回答。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessageTimelineExecutionTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun executionStepsRenderInChronologicalOrderAboveFinalAnswerWithoutGlobalPanel() {
        val entries = listOf(
            user("帮我打开设置"),
            assistant("<think>先确认设备状态</think>", key = "msg_thought", timestamp = 2L),
            assistant(
                """
                <tool_call name="adb_shell">
                  <command>am start -a android.settings.SETTINGS</command>
                  <result status="success">设置已打开</result>
                </tool_call>
                """.trimIndent(),
                key = "msg_tool",
                timestamp = 3L,
            ),
            assistant("设置应用已经打开了。", key = "msg_final", timestamp = 4L),
        )

        compose.setContent {
            MaterialTheme { MessageTimeline(entries) }
        }

        compose.onNodeWithText("已执行", substring = true).assertDoesNotExist()
        compose.onNodeWithText("执行过程", substring = true).assertDoesNotExist()

        val summaryTop = compose.onNodeWithText("1 次工具调用").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        val finalTopCollapsed = compose.onNodeWithText("设置应用已经打开了。", substring = true).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(summaryTop < finalTopCollapsed, "过程折叠大卡片应在最终回答上方")
        compose.onNodeWithText("[adb_shell]", substring = true).assertDoesNotExist()

        compose.onNodeWithText("1 次工具调用").performClick()
        val thoughtTop = compose.onNodeWithText("已思考", substring = true).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        val toolTop = compose.onNodeWithText("[adb_shell]", substring = true).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        val finalTop = compose.onNodeWithText("设置应用已经打开了。", substring = true).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top

        assertTrue(thoughtTop < toolTop, "思考卡片应在工具卡片上方")
        assertTrue(toolTop < finalTop, "工具卡片应在最终回答上方")

        // 单张思考卡完成后默认收起，点击自身可独立展开
        compose.onNodeWithText("先确认设备状态", substring = true).assertDoesNotExist()
        compose.onNodeWithText("已思考", substring = true).performClick()
        compose.onNodeWithText("先确认设备状态", substring = true).assertIsDisplayed()
    }

    @Test
    fun liveExecutionKeepsThoughtVisibleDirectlyBeforeFinalAnswer() {
        val entries = listOf(
            user("现在几点"),
            assistant("<think>正在查询系统时间", key = "msg_live", timestamp = 2L, isStreaming = true),
        )

        compose.setContent {
            MaterialTheme { MessageTimeline(entries) }
        }

        compose.onNodeWithText("执行过程", substring = true).assertDoesNotExist()
        compose.onNodeWithText("正在思考", substring = true).assertIsDisplayed()
        compose.onNodeWithText("正在查询系统时间", substring = true).assertIsDisplayed()
        compose.onNodeWithText("已完成", substring = true).assertDoesNotExist()
    }

    @Test
    fun ordinaryMultipleAssistantMessagesStaySeparate() {
        val entries = listOf(
            user("你好"),
            assistant("第一段回复", key = "msg_1", timestamp = 2L),
            assistant("第二段回复", key = "msg_2", timestamp = 3L),
        )

        compose.setContent {
            MaterialTheme { MessageTimeline(entries) }
        }

        compose.onNodeWithText("第一段回复", substring = true).assertIsDisplayed()
        compose.onNodeWithText("第二段回复", substring = true).assertIsDisplayed()
        compose.onNodeWithText("已完成", substring = true).assertDoesNotExist()
    }

    @Test
    fun finishedToolOnlyTurnRendersCardWithoutInventingAFinalAnswer() {
        val entries = listOf(
            user("执行一下"),
            assistant(
                """
                <tool_call name="adb_shell">
                  <command>input keyevent KEYCODE_HOME</command>
                  <result status="success">已回到桌面</result>
                </tool_call>
                """.trimIndent(),
                key = "msg_tool_only",
                timestamp = 2L,
            ),
        )

        compose.setContent {
            MaterialTheme { MessageTimeline(entries) }
        }

        compose.onNodeWithText("已执行", substring = true).assertDoesNotExist()
        compose.onNodeWithText("执行过程", substring = true).assertDoesNotExist()
        compose.onNodeWithText("[adb_shell]", substring = true).assertIsDisplayed()
        compose.onNodeWithText("已回到桌面", substring = true).assertIsDisplayed()
        compose.onNodeWithText("此消息没有可显示的文本", substring = true).assertDoesNotExist()
    }

    @Test
    fun interruptedThoughtRendersCardWithExplicitInterruptedStatus() {
        val entries = listOf(
            user("分析一下"),
            assistant("<think>还没分析完就中断了", key = "msg_interrupted", timestamp = 2L),
        )

        compose.setContent {
            MaterialTheme { MessageTimeline(entries) }
        }

        compose.onNodeWithText("执行过程", substring = true).assertDoesNotExist()
        compose.onNodeWithText("思考已中断", substring = true).assertIsDisplayed()
        compose.onNodeWithText("还没分析完就中断了", substring = true).assertDoesNotExist()

        compose.onNodeWithText("思考已中断", substring = true).performClick()
        compose.onNodeWithText("还没分析完就中断了", substring = true).assertIsDisplayed()
    }

    @Test
    fun userExpansionSurvivesStreamingIncrementsOfTheFinalAnswer() {
        var streamingText by mutableStateOf("<think>先检查</think>最终答案第一段")
        val entry = assistant("", key = "msg_stream_fold", timestamp = 2L, isStreaming = true)
        compose.setContent {
            val current = entry.copy(text = streamingText)
            MaterialTheme { MessageTimeline(listOf(user("继续"), current)) }
        }

        compose.onNodeWithText("执行过程", substring = true).assertDoesNotExist()
        compose.onNodeWithText("已思考", substring = true).performClick()
        compose.onNodeWithText("先检查", substring = true).assertIsDisplayed()

        compose.runOnIdle { streamingText = "$streamingText，第二段" }

        compose.onNodeWithText("已思考", substring = true).assertIsDisplayed()
        compose.onNodeWithText("先检查", substring = true).assertIsDisplayed()
        compose.onNodeWithText("第二段", substring = true).assertIsDisplayed()
    }

    private fun user(text: String) = TimelineEntry(
        key = "user_$text",
        sender = "user",
        text = text,
        isUser = true,
        timestamp = 1L,
        pendingAcceptance = false,
        batchGroupId = null,
    )

    private fun assistant(
        text: String,
        key: String,
        timestamp: Long,
        isStreaming: Boolean = false,
    ) = TimelineEntry(
        key = key,
        sender = "assistant",
        text = text,
        isUser = false,
        timestamp = timestamp,
        pendingAcceptance = false,
        batchGroupId = null,
        isStreaming = isStreaming,
    )
}
