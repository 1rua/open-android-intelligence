package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.model.ExecutionTimelineStep
import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.model.MessageReasoning
import com.openandroidintelligence.conversation.model.NativeExecutionStatus
import com.openandroidintelligence.conversation.model.NativeExecutionStep
import com.openandroidintelligence.conversation.ports.PageRequest
import com.openandroidintelligence.conversation.ports.TimelineMessage
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.conversation.remote.AgentEndpoint
import com.openandroidintelligence.conversation.remote.BackendId
import com.openandroidintelligence.conversation.remote.EndpointKey
import com.openandroidintelligence.conversation.remote.RemoteLocalIds
import com.openandroidintelligence.conversation.remote.RemoteServiceOrigin
import com.openandroidintelligence.conversation.remote.SessionCapability
import com.openandroidintelligence.conversation.sanitizer.ContextInjectionFilter
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcChannel
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcConnectionScope
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcEvent
import com.openandroidintelligence.gateway.schema.JsonValue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖全 Agent 后端（ACP / Codex / Harness V3）在实时流与历史回放两条链路下的异构步骤交错顺序。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrossBackendExecutionOrderTest {
    private val native = "session:cross-backend"
    private val local = RemoteLocalIds.encode(native)

    private fun acp(kind: String, content: String? = null, extra: List<Pair<String, Any?>> = emptyList()): JsonValue =
        obj(
            "method" to "session/update",
            "params" to obj(
                "sessionId" to native,
                "update" to obj(
                    *(listOf("sessionUpdate" to kind) +
                        listOfNotNull(content?.let { "content" to obj("type" to "text", "text" to it) }) +
                        extra).toTypedArray(),
                ),
            ),
        )

    private fun codex(method: String, vararg params: Pair<String, Any?>): JsonValue =
        obj("method" to method, "params" to obj("threadId" to native, *params))

    private fun durable(seq: Int, kind: String, data: JsonValue, time: Long = 1_000L): JsonValue =
        obj("type" to "event", "event" to obj("seq" to seq, "type" to kind, "time" to time, "data" to data))

    @Test
    fun `ACP实时流严格按思考1-工具A-思考2-工具B-最终回答顺序交错且不合并思考`() {
        var now = 1_000L
        val projection = RemoteTimelineProjection(local, clock = { now })
        projection.beginTurn("req-acp-1")

        projection.accept(acp("agent_thought_chunk", "先分析代码结构"))
        now = 1_100L
        projection.accept(
            acp(
                "tool_call",
                extra = listOf(
                    "toolCallId" to "call-a",
                    "title" to "read_file",
                    "rawInput" to obj("path" to "TimelineDisplay.kt"),
                ),
            ),
        )
        now = 1_200L
        projection.accept(
            acp(
                "tool_call_update",
                extra = listOf(
                    "toolCallId" to "call-a",
                    "status" to "completed",
                    "rawOutput" to "file content ok",
                ),
            ),
        )
        now = 1_300L
        projection.accept(acp("agent_thought_chunk", "再运行单元测试"))
        now = 1_400L
        projection.accept(
            acp(
                "tool_call",
                extra = listOf(
                    "toolCallId" to "call-b",
                    "title" to "bash",
                    "rawInput" to obj("command" to "./gradlew test"),
                ),
            ),
        )
        now = 1_500L
        projection.accept(
            acp(
                "tool_call_update",
                extra = listOf(
                    "toolCallId" to "call-b",
                    "status" to "completed",
                    "rawOutput" to "BUILD SUCCESSFUL",
                ),
            ),
        )
        now = 1_600L
        projection.accept(acp("agent_message_chunk", "已修复全部问题。"))
        now = 1_700L
        projection.accept(acp("prompt_completed", extra = listOf("stopReason" to "end_turn")))

        assertEquals(1, projection.rows.size)
        val row = projection.rows.values.single()
        assertEquals(1_000L, row.timestamp)
        assertEquals("已修复全部问题。", row.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.value })

        assertEquals(4, row.timelineSteps.size)
        val s0 = row.timelineSteps[0] as ExecutionTimelineStep.ThinkingStep
        val s1 = row.timelineSteps[1] as ExecutionTimelineStep.ToolCallStep
        val s2 = row.timelineSteps[2] as ExecutionTimelineStep.ThinkingStep
        val s3 = row.timelineSteps[3] as ExecutionTimelineStep.ToolCallStep

        assertEquals("先分析代码结构", s0.text)
        assertTrue(s0.complete)
        assertEquals("call-a", s1.step.stepId)
        assertEquals("read_file", s1.step.toolName)
        assertEquals(NativeExecutionStatus.COMPLETED, s1.step.status)
        assertEquals("file content ok", s1.step.output)
        assertEquals("再运行单元测试", s2.text)
        assertTrue(s2.complete)
        assertEquals("call-b", s3.step.stepId)
        assertEquals("bash", s3.step.toolName)
        assertEquals(NativeExecutionStatus.COMPLETED, s3.step.status)
        assertEquals("BUILD SUCCESSFUL", s3.step.output)
        assertEquals(listOf(0, 1, 2, 3), row.timelineSteps.map { it.orderIndex })
        assertEquals(listOf("先分析代码结构", "再运行单元测试"), row.reasoning.map { it.text })
    }

    @Test
    fun `ACP工具先于思考且带中间叙述文本时精确记录bodyOffset交错顺序`() {
        val projection = RemoteTimelineProjection(local)
        projection.beginTurn("req-acp-offset")

        projection.accept(acp("agent_message_chunk", "开始检查。"))
        projection.accept(acp("tool_call", extra = listOf("toolCallId" to "t1", "title" to "check_env")))
        projection.accept(acp("tool_call_update", extra = listOf("toolCallId" to "t1", "status" to "completed", "output" to "ok")))
        projection.accept(acp("agent_thought_chunk", "检查结果正常"))
        projection.accept(acp("agent_message_chunk", "准备下一步。"))
        projection.accept(acp("tool_call", extra = listOf("toolCallId" to "t2", "title" to "run_task")))
        projection.accept(acp("agent_message_chunk", "全部完成。"))
        projection.accept(acp("prompt_completed", extra = listOf("stopReason" to "end_turn")))

        val row = projection.rows.values.single()
        assertEquals("开始检查。准备下一步。全部完成。", row.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.value })
        assertEquals(3, row.timelineSteps.size)
        val first = row.timelineSteps[0] as ExecutionTimelineStep.ToolCallStep
        val second = row.timelineSteps[1] as ExecutionTimelineStep.ThinkingStep
        val third = row.timelineSteps[2] as ExecutionTimelineStep.ToolCallStep
        assertEquals("t1", first.step.stepId)
        assertEquals(5, first.bodyOffset)
        assertEquals("检查结果正常", second.text)
        assertEquals(5, second.bodyOffset)
        assertEquals("t2", third.step.stepId)
        assertEquals(11, third.bodyOffset)
        assertEquals(NativeExecutionStatus.UNKNOWN, third.step.status)
    }

    @Test
    fun `ACP历史多轮session_update回放按轮次隔离并保留各自交错顺序`() = runTest {
        val historyRecords = listOf(
            acp("user_message_chunk", "第一问", listOf("messageId" to "u1")),
            acp("agent_thought_chunk", "第一轮思考"),
            acp("tool_call", extra = listOf("toolCallId" to "c1", "title" to "read_a")),
            acp("tool_call_update", extra = listOf("toolCallId" to "c1", "status" to "completed", "output" to "a")),
            acp("agent_message_chunk", "第一答"),
            acp("user_message_chunk", "第二问", listOf("messageId" to "u2")),
            acp("agent_thought_chunk", "第二轮思考"),
            acp("tool_call", extra = listOf("toolCallId" to "c2", "title" to "read_b")),
            acp("tool_call_update", extra = listOf("toolCallId" to "c2", "status" to "completed", "output" to "b")),
            acp("agent_message_chunk", "第二答"),
        )
        val repo = historyRepository("cursor", historyRecords)
        try {
            val page = repo.timeline(local, PageRequest())
            assertEquals(4, page.messages.size)
            assertEquals(listOf("user", "assistant", "user", "assistant"), page.messages.map { it.sender })

            val firstAssistant = page.messages[1]
            assertEquals("第一答", firstAssistant.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.value })
            assertEquals(2, firstAssistant.timelineSteps.size)
            assertTrue(firstAssistant.timelineSteps[0] is ExecutionTimelineStep.ThinkingStep)
            assertEquals("c1", (firstAssistant.timelineSteps[1] as ExecutionTimelineStep.ToolCallStep).stepId)

            val secondAssistant = page.messages[3]
            assertEquals("第二答", secondAssistant.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.value })
            assertEquals(2, secondAssistant.timelineSteps.size)
            assertTrue(secondAssistant.timelineSteps[0] is ExecutionTimelineStep.ThinkingStep)
            assertEquals("c2", (secondAssistant.timelineSteps[1] as ExecutionTimelineStep.ToolCallStep).stepId)
        } finally {
            repo.close()
        }
    }

    @Test
    fun `Codex实时流按实际到达顺序分配单调递增orderSequence且完成事件不改乱原序`() {
        val projection = RemoteTimelineProjection(local, clock = { 5_000L })
        projection.accept(codex("turn/started", "turn" to obj("id" to "T-codex")))

        projection.accept(codex("item/started", "item" to obj("id" to "r1", "type" to "reasoning")))
        projection.accept(codex("item/reasoning/textDelta", "itemId" to "r1", "delta" to "思考一"))
        projection.accept(codex("item/completed", "item" to obj("id" to "r1", "type" to "reasoning", "content" to "思考一")))

        val cmdStart = projection.accept(
            codex("item/started", "item" to obj("id" to "cmd1", "type" to "commandExecution", "command" to "ls -la")),
        ).filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().single().steps.single()

        projection.accept(codex("item/started", "item" to obj("id" to "r2", "type" to "reasoning")))
        projection.accept(codex("item/completed", "item" to obj("id" to "r2", "type" to "reasoning", "content" to "思考二")))

        // 即使 cmd1 在思考二之后才 completed，其 orderSequence 必须锁定在首次开始时的位置
        val cmdDone = projection.accept(
            codex("item/completed", "item" to obj("id" to "cmd1", "type" to "commandExecution", "output" to "total 8", "status" to "completed")),
        ).filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().single().steps.single()

        val mcpStart = projection.accept(
            codex("item/started", "item" to obj("id" to "mcp1", "type" to "mcpToolCall", "tool" to "search_docs")),
        ).filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().single().steps.single()
        val mcpDone = projection.accept(
            codex("item/completed", "item" to obj("id" to "mcp1", "type" to "mcpToolCall", "result" to "matched")),
        ).filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().single().steps.single()

        projection.accept(codex("item/agentMessage/delta", "itemId" to "a1", "delta" to "最终回复"))
        projection.accept(codex("item/completed", "item" to obj("id" to "a1", "type" to "agentMessage", "text" to "最终回复")))
        projection.accept(codex("turn/completed", "turn" to obj("id" to "T-codex", "status" to "completed")))

        val r1Seq = requireNotNull(projection.rows.getValue("r1").orderSequence)
        val cmdSeq = requireNotNull(cmdDone.orderSequence)
        val r2Seq = requireNotNull(projection.rows.getValue("r2").orderSequence)
        val mcpSeq = requireNotNull(mcpDone.orderSequence)
        val answerSeq = requireNotNull(projection.rows.getValue("a1").orderSequence)

        assertEquals(cmdStart.orderSequence, cmdDone.orderSequence)
        assertEquals(mcpStart.orderSequence, mcpDone.orderSequence)
        assertEquals("commandExecution", cmdDone.toolName)
        assertEquals("total 8", cmdDone.output)
        assertTrue("r1($r1Seq) < cmd1($cmdSeq)", r1Seq < cmdSeq)
        assertTrue("cmd1($cmdSeq) < r2($r2Seq)", cmdSeq < r2Seq)
        assertTrue("r2($r2Seq) < mcp1($mcpSeq)", r2Seq < mcpSeq)
        assertTrue("mcp1($mcpSeq) < answer($answerSeq)", mcpSeq < answerSeq)
    }

    @Test
    fun `Harness单次assistant-stream内思考与工具块流式交错保持原始块序`() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(obj("type" to "assistant-stream", "frame" to obj("type" to "start", "attemptId" to "att-mix", "turn" to 1)))
        projection.accept(
            obj(
                "type" to "assistant-stream",
                "frame" to obj(
                    "type" to "chunk",
                    "attemptId" to "att-mix",
                    "chunk" to obj("type" to "reasoning-delta", "index" to 0, "text" to "第一步思考"),
                ),
            ),
        )
        projection.accept(
            obj(
                "type" to "assistant-stream",
                "frame" to obj(
                    "type" to "chunk",
                    "attemptId" to "att-mix",
                    "chunk" to obj(
                        "type" to "tool-call-delta",
                        "index" to 1,
                        "id" to "tc-1",
                        "name" to "grep",
                        "delta" to "{\"pattern\":\"foo\"}",
                    ),
                ),
            ),
        )
        projection.accept(
            obj(
                "type" to "assistant-stream",
                "frame" to obj(
                    "type" to "chunk",
                    "attemptId" to "att-mix",
                    "chunk" to obj("type" to "reasoning-delta", "index" to 2, "text" to "第二步思考"),
                ),
            ),
        )
        projection.accept(
            obj(
                "type" to "assistant-stream",
                "frame" to obj(
                    "type" to "chunk",
                    "attemptId" to "att-mix",
                    "chunk" to obj(
                        "type" to "tool-call-delta",
                        "index" to 3,
                        "id" to "tc-2",
                        "name" to "read_file",
                        "delta" to "{\"path\":\"a.kt\"}",
                    ),
                ),
            ),
        )
        projection.accept(
            obj(
                "type" to "assistant-stream",
                "frame" to obj(
                    "type" to "chunk",
                    "attemptId" to "att-mix",
                    "chunk" to obj("type" to "text-delta", "index" to 4, "text" to "综合结论"),
                ),
            ),
        )
        val row = projection.rows.values.single()
        assertEquals(4, row.timelineSteps.size)
        assertEquals("第一步思考", (row.timelineSteps[0] as ExecutionTimelineStep.ThinkingStep).text)
        assertEquals("tc-1", (row.timelineSteps[1] as ExecutionTimelineStep.ToolCallStep).stepId)
        assertEquals("第二步思考", (row.timelineSteps[2] as ExecutionTimelineStep.ThinkingStep).text)
        assertEquals("tc-2", (row.timelineSteps[3] as ExecutionTimelineStep.ToolCallStep).stepId)
        assertEquals(listOf(0, 1, 2, 3), row.timelineSteps.map { it.orderIndex })
        assertEquals("综合结论", row.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.value })

        val endEvents = projection.accept(obj("type" to "assistant-stream", "frame" to obj("type" to "end", "attemptId" to "att-mix", "turn" to 1)))
        assertTrue(endEvents.any { it is VerifiedConversationEvent.TimelineTombstoned })
        assertTrue(projection.rows.isEmpty())
    }

    @Test
    fun `Harness历史消息内嵌thinking与tool_use块由独立tool_result回填且无重复行`() = runTest {
        val historyRecords = listOf(
            durable(1, "turn/start", obj("turn" to 1), time = 1_000L),
            durable(
                2,
                "user/message",
                obj("message" to obj("id" to "u-1", "content" to listOf(obj("type" to "text", "text" to "查一下日志")))),
                time = 1_000L,
            ),
            durable(
                3,
                "assistant/message",
                obj(
                    "message" to obj(
                        "id" to "a-1",
                        "content" to listOf(
                            obj("type" to "thinking", "thinking" to "先查日志文件"),
                            obj("type" to "tool_use", "id" to "tu-1", "name" to "read_file", "input" to obj("path" to "app.log")),
                            obj("type" to "thinking", "thinking" to "再检查进程"),
                            obj("type" to "tool_use", "id" to "tu-2", "name" to "bash", "input" to obj("command" to "ps")),
                            obj("type" to "text", "text" to "日志与进程均正常。"),
                        ),
                    ),
                ),
                time = 1_000L,
            ),
            durable(4, "tool/result", obj("callId" to "tu-1", "output" to "log ok"), time = 1_000L),
            durable(5, "tool/result", obj("callId" to "tu-2", "output" to "ps ok"), time = 1_000L),
            durable(6, "turn/end", obj("turn" to 1, "reason" to obj("kind" to "completed")), time = 1_000L),
        )
        val repo = historyRepository("harness", historyRecords)
        try {
            val page = repo.timeline(local, PageRequest())
            assertEquals("内嵌工具回填不应额外生成独立工具行", 2, page.messages.size)
            val assistant = page.messages[1]
            assertEquals("a-1", assistant.id)
            assertEquals(4, assistant.timelineSteps.size)

            val step0 = assistant.timelineSteps[0] as ExecutionTimelineStep.ThinkingStep
            val step1 = assistant.timelineSteps[1] as ExecutionTimelineStep.ToolCallStep
            val step2 = assistant.timelineSteps[2] as ExecutionTimelineStep.ThinkingStep
            val step3 = assistant.timelineSteps[3] as ExecutionTimelineStep.ToolCallStep
            assertEquals("先查日志文件", step0.text)
            assertEquals("tu-1", step1.step.stepId)
            assertEquals(NativeExecutionStatus.COMPLETED, step1.step.status)
            assertEquals("log ok", step1.step.output)
            assertEquals("再检查进程", step2.text)
            assertEquals("tu-2", step3.step.stepId)
            assertEquals(NativeExecutionStatus.COMPLETED, step3.step.status)
            assertEquals("ps ok", step3.step.output)
        } finally {
            repo.close()
        }
    }

    @Test
    fun `Harness历史独立事件即使时间戳相同也按seq顺序完整恢复思考与工具交错`() = runTest {
        val historyRecords = listOf(
            durable(1, "turn/start", obj("turn" to 1), time = 2_000L),
            durable(2, "user/message", obj("message" to obj("id" to "u-1", "content" to listOf(obj("type" to "text", "text" to "排查问题")))), time = 2_000L),
            durable(3, "assistant/message", obj("message" to obj("id" to "a-t1", "content" to listOf(obj("type" to "thinking", "thinking" to "思考一")))), time = 2_000L),
            durable(4, "tool/call", obj("id" to "h-1", "name" to "read_file", "input" to obj("path" to "a.kt")), time = 2_000L),
            durable(5, "tool/result", obj("callId" to "h-1", "output" to "content-a"), time = 2_000L),
            durable(6, "assistant/message", obj("message" to obj("id" to "a-t2", "content" to listOf(obj("type" to "thinking", "thinking" to "思考二")))), time = 2_000L),
            durable(7, "tool/call", obj("id" to "h-2", "name" to "bash", "input" to obj("command" to "git status")), time = 2_000L),
            durable(8, "tool/result", obj("callId" to "h-2", "output" to "clean"), time = 2_000L),
            durable(9, "assistant/message", obj("message" to obj("id" to "a-final", "content" to listOf(obj("type" to "text", "text" to "排查完成")))), time = 2_000L),
            durable(10, "turn/end", obj("turn" to 1, "reason" to obj("kind" to "completed")), time = 2_000L),
        )
        val repo = historyRepository("harness", historyRecords)
        try {
            val page = repo.timeline(local, PageRequest())
            assertEquals(
                listOf("u-1", "a-t1", "history-tool:h-1", "a-t2", "history-tool:h-2", "a-final"),
                page.messages.map { it.id },
            )
            val seqs = page.messages.mapNotNull { it.orderSequence }
            assertEquals(page.messages.size, seqs.size)
            assertTrue("历史消息 orderSequence 必须严格单调递增: $seqs", seqs.zipWithNext().all { (a, b) -> a < b })

            val tool1 = page.messages[2].timelineSteps.single() as ExecutionTimelineStep.ToolCallStep
            assertEquals("h-1", tool1.step.stepId)
            assertEquals(NativeExecutionStatus.COMPLETED, tool1.step.status)
            assertEquals("content-a", tool1.step.output)

            val tool2 = page.messages[4].timelineSteps.single() as ExecutionTimelineStep.ToolCallStep
            assertEquals("h-2", tool2.step.stepId)
            assertEquals(NativeExecutionStatus.COMPLETED, tool2.step.status)
            assertEquals("clean", tool2.step.output)
        } finally {
            repo.close()
        }
    }

    @Test
    fun `系统提示词脱敏后同步重算ThinkingStep与ToolCallStep的bodyOffset`() {
        val raw = "<system-reminder>内部上下文</system-reminder>前缀文本后缀文本"
        val offsetBeforeStrip = "<system-reminder>内部上下文</system-reminder>前缀文本".length
        val tool = NativeExecutionStep("t1", "ev1", "read_file", "{}", "ok", NativeExecutionStatus.COMPLETED)
        val message = TimelineMessage(
            id = "m-rebase",
            sender = "assistant",
            parts = listOf(MessagePart.Text(raw)),
            timestamp = 1_000L,
            conversationId = ConversationId(local),
            generationId = "g-1",
            reasoning = listOf(MessageReasoning("r1", "中间思考", offsetBeforeStrip, true)),
            executionSteps = listOf(tool),
            timelineSteps = listOf(
                ExecutionTimelineStep.ThinkingStep("r1", "中间思考", offsetBeforeStrip, true, 0),
                ExecutionTimelineStep.ToolCallStep(tool, offsetBeforeStrip, 1),
            ),
        )

        val projected = ContextInjectionFilter.projectMessage(message)
        assertNotNull(projected)
        val text = projected!!.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.value }
        assertEquals("前缀文本后缀文本", text)
        assertFalse(text.contains("system-reminder"))
        assertEquals(4, projected.reasoning.single().bodyOffset)
        assertEquals(listOf(4, 4), projected.timelineSteps.map { it.bodyOffset })
    }

    @Test
    fun `Harness block-start与tool-call-delta共用块索引且tool_result后流式续传不覆盖已完成结果`() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(obj("type" to "assistant-stream", "frame" to obj("type" to "start", "attemptId" to "att-dedup", "turn" to 1)))
        projection.accept(
            obj(
                "type" to "assistant-stream",
                "frame" to obj(
                    "type" to "chunk",
                    "attemptId" to "att-dedup",
                    "chunk" to obj("type" to "block-start", "index" to 0, "blockType" to "tool_use", "id" to "tc-dedup", "name" to "read_file"),
                ),
            ),
        )
        projection.accept(
            obj(
                "type" to "assistant-stream",
                "frame" to obj(
                    "type" to "chunk",
                    "attemptId" to "att-dedup",
                    "chunk" to obj("type" to "tool-call-delta", "index" to 0, "id" to "tc-dedup", "delta" to "{\"path\":\"Main.kt\"}"),
                ),
            ),
        )
        projection.accept(
            obj(
                "type" to "assistant-stream",
                "frame" to obj(
                    "type" to "chunk",
                    "attemptId" to "att-dedup",
                    "chunk" to obj("type" to "block-end", "index" to 0),
                ),
            ),
        )
        // 工具结果返回完成后，同一流继续推送第二段思考与最终正文
        projection.accept(durable(1, "tool/result", obj("callId" to "tc-dedup", "output" to "read ok")))
        projection.accept(
            obj(
                "type" to "assistant-stream",
                "frame" to obj(
                    "type" to "chunk",
                    "attemptId" to "att-dedup",
                    "chunk" to obj("type" to "reasoning-delta", "index" to 1, "text" to "结合文件继续推理"),
                ),
            ),
        )
        projection.accept(
            obj(
                "type" to "assistant-stream",
                "frame" to obj(
                    "type" to "chunk",
                    "attemptId" to "att-dedup",
                    "chunk" to obj("type" to "text-delta", "index" to 2, "text" to "结论已确认"),
                ),
            ),
        )

        val row = projection.rows.values.single()
        assertEquals("同一 index 与 id 绝不能生成重复工具块", 2, row.timelineSteps.size)
        val tool = row.timelineSteps[0] as ExecutionTimelineStep.ToolCallStep
        val thought = row.timelineSteps[1] as ExecutionTimelineStep.ThinkingStep
        assertEquals("tc-dedup", tool.step.stepId)
        assertEquals("read_file", tool.step.toolName)
        assertEquals("""{"path":"Main.kt"}""", tool.step.arguments)
        assertEquals("后续流式分片不得把已完成工具覆盖回 RUNNING", NativeExecutionStatus.COMPLETED, tool.step.status)
        assertEquals("read ok", tool.step.output)
        assertEquals("结合文件继续推理", thought.text)
    }

    @Test
    fun `Codex历史回放绑定同一turnId不拆段并严格保持思考与工具顺序`() = runTest {
        val historyRecords = listOf(
            codex("turn/started", "turn" to obj("id" to "T-hist-1")),
            codex("item/completed", "item" to obj("id" to "r1", "type" to "reasoning", "content" to "历史思考1")),
            codex("item/completed", "item" to obj("id" to "cmd1", "type" to "commandExecution", "command" to "ls", "output" to "ok", "status" to "completed")),
            codex("item/completed", "item" to obj("id" to "r2", "type" to "reasoning", "content" to "历史思考2")),
            codex("item/completed", "item" to obj("id" to "a1", "type" to "agentMessage", "text" to "历史回复")),
            codex("turn/completed", "turn" to obj("id" to "T-hist-1", "status" to "completed")),
        )
        val repo = historyRepository("codex", historyRecords)
        try {
            val page = repo.timeline(local, PageRequest())
            assertEquals(listOf("r1", "history-tool:cmd1", "r2", "a1"), page.messages.map { it.id })
            assertEquals(
                "同回合全部历史条目必须绑定相同 generationId",
                listOf("T-hist-1", "T-hist-1", "T-hist-1", "T-hist-1"),
                page.messages.map { it.generationId },
            )
            val seqs = page.messages.mapNotNull { it.orderSequence }
            assertTrue("Codex 历史顺序号必须严格单调递增: $seqs", seqs.zipWithNext().all { (a, b) -> a < b })
        } finally {
            repo.close()
        }
    }

    @Test
    fun `ACP历史回放结束后收口未完工具且不污染后续实时流与catchUp补帧`() = runTest {
        val historyRecords = listOf(
            acp("user_message_chunk", "旧问题", listOf("messageId" to "u-old")),
            acp("agent_thought_chunk", "旧思考"),
            acp("tool_call", extra = listOf("toolCallId" to "old-call", "title" to "stale_tool")),
        )
        val projection = RemoteTimelineProjection(local)
        historyRecords.forEach { projection.accept(it, history = true) }
        projection.finishHistoricalReplay()

        val histAssistant = projection.rows.values.single { it.sender == "assistant" }
        val histTool = histAssistant.timelineSteps.filterIsInstance<ExecutionTimelineStep.ToolCallStep>().single()
        assertEquals("历史回放结束后末轮未完成工具应收敛为 UNKNOWN", NativeExecutionStatus.UNKNOWN, histTool.step.status)

        // 历史后新来一轮仅靠 catchUp 补帧的实时结束事件：绝不能被旧历史标记跳过，也不能拼入历史行
        projection.accept(
            acp(
                "prompt_completed",
                extra = listOf(
                    "stopReason" to "end_turn",
                    "catchUp" to listOf(
                        acp("agent_thought_chunk", "实时补帧思考"),
                        acp("tool_call", extra = listOf("toolCallId" to "live-c1", "title" to "live_tool")),
                        acp("tool_call_update", extra = listOf("toolCallId" to "live-c1", "status" to "completed", "rawOutput" to "live-ok")),
                        acp("agent_message_chunk", "实时补帧答复"),
                    ),
                ),
            ),
        )

        val assistants = projection.rows.values.filter { it.sender == "assistant" }
        assertEquals("历史助手消息与实时补帧助手消息必须隔离为两行", 2, assistants.size)
        val liveAssistant = assistants.last()
        assertEquals("实时补帧答复", liveAssistant.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.value })
        assertEquals(2, liveAssistant.timelineSteps.size)
        assertEquals("实时补帧思考", (liveAssistant.timelineSteps[0] as ExecutionTimelineStep.ThinkingStep).text)
        assertEquals("live-c1", (liveAssistant.timelineSteps[1] as ExecutionTimelineStep.ToolCallStep).stepId)
    }

    @Test
    fun `实时流工具与后续持久化assistant_message单调顺序号不倒挂`() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(obj("type" to "assistant-stream", "frame" to obj("type" to "start", "attemptId" to "att-ord", "turn" to 1)))
        projection.accept(
            obj(
                "type" to "assistant-stream",
                "frame" to obj(
                    "type" to "chunk",
                    "attemptId" to "att-ord",
                    "chunk" to obj("type" to "reasoning-delta", "index" to 0, "text" to "思考一"),
                ),
            ),
        )
        val toolCall = projection.accept(durable(1, "tool/call", obj("id" to "c-ord", "name" to "bash")))
            .filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().single().steps.single()
        projection.accept(durable(2, "tool/result", obj("callId" to "c-ord", "output" to "ok")))
        projection.accept(
            durable(
                3,
                "assistant/message",
                obj("message" to obj("id" to "a-after-tool", "content" to listOf(obj("type" to "text", "text" to "最终回复")))),
            ),
        )
        val toolSeq = requireNotNull(toolCall.orderSequence)
        val answerSeq = requireNotNull(projection.rows.getValue("a-after-tool").orderSequence)
        assertTrue("后续持久化助手回复的顺序号($answerSeq)必须严格大于先前实时工具($toolSeq)", answerSeq > toolSeq)
    }

    private fun kotlinx.coroutines.test.TestScope.historyRepository(
        backend: String,
        records: List<JsonValue>,
    ): RemoteConversationRepository {
        val key = EndpointKey(
            RemoteServiceOrigin.fromCanonicalOrigin("https://remote.example"),
            "account",
            "host",
            BackendId(backend),
            "/workspace",
        )
        val workspace = RemoteWorkspace(AgentEndpoint(key, "测试工作区"), "/workspace")
        val carrier = object : RemoteSessionCarrier {
            override val channel = object : RemoteRpcChannel {
                override val scope = RemoteRpcConnectionScope("https://remote.example", "account", "host", "client", "conn", 1)
                override val capabilities = setOf("agent.acp.v1")
                override val events = emptyFlow<RemoteRpcEvent>()
                override suspend fun invoke(method: String, params: JsonValue.JObject, requestId: String, timeoutMs: Long) = obj()
                override suspend fun close() = Unit
            }
            override val backend = backend
            override val capabilities = setOf(SessionCapability.LIST, SessionCapability.HISTORY)
            override suspend fun workspaces() = listOf(workspace)
            override suspend fun sessions(workspace: RemoteWorkspace, page: PageRequest) =
                RemoteSessionPage(listOf(RemoteSessionRow(native, "会话", 0, 1)))
            override suspend fun create(workspace: RemoteWorkspace) = RemoteSessionRow(native, "", 0, 0)
            override suspend fun history(sessionId: String, page: PageRequest) = RemoteHistoryPage(records)
            override fun follow(sessionId: String): Flow<JsonValue> = flow {
                emit(obj("type" to "remote-stream-ready"))
                MutableSharedFlow<JsonValue>().collect { emit(it) }
            }
            override suspend fun prompt(sessionId: String, requestId: String, content: List<JsonValue>, model: RemoteModelSelection?) = obj()
            override suspend fun interrupt(sessionId: String, generationId: String) = obj()
            override suspend fun respond(sessionId: String, handle: String, allow: Boolean) = obj()
            override suspend fun models(sessionId: String) = RemoteModelCatalog(emptyList(), emptyMap())
            override suspend fun selectModel(sessionId: String, selection: RemoteModelSelection) = selection
        }
        return RemoteConversationRepository(carrier, workspace, backgroundScope, { local })
    }
}
