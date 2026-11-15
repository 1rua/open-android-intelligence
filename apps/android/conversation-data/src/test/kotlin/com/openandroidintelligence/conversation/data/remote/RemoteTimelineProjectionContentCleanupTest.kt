package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.remote.RemoteLocalIds
import com.openandroidintelligence.gateway.schema.JsonValue
import org.junit.Assert.*
import org.junit.Test

/** 通过生产投影回放历史与实时正文，系统注入不能形成气泡。 */
class RemoteTimelineProjectionContentCleanupTest {
    private val native = "session-cleanup"
    private val local = RemoteLocalIds.encode(native)
    private val reminder = "<system-reminder>\nInstructions from ~/.dsh/AGENTS.md\n内部上下文\n</system-reminder>"
    private fun event(seq: Int, kind: String, data: JsonValue) = obj("type" to "event", "event" to obj(
        "seq" to seq, "type" to kind, "data" to data))
    private fun message(id: String, text: String, vararg extra: Pair<String, Any?>) = obj(
        "id" to id, "content" to listOf(obj("type" to "text", "text" to text)), *extra)
    private fun acp(kind: String, text: String, vararg extra: Pair<String, Any?>) = obj("method" to "session/update",
        "params" to obj("sessionId" to native, "update" to obj("sessionUpdate" to kind,
            "content" to obj("type" to "text", "text" to text), *extra)))
    private fun body(projection: RemoteTimelineProjection) = projection.rows.values.joinToString("") { row ->
        row.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.value }
    }

    @Test fun `Harness历史隐藏系统角色和元消息并保留用户与助手正文`() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(event(1, "user/message", message("system", "系统配置", "role" to "system")), history = true)
        projection.accept(event(2, "user/message", message("meta", "内部配置", "isMeta" to true)), history = true)
        projection.accept(event(3, "user/message", message("user", "你好")), history = true)
        projection.accept(event(4, "assistant/message", obj("message" to message("assistant", reminder + "你好，有什么可以帮忙？"))), history = true)
        assertEquals(setOf("user", "assistant"), projection.rows.keys)
        assertEquals("你好你好，有什么可以帮忙？", body(projection))
    }

    @Test fun `纯系统提醒隐藏并且媒体消息仍保留`() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(event(1, "assistant/message", obj("message" to message("hidden", reminder))), history = true)
        projection.accept(event(2, "assistant/message", obj("message" to obj("id" to "image", "content" to listOf(
            obj("type" to "text", "text" to reminder), obj("type" to "image", "id" to "image-ref", "mimeType" to "image/png"))))), history = true)
        assertEquals(setOf("image"), projection.rows.keys)
        assertEquals(1, projection.rows.getValue("image").parts.size)
        assertTrue(projection.rows.getValue("image").parts.single() is MessagePart.Attachment)
    }

    @Test fun `Harness实时提醒跨增量仍隐藏并在闭合后恢复正常正文`() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(obj("type" to "assistant-stream", "frame" to obj("type" to "start", "attemptId" to "a")))
        listOf("<system-", "reminder>隐藏", "内容</system-rem", "inder>").forEach { delta ->
            projection.accept(obj("type" to "assistant-stream", "frame" to obj("type" to "chunk", "attemptId" to "a",
                "chunk" to obj("type" to "text-delta", "index" to 0, "text" to delta))))
            assertTrue("未闭合系统提醒不可出现气泡", projection.rows.isEmpty())
        }
        projection.accept(obj("type" to "assistant-stream", "frame" to obj("type" to "chunk", "attemptId" to "a",
            "chunk" to obj("type" to "text-delta", "index" to 0, "text" to "正常回答"))))
        assertEquals("正常回答", body(projection))
    }

    @Test fun `Codex原生增量保留清洗前累积内容以识别跨片段提醒`() {
        val projection = RemoteTimelineProjection(local)
        listOf("<system-rem", "inder>隐藏</system-reminder>", "回答").forEachIndexed { index, delta ->
            projection.accept(obj("method" to "item/agentMessage/delta", "params" to obj("threadId" to native,
                "itemId" to "answer", "delta" to delta)))
            assertEquals(if (index < 2) "" else "回答", body(projection))
        }
    }

    @Test fun `ACP实时用户与助手元消息不进入历史正文`() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(acp("user_message_chunk", "隐藏", "isMeta" to true))
        projection.accept(acp("agent_message_chunk", "系统", "role" to "system"))
        projection.accept(acp("agent_message_chunk", reminder))
        assertTrue(projection.rows.isEmpty())
        projection.accept(acp("agent_message_chunk", "回答"))
        assertEquals("回答", body(projection))
    }

    @Test fun `未知流分块在没有活动尝试时不制造缺口异常`() {
        val projection = RemoteTimelineProjection(local)
        projection.beginTurn("request")
        listOf("thinking", "tool", "meta", "status").forEach { type ->
            projection.accept(obj("type" to "assistant-stream", "frame" to obj("type" to "chunk", "attemptId" to "unknown",
                "chunk" to obj("type" to type))))
        }
        projection.accept(event(1, "turn/end", obj("reason" to obj("kind" to "completed"))))
        assertEquals(GenerationState.COMPLETED, projection.generationState)
        assertTrue(projection.rows.isEmpty())
    }

    @Test fun `Harness活动尝试快照恢复后可接续合法正文分块`() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(obj("type" to "snapshot", "cursor" to 4, "records" to emptyList<Any>(),
            "assistantStream" to obj("revision" to 2, "activeAttempt" to obj("attemptId" to "active",
                "startedAfterSeq" to 4, "turn" to 2, "step" to 1, "nextIndex" to 0, "stream" to emptyList<Any>()))))
        projection.accept(obj("type" to "assistant-stream", "frame" to obj("type" to "chunk", "attemptId" to "active",
            "revision" to 3, "index" to 0, "time" to 100,
            "chunk" to obj("type" to "text-delta", "index" to 0, "text" to "回答"))))
        assertEquals("回答", body(projection))
    }

    @Test fun `Codex完整原生条目保留元消息标记供统一投影过滤`() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(obj("method" to "item/completed", "params" to obj("threadId" to native,
            "item" to obj("type" to "agentMessage", "id" to "meta", "text" to "隐藏", "isMeta" to true))))
        assertTrue(projection.rows.isEmpty())
    }


    @Test fun `同消息的系统块与未知角色隐藏而其他正文附件保留`() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(event(1, "assistant/message", obj("message" to obj("id" to "mixed", "content" to listOf(
            obj("type" to "text", "text" to "隐藏", "isMeta" to true),
            obj("type" to "text", "text" to "保留"),
            obj("type" to "image", "id" to "image"))))), history = true)
        projection.accept(event(2, "user/message", message("unknown", "内部状态", "role" to "future-internal")), history = true)
        assertEquals(setOf("mixed"), projection.rows.keys)
        assertEquals("保留", body(projection))
        assertEquals(2, projection.rows.getValue("mixed").parts.size)
    }

    @Test fun `已显示正文被完整系统快照替换为空时撤下旧行`() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(event(1, "assistant/message", obj("message" to message("same", "旧正文"))))
        val events = projection.accept(event(2, "assistant/message", obj("message" to message("same", reminder))))
        assertTrue(projection.rows.isEmpty())
        assertTrue(events.any { it is com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.TimelineTombstoned })
    }

    @Test fun `已知正文没有开场快照仍保留真正缺口异常`() {
        val projection = RemoteTimelineProjection(local)
        try {
            projection.accept(obj("type" to "assistant-stream", "frame" to obj("type" to "chunk", "attemptId" to "missing",
                "chunk" to obj("type" to "text-delta", "text" to "不应吞掉"))))
            fail("已知正文缺口必须触发重同步错误")
        } catch (failure: RemoteSessionException) { assertEquals("REMOTE_ASSISTANT_STREAM_GAP", failure.code) }
    }


    @Test fun `Codex元消息身份阻止后续无标记正文思考完成与历史重新出现`() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(obj("method" to "turn/started", "params" to obj("threadId" to native, "turn" to obj("id" to "turn"))))
        val hidden = obj("id" to "hidden", "type" to "agentMessage", "isMeta" to true)
        val emitted = mutableListOf<com.openandroidintelligence.conversation.ports.VerifiedConversationEvent>()
        emitted += projection.accept(obj("method" to "item/started", "params" to obj("threadId" to native, "item" to hidden)))
        emitted += projection.accept(obj("method" to "item/agentMessage/delta", "params" to obj("threadId" to native,
            "itemId" to "hidden", "delta" to "内部正文")))
        emitted += projection.accept(obj("method" to "item/reasoning/textDelta", "params" to obj("threadId" to native,
            "itemId" to "hidden", "delta" to "内部思考")))
        emitted += projection.accept(obj("method" to "item/completed", "params" to obj("threadId" to native,
            "item" to obj("id" to "hidden", "type" to "agentMessage", "text" to "内部终稿"))))
        emitted += projection.accept(event(1, "assistant/message", obj("message" to message("hidden", "内部历史"))), history = true)
        projection.accept(obj("method" to "item/agentMessage/delta", "params" to obj("threadId" to native,
            "itemId" to "visible", "delta" to "正常回答")))
        emitted += projection.accept(obj("method" to "turn/completed", "params" to obj("threadId" to native,
            "turn" to obj("id" to "turn", "status" to "completed"))))
        assertEquals(setOf("visible"), projection.rows.keys)
        assertEquals("正常回答", body(projection))
        assertTrue("内部消息不能进入镜像或通知使用的正文事件", emitted.filterIsInstance<com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.TimelineUpsert>()
            .none { it.message.id == "hidden" })
    }

    @Test fun `Harness元内容块记住索引且不影响同尝试的正常块和失败工具事实`() {
        val projection = RemoteTimelineProjection(local)
        val emitted = mutableListOf<com.openandroidintelligence.conversation.ports.VerifiedConversationEvent>()
        fun chunk(value: JsonValue) { emitted += projection.accept(obj("type" to "assistant-stream", "frame" to obj(
            "type" to "chunk", "attemptId" to "attempt", "chunk" to value))) }
        projection.accept(event(1, "turn/start", obj("turn" to 1)))
        projection.accept(obj("type" to "assistant-stream", "frame" to obj("type" to "start", "attemptId" to "attempt", "turn" to 1)))
        chunk(obj("type" to "block-start", "index" to 0, "blockType" to "text", "isMeta" to true))
        chunk(obj("type" to "text-delta", "index" to 0, "text" to "内部正文"))
        chunk(obj("type" to "reasoning-delta", "index" to 0, "text" to "内部思考"))
        chunk(obj("type" to "block-end", "index" to 0))
        chunk(obj("type" to "block-start", "index" to 1, "blockType" to "text"))
        chunk(obj("type" to "text-delta", "index" to 1, "text" to "正常回答"))
        val tools = projection.accept(event(2, "tool/result", obj("id" to "tool", "name" to "测试工具", "isError" to true)))
        emitted += projection.accept(event(3, "turn/end", obj("turn" to 1, "reason" to obj("kind" to "error"))))
        assertEquals("正常回答", body(projection))
        assertTrue(projection.rows.values.all { it.reasoning.isEmpty() })
        assertEquals(GenerationState.FAILED, projection.generationState)
        assertTrue(tools.any { it is com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.ExecutionStepsUpdated })
        assertTrue("隐藏内容不能先泄漏到任一正文事件", emitted.filterIsInstance<com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.TimelineUpsert>()
            .all { event -> event.message.parts.filterIsInstance<MessagePart.Text>().none { it.value.contains("内部") } && event.message.reasoning.none { it.text.contains("内部") } })
    }


    @Test fun `Harness隐藏尝试在增量结束与快照追赶后均不能复活且不跨会话实例`() {
        listOf(false, true).forEach { fromSnapshot ->
            val projection = RemoteTimelineProjection(local)
            val emitted = mutableListOf<com.openandroidintelligence.conversation.ports.VerifiedConversationEvent>()
            fun active(meta: Boolean) = obj("type" to "snapshot", "cursor" to 0, "records" to emptyList<Any>(),
                "assistantStream" to obj("activeAttempt" to obj("attemptId" to "hidden-attempt", "turn" to 1,
                    "isMeta" to meta, "stream" to emptyList<Any>())))
            if (fromSnapshot) emitted += projection.accept(active(true))
            else emitted += projection.accept(obj("type" to "assistant-stream", "frame" to obj("type" to "start",
                "attemptId" to "hidden-attempt", "turn" to 1, "isMeta" to true)))
            emitted += projection.accept(obj("type" to "assistant-stream", "frame" to obj("type" to "chunk",
                "attemptId" to "hidden-attempt", "chunk" to obj("type" to "text-delta", "index" to 0, "text" to "内部正文"))))
            emitted += projection.accept(obj("type" to "assistant-stream", "frame" to obj("type" to "end", "attemptId" to "hidden-attempt")))
            emitted += projection.accept(active(false))
            emitted += projection.accept(obj("type" to "snapshot", "cursor" to 0, "records" to emptyList<Any>(),
                "assistantStream" to obj("entries" to listOf(obj("attemptId" to "hidden-attempt",
                    "content" to listOf(obj("type" to "text", "text" to "内部追赶")))))))
            assertTrue("整次隐藏尝试不能被无标记增量或快照恢复", projection.rows.isEmpty())
            assertTrue(emitted.none { it is com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.TimelineUpsert })
            val nextSession = RemoteTimelineProjection(local)
            nextSession.accept(obj("type" to "assistant-stream", "frame" to obj("type" to "start", "attemptId" to "hidden-attempt")))
            nextSession.accept(obj("type" to "assistant-stream", "frame" to obj("type" to "chunk", "attemptId" to "hidden-attempt",
                "chunk" to obj("type" to "text-delta", "index" to 0, "text" to "新的正常回答"))))
            assertEquals("新的正常回答", body(nextSession))
        }
    }


    @Test fun `Codex参数外层元标记将隐藏身份延续到无标记正文和历史`() {
        listOf("isMeta" to true, "role" to "system").forEach { marker ->
            listOf("item/started", "item/agentMessage/delta", "item/reasoning/textDelta").forEach { method ->
                val projection = RemoteTimelineProjection(local)
                val identity = if (method == "item/started") "item" to obj("id" to "hidden", "type" to "agentMessage")
                    else "itemId" to "hidden"
                val emitted = mutableListOf<com.openandroidintelligence.conversation.ports.VerifiedConversationEvent>()
                emitted += projection.accept(obj("method" to method, "params" to obj("threadId" to native,
                    marker, identity, "delta" to "内部配置")))
                emitted += projection.accept(obj("method" to "item/agentMessage/delta", "params" to obj("threadId" to native,
                    "itemId" to "hidden", "delta" to "内部正文")))
                emitted += projection.accept(obj("method" to "item/reasoning/textDelta", "params" to obj("threadId" to native,
                    "itemId" to "hidden", "delta" to "内部思考")))
                emitted += projection.accept(obj("method" to "item/completed", "params" to obj("threadId" to native,
                    "item" to obj("id" to "hidden", "type" to "agentMessage", "text" to "内部终稿"))))
                emitted += projection.accept(event(1, "assistant/message", obj("message" to message("hidden", "内部历史"))), history = true)
                projection.accept(obj("method" to "item/agentMessage/delta", "params" to obj("threadId" to native,
                    "itemId" to "visible", "delta" to "正常回答")))
                assertEquals("$method 的 $marker 必须记住隐藏身份", setOf("visible"), projection.rows.keys)
                assertEquals("正常回答", body(projection))
                assertTrue(emitted.none { it is com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.TimelineUpsert })
            }
        }
    }

    @Test fun `其他会话的参数元标记不能污染当前会话同名消息身份`() {
        listOf("threadId", "sessionId").forEach { field ->
            val projection = RemoteTimelineProjection(local)
            projection.accept(obj("method" to "item/agentMessage/delta", "params" to obj("threadId" to native,
                "itemId" to "shared", "delta" to "正常")))
            val foreign = projection.accept(obj("method" to "item/started", "params" to obj(field to "other-session",
                "isMeta" to true, "item" to obj("id" to "shared", "type" to "agentMessage"))))
            projection.accept(obj("method" to "item/agentMessage/delta", "params" to obj("threadId" to native,
                "itemId" to "shared", "delta" to "回答")))
            assertEquals("正常回答", body(projection))
            assertTrue("外部会话不得撤下当前行或登记隐藏状态", foreign.isEmpty())
        }
    }

    @Test fun `Codex与ACP元包装不吞真实工具事实和失败取消终态`() {
        listOf("failed" to GenerationState.FAILED, "cancelled" to GenerationState.CANCELLED).forEach { (status, expected) ->
            val codex = RemoteTimelineProjection(local)
            codex.accept(obj("method" to "turn/started", "params" to obj("threadId" to native, "turn" to obj("id" to "turn"))))
            val tool = codex.accept(obj("method" to "item/completed", "params" to obj("threadId" to native, "isMeta" to true,
                "item" to obj("id" to "tool", "type" to "commandExecution", "isMeta" to true, "status" to "failed"))))
            codex.accept(obj("method" to "turn/completed", "params" to obj("threadId" to native, "role" to "system",
                "turn" to obj("id" to "turn", "status" to status))))
            assertTrue("工具事实不得按正文元标记丢弃", tool.any { it is com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.ExecutionStepsUpdated })
            assertEquals(expected, codex.generationState)
        }
        val acpProjection = RemoteTimelineProjection(local)
        acpProjection.beginTurn("request")
        val tool = acpProjection.accept(obj("method" to "session/update", "params" to obj("sessionId" to native, "isMeta" to true,
            "update" to obj("sessionUpdate" to "tool_call_update", "toolCallId" to "tool", "isMeta" to true, "status" to "failed"))))
        acpProjection.accept(obj("method" to "session/update", "params" to obj("sessionId" to native, "isMeta" to true,
            "update" to obj("sessionUpdate" to "prompt_failed", "isMeta" to true))))
        assertTrue(tool.any { it is com.openandroidintelligence.conversation.ports.VerifiedConversationEvent.ExecutionStepsUpdated })
        assertEquals(GenerationState.FAILED, acpProjection.generationState)
    }


    @Test fun `ACP用户分块外层元标记隐藏而正常用户与助手分块保留`() {
        listOf("isMeta" to true, "role" to "system").forEach { marker ->
            val projection = RemoteTimelineProjection(local)
            projection.accept(obj("method" to "session/update", "params" to obj(
                "sessionId" to native, marker,
                "update" to obj("sessionUpdate" to "user_message_chunk", "content" to obj("type" to "text", "text" to "内部用户上下文")))))
            assertTrue("ACP外层 $marker 不应形成用户正文", projection.rows.isEmpty())
            projection.accept(acp("user_message_chunk", "真实用户消息"))
            projection.accept(acp("agent_message_chunk", "正常助手回答"))
            assertEquals(listOf("真实用户消息", "正常助手回答"), projection.rows.values.flatMap { row ->
                row.parts.filterIsInstance<MessagePart.Text>().map { it.value }
            })
            assertTrue("助手正常正文不能被用户元标记影响", projection.rows.values.any { it.sender == "assistant" && body(projection).contains("正常助手回答") })
        }
    }


    @Test fun `ACP助手元分段丢弃当前片段后后续正常回答重新建立可见流`() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(acp("agent_message_chunk", "回答前半"))
        projection.accept(acp("agent_message_chunk", "内部元块", "role" to "system"))
        projection.accept(acp("agent_message_chunk", "回答后半"))
        assertEquals(listOf("回答前半", "回答后半"), projection.rows.values.map { row ->
            row.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.value }
        })
        assertTrue(projection.rows.values.none { row -> row.parts.filterIsInstance<MessagePart.Text>().any { it.value.contains("内部元块") } })
    }

}
