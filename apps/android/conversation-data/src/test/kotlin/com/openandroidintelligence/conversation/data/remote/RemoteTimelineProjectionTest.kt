package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.conversation.remote.RemoteLocalIds
import com.openandroidintelligence.gateway.schema.JsonValue
import org.junit.Assert.*
import org.junit.Test

/** 固定上游的合法事件顺序：历史只能补正文，实时执行与下一轮内容各自隔离。 */
class RemoteTimelineProjectionTest {
    private val native = "session:原始"
    private val local = RemoteLocalIds.encode(native)
    private fun acp(kind: String, content: String? = null, extra: List<Pair<String, Any?>> = emptyList()): JsonValue =
        obj("method" to "session/update", "params" to obj("sessionId" to native,
            "update" to obj(*(listOf("sessionUpdate" to kind) + listOfNotNull(content?.let { "content" to obj("type" to "text", "text" to it) }) + extra).toTypedArray())))
    private fun event(seq: Int, kind: String, data: JsonValue): JsonValue = obj("type" to "event", "event" to obj("seq" to seq,
        "type" to kind, "time" to 100, "data" to data))
    private fun text(projection: RemoteTimelineProjection): String = projection.rows.values.joinToString("\n") {
        it.parts.filterIsInstance<MessagePart.Text>().joinToString("") { part -> part.value }
    }

    @Test fun `Codex实时轮次之后补旧历史不改变当前执行身份或状态`() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(obj("method" to "turn/started", "params" to obj("threadId" to native, "turn" to obj("id" to "T2"))))
        projection.accept(event(1, "turn/start", obj("turn" to 1)), history = true)
        projection.accept(event(2, "turn/end", obj("turn" to 1, "reason" to obj("kind" to "completed"))), history = true)
        assertEquals("T2", projection.generationId)
        assertEquals(GenerationState.RUNNING, projection.generationState)
        assertNull(projection.terminalGeneration)
    }

    @Test fun `新一轮真实turn不能继承上一轮终态`() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(obj("method" to "turn/started", "params" to obj("threadId" to native,
            "turn" to obj("id" to "T1"))))
        projection.accept(obj("method" to "turn/completed", "params" to obj("threadId" to native,
            "turn" to obj("id" to "T1", "status" to "completed"))))
        projection.accept(obj("method" to "turn/started", "params" to obj("threadId" to native,
            "turn" to obj("id" to "T2"))))
        assertEquals("T2", projection.generationId)
        assertEquals(GenerationState.RUNNING, projection.generationState)
    }

    @Test fun `ACP第二轮仅终态追赶内容仍显示且不带入上一轮正文`() {
        val projection = RemoteTimelineProjection(local)
        projection.beginTurn("request:first")
        projection.accept(acp("agent_message_chunk", "第一轮"))
        projection.accept(acp("prompt_completed", extra = listOf("stopReason" to "end_turn")))
        projection.beginTurn("request:second")
        val completed = projection.accept(acp("prompt_completed", extra = listOf("stopReason" to "end_turn", "catchUp" to listOf(acp("agent_message_chunk", "第二轮")))))
        assertEquals(listOf("第二轮"), completed.filterIsInstance<VerifiedConversationEvent.TimelineUpsert>().map { it.message.parts.filterIsInstance<MessagePart.Text>().joinToString("") { part -> part.value } }.distinct())
        assertEquals("第一轮\n第二轮", text(projection))
    }

    @Test fun `ACP同轮已有实时正文时终态追赶副本不能重复追加`() {
        val projection = RemoteTimelineProjection(local)
        projection.beginTurn("request:first")
        projection.accept(acp("agent_message_chunk", "正文"))
        projection.accept(acp("prompt_completed", extra = listOf("stopReason" to "end_turn", "catchUp" to listOf(acp("agent_message_chunk", "正文")))))
        assertEquals("正文", text(projection))
    }

    @Test fun `Harness思考流与历史思考块使用既有思考卡片结构`() {
        val projection = RemoteTimelineProjection(local)
        projection.accept(obj("type" to "assistant-stream", "frame" to obj("type" to "start", "attemptId" to "attempt", "turn" to 1)))
        projection.accept(obj("type" to "assistant-stream", "frame" to obj("type" to "chunk", "attemptId" to "attempt",
            "chunk" to obj("type" to "reasoning-delta", "index" to 0, "text" to "正在推理"))))
        assertEquals("正在推理", projection.rows.values.single().reasoning.single().text)
        assertFalse(projection.rows.values.single().reasoning.single().complete)
        assertEquals("", text(projection))
        projection.accept(event(1, "assistant/message", obj("message" to obj("id" to "history", "content" to listOf(
            obj("type" to "reasoning", "text" to "历史推理"), obj("type" to "text", "text" to "答案"))))), history = true)
        assertEquals("历史推理", projection.rows.getValue("history").reasoning.single().text)
        assertTrue(projection.rows.getValue("history").reasoning.single().complete)
        assertEquals("答案", projection.rows.getValue("history").parts.filterIsInstance<MessagePart.Text>().single().value)
    }

    @Test fun `ACP和Codex原生思考增量保留在现有思考卡片`() {
        val acpProjection = RemoteTimelineProjection(local)
        acpProjection.beginTurn("request")
        acpProjection.accept(acp("agent_thought_chunk", "先想"))
        acpProjection.accept(acp("agent_message_chunk", "回答"))
        assertEquals("先想", acpProjection.rows.values.single().reasoning.single().text)
        assertTrue(acpProjection.rows.values.single().reasoning.single().complete)
        assertEquals("回答", text(acpProjection))
        val codex = RemoteTimelineProjection(local)
        codex.accept(obj("method" to "turn/started", "params" to obj("threadId" to native, "turn" to obj("id" to "T"))))
        codex.accept(obj("method" to "item/reasoning/summaryTextDelta", "params" to obj("threadId" to native, "itemId" to "reasoning", "summaryIndex" to 0, "delta" to "摘要")))
        codex.accept(obj("method" to "item/reasoning/textDelta", "params" to obj("threadId" to native, "itemId" to "reasoning", "contentIndex" to 0, "delta" to "细节")))
        assertEquals(listOf("摘要", "细节"), codex.rows.values.single().reasoning.map { it.text })
        assertEquals("", text(codex))
    }

    @Test fun `历史正文保留原发生时间并明确排除实时提醒`() {
        val projection = RemoteTimelineProjection(local)
        val historical = projection.accept(event(1, "assistant/message", obj("message" to obj("id" to "old", "content" to listOf(obj("type" to "text", "text" to "旧回答"))))), history = true)
            .filterIsInstance<VerifiedConversationEvent.TimelineUpsert>().single()
        assertEquals(100L, historical.occurredAt)
        assertTrue(historical.eventId.startsWith("history:"))
    }

    @Test fun `取消或失败的思考保持未闭合而成功终态明确闭合`() {
        listOf("cancelled" to false, "failed" to false, "completed" to true).forEach { (status, complete) ->
            val projection = RemoteTimelineProjection(local)
            projection.accept(obj("method" to "turn/started", "params" to obj("threadId" to native, "turn" to obj("id" to "T"))))
            projection.accept(obj("method" to "item/reasoning/textDelta", "params" to obj("threadId" to native, "itemId" to "reasoning", "delta" to "尚在推理")))
            projection.accept(obj("method" to "turn/completed", "params" to obj("threadId" to native, "turn" to obj("id" to "T", "status" to status))))
            assertEquals(status, complete, projection.rows.values.single().reasoning.single().complete)
            assertEquals("CONFIRMED", projection.rows.values.single().state)
        }
    }
}
