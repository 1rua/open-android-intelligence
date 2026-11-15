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

}
