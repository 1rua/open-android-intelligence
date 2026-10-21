package com.openandroidintelligence.conversation.workbench

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.openandroidintelligence.conversation.model.TimelineAttachment
import com.openandroidintelligence.conversation.state.TimelineEntry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * 执行过程聚合面板与实时内容的 Compose 交互测试（规格 §4.3 / §4.4 / §7.1）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExecutionProcessComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun assistantEntry(
        key: String,
        text: String,
        streaming: Boolean = false,
        attachments: List<TimelineAttachment> = emptyList(),
    ) = TimelineEntry(
        key = key,
        sender = "assistant",
        text = text,
        isUser = false,
        timestamp = 0L,
        pendingAcceptance = false,
        batchGroupId = null,
        attachments = attachments,
        isStreaming = streaming,
    )

    private fun runOf(vararg entries: TimelineEntry): AssistantExecutionRun {
        val item = TimelineDisplayModel().build(entries.toList()).single()
        assertTrue(item is TimelineDisplayItem.Execution, "期望聚合为执行段，实际为 $item")
        return (item as TimelineDisplayItem.Execution).run
    }

    // ==========================================
    // 1. 实时（未聚合）内容
    // ==========================================

    @Test
    fun realtimeExecutionEntryRendersLiveThoughtCardInsteadOfPanel() {
        val entry = assistantEntry("a1", "<think>正在分析设备状态", streaming = true)
        val items = TimelineDisplayModel().build(listOf(entry))
        assertTrue(items.single() is TimelineDisplayItem.Single)

        compose.setContent {
            MaterialTheme {
                AssistantMessageContent(entry)
            }
        }

        compose.onNodeWithText("AI 正在思考", substring = true).assertIsDisplayed()
        compose.onNodeWithText("正在分析设备状态", substring = true).assertIsDisplayed()
        compose.onNodeWithText("已完成", substring = true).assertDoesNotExist()
    }

    @Test
    fun realtimeNarrativeAfterToolCallRendersCompactMarkdown() {
        val entry = assistantEntry(
            "a1",
            "<tool_call name=\"adb_shell\"><command>dumpsys battery</command></tool_call>\n\n正在读取电池信息",
            streaming = true,
        )

        compose.setContent {
            MaterialTheme {
                AssistantMessageContent(entry)
            }
        }

        // 工具已闭合但仍随消息实时呈现；其后的叙述走紧凑 Markdown。
        compose.onNodeWithText("执行成功", substring = true).assertIsDisplayed()
        compose.onNodeWithText("正在读取电池信息", substring = true).assertIsDisplayed()
    }

    @Test
    fun normalEntryKeepsOrdinaryMarkdownRendering() {
        val entry = assistantEntry("a1", "# 遥测总览\n\n系统运行正常")

        compose.setContent {
            MaterialTheme {
                AssistantMessageContent(entry)
            }
        }

        compose.onNodeWithText("遥测总览", substring = true).assertIsDisplayed()
        compose.onNodeWithText("系统运行正常", substring = true).assertIsDisplayed()
        compose.onNodeWithText("AI 正在思考", substring = true).assertDoesNotExist()
    }

    @Test
    fun blankCompletedEntryWithoutAttachmentsShowsFallbackText() {
        val entry = assistantEntry("a1", "")

        compose.setContent {
            MaterialTheme {
                AssistantMessageContent(entry)
            }
        }

        compose.onNodeWithText("此消息没有可显示的文本").assertIsDisplayed()
    }

    // ==========================================
    // 2. 聚合面板
    // ==========================================

    @Test
    fun foldedRunShowsCollapsedPanelThenExpandsToSteps() {
        val run = runOf(
            assistantEntry("a1", "<think>先检查设备连接</think>"),
            assistantEntry("a2", "最终回答：已完成。"),
        )

        compose.setContent {
            MaterialTheme {
                AssistantExecutionTurnBody(run)
            }
        }

        compose.onNodeWithText("已完成 1 步执行与思考", substring = true).assertIsDisplayed()
        compose.onNodeWithText("最终回答：已完成。", substring = true).assertIsDisplayed()
        // 折叠时步骤内容不在组合树里。
        compose.onNodeWithText("已深度思考", substring = true).assertDoesNotExist()

        compose.onNodeWithText("已完成 1 步执行与思考", substring = true).performClick()

        compose.onNodeWithText("执行过程 · 1 步", substring = true).assertIsDisplayed()
        compose.onNodeWithText("已深度思考", substring = true).assertIsDisplayed()
    }

    @Test
    fun executionWithoutFinalAnswerShowsOnlyPanelAndMetadataSurface() {
        val run = runOf(
            assistantEntry(
                "a1",
                "<tool_call name=\"adb_shell\"><command>pm list packages</command></tool_call>",
            ),
        )

        compose.setContent {
            MaterialTheme {
                AssistantExecutionTurnBody(run)
            }
        }

        compose.onNodeWithText("已完成 1 步执行与思考", substring = true).assertIsDisplayed()
        // 没有最终正文：不伪造回答占位，也不显示步骤详情。
        compose.onNodeWithText("执行成功", substring = true).assertDoesNotExist()

        compose.onNodeWithText("已完成 1 步执行与思考", substring = true).performClick()

        compose.onNodeWithText("执行成功", substring = true).assertIsDisplayed()
        // 面板里的工具卡同样按规格默认折叠：再点一次摘要栏才看到命令与输出。
        compose.onNodeWithText("[adb_shell]").performClick()
        compose.onNodeWithText("pm list packages", substring = true).assertIsDisplayed()
    }

    @Test
    fun interruptedRunShowsInterruptedTitleAndWarningIcon() {
        val run = runOf(assistantEntry("a1", "<think>断流思考"))

        compose.setContent {
            MaterialTheme {
                AssistantExecutionTurnBody(run)
            }
        }

        compose.onNodeWithText("含 1 步失败", substring = true).assertIsDisplayed()
        compose.onNodeWithText("已中断", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("执行过程中断").assertIsDisplayed()
    }

    @Test
    fun failedToolStepShowsFailureSuffixAndErrorIcon() {
        val run = runOf(
            assistantEntry(
                "a1",
                "<tool_call name=\"adb_shell\"><command>bad</command>" +
                    "<result status=\"failed\">boom</result></tool_call>",
            ),
        )

        compose.setContent {
            MaterialTheme {
                AssistantExecutionTurnBody(run)
            }
        }

        compose.onNodeWithText("含 1 步失败", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("执行过程包含失败步骤").assertIsDisplayed()
    }

    @Test
    fun userExpandedPanelStaysExpandedAcrossStreamingUpdates() {
        var run by mutableStateOf(
            runOf(
                assistantEntry("a1", "<think>先检查设备连接</think>"),
                assistantEntry("a2", "第一版回答", streaming = true),
            ),
        )

        compose.setContent {
            MaterialTheme {
                AssistantExecutionTurnBody(run)
            }
        }

        compose.onNodeWithText("已完成 1 步执行与思考", substring = true).performClick()
        compose.onNodeWithText("执行过程 · 1 步", substring = true).assertIsDisplayed()

        // 模拟同一轮继续流式输出：run 重建但 key 不变，用户展开状态必须保持。
        compose.runOnIdle {
            run = runOf(
                assistantEntry("a1", "<think>先检查设备连接</think>"),
                assistantEntry("a2", "第二版回答，仍在流式输出", streaming = true),
            )
        }
        compose.waitForIdle()

        compose.onNodeWithText("执行过程 · 1 步", substring = true).assertIsDisplayed()
        compose.onNodeWithText("已深度思考", substring = true).assertIsDisplayed()
    }

    @Test
    fun processAttachmentsSlotRendersAttachmentsInsidePanel() {
        val attachment = TimelineAttachment(draftId = "att-1", filename = "log.txt", mediaType = "text/plain")
        val run = runOf(
            assistantEntry("a1", "<think>思考</think>"),
            assistantEntry("a2", "", attachments = listOf(attachment)),
        )

        compose.setContent {
            MaterialTheme {
                AssistantExecutionTurnBody(
                    run = run,
                    processAttachmentsSlot = { entry -> Text("过程附件:${entry.key}") },
                )
            }
        }

        compose.onNodeWithText("已完成 1 步执行与思考", substring = true).performClick()

        compose.onNodeWithText("过程附件:a2", substring = true).assertIsDisplayed()
    }

    @Test
    fun collapsedPanelExposesAccessibleStateDescription() {
        val run = runOf(
            assistantEntry("a1", "<think>思考</think>"),
            assistantEntry("a2", "回答"),
        )

        compose.setContent {
            MaterialTheme {
                AssistantExecutionTurnBody(run)
            }
        }

        compose.onNodeWithContentDescription(
            "执行过程，已完成 1 步，已收起",
            substring = true,
        ).assertIsDisplayed()

        compose.onNodeWithText("已完成 1 步执行与思考", substring = true).performClick()

        compose.onNodeWithContentDescription(
            "执行过程，已完成 1 步，已展开",
            substring = true,
        ).assertIsDisplayed()
    }
}
