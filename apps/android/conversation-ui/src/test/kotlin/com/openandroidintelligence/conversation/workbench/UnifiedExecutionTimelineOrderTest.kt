package com.openandroidintelligence.conversation.workbench

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.openandroidintelligence.conversation.assistant.AssistantStateDto
import com.openandroidintelligence.conversation.assistant.decodeState
import com.openandroidintelligence.conversation.assistant.decodeTimeline
import com.openandroidintelligence.conversation.assistant.encodeState
import com.openandroidintelligence.conversation.assistant.toDto
import com.openandroidintelligence.conversation.model.CatalogVersion
import com.openandroidintelligence.conversation.model.ClientMessageId
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.model.ExecutionTimelineStep
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.model.MessageReasoning
import com.openandroidintelligence.conversation.model.NativeExecutionStatus
import com.openandroidintelligence.conversation.model.NativeExecutionStep
import com.openandroidintelligence.conversation.model.StreamPresentationOrigin
import com.openandroidintelligence.conversation.ports.AgentCommandCatalog
import com.openandroidintelligence.conversation.ports.AgentCommandCatalogRepository
import com.openandroidintelligence.conversation.ports.CancelGenerationOutcome
import com.openandroidintelligence.conversation.ports.CancelGenerationResult
import com.openandroidintelligence.conversation.ports.ConversationPage
import com.openandroidintelligence.conversation.ports.ConversationRepository
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.ports.ConversationSummary
import com.openandroidintelligence.conversation.ports.GenerationTracker
import com.openandroidintelligence.conversation.ports.MessageBatch
import com.openandroidintelligence.conversation.ports.OutgoingMessage
import com.openandroidintelligence.conversation.ports.PageRequest
import com.openandroidintelligence.conversation.ports.TimelineMessage
import com.openandroidintelligence.conversation.ports.TimelinePage
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.state.StreamPresentationMetadata
import com.openandroidintelligence.conversation.state.TimelineEntry
import com.openandroidintelligence.conversation.state.WorkbenchController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 端到端验证统一异构执行时间线：
 * 1. WorkbenchController 实时流 Reducer 不再将工具事实整体插队到思考上方；
 * 2. TimelineDisplayModel / MessageTimeline / AssistantMessageContent 按单循环渲染交错步骤；
 * 3. 流式思考刷字呈现与跨进程桥接协议均完整保留顺序语义。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UnifiedExecutionTimelineOrderTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `WorkbenchController实时接收跨事件思考与工具并经MessageTimeline严格交错渲染`() = runTest {
        val host = FakeHost()
        val controller = WorkbenchController(
            scope = this,
            repository = host,
            catalogRepository = object : AgentCommandCatalogRepository {
                override suspend fun get(gatewayId: String, languageCode: String) =
                    AgentCommandCatalog(CatalogVersion("test"), emptyList())
            },
            scopeFactory = { ConversationScope("profile", "gateway", "account", "install") },
            persistenceDispatcher = UnconfinedTestDispatcher(testScheduler),
            authoritativeMessageIds = true,
            replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false),
        )
        try {
            runCurrent()
            controller.openThread("a")
            runCurrent()

            // 所有事件故意共用同一时间戳 200L，验证仅靠 orderSequence 即可精准保序
            host.events.emit(
                VerifiedConversationEvent.TimelineUpsert(
                    eventId = "ev-t1",
                    occurredAt = 200L,
                    revision = 1L,
                    message = TimelineMessage(
                        id = "thought-1",
                        sender = "assistant",
                        parts = emptyList(),
                        timestamp = 200L,
                        conversationId = ConversationId("a"),
                        generationId = "g1",
                        reasoning = listOf(MessageReasoning("r1", "先读取配置", 0, true)),
                        timelineSteps = listOf(ExecutionTimelineStep.ThinkingStep("r1", "先读取配置", 0, true, 0)),
                        orderSequence = 1L,
                    ),
                ),
            )
            val toolARunning = NativeExecutionStep(
                stepId = "tool-a",
                eventId = "ev-a1",
                toolName = "read_config",
                arguments = "{\"path\":\"a.yml\"}",
                output = null,
                status = NativeExecutionStatus.RUNNING,
                revision = 1L,
                occurredAt = 200L,
                orderSequence = 2L,
            )
            host.events.emit(
                VerifiedConversationEvent.ExecutionStepsUpdated(
                    eventId = "exec-1",
                    occurredAt = 200L,
                    conversationId = ConversationId("a"),
                    generationId = "g1",
                    executionRevision = 1L,
                    steps = listOf(toolARunning),
                    clientMessageId = "client-a",
                    userMessageId = "user-a",
                ),
            )
            host.events.emit(
                VerifiedConversationEvent.TimelineUpsert(
                    eventId = "ev-t2",
                    occurredAt = 200L,
                    revision = 2L,
                    message = TimelineMessage(
                        id = "thought-2",
                        sender = "assistant",
                        parts = emptyList(),
                        timestamp = 200L,
                        conversationId = ConversationId("a"),
                        generationId = "g1",
                        reasoning = listOf(MessageReasoning("r2", "再执行检查命令", 0, true)),
                        timelineSteps = listOf(ExecutionTimelineStep.ThinkingStep("r2", "再执行检查命令", 0, true, 0)),
                        orderSequence = 3L,
                    ),
                ),
            )
            val toolADone = toolARunning.copy(
                eventId = "ev-a2",
                output = "cfg ok",
                status = NativeExecutionStatus.COMPLETED,
                revision = 2L,
            )
            val toolBDone = NativeExecutionStep(
                stepId = "tool-b",
                eventId = "ev-b1",
                toolName = "run_check",
                arguments = "{\"cmd\":\"check\"}",
                output = "check ok",
                status = NativeExecutionStatus.COMPLETED,
                revision = 2L,
                occurredAt = 200L,
                orderSequence = 4L,
            )
            host.events.emit(
                VerifiedConversationEvent.ExecutionStepsUpdated(
                    eventId = "exec-2",
                    occurredAt = 200L,
                    conversationId = ConversationId("a"),
                    generationId = "g1",
                    executionRevision = 2L,
                    steps = listOf(toolADone, toolBDone),
                    clientMessageId = "client-a",
                    userMessageId = "user-a",
                ),
            )
            host.events.emit(
                VerifiedConversationEvent.TimelineUpsert(
                    eventId = "ev-final",
                    occurredAt = 200L,
                    revision = 3L,
                    message = TimelineMessage(
                        id = "final-answer",
                        sender = "assistant",
                        parts = listOf(MessagePart.Text("配置与检查全部通过。")),
                        timestamp = 200L,
                        conversationId = ConversationId("a"),
                        generationId = "g1",
                        orderSequence = 5L,
                    ),
                ),
            )
            host.generationState.value = GenerationState.COMPLETED
            runCurrent()

            val entries = (controller.state.value.timeline as Loadable.Ready).value
            val display = TimelineDisplayModel().build(entries, GenerationState.COMPLETED)
            val executionRun = display.filterIsInstance<TimelineDisplayItem.Execution>().single().run
            assertEquals(
                listOf("thought", "read_config", "thought", "run_check"),
                executionRun.steps.map { step ->
                    when (step) {
                        is ExecutionStep.Thought -> "thought"
                        is ExecutionStep.ToolCall -> step.block.toolName
                        else -> "other"
                    }
                },
            )
            assertTrue(executionRun.hasFinalAnswer)

            compose.setContent {
                MaterialTheme {
                    Box(Modifier.verticalScroll(rememberScrollState())) {
                        MessageTimeline(entries = entries, generation = GenerationState.COMPLETED)
                    }
                }
            }

            val thoughts = compose.onAllNodesWithText("已思考", substring = true).fetchSemanticsNodes()
            assertEquals(2, thoughts.size)
            val thought1Top = thoughts[0].positionInRoot.y
            val toolATop = compose.onNodeWithText("[read_config]").fetchSemanticsNode().positionInRoot.y
            val thought2Top = thoughts[1].positionInRoot.y
            val toolBTop = compose.onNodeWithText("[run_check]").fetchSemanticsNode().positionInRoot.y
            val finalTop = compose.onNodeWithText("配置与检查全部通过。").fetchSemanticsNode().positionInRoot.y

            assertTrue(thought1Top < toolATop, "思考1 < 工具A ($thought1Top vs $toolATop)")
            assertTrue(toolATop < thought2Top, "工具A < 思考2 ($toolATop vs $thought2Top)")
            assertTrue(thought2Top < toolBTop, "思考2 < 工具B ($thought2Top vs $toolBTop)")
            assertTrue(toolBTop < finalTop, "工具B < 最终回答 ($toolBTop vs $finalTop)")
        } finally {
            controller.close()
        }
    }

    @Test
    fun `单条消息含前置叙述与交错timelineSteps时MessageTimeline与AssistantMessageContent均严格按序渲染`() {
        val toolA = NativeExecutionStep("ta", "e1", "list_files", "{}", "ok-a", NativeExecutionStatus.COMPLETED)
        val toolB = NativeExecutionStep("tb", "e2", "run_tests", "{}", "ok-b", NativeExecutionStatus.COMPLETED)
        val prefix = "先说明背景。\n"
        val entry = TimelineEntry(
            key = "single-acp-turn",
            sender = "assistant",
            text = "${prefix}最终验收通过。",
            isUser = false,
            timestamp = 1_000L,
            pendingAcceptance = false,
            batchGroupId = null,
            generationId = "g-single",
            executionGenerationState = GenerationState.COMPLETED,
            executionSteps = listOf(toolA, toolB),
            reasoning = listOf(
                MessageReasoning("r1", "思考一", prefix.length, true),
                MessageReasoning("r2", "思考二", prefix.length, true),
            ),
            timelineSteps = listOf(
                ExecutionTimelineStep.ThinkingStep("r1", "思考一", prefix.length, true, 0),
                ExecutionTimelineStep.ToolCallStep(toolA, prefix.length, 1),
                ExecutionTimelineStep.ThinkingStep("r2", "思考二", prefix.length, true, 2),
                ExecutionTimelineStep.ToolCallStep(toolB, prefix.length, 3),
            ),
        )

        compose.setContent {
            MaterialTheme {
                Box(Modifier.verticalScroll(rememberScrollState())) {
                    MessageTimeline(entries = listOf(entry), generation = GenerationState.COMPLETED)
                }
            }
        }

        val narrativeTop = compose.onNodeWithText("先说明背景。").fetchSemanticsNode().positionInRoot.y
        val thoughts = compose.onAllNodesWithText("已思考", substring = true).fetchSemanticsNodes()
        assertEquals(2, thoughts.size)
        val thought1Top = thoughts[0].positionInRoot.y
        val toolATop = compose.onNodeWithText("[list_files]").fetchSemanticsNode().positionInRoot.y
        val thought2Top = thoughts[1].positionInRoot.y
        val toolBTop = compose.onNodeWithText("[run_tests]").fetchSemanticsNode().positionInRoot.y
        val finalTop = compose.onNodeWithText("最终验收通过。").fetchSemanticsNode().positionInRoot.y

        assertTrue(narrativeTop < thought1Top, "前置叙述 < 思考1 ($narrativeTop vs $thought1Top)")
        assertTrue(thought1Top < toolATop, "思考1 < 工具A ($thought1Top vs $toolATop)")
        assertTrue(toolATop < thought2Top, "工具A < 思考2 ($toolATop vs $thought2Top)")
        assertTrue(thought2Top < toolBTop, "思考2 < 工具B ($thought2Top vs $toolBTop)")
        assertTrue(toolBTop < finalTop, "工具B < 最终回答 ($toolBTop vs $finalTop)")
    }

    @Test
    fun `单条消息直接通过AssistantMessageContent渲染交错步骤不发生同类归堆`() {
        val toolA = NativeExecutionStep("ta", "e1", "fetch_logs", "{}", "logs", NativeExecutionStatus.COMPLETED)
        val toolB = NativeExecutionStep("tb", "e2", "verify_fix", "{}", "verified", NativeExecutionStatus.COMPLETED)
        val prefix = "准备开始排查。\n"
        val entry = TimelineEntry(
            key = "direct-content-turn",
            sender = "assistant",
            text = "${prefix}排查结束。",
            isUser = false,
            timestamp = 1_000L,
            pendingAcceptance = false,
            batchGroupId = null,
            generationId = "g-direct",
            executionGenerationState = GenerationState.COMPLETED,
            executionSteps = listOf(toolA, toolB),
            reasoning = listOf(
                MessageReasoning("r1", "先看日志", prefix.length, true),
                MessageReasoning("r2", "再验证修复", prefix.length, true),
            ),
            timelineSteps = listOf(
                ExecutionTimelineStep.ThinkingStep("r1", "先看日志", prefix.length, true, 0),
                ExecutionTimelineStep.ToolCallStep(toolA, prefix.length, 1),
                ExecutionTimelineStep.ThinkingStep("r2", "再验证修复", prefix.length, true, 2),
                ExecutionTimelineStep.ToolCallStep(toolB, prefix.length, 3),
            ),
        )

        compose.setContent {
            MaterialTheme {
                Box(Modifier.verticalScroll(rememberScrollState())) {
                    AssistantMessageContent(entry = entry)
                }
            }
        }

        val prefixTop = compose.onNodeWithText("准备开始排查。").fetchSemanticsNode().positionInRoot.y
        val thoughts = compose.onAllNodesWithText("已思考", substring = true).fetchSemanticsNodes()
        assertEquals(2, thoughts.size)
        val t1Top = thoughts[0].positionInRoot.y
        val toolATop = compose.onNodeWithText("[fetch_logs]").fetchSemanticsNode().positionInRoot.y
        val t2Top = thoughts[1].positionInRoot.y
        val toolBTop = compose.onNodeWithText("[verify_fix]").fetchSemanticsNode().positionInRoot.y
        val endTop = compose.onNodeWithText("排查结束。").fetchSemanticsNode().positionInRoot.y

        assertTrue(prefixTop < t1Top && t1Top < toolATop && toolATop < t2Top && t2Top < toolBTop && toolBTop < endTop)
    }

    @Test
    fun `单条消息纯工具带前置叙述偏移时保留前置叙述在工具上方`() {
        val tool = NativeExecutionStep("t-read", "e1", "read_file", "{}", "ok", NativeExecutionStatus.COMPLETED)
        val prefix = "我先读取配置文件。\n"
        val entry = TimelineEntry(
            key = "narrative-before-tool",
            sender = "assistant",
            text = "${prefix}读取结果正常。",
            isUser = false,
            timestamp = 1_000L,
            pendingAcceptance = false,
            batchGroupId = null,
            generationId = "g-tool-offset",
            executionGenerationState = GenerationState.COMPLETED,
            executionSteps = listOf(tool),
            timelineSteps = listOf(ExecutionTimelineStep.ToolCallStep(tool, prefix.length, 0)),
        )

        compose.setContent {
            MaterialTheme {
                Box(Modifier.verticalScroll(rememberScrollState())) {
                    MessageTimeline(entries = listOf(entry), generation = GenerationState.COMPLETED)
                }
            }
        }

        compose.onAllNodesWithText("[read_file]").assertCountEquals(1)
        val prefixTop = compose.onNodeWithText("我先读取配置文件。").fetchSemanticsNode().positionInRoot.y
        val toolTop = compose.onNodeWithText("[read_file]").fetchSemanticsNode().positionInRoot.y
        val suffixTop = compose.onNodeWithText("读取结果正常。").fetchSemanticsNode().positionInRoot.y
        assertTrue(prefixTop < toolTop && toolTop < suffixTop)
    }

    @Test
    fun `流式思考与工具交替推进保持流式思考呈现与最终有序布局`() {
        val toolRunning = NativeExecutionStep("s-live", "e1", "shell_exec", "{}", null, NativeExecutionStatus.RUNNING, revision = 1L)
        val toolDone = toolRunning.copy(output = "done", status = NativeExecutionStatus.COMPLETED, revision = 2L)

        var entries by mutableStateOf(
            listOf(
                TimelineEntry(
                    key = "live-turn",
                    sender = "assistant",
                    text = "",
                    isUser = false,
                    timestamp = 1_000L,
                    pendingAcceptance = false,
                    batchGroupId = null,
                    isStreaming = true,
                    generationId = "g-live",
                    executionGenerationState = GenerationState.RUNNING,
                    presentation = StreamPresentationMetadata(StreamPresentationOrigin.LIVE, 1L),
                    timelineSteps = listOf(
                        ExecutionTimelineStep.ThinkingStep("r1", "正在分析需求", 0, complete = false, orderIndex = 0),
                    ),
                ),
            ),
        )
        var generation by mutableStateOf(GenerationState.RUNNING)

        compose.setContent {
            MaterialTheme {
                val registry = rememberStreamingPresentations(entries = entries, owner = "live-test", generation = generation)
                CompositionLocalProvider(LocalStreamingPresentationRegistry provides registry) {
                    Box(Modifier.verticalScroll(rememberScrollState())) {
                        MessageTimeline(entries = entries, generation = generation)
                    }
                }
            }
        }

        compose.onNodeWithText("正在思考", substring = true).assertIsDisplayed()

        // 推进到：思考1闭合 -> 工具A完成 -> 思考2流式 -> 最终回答
        entries = listOf(
            entries.single().copy(
                text = "最终答复已完成。",
                isStreaming = false,
                executionGenerationState = GenerationState.COMPLETED,
                executionSteps = listOf(toolDone),
                presentation = StreamPresentationMetadata(StreamPresentationOrigin.LIVE, 4L),
                timelineSteps = listOf(
                    ExecutionTimelineStep.ThinkingStep("r1", "正在分析需求", 0, complete = true, orderIndex = 0),
                    ExecutionTimelineStep.ToolCallStep(toolDone, 0, orderIndex = 1),
                    ExecutionTimelineStep.ThinkingStep("r2", "汇总工具输出", 0, complete = true, orderIndex = 2),
                ),
            ),
        )
        generation = GenerationState.COMPLETED
        compose.waitForIdle()

        val thoughts = compose.onAllNodesWithText("已思考", substring = true).fetchSemanticsNodes()
        assertEquals(2, thoughts.size)
        val t1Top = thoughts[0].positionInRoot.y
        val toolTop = compose.onNodeWithText("[shell_exec]").fetchSemanticsNode().positionInRoot.y
        val t2Top = thoughts[1].positionInRoot.y
        val finalTop = compose.onNodeWithText("最终答复已完成。").fetchSemanticsNode().positionInRoot.y
        assertTrue(t1Top < toolTop && toolTop < t2Top && t2Top < finalTop)
    }

    @Test
    fun `跨进程桥接协议编解码完整保留timelineSteps与orderSequence`() {
        val toolA = NativeExecutionStep(
            stepId = "tool-a",
            eventId = "ev-a",
            toolName = "read_file",
            arguments = "{\"path\":\"a.kt\"}",
            output = "ok",
            status = NativeExecutionStatus.COMPLETED,
            revision = 2L,
            orderSequence = 12L,
        )
        val original = TimelineEntry(
            key = "bridge-turn",
            sender = "assistant",
            text = "结论",
            isUser = false,
            timestamp = 1_000L,
            pendingAcceptance = false,
            batchGroupId = null,
            generationId = "g-bridge",
            executionSteps = listOf(toolA),
            timelineSteps = listOf(
                ExecutionTimelineStep.ThinkingStep("r1", "先思考", 0, true, 0),
                ExecutionTimelineStep.ToolCallStep(toolA, 0, 1),
                ExecutionTimelineStep.ThinkingStep("r2", "再思考", 0, true, 2),
            ),
            orderSequence = 10L,
        )

        val encoded = encodeState(AssistantStateDto(timeline = listOf(original.toDto())))
        val decodedState = requireNotNull(decodeState(encoded))
        val restored = decodeTimeline(decodedState).single()

        assertEquals(original.orderSequence, restored.orderSequence)
        assertEquals(3, restored.timelineSteps.size)
        assertEquals(original.timelineSteps[0], restored.timelineSteps[0])
        val restoredTool = restored.timelineSteps[1] as ExecutionTimelineStep.ToolCallStep
        assertEquals("tool-a", restoredTool.step.stepId)
        assertEquals("read_file", restoredTool.step.toolName)
        assertEquals("""{"path":"a.kt"}""", restoredTool.step.arguments)
        assertEquals("ok", restoredTool.step.output)
        assertEquals(NativeExecutionStatus.COMPLETED, restoredTool.step.status)
        assertEquals(2L, restoredTool.step.revision)
        assertEquals(12L, restoredTool.step.orderSequence)
        assertEquals(0, restoredTool.bodyOffset)
        assertEquals(1, restoredTool.orderIndex)
        assertEquals(original.timelineSteps[2], restored.timelineSteps[2])
    }

    @Test
    fun `旧XML思考与工具标记交错按原序渲染且卡片展开与稳定stepKey互不干扰`() {
        val text = """
            <think>第一段旧标记思考</think>
            <tool_call name="xml_tool_a">
              <command>cmd_a</command>
              <result status="success">res_a</result>
            </tool_call>
            <think>第二段旧标记思考</think>
            <tool_call name="xml_tool_b">
              <command>cmd_b</command>
              <result status="success">res_b</result>
            </tool_call>
            旧格式最终答案。
        """.trimIndent()
        val entry = TimelineEntry(
            key = "legacy-xml-turn",
            sender = "assistant",
            text = text,
            isUser = false,
            timestamp = 1_000L,
            pendingAcceptance = false,
            batchGroupId = null,
            generationId = "g-xml",
        )

        compose.setContent {
            MaterialTheme {
                Box(Modifier.verticalScroll(rememberScrollState())) {
                    MessageTimeline(entries = listOf(entry), generation = GenerationState.COMPLETED)
                }
            }
        }

        val thoughts = compose.onAllNodesWithText("已思考", substring = true).fetchSemanticsNodes()
        assertEquals(2, thoughts.size)
        val t1Top = thoughts[0].positionInRoot.y
        val toolATop = compose.onNodeWithText("[xml_tool_a]").fetchSemanticsNode().positionInRoot.y
        val t2Top = thoughts[1].positionInRoot.y
        val toolBTop = compose.onNodeWithText("[xml_tool_b]").fetchSemanticsNode().positionInRoot.y
        val finalTop = compose.onNodeWithText("旧格式最终答案。").fetchSemanticsNode().positionInRoot.y
        assertTrue(t1Top < toolATop && toolATop < t2Top && t2Top < toolBTop && toolBTop < finalTop)

        // 点击展开第一张思考卡，第二张思考卡保持折叠互不干扰
        compose.onAllNodesWithText("已思考", substring = true)[0].performClick()
        compose.onNodeWithText("第一段旧标记思考").assertIsDisplayed()

        // 验证原生 ThinkingStep 的 stepKey 基于 stepId 保持稳定（前置块增加不导致 key 漂移）
        val toolBefore = NativeExecutionStep("s-pre", "ev-pre", "read_file", "{}", "ok", NativeExecutionStatus.COMPLETED)
        val withoutPrefix = TimelineEntry(
            key = "stable-key-entry",
            sender = "assistant",
            text = "",
            isUser = false,
            timestamp = 1_000L,
            pendingAcceptance = false,
            batchGroupId = null,
            generationId = "g-key",
            timelineSteps = listOf(ExecutionTimelineStep.ThinkingStep("r-stable", "稳定思考", 0, true, 0)),
        )
        val withPrefixTool = withoutPrefix.copy(
            executionSteps = listOf(toolBefore),
            timelineSteps = listOf(
                ExecutionTimelineStep.ToolCallStep(toolBefore, 0, 0),
                ExecutionTimelineStep.ThinkingStep("r-stable", "稳定思考", 0, true, 1),
            ),
        )
        val model = TimelineDisplayModel()
        val keyBefore = (model.build(listOf(withoutPrefix), GenerationState.COMPLETED).single() as TimelineDisplayItem.Execution)
            .run.steps.filterIsInstance<ExecutionStep.Thought>().single().stepKey
        val keyAfter = (model.build(listOf(withPrefixTool), GenerationState.COMPLETED).single() as TimelineDisplayItem.Execution)
            .run.steps.filterIsInstance<ExecutionStep.Thought>().single().stepKey
        assertEquals(keyBefore, keyAfter, "前置块变化不得导致原生思考卡 stepKey 漂移")
        val clock = longArrayOf(1_000L)
        val timing = ExecutionTimingStore { clock[0] }
        timing.observe(keyBefore, running = true)
        clock[0] = 2_500L
        timing.observe(keyAfter, running = false)
        assertEquals(1_500L, timing.durationOf(keyAfter))
    }

    @Test
    fun `同时间戳无orderSequence时按到达序将工具穿插在两次思考之间`() = runTest {
        val host = FakeHost()
        val controller = WorkbenchController(
            scope = this,
            repository = host,
            catalogRepository = object : AgentCommandCatalogRepository {
                override suspend fun get(gatewayId: String, languageCode: String) =
                    AgentCommandCatalog(CatalogVersion("test"), emptyList())
            },
            scopeFactory = { ConversationScope("profile", "gateway", "account", "install") },
            persistenceDispatcher = UnconfinedTestDispatcher(testScheduler),
            authoritativeMessageIds = true,
            replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false),
        )
        try {
            runCurrent()
            controller.openThread("a")
            runCurrent()

            // 所有事件时间戳完全相同且故意不传 orderSequence，验证仅凭真实到达顺序不跳过比较
            host.events.emit(
                VerifiedConversationEvent.TimelineUpsert(
                    eventId = "arr-t1",
                    occurredAt = 500L,
                    revision = 1L,
                    message = TimelineMessage(
                        id = "arr-thought-1",
                        sender = "assistant",
                        parts = emptyList(),
                        timestamp = 500L,
                        conversationId = ConversationId("a"),
                        generationId = "g1",
                        reasoning = listOf(MessageReasoning("r1", "到达一", 0, true)),
                    ),
                ),
            )
            host.events.emit(
                VerifiedConversationEvent.ExecutionStepsUpdated(
                    eventId = "arr-tool",
                    occurredAt = 500L,
                    conversationId = ConversationId("a"),
                    generationId = "g1",
                    executionRevision = 1L,
                    steps = listOf(
                        NativeExecutionStep("arr-s1", "ev1", "grep_tool", "{}", "ok", NativeExecutionStatus.COMPLETED, 1L, occurredAt = 500L),
                    ),
                    clientMessageId = "client-a",
                    userMessageId = "user-a",
                ),
            )
            host.events.emit(
                VerifiedConversationEvent.TimelineUpsert(
                    eventId = "arr-t2",
                    occurredAt = 500L,
                    revision = 2L,
                    message = TimelineMessage(
                        id = "arr-thought-2",
                        sender = "assistant",
                        parts = listOf(MessagePart.Text("到达收尾")),
                        timestamp = 500L,
                        conversationId = ConversationId("a"),
                        generationId = "g1",
                        reasoning = listOf(MessageReasoning("r2", "到达二", 0, true)),
                    ),
                ),
            )
            host.generationState.value = GenerationState.COMPLETED
            runCurrent()

            val entries = (controller.state.value.timeline as Loadable.Ready).value
            val run = TimelineDisplayModel().build(entries, GenerationState.COMPLETED)
                .filterIsInstance<TimelineDisplayItem.Execution>().single().run
            assertEquals(
                listOf("到达一", "grep_tool", "到达二"),
                run.steps.mapNotNull { step ->
                    when (step) {
                        is ExecutionStep.Thought -> step.block.thought
                        is ExecutionStep.ToolCall -> step.block.toolName
                        else -> null
                    }
                },
            )
        } finally {
            controller.close()
        }
    }

    private class FakeHost : ConversationRepository, GenerationTracker {
        val events = MutableSharedFlow<VerifiedConversationEvent>(extraBufferCapacity = 32)
        override val generationId = MutableStateFlow<String?>("g1")
        override val generationState = MutableStateFlow(GenerationState.RUNNING)
        override suspend fun listConversations(scope: ConversationScope, page: PageRequest) =
            ConversationPage(listOf(ConversationSummary(ConversationId("a"), "a", 0)), null)
        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) = error("unused")
        override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(
            listOf(
                TimelineMessage(
                    id = "user-$conversationId",
                    sender = "user",
                    parts = listOf(MessagePart.Text("帮我排查")),
                    timestamp = 100L,
                    conversationId = ConversationId(conversationId),
                    clientMessageId = ClientMessageId("client-$conversationId"),
                    orderSequence = 0L,
                ),
            ),
            null,
        )
        override suspend fun submitMessage(message: OutgoingMessage) = error("unused")
        override suspend fun submitBatch(batch: MessageBatch) = error("unused")
        override suspend fun cancelGeneration(generationId: String, requestId: String) =
            CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
        override fun observeEvents(scope: ConversationScope) = events
    }
}
