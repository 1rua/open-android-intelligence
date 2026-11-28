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

    @Test
    fun `独立历史tool_call先于含内嵌tool_use的assistant_message到达时回收临时history_tool行并升级真实sourceMessageId`() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(durable(1, "turn/start", obj("turn" to 1)), history = true)
        projection.accept(durable(2, "tool/call", obj("id" to "tu-claim", "name" to "read_file", "input" to obj("path" to "a.kt"))), history = true)
        projection.accept(durable(3, "tool/result", obj("callId" to "tu-claim", "output" to "ok-claim")), history = true)
        assertTrue("先到的独立历史工具应暂存临时行", "history-tool:tu-claim" in projection.rows)

        val claimEvents = projection.accept(
            durable(
                4,
                "assistant/message",
                obj(
                    "message" to obj(
                        "id" to "a-real",
                        "content" to listOf(
                            obj("type" to "thinking", "thinking" to "先看文件"),
                            obj("type" to "tool_use", "id" to "tu-claim", "name" to "read_file", "input" to obj("path" to "a.kt")),
                            obj("type" to "text", "text" to "已核对完毕"),
                        ),
                    ),
                ),
            ),
            history = true,
        )

        assertFalse("正式消息认领内嵌工具后必须移除临时 history-tool 行", "history-tool:tu-claim" in projection.rows)
        assertTrue(
            "认领时应发出临时行 Tombstone 事件",
            claimEvents.any { it is VerifiedConversationEvent.TimelineTombstoned && it.messageId == "history-tool:tu-claim" },
        )
        val realRow = projection.rows.getValue("a-real")
        val claimedTool = realRow.timelineSteps.filterIsInstance<ExecutionTimelineStep.ToolCallStep>().single().step
        assertEquals("a-real", claimedTool.sourceMessageId)
        assertEquals(NativeExecutionStatus.COMPLETED, claimedTool.status)
        assertEquals("ok-claim", claimedTool.output)
    }

    @Test
    fun `AssistantContent默认块索引碰撞时不同callId与正文分片互不串写且空对象占位不拼出非法JSON`() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(obj("type" to "assistant-stream", "frame" to obj("type" to "start", "attemptId" to "att-collision", "turn" to 1)))
        // block-start 带默认空对象 input: {}，随后分片不带 callId 连续增量
        projection.accept(
            obj(
                "type" to "assistant-stream",
                "frame" to obj(
                    "type" to "chunk",
                    "attemptId" to "att-collision",
                    "chunk" to obj("type" to "block-start", "blockType" to "tool_use", "id" to "c-first", "name" to "read_file", "input" to obj()),
                ),
            ),
        )
        projection.accept(
            obj(
                "type" to "assistant-stream",
                "frame" to obj(
                    "type" to "chunk",
                    "attemptId" to "att-collision",
                    "chunk" to obj("type" to "tool-call-delta", "delta" to "{\"path\":\""),
                ),
            ),
        )
        projection.accept(
            obj(
                "type" to "assistant-stream",
                "frame" to obj(
                    "type" to "chunk",
                    "attemptId" to "att-collision",
                    "chunk" to obj("type" to "tool-call-delta", "delta" to "a.kt\"}"),
                ),
            ),
        )
        // 第二个不同 callId 的工具同样未携带 index（回退为 "0"），不得覆盖第一个工具
        projection.accept(
            obj(
                "type" to "assistant-stream",
                "frame" to obj(
                    "type" to "chunk",
                    "attemptId" to "att-collision",
                    "chunk" to obj("type" to "tool-call-delta", "id" to "c-second", "name" to "bash", "delta" to "{\"cmd\":\"ls\"}"),
                ),
            ),
        )
        // 同默认 index "0" 的正文分片不得拼入工具 arguments
        projection.accept(
            obj(
                "type" to "assistant-stream",
                "frame" to obj(
                    "type" to "chunk",
                    "attemptId" to "att-collision",
                    "chunk" to obj("type" to "text-delta", "text" to "独立正文内容"),
                ),
            ),
        )

        val row = projection.rows.values.single()
        val tools = row.timelineSteps.filterIsInstance<ExecutionTimelineStep.ToolCallStep>().map { it.step }
        assertEquals(listOf("c-first", "c-second"), tools.map { it.stepId })
        assertEquals("""{"path":"a.kt"}""", tools[0].arguments)
        assertEquals("""{"cmd":"ls"}""", tools[1].arguments)
        assertEquals("独立正文内容", row.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.value })
    }

    @Test
    fun `Codex流式思考在item_completed重建快照时保留原stepId不突变`() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(codex("turn/started", "turn" to obj("id" to "T-key")))
        projection.accept(codex("item/started", "item" to obj("id" to "r-key", "type" to "reasoning")))
        projection.accept(codex("item/reasoning/summaryTextDelta", "itemId" to "r-key", "summaryIndex" to 0, "delta" to "流式摘要"))
        val streamingStepId = (projection.rows.getValue("r-key").timelineSteps.single() as ExecutionTimelineStep.ThinkingStep).stepId
        assertEquals("summary:0", streamingStepId)

        projection.accept(codex("item/completed", "item" to obj("id" to "r-key", "type" to "reasoning", "summary" to listOf("流式摘要完整版"))))
        val completedStep = projection.rows.getValue("r-key").timelineSteps.single() as ExecutionTimelineStep.ThinkingStep
        assertEquals("完成快照必须继承流式阶段的首个思考块 key", streamingStepId, completedStep.stepId)
        assertEquals("流式摘要完整版", completedStep.text)
        assertTrue(completedStep.complete)
    }

    @Test
    fun `历史turn_end收敛遗留RUNNING工具时同步返回TimelineUpsert事件`() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(durable(1, "turn/start", obj("turn" to 9)), history = true)
        projection.accept(durable(2, "tool/call", obj("id" to "h-unsettled", "name" to "bash")), history = true)
        val endEvents = projection.accept(
            durable(3, "turn/end", obj("turn" to 9, "reason" to obj("kind" to "completed"))),
            history = true,
        )
        val upserted = endEvents.filterIsInstance<VerifiedConversationEvent.TimelineUpsert>().single().message
        assertEquals("history-tool:h-unsettled", upserted.id)
        assertEquals(NativeExecutionStatus.UNKNOWN, upserted.executionSteps.single().status)
    }

    @Test
    fun `乱序裸tool_result先于tool_call到达时按callId缓冲合并且绝不产生孤立tool卡`() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(durable(1, "turn/start", obj("turn" to 1)))

        // 1. 裸结果先于调用到达（且携带空字符串 callId 与真实 toolCallId）：绝不生成孤立 [tool] 卡
        val earlyResultEvents = projection.accept(
            durable(2, "tool/result", obj("callId" to "", "toolCallId" to "call-ooo-1", "output" to "early-ok")),
        )
        assertTrue("裸结果先到时不得发出孤立事件", earlyResultEvents.isEmpty())
        assertTrue("裸结果先到时不得在时间线造孤立行", projection.rows.isEmpty())

        // 2. 随后对应 tool/call 到达：自动合并先前缓冲结果，单卡即达 COMPLETED
        val callEvents = projection.accept(
            durable(3, "tool/call", obj("callId" to "call-ooo-1", "name" to "read_file", "arguments" to """{"path":"a.kt"}""")),
        )
        val mergedStep = callEvents.filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().single().steps.single()
        assertEquals("call-ooo-1", mergedStep.stepId)
        assertEquals("read_file", mergedStep.toolName)
        assertEquals("""{"path":"a.kt"}""", mergedStep.arguments)
        assertEquals("early-ok", mergedStep.output)
        assertEquals(NativeExecutionStatus.COMPLETED, mergedStep.status)

        // 3. 完全无主的孤立裸结果（无论带未匹配 callId 还是缺失 callId 且无工具名）：绝不生成孤立 [tool] 卡片
        assertTrue(
            projection.accept(durable(4, "tool/result", obj("callId" to "ghost-call-99", "output" to "orphan"))).isEmpty(),
        )
        assertTrue(
            projection.accept(durable(5, "tool/result", obj("toolName" to "tool", "output" to ""))).isEmpty(),
        )
        assertTrue("孤立结果绝不写入时间线行", projection.rows.isEmpty())
    }

    @Test
    fun `流式block_start无真实ID后经tool_call_delta重绑真实callId并与tool_result单卡配对`() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(obj("type" to "assistant-stream", "frame" to obj("type" to "start", "attemptId" to "att-rebind", "turn" to 1)))
        projection.accept(
            obj(
                "type" to "assistant-stream",
                "frame" to obj(
                    "type" to "chunk",
                    "attemptId" to "att-rebind",
                    "chunk" to obj("type" to "block-start", "index" to 0, "blockType" to "tool_use", "name" to "grep"),
                ),
            ),
        )
        projection.accept(
            obj(
                "type" to "assistant-stream",
                "frame" to obj(
                    "type" to "chunk",
                    "attemptId" to "att-rebind",
                    "chunk" to obj("type" to "tool-call-delta", "index" to 0, "callId" to "real-call-77", "delta" to """{"q":"TODO"}"""),
                ),
            ),
        )
        val resultEvents = projection.accept(
            durable(10, "tool/result", obj("callId" to "real-call-77", "output" to "3 matches")),
        )
        // 已更新原宿主流式行，不再补发重复 ExecutionStepsUpdated
        assertTrue(resultEvents.filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().isEmpty())
        val row = projection.rows.getValue("remote-stream:att-rebind")
        val steps = row.timelineSteps.filterIsInstance<ExecutionTimelineStep.ToolCallStep>().map { it.step }
        assertEquals("重绑后必须只保留单张卡片", 1, steps.size)
        assertEquals("real-call-77", steps.single().stepId)
        assertEquals("grep", steps.single().toolName)
        assertEquals("""{"q":"TODO"}""", steps.single().arguments)
        assertEquals("3 matches", steps.single().output)
        assertEquals(NativeExecutionStatus.COMPLETED, steps.single().status)
    }

    @Test
    fun `Codex的functionCall按call_id配对且增量outputDelta与MCP格式及隐藏条目过滤均生效`() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(codex("turn/started", "turn" to obj("id" to "T-pair")))

        // 1. hookPrompt / contextCompaction 隐藏条目不得生成消息或工具卡
        assertTrue(
            projection.accept(codex("item/completed", "item" to obj("id" to "hp-1", "type" to "hookPrompt", "text" to "internal hook"))).isEmpty(),
        )
        assertTrue(
            projection.accept(codex("item/completed", "item" to obj("id" to "cc-1", "type" to "contextCompaction", "text" to "compacted"))).isEmpty(),
        )

        // 2. functionCall + functionCallOutput 通过 call_id（而非 item.id）配对
        val fcStart = projection.accept(
            codex("item/started", "item" to obj("id" to "item-fc-1", "type" to "functionCall", "call_id" to "fc_call_9", "name" to "search", "arguments" to """{"q":"kotlin"}""")),
        ).filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().single().steps.single()
        assertEquals("fc_call_9", fcStart.stepId)
        val fcDone = projection.accept(
            codex("item/completed", "item" to obj("id" to "item-fco-2", "type" to "functionCallOutput", "call_id" to "fc_call_9", "output" to "found 5")),
        ).filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().single().steps.single()
        assertEquals("fc_call_9", fcDone.stepId)
        assertEquals("search", fcDone.toolName)
        assertEquals("found 5", fcDone.output)
        assertEquals(NativeExecutionStatus.COMPLETED, fcDone.status)

        // 3. commandExecution 增量 outputDelta 累计输出 + MCP server/tool 名称 + contentItems 文本提取
        projection.accept(codex("item/started", "item" to obj("id" to "cmd-delta", "type" to "commandExecution", "command" to "echo hi")))
        val deltaStep = projection.accept(
            codex("item/commandExecution/outputDelta", "itemId" to "cmd-delta", "delta" to "line1\n"),
        ).filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().single().steps.single()
        assertEquals("line1\n", deltaStep.output)
        val deltaStep2 = projection.accept(
            codex("item/commandExecution/outputDelta", "itemId" to "cmd-delta", "delta" to "line2"),
        ).filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().single().steps.single()
        assertEquals("line1\nline2", deltaStep2.output)

        val mcpDone = projection.accept(
            codex(
                "item/completed",
                "item" to obj(
                    "id" to "mcp-ci",
                    "type" to "mcpToolCall",
                    "server" to "github",
                    "tool" to "list_issues",
                    "contentItems" to listOf(obj("type" to "text", "text" to "Issue #250")),
                ),
            ),
        ).filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().single().steps.single()
        assertEquals("github: list_issues", mcpDone.toolName)
        assertEquals("Issue #250", mcpDone.output)
    }

    @Test
    fun `ACP在已有正文轮次仍通过catchUp回放tool_call_update并支持跨轮回写与去孤立卡`() {
        val projection = RemoteTimelineProjection(local)
        projection.beginTurn("acp-req-1")

        // 1. 孤立裸 tool_call_update 无调用定义时不得创建孤立 [tool] 卡
        assertTrue(
            projection.accept(
                acp("tool_call_update", extra = listOf("toolCallId" to "ghost-acp", "status" to "completed")),
            ).isEmpty(),
        )
        assertTrue(projection.rows.isEmpty())

        // 2. 本轮先有正文 + tool_call（in_progress），导致 acpTurnHadContent = true
        projection.accept(acp("agent_message_chunk", content = "正在查询"))
        projection.accept(
            acp(
                "tool_call",
                extra = listOf("toolCallId" to "acp-tc-1", "title" to "fetch_logs", "rawInput" to obj("limit" to 20), "status" to "in_progress"),
            ),
        )
        val firstRowId = projection.rows.keys.single()

        // 3. prompt_completed 携带 catchUp 中的 tool_call_update（带嵌套 ToolCallContent）：必须回放完成而非标 UNKNOWN
        projection.accept(
            acp(
                "prompt_completed",
                extra = listOf(
                    "stopReason" to "end_turn",
                    "catchUp" to listOf(
                        acp(
                            "tool_call_update",
                            extra = listOf(
                                "toolCallId" to "acp-tc-1",
                                "status" to "completed",
                                "content" to listOf(
                                    obj("type" to "content", "content" to obj("type" to "text", "text" to "log line ok")),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val firstRow = projection.rows.getValue(firstRowId)
        val step1 = firstRow.executionSteps.single()
        assertEquals("acp-tc-1", step1.stepId)
        assertEquals("fetch_logs", step1.toolName)
        assertEquals("log line ok", step1.output)
        assertEquals(NativeExecutionStatus.COMPLETED, step1.status)

        // 4. 跨轮迟到 tool_call_update（命中上一轮的 acp-tc-1）：必须回写原消息行而不是在新轮创建重复卡
        projection.beginTurn("acp-req-2")
        projection.accept(acp("agent_message_chunk", content = "第二轮正文"))
        projection.accept(
            acp(
                "tool_call_update",
                extra = listOf("toolCallId" to "acp-tc-1", "status" to "completed", "rawOutput" to "late-enriched"),
            ),
        )
        assertEquals(2, projection.rows.size)
        val secondRow = projection.rows.values.last()
        assertTrue("跨轮迟到更新不得污染新轮工具列表", secondRow.executionSteps.isEmpty())
        assertEquals("late-enriched", projection.rows.getValue(firstRowId).executionSteps.single().output)
    }

    @Test
    fun `OpenAI与Hermes风格tool_calls数组和role_tool消息精确配对并隐藏内部tool行`() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(
            durable(
                1,
                "assistant/message",
                obj(
                    "turnId" to "turn-hermes",
                    "message" to obj(
                        "id" to "msg-hermes-asst",
                        "role" to "assistant",
                        "content" to listOf(obj("type" to "text", "text" to "先查一下目录")),
                        "tool_calls" to listOf(
                            obj(
                                "id" to "call_hermes_1",
                                "type" to "function",
                                "function" to obj("name" to "shell", "arguments" to """{"cmd":"pwd"}"""),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val toolResultEvents = projection.accept(
            durable(
                2,
                "user/message",
                obj(
                    "turnId" to "turn-hermes",
                    "message" to obj(
                        "id" to "msg-hermes-tool-res",
                        "role" to "tool",
                        "tool_call_id" to "call_hermes_1",
                        "content" to "/workspace/project",
                    ),
                ),
            ),
        )
        assertFalse("role=tool 内部结果行绝不能上屏", "msg-hermes-tool-res" in projection.rows)
        val updatedRow = projection.rows.getValue("msg-hermes-asst")
        val step = updatedRow.executionSteps.single()
        assertEquals("call_hermes_1", step.stepId)
        assertEquals("shell", step.toolName)
        assertEquals("""{"cmd":"pwd"}""", step.arguments)
        assertEquals("/workspace/project", step.output)
        assertEquals(NativeExecutionStatus.COMPLETED, step.status)
        assertEquals(1, toolResultEvents.filterIsInstance<VerifiedConversationEvent.TimelineUpsert>().size)
    }

    @Test
    fun `ACP进行中预览内容不误判完成且Codex拒绝态判失败及MCP结果与stdout_data提取准确`() {
        val projection = RemoteTimelineProjection(local)

        // 1. ACP tool_call 显式 status=in_progress 且携带预览 content：必须保持 RUNNING，待 completed 才转终态
        projection.beginTurn("acp-inprogress-req")
        projection.accept(
            acp(
                "tool_call",
                extra = listOf(
                    "toolCallId" to "acp-preview-1",
                    "title" to "apply_patch",
                    "status" to "in_progress",
                    "content" to listOf(obj("type" to "content", "content" to obj("type" to "text", "text" to "preview diff"))),
                ),
            ),
        )
        val rowId = projection.rows.keys.single()
        val runningStep = projection.rows.getValue(rowId).executionSteps.single()
        assertEquals(NativeExecutionStatus.RUNNING, runningStep.status)
        assertEquals("preview diff", runningStep.output)

        projection.accept(
            acp(
                "tool_call_update",
                extra = listOf(
                    "toolCallId" to "acp-preview-1",
                    "status" to "completed",
                    "rawOutput" to "patched 1 file",
                ),
            ),
        )
        val doneStep = projection.rows.getValue(rowId).executionSteps.single()
        assertEquals(NativeExecutionStatus.COMPLETED, doneStep.status)
        assertEquals("patched 1 file", doneStep.output)

        // 2. Codex declined 判 FAILED；MCP result:{content:[{type:text}]} 提取纯文本；tool/result 支持 data 与 stdout+stderr
        val declinedStep = projection.accept(
            codex(
                "item/completed",
                "item" to obj(
                    "id" to "cmd-declined",
                    "type" to "commandExecution",
                    "command" to "rm -rf /",
                    "status" to "declined",
                    "stderr" to "user declined",
                ),
            ),
        ).filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().single().steps.single()
        assertEquals(NativeExecutionStatus.FAILED, declinedStep.status)
        assertEquals("user declined", declinedStep.output)

        val mcpEnvelopeStep = projection.accept(
            codex(
                "item/completed",
                "item" to obj(
                    "id" to "mcp-env-1",
                    "type" to "mcpToolCall",
                    "server" to "docs",
                    "tool" to "search",
                    "result" to obj("content" to listOf(obj("type" to "text", "text" to "Found spec"))),
                ),
            ),
        ).filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().single().steps.single()
        assertEquals("docs: search", mcpEnvelopeStep.toolName)
        assertEquals("Found spec", mcpEnvelopeStep.output)

        projection.accept(durable(20, "tool/call", obj("callId" to "call-data-1", "name" to "fetch")))
        val dataResult = projection.accept(
            durable(21, "tool/result", obj("callId" to "call-data-1", "data" to "payload-from-data")),
        ).filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().single().steps.single()
        assertEquals("payload-from-data", dataResult.output)
        assertEquals(NativeExecutionStatus.COMPLETED, dataResult.status)

        projection.accept(durable(22, "tool/call", obj("callId" to "call-std-1", "name" to "run")))
        val stdResult = projection.accept(
            durable(23, "tool/result", obj("callId" to "call-std-1", "stdout" to "out-1", "stderr" to "err-1")),
        ).filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().single().steps.single()
        assertEquals("out-1\nerr-1", stdResult.output)
        assertEquals(NativeExecutionStatus.COMPLETED, stdResult.status)

        // 3. 已终态工具收到迟到 outputDelta 必须跳过，不能重复追加污染最终输出
        val duplicateDeltaEvents = projection.accept(
            codex("item/commandExecution/outputDelta", "itemId" to "cmd-declined", "delta" to "late-noise"),
        )
        assertTrue("终态步骤不得追加迟到增量输出", duplicateDeltaEvents.isEmpty())
    }

    @Test
    fun `Codex的item_completed_functionCall保持运行态待functionCallOutput配对且无结果回合收敛为UNKNOWN`() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(codex("turn/started", "turn" to obj("id" to "T-fc")), history = true)

        // 1. item/completed(functionCall) 仅表示参数生成完成，未含输出时必须保持 RUNNING 而非 COMPLETED
        projection.accept(
            codex(
                "item/completed",
                "turnId" to "T-fc",
                "item" to obj(
                    "id" to "item-fc-call-1",
                    "type" to "functionCall",
                    "call_id" to "call_fc_pair_1",
                    "name" to "read_file",
                    "arguments" to """{"path":"build.gradle.kts"}""",
                ),
            ),
            history = true,
        )
        val runningRow = projection.rows.getValue("history-tool:call_fc_pair_1")
        assertEquals(NativeExecutionStatus.RUNNING, runningRow.executionSteps.single().status)

        // 2. 随后同 call_id 的 functionCallOutput 到达 -> 才转为 COMPLETED 并写入输出
        projection.accept(
            codex(
                "item/completed",
                "turnId" to "T-fc",
                "item" to obj(
                    "id" to "item-fco-1",
                    "type" to "functionCallOutput",
                    "call_id" to "call_fc_pair_1",
                    "output" to "plugins { id(\"android\") }",
                ),
            ),
            history = true,
        )
        val pairedRow = projection.rows.getValue("history-tool:call_fc_pair_1")
        assertEquals(NativeExecutionStatus.COMPLETED, pairedRow.executionSteps.single().status)
        assertEquals("plugins { id(\"android\") }", pairedRow.executionSteps.single().output)

        // 3. 另一条 functionCall 同样以 item/completed 到达但直到回合结束都无 functionCallOutput -> 收敛为 UNKNOWN（未返回结果）
        projection.accept(
            codex(
                "item/completed",
                "turnId" to "T-fc",
                "item" to obj(
                    "id" to "item-fc-call-2",
                    "type" to "functionCall",
                    "call_id" to "call_fc_missing_2",
                    "name" to "search_docs",
                    "arguments" to """{"q":"compose"}""",
                ),
            ),
            history = true,
        )
        assertEquals(NativeExecutionStatus.RUNNING, projection.rows.getValue("history-tool:call_fc_missing_2").executionSteps.single().status)
        projection.accept(codex("turn/completed", "turn" to obj("id" to "T-fc", "status" to "completed")), history = true)
        val missingRow = projection.rows.getValue("history-tool:call_fc_missing_2")
        assertEquals(NativeExecutionStatus.UNKNOWN, missingRow.executionSteps.single().status)
    }

    @Test
    fun `活跃运行中回合刷新历史或接收含流式snapshot时不得误将工具标为UNKNOWN`() = runTest {
        // 1. snapshot 帧包含进行中回合与活跃 assistantStream：已结束的旧回合标 UNKNOWN，活跃回合保持 RUNNING
        val projection = RemoteTimelineProjection(local)
        projection.accept(
            obj(
                "type" to "snapshot",
                "records" to listOf(
                    durable(1, "turn/start", obj("turn" to 1)),
                    durable(2, "tool/call", obj("callId" to "old-unfinished", "name" to "bash")),
                    durable(3, "turn/end", obj("turn" to 1, "reason" to obj("kind" to "completed"))),
                    durable(4, "turn/start", obj("turn" to 2)),
                    durable(5, "tool/call", obj("callId" to "live-active-call", "name" to "read_file")),
                ),
                "assistantStream" to obj(
                    "entries" to listOf(
                        obj("attemptId" to "att-live", "turn" to 2, "content" to listOf(obj("type" to "text", "text" to "执行中"))),
                    ),
                ),
            ),
            history = true,
        )
        assertEquals(
            "已结束历史回合缺失结果应收敛为 UNKNOWN",
            NativeExecutionStatus.UNKNOWN,
            projection.rows.getValue("history-tool:old-unfinished").executionSteps.single().status,
        )
        assertEquals(
            "活跃运行中回合的工具不得在刷新快照时误标 UNKNOWN",
            NativeExecutionStatus.RUNNING,
            projection.rows.getValue("history-tool:live-active-call").executionSteps.single().status,
        )

        // 2. RemoteConversationRepository.loadTimeline 透传 result.activeGenerationId 跳过活跃回合收尾
        val activeTurnRecords = listOf(
            durable(10, "turn/start", obj("turn" to 7)),
            durable(11, "tool/call", obj("callId" to "repo-active-call", "name" to "grep")),
        )
        val repo = historyRepository("harness", activeTurnRecords, activeGenerationId = "7", activityKnown = true)
        val page = repo.timeline(local, PageRequest())
        val step = page.messages.single { it.id == "history-tool:repo-active-call" }.executionSteps.single()
        assertEquals("loadTimeline 活跃回合工具必须保持 RUNNING", NativeExecutionStatus.RUNNING, step.status)
    }

    @Test
    fun `function_response顶层消息回写工具且隐藏气泡且assistantSnapshot跨消息tool_result不重复插卡且实时tool_calls标LIVE`() {
        val projection = RemoteTimelineProjection(local)

        // 1. 实时非流式 assistant/message 携带 tool_calls -> presentationOrigin 必须为 LIVE（不抑制工具卡入场动效）
        val callEvents = projection.accept(
            durable(
                1,
                "assistant/message",
                obj(
                    "turnId" to "turn-fr",
                    "message" to obj(
                        "id" to "msg-asst-fr",
                        "role" to "assistant",
                        "content" to listOf(obj("type" to "text", "text" to "调用工具")),
                        "tool_calls" to listOf(
                            obj(
                                "id" to "call-fr-1",
                                "type" to "function",
                                "function" to obj("name" to "lookup", "arguments" to """{"k":"v"}"""),
                            ),
                        ),
                    ),
                ),
            ),
        )
        val liveCallUpsert = callEvents.filterIsInstance<VerifiedConversationEvent.TimelineUpsert>().single()
        assertEquals(
            "实时非流式带工具调用消息必须标记 LIVE",
            com.openandroidintelligence.conversation.model.StreamPresentationOrigin.LIVE,
            liveCallUpsert.presentationOrigin,
        )

        // 2. sender="user" 且 messageType="function_response" 的顶层工具结果：必须回写原工具卡且隐藏自身气泡
        val frEvents = projection.accept(
            durable(
                2,
                "user/message",
                obj(
                    "turnId" to "turn-fr",
                    "message" to obj(
                        "id" to "msg-fr-hidden",
                        "role" to "user",
                        "messageType" to "function_response",
                        "tool_call_id" to "call-fr-1",
                        "content" to "lookup-ok",
                    ),
                ),
            ),
        )
        assertFalse("function_response 消息绝不能上屏", "msg-fr-hidden" in projection.rows)
        val updatedFrStep = projection.rows.getValue("msg-asst-fr").executionSteps.single()
        assertEquals("lookup-ok", updatedFrStep.output)
        assertEquals(NativeExecutionStatus.COMPLETED, updatedFrStep.status)
        assertEquals(1, frEvents.filterIsInstance<VerifiedConversationEvent.TimelineUpsert>().count { it.message.id == "msg-asst-fr" })

        // 3. assistantSnapshot 中出现归属前一条消息（msg-prev-tool）的 tool_result：回写原宿主行，不得在当前快照行重复造卡
        projection.accept(
            obj(
                "type" to "snapshot",
                "records" to listOf(
                    durable(
                        10,
                        "assistant/message",
                        obj(
                            "turnId" to "turn-snap",
                            "message" to obj(
                                "id" to "msg-prev-tool",
                                "role" to "assistant",
                                "content" to listOf(
                                    obj("type" to "tool_use", "id" to "call-prev-1", "name" to "read_file", "input" to obj("path" to "a.kt")),
                                ),
                            ),
                        ),
                    ),
                ),
                "assistantStream" to obj(
                    "entries" to listOf(
                        obj(
                            "attemptId" to "att-cross",
                            "turn" to "turn-snap",
                            "content" to listOf(
                                obj("type" to "tool_result", "tool_use_id" to "call-prev-1", "output" to "prev-file-data"),
                                obj("type" to "text", "text" to "根据读取结果继续"),
                            ),
                        ),
                    ),
                ),
            ),
            history = true,
        )
        val prevRow = projection.rows.getValue("msg-prev-tool")
        assertEquals("prev-file-data", prevRow.executionSteps.single().output)
        assertEquals(NativeExecutionStatus.COMPLETED, prevRow.executionSteps.single().status)
        val snapStreamRow = projection.rows.getValue("remote-stream:att-cross")
        assertTrue("跨消息 tool_result 不得在当前 assistantSnapshot 行重复插卡", snapStreamRow.executionSteps.isEmpty())
    }

    @Test
    fun `Codex持久化tool_result从message_content的tool-result块提取配对ID与输出覆盖无外层和带外层callId形态`() = runTest {
        val historyRecords = listOf(
            durable(1, "turn/start", obj("turn" to 1), time = 1_000L),
            durable(
                2,
                "tool/call",
                obj("turn" to 1, "callId" to "codex-cmd-1", "name" to "commandExecution", "command" to "ls -la"),
                time = 1_000L,
            ),
            // 形态 A：外层 data 不带 callId，toolCallId 与输出均在 data.message.content 的 tool-result 块内
            durable(
                3,
                "tool/result",
                obj(
                    "turn" to 1,
                    "message" to obj(
                        "role" to "tool",
                        "content" to listOf(
                            obj(
                                "type" to "tool-result",
                                "toolCallId" to "codex-cmd-1",
                                "toolName" to "commandExecution",
                                "output" to "total 12\nREADME.md",
                            ),
                        ),
                    ),
                ),
                time = 1_010L,
            ),
            durable(
                4,
                "tool/call",
                obj("turn" to 1, "callId" to "codex-cmd-2", "name" to "commandExecution", "command" to "git status"),
                time = 1_020L,
            ),
            // 形态 B：外层 data 额外带 callId，但实际输出在 data.message.content 的 tool-result 块内
            durable(
                5,
                "tool/result",
                obj(
                    "turn" to 1,
                    "callId" to "codex-cmd-2",
                    "message" to obj(
                        "role" to "tool",
                        "content" to listOf(
                            obj(
                                "type" to "tool-result",
                                "toolCallId" to "codex-cmd-2",
                                "output" to obj("type" to "text", "value" to "nothing to commit"),
                            ),
                        ),
                    ),
                ),
                time = 1_030L,
            ),
            durable(
                6,
                "tool/call",
                obj("turn" to 1, "callId" to "codex-cmd-3", "name" to "read_file", "arguments" to """{"path":"a.kt"}"""),
                time = 1_040L,
            ),
            durable(
                7,
                "tool/call",
                obj("turn" to 1, "callId" to "codex-cmd-4", "name" to "read_file", "arguments" to """{"path":"b.kt"}"""),
                time = 1_045L,
            ),
            // 形态 C：单条持久化消息携带多个 tool_result / tool-result 块
            durable(
                8,
                "tool/result",
                obj(
                    "turn" to 1,
                    "message" to obj(
                        "role" to "tool",
                        "content" to listOf(
                            obj("type" to "tool-result", "toolCallId" to "codex-cmd-3", "content" to listOf(obj("type" to "text", "text" to "file-a"))),
                            obj("type" to "tool_result", "tool_use_id" to "codex-cmd-4", "isError" to true, "output" to "not found"),
                        ),
                    ),
                ),
                time = 1_050L,
            ),
            durable(9, "turn/end", obj("turn" to 1, "reason" to obj("kind" to "completed")), time = 1_060L),
        )

        val repo = historyRepository("codex", historyRecords, activityKnown = true)
        try {
            val firstPage = repo.timeline(local, PageRequest())
            val refreshedPage = repo.timeline(local, PageRequest())
            for (page in listOf(firstPage, refreshedPage)) {
                val byId = page.messages.associateBy { it.id }
                val step1 = byId.getValue("history-tool:codex-cmd-1").executionSteps.single()
                assertEquals(NativeExecutionStatus.COMPLETED, step1.status)
                assertEquals("total 12\nREADME.md", step1.output)

                val step2 = byId.getValue("history-tool:codex-cmd-2").executionSteps.single()
                assertEquals(NativeExecutionStatus.COMPLETED, step2.status)
                assertEquals("nothing to commit", step2.output)

                val step3 = byId.getValue("history-tool:codex-cmd-3").executionSteps.single()
                assertEquals(NativeExecutionStatus.COMPLETED, step3.status)
                assertEquals("file-a", step3.output)

                val step4 = byId.getValue("history-tool:codex-cmd-4").executionSteps.single()
                assertEquals(NativeExecutionStatus.FAILED, step4.status)
                assertEquals("not found", step4.output)
            }
        } finally {
            repo.close()
        }
    }

    @Test
    fun `DSH开场snapshot从activeAttempt恢复活跃流与工具调用后同attemptId后续chunk正常续接不抛GAP`() {
        // 1. 仓库样例形态 A（builtin-review-round2-fixed-probe）：activeAttempt.stream 含 text-chunks，后续 chunk 续接正文
        val projection = RemoteTimelineProjection(local, clock = { 2_000L })
        projection.accept(
            obj(
                "type" to "snapshot",
                "cursor" to 2,
                "records" to emptyList<Any>(),
                "assistantStream" to obj(
                    "revision" to 2,
                    "activeAttempt" to obj(
                        "turn" to 1,
                        "attemptId" to "attempt-one",
                        "stream" to listOf(
                            obj("type" to "text-chunks", "texts" to listOf("已有前缀")),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(com.openandroidintelligence.conversation.model.GenerationState.RUNNING, projection.generationState)
        assertEquals("1", projection.generationId)
        val afterSnap = projection.rows.getValue("remote-stream:attempt-one")
        assertEquals("已有前缀", afterSnap.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.value })

        projection.accept(
            obj(
                "type" to "assistant-stream",
                "frame" to obj(
                    "type" to "chunk",
                    "attemptId" to "attempt-one",
                    "chunk" to obj("type" to "text-delta", "text" to "后续分片"),
                ),
            ),
        )
        val afterChunk = projection.rows.getValue("remote-stream:attempt-one")
        assertEquals("已有前缀后续分片", afterChunk.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.value })

        // 2. 仓库样例形态 B（builtin-review-fixed-probe）：省略 records 且 stream 为空数组，后续 chunk 不抛 REMOTE_ASSISTANT_STREAM_GAP
        val emptyStreamProjection = RemoteTimelineProjection(local, clock = { 3_000L })
        emptyStreamProjection.accept(
            obj(
                "type" to "snapshot",
                "assistantStream" to obj(
                    "revision" to 1,
                    "activeAttempt" to obj(
                        "attemptId" to "already-started",
                        "turn" to 1,
                        "stream" to emptyList<Any>(),
                    ),
                ),
            ),
        )
        emptyStreamProjection.accept(
            obj(
                "type" to "assistant-stream",
                "frame" to obj(
                    "type" to "chunk",
                    "attemptId" to "already-started",
                    "chunk" to obj("type" to "text-delta", "text" to "空流后首片"),
                ),
            ),
        )
        assertEquals(
            "空流后首片",
            emptyStreamProjection.rows.getValue("remote-stream:already-started").parts
                .filterIsInstance<MessagePart.Text>().joinToString("") { it.value },
        )

        // 3. 含 tool-call-chunks、nextIndex 与历史运行中工具的 activeAttempt：保留活跃工具、忽略重复旧 index、正常续接并回写工具结果
        val toolStreamProjection = RemoteTimelineProjection(local, clock = { 4_000L })
        toolStreamProjection.accept(
            obj(
                "type" to "snapshot",
                "cursor" to 5,
                "records" to listOf(
                    durable(4, "turn/start", obj("turn" to 2), time = 3_900L),
                    durable(5, "tool/call", obj("turn" to 2, "callId" to "hist-running", "name" to "grep"), time = 3_950L),
                ),
                "assistantStream" to obj(
                    "revision" to 4,
                    "activeAttempt" to obj(
                        "attemptId" to "attempt-tool",
                        "startedAfterSeq" to 5,
                        "turn" to 2,
                        "step" to 1,
                        "nextIndex" to 2,
                        "stream" to listOf(
                            obj("type" to "chunkrow/tool-call-chunks", "index" to 0, "id" to "tc-snap-1", "name" to "read_file", "args" to listOf("{\"path\":", "\"Main.kt\"}")),
                            obj("type" to "chunkrow/text-chunks", "index" to 1, "texts" to listOf("正在读取")),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(
            "activeAttempt 声明 turn=2 活跃时，历史运行中工具不得误标 UNKNOWN",
            NativeExecutionStatus.RUNNING,
            toolStreamProjection.rows.getValue("history-tool:hist-running").executionSteps.single().status,
        )
        // 已在 snapshot 中覆盖的旧序号 index=1 忽略不重复追加
        assertTrue(
            toolStreamProjection.accept(
                obj(
                    "type" to "assistant-stream",
                    "frame" to obj("type" to "chunk", "attemptId" to "attempt-tool", "index" to 1, "chunk" to obj("type" to "text-delta", "index" to 1, "text" to "正在读取")),
                ),
            ).isEmpty(),
        )
        // 预期序号 index=2 正常续接正文，随后 tool/result 回写原工具卡
        toolStreamProjection.accept(
            obj(
                "type" to "assistant-stream",
                "frame" to obj("type" to "chunk", "attemptId" to "attempt-tool", "index" to 2, "chunk" to obj("type" to "text-delta", "index" to 1, "text" to "并分析")),
            ),
        )
        toolStreamProjection.accept(
            durable(6, "tool/result", obj("turn" to 2, "callId" to "tc-snap-1", "output" to "package demo"), time = 4_100L),
        )
        val liveRow = toolStreamProjection.rows.getValue("remote-stream:attempt-tool")
        assertEquals("正在读取并分析", liveRow.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.value })
        val liveTool = liveRow.executionSteps.single()
        assertEquals("tc-snap-1", liveTool.stepId)
        assertEquals("read_file", liveTool.toolName)
        assertEquals("""{"path":"Main.kt"}""", liveTool.arguments)
        assertEquals("package demo", liveTool.output)
        assertEquals(NativeExecutionStatus.COMPLETED, liveTool.status)
    }

    @Test
    fun `Codex实时原生turnId与历史数字turn序号对齐时活跃工具保持RUNNING不被误改UNKNOWN`() = runTest {
        val projection = RemoteTimelineProjection(local, clock = { 1_000L })
        // 1. 实时链路先用原生 turnId 投影运行中的工具
        projection.accept(codex("turn/started", "turn" to obj("id" to "turn_native_active")))
        projection.accept(
            codex(
                "item/started",
                "turnId" to "turn_native_active",
                "item" to obj("id" to "cmd-live-1", "type" to "commandExecution", "command" to "./gradlew test"),
            ),
        )

        // 2. 随后加载同一会话的 dsh/sessionHistory：历史事件里的 turn 为数字 1，而分页 activeTurnId 为原生 ID
        projection.accept(durable(1, "turn/start", obj("turn" to 0), time = 500L), history = true)
        projection.accept(durable(2, "tool/call", obj("turn" to 0, "callId" to "cmd-old-0", "name" to "bash"), time = 510L), history = true)
        projection.accept(durable(3, "turn/end", obj("turn" to 0, "reason" to obj("kind" to "completed")), time = 520L), history = true)
        projection.accept(durable(4, "turn/start", obj("turn" to 1), time = 1_000L), history = true)
        projection.accept(durable(5, "tool/call", obj("turn" to 1, "callId" to "cmd-live-1", "name" to "commandExecution", "command" to "./gradlew test"), time = 1_010L), history = true)
        projection.accept(durable(6, "tool/call", obj("turn" to 1, "callId" to "cmd-live-2", "name" to "read_file"), time = 1_020L), history = true)
        projection.finishHistoricalReplay(activeGenerationId = "turn_native_active")

        val oldRow = projection.rows.getValue("history-tool:cmd-old-0")
        assertEquals("已结束旧轮次缺结果工具仍应收敛为 UNKNOWN", NativeExecutionStatus.UNKNOWN, oldRow.executionSteps.single().status)

        val activeRow1 = projection.rows.getValue("history-tool:cmd-live-1")
        assertEquals("已绑定原生轮次的工具卡必须保留原生 generationId", "turn_native_active", activeRow1.generationId)
        assertEquals("活跃回合工具绝不能因数字 turn 与原生 activeTurnId 失配被改成 UNKNOWN", NativeExecutionStatus.RUNNING, activeRow1.executionSteps.single().status)

        val activeRow2 = projection.rows.getValue("history-tool:cmd-live-2")
        assertEquals("同活跃数字回合内的其他运行中工具也应保持 RUNNING", NativeExecutionStatus.RUNNING, activeRow2.executionSteps.single().status)

        // 3. 冷打开场景：仓储首次加载仅含数字 turn=1 历史记录且分页声明原生 activeTurnId，同样保持 RUNNING
        val coldRecords = listOf(
            durable(1, "turn/start", obj("turn" to 1), time = 2_000L),
            durable(2, "tool/call", obj("turn" to 1, "callId" to "cmd-cold-1", "name" to "commandExecution"), time = 2_010L),
        )
        val repo = historyRepository("codex", coldRecords, activeGenerationId = "turn_native_cold", activityKnown = true)
        try {
            val page = repo.timeline(local, PageRequest())
            val coldStep = page.messages.single { it.id == "history-tool:cmd-cold-1" }.executionSteps.single()
            assertEquals(NativeExecutionStatus.RUNNING, coldStep.status)
        } finally {
            repo.close()
        }
    }

    @Test
    fun `Codex内部条目在实时与历史入口使用统一过滤判定且codex_compaction不产生工具卡`() = runTest {
        val projection = RemoteTimelineProjection(local)
        projection.accept(codex("turn/started", "turn" to obj("id" to "T-filter")))
        assertTrue(projection.accept(codex("item/started", "item" to obj("id" to "c-live", "type" to "contextCompaction"))).isEmpty())
        assertTrue(projection.accept(codex("item/completed", "item" to obj("id" to "c-live", "type" to "contextCompaction"))).isEmpty())
        assertTrue(projection.accept(codex("item/completed", "item" to obj("id" to "h-live", "type" to "hookPrompt"))).isEmpty())
        assertTrue(projection.rows.isEmpty())

        val historyRecords = listOf(
            durable(1, "turn/start", obj("turn" to 1), time = 1_000L),
            durable(2, "tool/call", obj("turn" to 1, "callId" to "cmp-1", "name" to "codex.compaction"), time = 1_010L),
            durable(
                3,
                "tool/result",
                obj(
                    "turn" to 1,
                    "message" to obj(
                        "role" to "tool",
                        "content" to listOf(
                            obj("type" to "tool-result", "toolCallId" to "cmp-1", "output" to "Context compacted"),
                        ),
                    ),
                ),
                time = 1_020L,
            ),
            durable(4, "tool/call", obj("turn" to 1, "callId" to "hk-1", "name" to "hookPrompt"), time = 1_025L),
            durable(5, "tool/call", obj("turn" to 1, "callId" to "real-cmd-1", "name" to "commandExecution", "command" to "echo ok"), time = 1_030L),
            durable(6, "tool/result", obj("turn" to 1, "callId" to "real-cmd-1", "output" to "ok"), time = 1_040L),
            durable(7, "turn/end", obj("turn" to 1, "reason" to obj("kind" to "completed")), time = 1_050L),
        )
        val repo = historyRepository("codex", historyRecords, activityKnown = true)
        try {
            val page = repo.timeline(local, PageRequest())
            assertEquals("codex.compaction 与 hookPrompt 历史条目不得新增工具卡", listOf("history-tool:real-cmd-1"), page.messages.map { it.id })
            assertEquals(1, page.messages.sumOf { it.executionSteps.size })
            assertEquals("ok", page.messages.single().executionSteps.single().output)
        } finally {
            repo.close()
        }
    }

    @Test
    fun `缺少配对ID的裸tool_result即使携带真实工具名在实时与历史链路均绝不创建新卡片`() {
        // 1. 实时链路
        val liveProjection = RemoteTimelineProjection(local)
        liveProjection.accept(durable(1, "turn/start", obj("turn" to 1)))
        val liveCallEvents = liveProjection.accept(
            durable(2, "tool/call", obj("callId" to "call-01", "name" to "read_file", "arguments" to """{"path":"a.kt"}""")),
        )
        assertEquals(1, liveCallEvents.filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().size)
        val liveUnpairedResult = liveProjection.accept(
            durable(3, "tool/result", obj("name" to "read_file", "output" to "orphan output")),
        )
        assertTrue("缺少配对 ID 的裸 tool/result 即使带真实 name 也绝不能产生 event:3 新卡", liveUnpairedResult.isEmpty())
        assertTrue(liveProjection.rows.isEmpty())

        // 2. 历史链路：仅保留 history-tool:call-01（绝不出现 history-tool:event:3），回合结束后自然标注 UNKNOWN
        val historyProjection = RemoteTimelineProjection(local)
        historyProjection.accept(durable(1, "turn/start", obj("turn" to 1)), history = true)
        historyProjection.accept(
            durable(2, "tool/call", obj("turn" to 1, "callId" to "call-01", "name" to "read_file", "arguments" to """{"path":"a.kt"}""")),
            history = true,
        )
        val histUnpairedResult = historyProjection.accept(
            durable(3, "tool/result", obj("turn" to 1, "name" to "read_file", "output" to "orphan output")),
            history = true,
        )
        assertTrue("历史链路缺少配对 ID 的裸 tool/result 不得新增卡片", histUnpairedResult.isEmpty())
        historyProjection.accept(durable(4, "turn/end", obj("turn" to 1, "reason" to obj("kind" to "completed"))), history = true)

        assertEquals(listOf("history-tool:call-01"), historyProjection.rows.keys.toList())
        val step = historyProjection.rows.getValue("history-tool:call-01").executionSteps.single()
        assertEquals("call-01", step.stepId)
        assertEquals("无匹配结果的原调用在回合结束后应标注 UNKNOWN", NativeExecutionStatus.UNKNOWN, step.status)
    }

    @Test
    fun `Codex先收outputDelta后收带完整aggregatedOutput的item_completed优先保留终态完整输出`() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(codex("turn/started", "turn" to obj("id" to "T-delta-priority")))

        // 1. 缓冲路径：先收到 outputDelta（尚未 item/started），再收到携带两行 aggregatedOutput 的 item/completed
        val bufferedDeltaEvents = projection.accept(
            codex("item/commandExecution/outputDelta", "turnId" to "T-delta-priority", "itemId" to "cmd-buf-1", "delta" to "第一行"),
        )
        assertTrue(bufferedDeltaEvents.isEmpty())
        val completedStep = projection.accept(
            codex(
                "item/completed",
                "turnId" to "T-delta-priority",
                "item" to obj(
                    "id" to "cmd-buf-1",
                    "type" to "commandExecution",
                    "command" to "printf '第一行\\n第二行'",
                    "aggregatedOutput" to "第一行\n第二行",
                    "status" to "completed",
                ),
            ),
        ).filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().single().steps.single()
        assertEquals(NativeExecutionStatus.COMPLETED, completedStep.status)
        assertEquals("终态完整输出必须优先保留，不得被先前缓冲的增量覆盖", "第一行\n第二行", completedStep.output)

        // 2. 缓冲回填路径：若终态 item/completed 未携带输出，则回填先前累计的增量输出
        projection.accept(
            codex("item/commandExecution/outputDelta", "turnId" to "T-delta-priority", "itemId" to "cmd-buf-2", "delta" to "第一行"),
        )
        val backfilledStep = projection.accept(
            codex(
                "item/completed",
                "turnId" to "T-delta-priority",
                "item" to obj(
                    "id" to "cmd-buf-2",
                    "type" to "commandExecution",
                    "command" to "echo 第一行",
                    "status" to "completed",
                ),
            ),
        ).filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().single().steps.single()
        assertEquals(NativeExecutionStatus.COMPLETED, backfilledStep.status)
        assertEquals("终态未带输出时应回填先前累计的增量输出", "第一行", backfilledStep.output)

        // 3. 已登记路径：item/started -> outputDelta("第一行") -> item/completed(aggregatedOutput="第一行\n第二行")
        projection.accept(
            codex("item/started", "turnId" to "T-delta-priority", "item" to obj("id" to "cmd-reg-3", "type" to "commandExecution", "command" to "cmd")),
        )
        projection.accept(
            codex("item/commandExecution/outputDelta", "turnId" to "T-delta-priority", "itemId" to "cmd-reg-3", "delta" to "第一行"),
        )
        val regCompleted = projection.accept(
            codex(
                "item/completed",
                "turnId" to "T-delta-priority",
                "item" to obj("id" to "cmd-reg-3", "type" to "commandExecution", "aggregatedOutput" to "第一行\n第二行", "status" to "completed"),
            ),
        ).filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().single().steps.single()
        assertEquals(NativeExecutionStatus.COMPLETED, regCompleted.status)
        assertEquals("第一行\n第二行", regCompleted.output)
    }

    private fun kotlinx.coroutines.test.TestScope.historyRepository(
        backend: String,
        records: List<JsonValue>,
        activeGenerationId: String? = null,
        activityKnown: Boolean = false,
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
            override suspend fun history(sessionId: String, page: PageRequest) =
                RemoteHistoryPage(records, activeGenerationId = activeGenerationId, activityKnown = activityKnown)
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
