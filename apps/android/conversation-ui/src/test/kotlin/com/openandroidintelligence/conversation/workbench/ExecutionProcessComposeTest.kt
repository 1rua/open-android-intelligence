package com.openandroidintelligence.conversation.workbench

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.model.NativeExecutionStatus
import com.openandroidintelligence.conversation.model.NativeExecutionStep
import com.openandroidintelligence.conversation.model.TimelineAttachment
import com.openandroidintelligence.conversation.state.TimelineEntry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * 真实时间线宿主下的执行步骤与交互测试（R3：无外层集中折叠框，单步自洽展开/收起）。
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
        generationId: String? = null,
        executionSteps: List<NativeExecutionStep> = emptyList(),
        executionGenerationState: GenerationState? = null,
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
        generationId = generationId,
        executionSteps = executionSteps,
        executionGenerationState = executionGenerationState,
    )

    private fun runOf(vararg entries: TimelineEntry): AssistantExecutionRun {
        val item = TimelineDisplayModel().build(entries.toList()).single()
        assertTrue(item is TimelineDisplayItem.Execution, "期望生成执行段，实际为 $item")
        return (item as TimelineDisplayItem.Execution).run
    }

    @Test
    fun completedGenerationDoesNotShowLiveCursorWhenToolMarkupIsUnclosed() {
        val entry = assistantEntry("tool", "<tool_call name=\"read_file\"><command>Reading config.yaml...</command>", streaming = true)

        compose.setContent {
            MaterialTheme {
                MessageTimeline(listOf(entry), generation = GenerationState.COMPLETED)
            }
        }

        compose.onNodeWithText("执行过程", substring = true).assertDoesNotExist()
        compose.onNodeWithText("已执行", substring = true).assertDoesNotExist()
        compose.onNodeWithText("[read_file]").assertIsDisplayed()
        compose.onNodeWithText("执行已中断", substring = true).assertIsDisplayed()
        compose.onNodeWithText("正在输出", substring = true).assertDoesNotExist()
        compose.onNodeWithText("执行中", substring = true).assertDoesNotExist()
    }

    @Test
    fun completedPlainActivityLogDoesNotKeepAStreamingCursor() {
        val entry = assistantEntry("log", "Reading config.yaml...", streaming = true)

        compose.setContent {
            MaterialTheme {
                MessageTimeline(listOf(entry), generation = GenerationState.COMPLETED)
            }
        }

        compose.onNodeWithText("Reading config.yaml...", substring = true).assertIsDisplayed()
        compose.onNodeWithText("正在输出", substring = true).assertDoesNotExist()
    }

    @Test
    fun realtimeExecutionEntryKeepsLiveThoughtVisibleDirectly() {
        val entry = assistantEntry("a1", "<think>正在分析设备状态", streaming = true)

        compose.setContent {
            MaterialTheme {
                MessageTimeline(listOf(entry), generation = GenerationState.RUNNING)
            }
        }

        compose.onNodeWithText("执行过程", substring = true).assertDoesNotExist()
        compose.onNodeWithText("已执行", substring = true).assertDoesNotExist()
        compose.onNodeWithText("正在思考", substring = true).assertIsDisplayed()
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
                MessageTimeline(listOf(entry), generation = GenerationState.RUNNING)
            }
        }

        compose.onNodeWithText("执行过程", substring = true).assertDoesNotExist()
        compose.onNodeWithText("执行成功", substring = true).assertIsDisplayed()
        compose.onNodeWithText("正在读取电池信息", substring = true).assertIsDisplayed()
    }

    @Test
    fun normalEntryKeepsOrdinaryMarkdownRendering() {
        val entry = assistantEntry("a1", "# 遥测总览\n\n系统运行正常")

        compose.setContent {
            MaterialTheme {
                MessageTimeline(listOf(entry), generation = GenerationState.COMPLETED)
            }
        }

        compose.onNodeWithText("遥测总览", substring = true).assertIsDisplayed()
        compose.onNodeWithText("系统运行正常", substring = true).assertIsDisplayed()
        compose.onNodeWithText("正在思考", substring = true).assertDoesNotExist()
    }

    @Test
    fun blankCompletedEntryWithoutAttachmentsShowsNoFallbackText() {
        val entry = assistantEntry("a1", "")

        compose.setContent {
            MaterialTheme {
                MessageTimeline(listOf(entry), generation = GenerationState.COMPLETED)
            }
        }

        compose.onNodeWithText("此消息没有可显示的文本").assertDoesNotExist()
    }

    @Test
    fun completedTurnShowsCollapsedThoughtCardDirectlyThenExpandsOnClick() {
        val entries = listOf(
            assistantEntry("a1", "<think>先检查设备连接</think>"),
            assistantEntry("a2", "最终回答：已完成。"),
        )

        compose.setContent {
            MaterialTheme {
                MessageTimeline(entries, generation = GenerationState.COMPLETED)
            }
        }

        compose.onNodeWithText("已执行", substring = true).assertDoesNotExist()
        compose.onNodeWithText("执行过程", substring = true).assertDoesNotExist()
        compose.onNodeWithText("已思考", substring = true).assertIsDisplayed()
        compose.onNodeWithText("最终回答：已完成。", substring = true).assertIsDisplayed()
        compose.onNodeWithText("先检查设备连接", substring = true).assertDoesNotExist()

        compose.onNodeWithText("已思考", substring = true).performClick()
        compose.onNodeWithText("先检查设备连接", substring = true).assertIsDisplayed()
    }

    @Test
    fun executionWithoutFinalAnswerShowsOnlyStepCards() {
        val entry = assistantEntry(
            "a1",
            "<tool_call name=\"adb_shell\"><command>pm list packages</command></tool_call>",
        )

        compose.setContent {
            MaterialTheme {
                MessageTimeline(listOf(entry), generation = GenerationState.COMPLETED)
            }
        }

        compose.onNodeWithText("已执行", substring = true).assertDoesNotExist()
        compose.onNodeWithText("执行过程", substring = true).assertDoesNotExist()
        compose.onNodeWithText("此消息没有可显示的文本").assertDoesNotExist()
        compose.onNodeWithText("[adb_shell]").assertIsDisplayed()
        compose.onNodeWithText("执行成功", substring = true).assertIsDisplayed()

        compose.onNodeWithText("[adb_shell]").performClick()
        compose.onNodeWithText("pm list packages", substring = true).assertIsDisplayed()
    }

    @Test
    fun interruptedRunShowsInterruptedThoughtCardAndWarningIcon() {
        val entry = assistantEntry("a1", "<think>断流思考")

        compose.setContent {
            MaterialTheme {
                MessageTimeline(listOf(entry), generation = GenerationState.CANCELLED)
            }
        }

        compose.onNodeWithText("执行过程", substring = true).assertDoesNotExist()
        compose.onNodeWithText("思考已中断", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("思考已中断").assertIsDisplayed()
        compose.onNodeWithText("断流思考", substring = true).assertDoesNotExist()

        compose.onNodeWithText("思考已中断", substring = true).performClick()
        compose.onNodeWithText("断流思考", substring = true).assertIsDisplayed()
    }

    @Test
    fun failedToolStepShowsFailureSuffixAndDetailsOnExpand() {
        val entry = assistantEntry(
            "a1",
            "<tool_call name=\"adb_shell\"><command>bad</command>" +
                "<result status=\"failed\">boom</result></tool_call>",
        )

        compose.setContent {
            MaterialTheme {
                MessageTimeline(listOf(entry), generation = GenerationState.FAILED)
            }
        }

        compose.onNodeWithText("执行过程", substring = true).assertDoesNotExist()
        compose.onNodeWithText("[adb_shell]").assertIsDisplayed()
        compose.onNodeWithText("boom", substring = true).assertIsDisplayed()
        compose.onNodeWithText("bad", substring = true).assertDoesNotExist()

        compose.onNodeWithText("[adb_shell]").performClick()
        compose.onNodeWithText("bad", substring = true).assertIsDisplayed()
    }

    @Test
    fun userExpandedStepCardStaysExpandedAcrossStreamingUpdates() {
        var entries by mutableStateOf(
            listOf(
                assistantEntry("a1", "<think>先检查设备连接</think>"),
                assistantEntry("a2", "第一版回答", streaming = true),
            ),
        )

        compose.setContent {
            MaterialTheme {
                MessageTimeline(entries, generation = GenerationState.RUNNING)
            }
        }

        compose.onNodeWithText("执行过程", substring = true).assertDoesNotExist()
        compose.onNodeWithText("已思考", substring = true).performClick()
        compose.onNodeWithText("先检查设备连接", substring = true).assertIsDisplayed()

        compose.runOnIdle {
            entries = listOf(
                assistantEntry("a1", "<think>先检查设备连接</think>"),
                assistantEntry("a2", "第二版回答，仍在流式输出", streaming = true),
            )
        }
        compose.waitForIdle()

        compose.onNodeWithText("已思考", substring = true).assertIsDisplayed()
        compose.onNodeWithText("先检查设备连接", substring = true).assertIsDisplayed()
        compose.onNodeWithText("第二版回答，仍在流式输出", substring = true).assertIsDisplayed()
    }

    @Test
    fun runningExecutionKeepsLiveIndicatorOnlyOnActiveSteps() {
        val legacyEntry = assistantEntry("legacy-live", "<think>继续思考", streaming = true, generationId = "g-legacy")
        val nativeEntry = assistantEntry(
            key = "native-source",
            text = "",
            generationId = "running-generation",
            executionGenerationState = GenerationState.RUNNING,
            executionSteps = listOf(
                NativeExecutionStep(
                    stepId = "finished-step",
                    eventId = "completed-event",
                    toolName = "read_file",
                    arguments = "{}",
                    output = "已有工具结果",
                    status = NativeExecutionStatus.COMPLETED,
                ),
            ),
        )

        compose.setContent {
            MaterialTheme {
                MessageTimeline(listOf(legacyEntry, nativeEntry), generation = GenerationState.RUNNING)
            }
        }

        compose.onNodeWithText("执行过程", substring = true).assertDoesNotExist()
        compose.onNodeWithText("已执行", substring = true).assertDoesNotExist()
        compose.onNodeWithText("正在思考", substring = true).assertIsDisplayed()
        compose.onNodeWithText("[read_file]").assertIsDisplayed()
        compose.onNodeWithText("已完成", substring = true).assertIsDisplayed()
    }

    @Test
    fun liveExecutionDefaultsExpandedThenCollapsesOnCompletion() {
        var entries by mutableStateOf(
            listOf(assistantEntry("automatic", "<think>自动收起前的思考详情", streaming = true)),
        )
        var generation by mutableStateOf(GenerationState.RUNNING)

        compose.setContent {
            MaterialTheme {
                MessageTimeline(entries, generation = generation)
            }
        }

        compose.onNodeWithText("执行过程", substring = true).assertDoesNotExist()
        compose.onNodeWithText("正在思考", substring = true).assertIsDisplayed()
        compose.onNodeWithText("自动收起前的思考详情", substring = true).assertIsDisplayed()

        compose.runOnIdle {
            entries = listOf(assistantEntry("automatic", "<think>自动收起前的思考详情</think>最终回答"))
            generation = GenerationState.COMPLETED
        }
        compose.waitForIdle()

        compose.onNodeWithText("已执行", substring = true).assertDoesNotExist()
        compose.onNodeWithText("已思考", substring = true).assertIsDisplayed()
        compose.onNodeWithText("自动收起前的思考详情", substring = true).assertDoesNotExist()
        compose.onNodeWithText("最终回答").assertIsDisplayed()
    }

    @Test
    fun manualStepChoiceSurvivesStreamingAndGenerationCompletion() {
        var entries by mutableStateOf(
            listOf(assistantEntry("manual", "<think>手动选择保留的思考详情", streaming = true)),
        )
        var generation by mutableStateOf(GenerationState.RUNNING)

        compose.setContent {
            MaterialTheme {
                MessageTimeline(entries, generation = generation)
            }
        }

        // 手动收起单张思考卡后，后续流式增量不能重新展开。
        compose.onNodeWithText("正在思考", substring = true).assertIsDisplayed().performClick()
        compose.onNodeWithText("手动选择保留的思考详情", substring = true).assertDoesNotExist()

        compose.runOnIdle {
            entries = listOf(assistantEntry("manual", "<think>手动选择保留的思考详情与增量", streaming = true))
        }
        compose.waitForIdle()
        compose.onNodeWithText("手动选择保留的思考详情与增量", substring = true).assertDoesNotExist()

        // 用户重新展开后，即使思考闭合与生成结束也保留选择；最终正文继续独立展示。
        compose.onNodeWithText("正在思考", substring = true).performClick()
        compose.onNodeWithText("手动选择保留的思考详情与增量", substring = true).assertIsDisplayed()

        compose.runOnIdle {
            entries = listOf(assistantEntry("manual", "<think>手动选择保留的思考详情与增量</think>最终回答第一版"))
            generation = GenerationState.COMPLETED
        }
        compose.waitForIdle()
        compose.onNodeWithText("已思考", substring = true).assertIsDisplayed()
        compose.onNodeWithText("手动选择保留的思考详情与增量", substring = true).assertIsDisplayed()
        compose.onNodeWithText("最终回答第一版").assertIsDisplayed()

        compose.runOnIdle {
            entries = listOf(assistantEntry("manual", "<think>手动选择保留的思考详情与增量</think>最终回答第二版"))
        }
        compose.waitForIdle()
        compose.onNodeWithText("已思考", substring = true).assertIsDisplayed()
        compose.onNodeWithText("手动选择保留的思考详情与增量", substring = true).assertIsDisplayed()
        compose.onNodeWithText("最终回答第二版").assertIsDisplayed()
    }

    @Test
    fun processAttachmentsRenderDirectlyWithoutGlobalCollapsePanel() {
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

        compose.onNodeWithText("执行过程", substring = true).assertDoesNotExist()
        compose.onNodeWithText("已思考", substring = true).assertIsDisplayed()
        compose.onNodeWithText("过程附件:a2", substring = true).assertIsDisplayed()
    }

    @Test
    fun collapsedThoughtCardExposesAccessibleStateDescription() {
        val entries = listOf(
            assistantEntry("a1", "<think>思考</think>"),
            assistantEntry("a2", "回答"),
        )

        compose.setContent {
            MaterialTheme {
                MessageTimeline(entries, generation = GenerationState.COMPLETED)
            }
        }

        compose.onNodeWithText("执行过程", substring = true).assertDoesNotExist()
        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已收起"),
        ).assertIsDisplayed()

        compose.onNodeWithText("已思考", substring = true).performClick()

        compose.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "已展开"),
        ).assertIsDisplayed()
    }

    @Test
    fun inactiveStreamingEmptyEntryDoesNotMountEmptyMessageBubble() {
        val entry = assistantEntry("empty_inactive_stream", "", streaming = true)

        compose.setContent {
            MaterialTheme {
                MessageTimeline(listOf(entry), generation = GenerationState.RUNNING)
            }
        }

        compose.onNodeWithTag("message-bubble:empty_inactive_stream").assertDoesNotExist()
    }
}
