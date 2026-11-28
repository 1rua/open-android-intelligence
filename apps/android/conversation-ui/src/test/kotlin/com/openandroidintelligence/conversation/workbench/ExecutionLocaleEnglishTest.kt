package com.openandroidintelligence.conversation.workbench

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.model.MessageReasoning
import com.openandroidintelligence.conversation.model.NativeExecutionStatus
import com.openandroidintelligence.conversation.model.NativeExecutionStep
import com.openandroidintelligence.conversation.state.TimelineEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 英文环境下的工具卡片状态文案与回合折叠大卡文案回归测试（覆盖 `values-en/strings_conversation.xml`）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w411dp-h891dp-420dpi")
class ExecutionLocaleEnglishTest {

    @get:Rule
    val compose = createComposeRule()

    private fun assistantEntry(
        key: String,
        text: String,
        generationId: String? = null,
        reasoning: List<MessageReasoning> = emptyList(),
        executionSteps: List<NativeExecutionStep> = emptyList(),
        executionGenerationState: GenerationState? = GenerationState.COMPLETED,
    ) = TimelineEntry(
        key = key,
        sender = "assistant",
        text = text,
        isUser = false,
        timestamp = 0L,
        pendingAcceptance = false,
        batchGroupId = null,
        isStreaming = false,
        generationId = generationId,
        reasoning = reasoning,
        executionSteps = executionSteps,
        executionGenerationState = executionGenerationState,
    )

    @Test
    fun englishToolCardRendersNoResultAndInterruptedNoResultStatuses() {
        val unknownBlock = TimelineBlock.ToolCallBlock(
            toolName = "read_file",
            command = """{"path":"README.md"...""",
            output = null,
            isSuccess = true,
            isRunning = false,
            executionStatus = NativeExecutionStatus.UNKNOWN,
        )
        val interruptedBlock = TimelineBlock.ToolCallBlock(
            toolName = "bash",
            command = "sleep 30",
            output = null,
            isSuccess = true,
            isRunning = true,
            executionStatus = null,
        )

        compose.setContent {
            MaterialTheme {
                Column {
                    MarkdownToolCallView(block = unknownBlock, isStreaming = false)
                    MarkdownToolCallView(block = interruptedBlock, isStreaming = false)
                }
            }
        }

        // 折叠态展示英文状态文案，不回落中文
        compose.onNodeWithText("No result returned").assertIsDisplayed()
        compose.onNodeWithContentDescription("No result returned").assertIsDisplayed()
        compose.onNodeWithText("Interrupted · No result returned").assertIsDisplayed()
        compose.onNodeWithContentDescription("Interrupted").assertIsDisplayed()
        compose.onNodeWithText("未返回结果", substring = true).assertDoesNotExist()
        compose.onNodeWithText("执行已中断", substring = true).assertDoesNotExist()

        // 展开两条卡片后，参数/命令/截断提示/未返回结果/复制按钮均呈现英文
        compose.onNodeWithText("No result returned").performClick()
        compose.onNodeWithText("Arguments").assertIsDisplayed()
        compose.onNodeWithText("Arguments may be truncated").assertIsDisplayed()
        compose.onNodeWithText("Copy").assertIsDisplayed()
        compose.onAllNodesWithText("No result returned").assertCountEquals(2)

        compose.onNodeWithText("Interrupted · No result returned").performClick()
        compose.onNodeWithText("Command").assertIsDisplayed()
        compose.onAllNodesWithText("No result returned").assertCountEquals(3)
    }

    @Test
    fun englishToolCardRendersOtherStatusesAndDetailLabels() {
        val runningBlock = TimelineBlock.ToolCallBlock(
            toolName = "bash",
            command = "ls",
            output = null,
            isSuccess = true,
            isRunning = true,
            executionStatus = NativeExecutionStatus.RUNNING,
        )
        val failedBlock = TimelineBlock.ToolCallBlock(
            toolName = "bash",
            command = "bad",
            output = "permission denied",
            isSuccess = false,
            isRunning = false,
            executionStatus = NativeExecutionStatus.FAILED,
        )
        val completedBlock = TimelineBlock.ToolCallBlock(
            toolName = "read_file",
            command = "{}",
            output = "ok",
            isSuccess = true,
            isRunning = false,
            executionStatus = NativeExecutionStatus.COMPLETED,
        )
        val invocationOnlyBlock = TimelineBlock.ToolCallBlock(
            toolName = "ping",
            command = "",
            output = null,
            isSuccess = true,
            isRunning = false,
            invocationOnly = true,
        )

        compose.setContent {
            MaterialTheme {
                Column {
                    MarkdownToolCallView(block = runningBlock, isStreaming = true)
                    MarkdownToolCallView(block = failedBlock, isStreaming = false)
                    MarkdownToolCallView(block = completedBlock, isStreaming = false)
                    MarkdownToolCallView(block = invocationOnlyBlock, isStreaming = false)
                }
            }
        }

        compose.onNodeWithText("Running").assertIsDisplayed()
        compose.onNodeWithText("Failed").assertIsDisplayed()
        compose.onNodeWithText("Completed").assertIsDisplayed()
        compose.onNodeWithText("Called").assertIsDisplayed()

        compose.onNodeWithText("Failed").performClick()
        compose.onNodeWithText("Error output").assertIsDisplayed()

        compose.onNodeWithText("Completed").performClick()
        compose.onNodeWithText("Output").assertIsDisplayed()

        compose.onNodeWithText("Called").performClick()
        compose.onNodeWithText("This call returned no value").assertIsDisplayed()
    }

    @Test
    fun englishTurnProcessCollapseCardRendersSingularPluralThoughtOnlyAndToggleLabels() {
        val singleToolTurn = listOf(
            assistantEntry(
                key = "single-tool",
                text = "Single tool done.",
                generationId = "g-single",
                executionSteps = listOf(
                    NativeExecutionStep("s1", "ev1", "read_file", "{}", "ok", NativeExecutionStatus.COMPLETED),
                ),
            ),
        )
        val multiToolTurn = listOf(
            assistantEntry(
                key = "multi-tool",
                text = "Two tools done.",
                generationId = "g-multi",
                executionSteps = listOf(
                    NativeExecutionStep("s2", "ev2", "read_file", "{}", "ok1", NativeExecutionStatus.COMPLETED),
                    NativeExecutionStep("s3", "ev3", "bash", "ls", "ok2", NativeExecutionStatus.COMPLETED),
                ),
            ),
        )
        val thoughtOnlyTurn = listOf(
            assistantEntry(
                key = "thought-only",
                text = "<think>First thought</think>\nMiddle note\n<think>Second thought</think>\nFinal conclusion.",
                generationId = "g-thought",
            ),
        )

        compose.setContent {
            MaterialTheme {
                Column {
                    MessageTimeline(singleToolTurn, generation = GenerationState.COMPLETED)
                    MessageTimeline(multiToolTurn, generation = GenerationState.COMPLETED)
                    MessageTimeline(thoughtOnlyTurn, generation = GenerationState.COMPLETED)
                }
            }
        }

        compose.onNodeWithText("1 tool call").assertIsDisplayed()
        compose.onNodeWithText("2 tool calls").assertIsDisplayed()
        compose.onNodeWithText("Thought process").assertIsDisplayed()
        compose.onNodeWithText("次工具调用", substring = true).assertDoesNotExist()
        compose.onNodeWithText("思考过程", substring = true).assertDoesNotExist()

        // 核验折叠大卡的可点击头部无障碍状态描述与点击动作标签均为英文，并随展开/收起切换
        val multiHeader = compose.onNodeWithText("2 tool calls")
        val collapsedConfig = multiHeader.fetchSemanticsNode().config
        assertEquals("Collapsed", collapsedConfig[SemanticsProperties.StateDescription])
        assertEquals("Expand process details", collapsedConfig[SemanticsActions.OnClick].label)

        multiHeader.performClick()
        val expandedConfig = multiHeader.fetchSemanticsNode().config
        assertEquals("Expanded", expandedConfig[SemanticsProperties.StateDescription])
        assertEquals("Collapse process details", expandedConfig[SemanticsActions.OnClick].label)
        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"),
        ).assertIsDisplayed()
    }
}
