package com.openandroidintelligence.conversation.workbench

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.state.TimelineEntry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 验证 R3 / F3：真实时间线宿主下的异构步骤时序排列与消除大段空白空行。
 * 所有 UI 用例均通过真实宿主 [MessageTimeline]（经过 [TimelineDisplayModel] -> [TimelineDisplayRow]
 * -> [ExecutionTurnRow] -> [AssistantExecutionTurnBody]）驱动，绝不绕过宿主直调子组件；
 * 严格断言全局集中折叠面板（“执行过程” / “已执行”）不存在，且步骤 Y 轴坐标单调递增。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HeterogeneousStepTimelineTest {

    @get:Rule
    val compose = createComposeRule()

    private fun assistantEntry(
        key: String = "msg_turn",
        text: String = "",
        streaming: Boolean = false,
        reasoning: List<MessageReasoning> = emptyList(),
        generationId: String? = "gen_turn",
        executionSteps: List<NativeExecutionStep> = emptyList(),
        executionFact: Boolean = false,
        executionGenerationState: GenerationState? = null,
        timestamp: Long = 1000L,
    ) = TimelineEntry(
        key = key,
        sender = if (executionFact) "system" else "assistant",
        text = text,
        isUser = false,
        timestamp = timestamp,
        pendingAcceptance = false,
        batchGroupId = null,
        isStreaming = streaming,
        generationId = generationId,
        executionSteps = executionSteps,
        executionFact = executionFact,
        executionGenerationState = executionGenerationState,
        reasoning = reasoning,
    )

    // ==========================================
    // Tier 1: 功能覆盖测试 (F3 异构步骤时序排列与无空行)
    // ==========================================

    @Test
    fun t1_F3_01_Step_Sequence_Order() {
        // 验证异构时序：通过真实 MessageTimeline 渲染 思考 -> 工具1 -> 中间叙述 -> 工具2 -> 最终回答，
        // 验证不存在集中折叠框，且屏幕 Y 轴位置严格按真实发生先后顺序单调递增。
        val text = """
            <tool_call name="check_wifi">
              <command>get_wifi_status</command>
              <result status="success">Connected</result>
            </tool_call>
            中间过渡叙述
            <tool_call name="ping_host">
              <command>ping -c 1 8.8.8.8</command>
              <result status="success">1 packets transmitted, 1 received</result>
            </tool_call>
            网络正常可用。
        """.trimIndent()

        val reasoningList = listOf(
            MessageReasoning("r1", "先思考第一步：检查无线网连接", 0, true),
        )
        val entry = assistantEntry(text = text, reasoning = reasoningList)

        val blocks = structuredTimelineBlocks(entry)
        assertTrue(blocks.isNotEmpty())
        assertTrue(blocks[0] is TimelineBlock.ThoughtBlock)
        assertEquals("先思考第一步：检查无线网连接", (blocks[0] as TimelineBlock.ThoughtBlock).thought)

        compose.setContent {
            MaterialTheme {
                MessageTimeline(entries = listOf(entry), generation = GenerationState.COMPLETED)
            }
        }

        compose.onNodeWithText("执行过程", substring = true).assertDoesNotExist()
        compose.onNodeWithText("已执行", substring = true).assertDoesNotExist()

        val thoughtTop = compose.onNodeWithText("已思考", substring = true).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        val tool1Top = compose.onNodeWithText("[check_wifi]").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        val narrativeTop = compose.onNodeWithText("中间过渡叙述").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        val tool2Top = compose.onNodeWithText("[ping_host]").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        val finalTop = compose.onNodeWithText("网络正常可用。").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top

        assertTrue(thoughtTop < tool1Top, "思考卡必须在工具1之上: $thoughtTop < $tool1Top")
        assertTrue(tool1Top < narrativeTop, "工具1必须在中间叙述之上: $tool1Top < $narrativeTop")
        assertTrue(narrativeTop < tool2Top, "中间叙述必须在工具2之上: $narrativeTop < $tool2Top")
        assertTrue(tool2Top < finalTop, "工具2必须在最终回答之上: $tool2Top < $finalTop")
    }

    @Test
    fun t1_F3_02_No_Global_Collapse_Panel() {
        // 通过真实 MessageTimeline 宿主验证彻底移除集中式“执行过程 · N 步” / “已执行 N 步”外层折叠框
        val toolXml = """
            <tool_call name="tool_a">
              <command>cmd_a</command>
              <result status="success">ok_a</result>
            </tool_call>
            <tool_call name="tool_b">
              <command>cmd_b</command>
              <result status="success">ok_b</result>
            </tool_call>
            操作已全部完成。
        """.trimIndent()
        val entry = assistantEntry(text = toolXml)

        compose.setContent {
            MaterialTheme {
                MessageTimeline(entries = listOf(entry), generation = GenerationState.COMPLETED)
            }
        }

        compose.onNodeWithText("执行过程", substring = true).assertDoesNotExist()
        compose.onNodeWithText("已执行", substring = true).assertDoesNotExist()
        val toolATop = compose.onNodeWithText("[tool_a]").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        val toolBTop = compose.onNodeWithText("[tool_b]").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        val finalTop = compose.onNodeWithText("操作已全部完成。").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(toolATop < toolBTop, "tool_a 必须在 tool_b 之上")
        assertTrue(toolBTop < finalTop, "tool_b 必须在最终回答之上")
    }

    @Test
    fun t1_F3_03_Blank_Paragraph_Filtered() {
        // 验证思考卡与工具卡之间的空白换行符被严格过滤，不生成空白 Paragraph 节点
        val textWithBlankLines = "\n\n\n<tool_call name=\"query_data\"><command>select</command></tool_call>\n\n\n"
        val reasoning = listOf(MessageReasoning("r1", "思考内容", 0, true))
        val entry = assistantEntry(text = textWithBlankLines, reasoning = reasoning)

        val blocks = structuredTimelineBlocks(entry)

        val emptyParagraphs = blocks.filterIsInstance<TimelineBlock.Paragraph>().filter { it.text.isBlank() }
        assertEquals(0, emptyParagraphs.size, "所有纯空白段落节点必须被彻底过滤")

        compose.setContent {
            MaterialTheme {
                MessageTimeline(entries = listOf(entry), generation = GenerationState.COMPLETED)
            }
        }

        compose.onNodeWithText("执行过程", substring = true).assertDoesNotExist()
        compose.onNodeWithText("已执行", substring = true).assertDoesNotExist()
        compose.onNodeWithText("已思考", substring = true).assertIsDisplayed()
        compose.onNodeWithText("[query_data]").assertIsDisplayed()
    }

    @Test
    fun t1_F3_04_Step_Key_Unique() {
        // 通过真实 MessageTimeline 验证各步骤具备独立 Key，展开/收起状态互不干扰
        val toolXml = """
            <tool_call name="step_one">
              <command>cmd1</command>
              <result status="success">res1</result>
            </tool_call>
            <tool_call name="step_two">
              <command>cmd2</command>
              <result status="success">res2</result>
            </tool_call>
        """.trimIndent()
        val entry = assistantEntry(key = "turn_keys", text = toolXml)

        compose.setContent {
            MaterialTheme {
                MessageTimeline(entries = listOf(entry), generation = GenerationState.COMPLETED)
            }
        }

        compose.onNodeWithText("执行过程", substring = true).assertDoesNotExist()
        val card1 = compose.onNodeWithText("res1")
        val card2 = compose.onNodeWithText("res2")
        card1.assertIsDisplayed()
        card2.assertIsDisplayed()

        card1.performClick()
        compose.onNodeWithText("cmd1").assertIsDisplayed()
        compose.onNodeWithText("cmd2").assertDoesNotExist()
    }

    @Test
    fun t1_F3_05_Final_Answer_Separation() {
        // 验证最终回答正文在所有执行步骤下方独立展示，不被折叠隐藏
        val text = """
            <tool_call name="fetch_status">
              <command>status</command>
              <result status="success">running</result>
            </tool_call>
            这是最终的完整分析报告结论。
        """.trimIndent()
        val entry = assistantEntry(text = text)

        compose.setContent {
            MaterialTheme {
                MessageTimeline(entries = listOf(entry), generation = GenerationState.COMPLETED)
            }
        }

        compose.onNodeWithText("执行过程", substring = true).assertDoesNotExist()
        compose.onNodeWithText("已执行", substring = true).assertDoesNotExist()
        val toolTop = compose.onNodeWithText("[fetch_status]").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        val finalTop = compose.onNodeWithText("这是最终的完整分析报告结论。").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(toolTop < finalTop, "工具步骤必须在最终回答上方")
    }

    @Test
    fun t1_F3_06_Mixed_NativeSteps_And_Reasoning_Same_Entry_Via_Host() {
        // 验证单条 TimelineEntry 同时携带原生 executionSteps 与 reasoning（且正文为空）时，
        // 经真实 MessageTimeline 渲染绝不丢弃 reasoning，且思考在工具之上按序呈现。
        val entry = assistantEntry(
            key = "mixed_entry",
            text = "",
            reasoning = listOf(MessageReasoning("r1", "混合思考", 0, true)),
            generationId = "g1",
            executionSteps = listOf(
                NativeExecutionStep(
                    stepId = "step_grep",
                    eventId = "ev_1",
                    toolName = "grep_code",
                    arguments = "{\"pattern\":\"MessageTimeline\"}",
                    output = "Found 3 matches",
                    status = NativeExecutionStatus.COMPLETED,
                ),
            ),
            executionGenerationState = GenerationState.COMPLETED,
        )

        compose.setContent {
            MaterialTheme {
                MessageTimeline(entries = listOf(entry), generation = GenerationState.COMPLETED)
            }
        }

        compose.onNodeWithText("执行过程", substring = true).assertDoesNotExist()
        compose.onNodeWithText("已执行", substring = true).assertDoesNotExist()
        compose.onNodeWithText("此消息没有可显示的文本").assertDoesNotExist()

        val thoughtTop = compose.onNodeWithText("已思考", substring = true).assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        val toolTop = compose.onNodeWithText("[grep_code]").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(thoughtTop < toolTop, "混合条目中的思考必须在原生工具之前: $thoughtTop < $toolTop")

        compose.onNodeWithText("已思考", substring = true).performClick()
        compose.onNodeWithText("混合思考", substring = true).assertIsDisplayed()
    }

    @Test
    fun t1_F3_07_Multi_Entry_Interleaved_Turn_Via_Host() {
        // 验证多条相邻同 generationId 条目穿插时序：[思考1 -> 原生工具1 -> 思考2 -> 原生工具2 -> 最终正文]
        val entries = listOf(
            assistantEntry(
                key = "e_t1",
                text = "",
                reasoning = listOf(MessageReasoning("r1", "思考阶段一", 0, true)),
                generationId = "g_interleaved",
                timestamp = 100L,
            ),
            assistantEntry(
                key = "e_tool1",
                text = "",
                generationId = "g_interleaved",
                executionFact = true,
                executionGenerationState = GenerationState.COMPLETED,
                executionSteps = listOf(
                    NativeExecutionStep("s1", "ev1", "read_file", "{\"path\":\"a.kt\"}", "ok1", NativeExecutionStatus.COMPLETED),
                ),
                timestamp = 200L,
            ),
            assistantEntry(
                key = "e_t2",
                text = "",
                reasoning = listOf(MessageReasoning("r2", "思考阶段二", 0, true)),
                generationId = "g_interleaved",
                timestamp = 300L,
            ),
            assistantEntry(
                key = "e_tool2",
                text = "",
                generationId = "g_interleaved",
                executionFact = true,
                executionGenerationState = GenerationState.COMPLETED,
                executionSteps = listOf(
                    NativeExecutionStep("s2", "ev2", "bash", "ls", "ok2", NativeExecutionStatus.COMPLETED),
                ),
                timestamp = 400L,
            ),
            assistantEntry(
                key = "e_final",
                text = "最终整合结论。",
                generationId = "g_interleaved",
                timestamp = 500L,
            ),
        )

        compose.setContent {
            MaterialTheme {
                Box(Modifier.verticalScroll(rememberScrollState())) {
                    MessageTimeline(entries = entries, generation = GenerationState.COMPLETED)
                }
            }
        }

        compose.onNodeWithText("执行过程", substring = true).assertDoesNotExist()
        compose.onNodeWithText("已执行", substring = true).assertDoesNotExist()

        val thoughtNodes = compose.onAllNodesWithText("已思考", substring = true).fetchSemanticsNodes()
        assertEquals(2, thoughtNodes.size, "两条独立思考步必须各自渲染")
        val thought1Top = thoughtNodes[0].positionInRoot.y
        val tool1Top = compose.onNodeWithText("[read_file]").fetchSemanticsNode().positionInRoot.y
        val thought2Top = thoughtNodes[1].positionInRoot.y
        val tool2Top = compose.onNodeWithText("[bash]").fetchSemanticsNode().positionInRoot.y
        val finalTop = compose.onNodeWithText("最终整合结论。").fetchSemanticsNode().positionInRoot.y

        assertTrue(thought1Top < tool1Top, "思考1 < 工具1 ($thought1Top vs $tool1Top)")
        assertTrue(tool1Top < thought2Top, "工具1 < 思考2 ($tool1Top vs $thought2Top)")
        assertTrue(thought2Top < tool2Top, "思考2 < 工具2 ($thought2Top vs $tool2Top)")
        assertTrue(tool2Top < finalTop, "工具2 < 最终正文 ($tool2Top vs $finalTop)")
    }

    // ==========================================
    // Tier 2: 边界与极端条件测试 (F3 Boundary & Corner Cases)
    // ==========================================

    @Test
    fun t2_F3_01_Multiple_Consecutive_Newlines() {
        // 边界测试：思考与工具之间存在多重连续换行（\n\n\n\n\n\n），界面不产生空白间距空行
        val text = "\n\n\n\n\n\n<tool_call name=\"clean_tool\"><command>clean</command></tool_call>\n\n\n\n\n\n"
        val reasoning = listOf(MessageReasoning("r1", "思考步骤", 0, true))
        val entry = assistantEntry(text = text, reasoning = reasoning)

        val blocks = structuredTimelineBlocks(entry)
        val blankParagraphs = blocks.filterIsInstance<TimelineBlock.Paragraph>().filter { it.text.isBlank() }
        assertTrue(blankParagraphs.isEmpty(), "多重连续换行产生的空白段落必须全量过滤")

        compose.setContent {
            MaterialTheme {
                MessageTimeline(entries = listOf(entry), generation = GenerationState.COMPLETED)
            }
        }
        compose.onNodeWithText("[clean_tool]").assertIsDisplayed()
        compose.onNodeWithText("已思考", substring = true).assertIsDisplayed()
    }

    @Test
    fun t2_F3_02_Only_Thoughts_No_Tools() {
        // 边界测试：只有思考步骤没有工具调用
        val text = "最终回答内容"
        val reasoning = listOf(
            MessageReasoning("r1", "思考第一阶段", 0, true),
            MessageReasoning("r2", "思考第二阶段", 10, true),
        )
        val entry = assistantEntry(text = text, reasoning = reasoning)

        compose.setContent {
            MaterialTheme {
                MessageTimeline(entries = listOf(entry), generation = GenerationState.COMPLETED)
            }
        }

        compose.onNodeWithText("执行过程", substring = true).assertDoesNotExist()
        compose.onNodeWithText("最终回答内容").assertIsDisplayed()
        compose.onAllNodesWithText("已思考", substring = true).assertCountEquals(2)
    }

    @Test
    fun t2_F3_03_Only_Tools_No_Thoughts() {
        // 边界测试：只有工具调用没有思考步骤
        val text = """
            <tool_call name="device_info">
              <command>getprop ro.build.version.release</command>
              <result status="success">15</result>
            </tool_call>
            设备系统版本为 Android 15。
        """.trimIndent()
        val entry = assistantEntry(text = text, reasoning = emptyList())

        compose.setContent {
            MaterialTheme {
                MessageTimeline(entries = listOf(entry), generation = GenerationState.COMPLETED)
            }
        }

        compose.onNodeWithText("执行过程", substring = true).assertDoesNotExist()
        compose.onNodeWithText("[device_info]").assertIsDisplayed()
        compose.onNodeWithText("设备系统版本为 Android 15。").assertIsDisplayed()
    }

    @Test
    fun t2_F3_04_Zero_Duration_Instant_Steps() {
        // 边界测试：零耗时瞬间完成的步骤正常渲染不崩溃
        val text = """
            <tool_call name="instant_tool">
              <command>noop</command>
              <result status="success">done</result>
            </tool_call>
        """.trimIndent()
        val entry = assistantEntry(text = text)

        compose.setContent {
            MaterialTheme {
                MessageTimeline(entries = listOf(entry), generation = GenerationState.COMPLETED)
            }
        }

        compose.onNodeWithText("执行过程", substring = true).assertDoesNotExist()
        compose.onNodeWithText("[instant_tool]").assertIsDisplayed()
        compose.onNodeWithText("done").assertIsDisplayed()
    }

    @Test
    fun t2_F3_05_Long_Tool_Output_Interleaved() {
        // 边界测试：极长工具输出与大段文本交替穿插
        val hugeResult = "Output line: " + "X".repeat(5000)
        val text = """
            <tool_call name="heavy_tool">
              <command>heavy_run</command>
              <result status="success">$hugeResult</result>
            </tool_call>
            中间长分析段落：${"Y".repeat(2000)}
            <tool_call name="finish_tool">
              <command>finish</command>
              <result status="success">ok</result>
            </tool_call>
            全部执行完毕。
        """.trimIndent()
        val entry = assistantEntry(text = text)

        compose.setContent {
            MaterialTheme {
                MessageTimeline(entries = listOf(entry), generation = GenerationState.COMPLETED)
            }
        }

        compose.onNodeWithText("执行过程", substring = true).assertDoesNotExist()
        compose.onNodeWithText("[heavy_tool]").assertIsDisplayed()
        compose.onNodeWithText("[finish_tool]").assertIsDisplayed()
        compose.onNodeWithText("全部执行完毕。").assertIsDisplayed()
    }

    @Test
    fun t2_F3_06_Single_Entry_NativeSteps_And_PlainText_No_Duplication() {
        // 回归测试 R3：单条 TimelineEntry 同时携带原生 executionSteps 与纯正文回复（无 reasoning 且无 XML 标记）时，
        // 经真实宿主 MessageTimeline 渲染后，每个工具卡片在整个界面中只能渲染 1 次且位于正文上方。
        val entry = assistantEntry(
            key = "single_native_and_plain_text",
            text = "我已经查看了目录内容，一切正常。",
            reasoning = emptyList(),
            generationId = "gen_single_native",
            executionSteps = listOf(
                NativeExecutionStep(
                    stepId = "step_bash_1",
                    eventId = "ev_bash_1",
                    toolName = "bash",
                    arguments = "ls -la",
                    output = "total 16",
                    status = NativeExecutionStatus.COMPLETED,
                ),
            ),
            executionGenerationState = GenerationState.COMPLETED,
        )

        compose.setContent {
            MaterialTheme {
                MessageTimeline(entries = listOf(entry), generation = GenerationState.COMPLETED)
            }
        }

        compose.onNodeWithText("执行过程", substring = true).assertDoesNotExist()
        compose.onNodeWithText("已执行", substring = true).assertDoesNotExist()
        compose.onAllNodesWithText("[bash]").assertCountEquals(1)

        val toolTop = compose.onNodeWithText("[bash]").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        val finalTop = compose.onNodeWithText("我已经查看了目录内容，一切正常。").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(toolTop < finalTop, "原生工具卡片必须在最终正文上方: $toolTop < $finalTop")
    }

    @Test
    fun t2_F3_07_Repeated_NativeSteps_Across_Entries_No_Duplication() {
        // 回归测试 R3：多条同 generationId 条目跨条目重复携带 step1，且末条带有新增 step2 与纯正文回复时，
        // 经真实宿主 MessageTimeline 渲染后，每个工具卡片在整个界面中均严格只出现 1 次。
        val step1 = NativeExecutionStep(
            stepId = "step_read",
            eventId = "ev_1",
            toolName = "read_file",
            arguments = "{\"path\":\"Main.kt\"}",
            output = "fun main() {}",
            status = NativeExecutionStatus.COMPLETED,
        )
        val step2 = NativeExecutionStep(
            stepId = "step_grep",
            eventId = "ev_2",
            toolName = "grep_code",
            arguments = "{\"pattern\":\"main\"}",
            output = "Main.kt:1",
            status = NativeExecutionStatus.COMPLETED,
        )
        val entries = listOf(
            assistantEntry(
                key = "e1_fact",
                text = "",
                generationId = "gen_multi_native",
                executionFact = true,
                executionSteps = listOf(step1),
                executionGenerationState = GenerationState.COMPLETED,
                timestamp = 100L,
            ),
            assistantEntry(
                key = "e2_final",
                text = "已完成全部检查。",
                generationId = "gen_multi_native",
                executionSteps = listOf(step1, step2),
                executionGenerationState = GenerationState.COMPLETED,
                timestamp = 200L,
            ),
        )

        compose.setContent {
            MaterialTheme {
                MessageTimeline(entries = entries, generation = GenerationState.COMPLETED)
            }
        }

        compose.onAllNodesWithText("[read_file]").assertCountEquals(1)
        compose.onAllNodesWithText("[grep_code]").assertCountEquals(1)
        val readTop = compose.onNodeWithText("[read_file]").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        val grepTop = compose.onNodeWithText("[grep_code]").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        val finalTop = compose.onNodeWithText("已完成全部检查。").assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot.top
        assertTrue(readTop < grepTop, "step1 必须在 step2 上方: $readTop < $grepTop")
        assertTrue(grepTop < finalTop, "step2 必须在最终正文上方: $grepTop < $finalTop")
    }
}
