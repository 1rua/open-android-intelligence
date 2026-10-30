package com.openandroidintelligence.mobile.conversations

import com.openandroidintelligence.conversation.data.GatewayEventDecoder
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.gateway.agents.AgentSessionClient
import com.openandroidintelligence.gateway.agents.AgentSessionException
import com.openandroidintelligence.gateway.agents.CoreAgent
import com.openandroidintelligence.gateway.agents.NativeEventApproval
import com.openandroidintelligence.gateway.agents.NativeEventPage
import com.openandroidintelligence.gateway.events.GatewayEvent
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class NativeEventSyncFailure(val stage: String, cause: Exception) : Exception(stage, cause)

/** 固定的账号/Agent/会话读取者；内存 UI 通知不参与 ACK 的成功判定。 */
internal class NativeEventSynchronizer(
    private val client: AgentSessionClient,
    private val agent: CoreAgent,
    private val conversationId: String,
    private val store: NativeConversationStore,
    private val refresh: suspend (NativeEventPage) -> List<VerifiedConversationEvent>,
    private val publish: suspend (VerifiedConversationEvent) -> Unit,
    private val publishApproval: suspend (NativeEventApproval) -> Unit,
    private val committed: suspend () -> Unit = {},
    private val checkOwner: () -> Unit = {},
) {
    private val mutex = Mutex()
    private var needsRefresh = true

    suspend fun synchronize(waitMs: Int = 0): Boolean = mutex.withLock {
        checkOwner()
        // 上次 ACK 响应丢失或本机确认失败：同一 cursor 重试，不能先跳到下一页。
        store.pendingAck(conversationId)?.let { pending ->
            acknowledge(pending)
            store.confirmAck(conversationId, pending)
        }
        val cursor = store.eventCursor(conversationId)
        var snapshot = cursor == null
        val page = try {
            client.events(agent, conversationId, cursor, waitMs = if (snapshot || needsRefresh) 0 else waitMs, snapshot = snapshot)
        } catch (failure: AgentSessionException) {
            if (failure.code != "CURSOR_EXPIRED") throw failure
            snapshot = true
            client.events(agent, conversationId, snapshot = true)
        }
        val approvals = page.approvals.orEmpty().toMutableList()
        val lifecycle = mutableListOf<VerifiedConversationEvent>()
        for (event in page.events) {
            val type = JsonFields.string(event, "eventType") ?: throw AgentSessionException("AGENT_RESPONSE_INVALID")
            val payload = JsonFields.obj(JsonFields.field(event, "payload")) ?: throw AgentSessionException("AGENT_RESPONSE_INVALID")
            if (type == "gateway.notice") {
                if (JsonFields.string(payload, "noticeCode") != "oai.native.execution.updated")
                    throw AgentSessionException("AGENT_RESPONSE_INVALID")
            } else {
                if (JsonFields.string(payload, "conversationId") != conversationId)
                    throw AgentSessionException("NATIVE_EVENT_SCOPE_MISMATCH")
                when {
                    type == "conversation.approval.requested" -> approvals += NativeEventApproval("approval_requested", payload)
                    type == "conversation.approval.resolved" -> approvals += NativeEventApproval("approval_resolved", payload)
                    type == "conversation.message.status" || type == "conversation.generation.cancelled" -> {
                        lifecycle += GatewayEventDecoder.decode(GatewayEvent(JsonFields.string(event, "eventId"), type,
                            Json.canonical(event))) ?: throw AgentSessionException("AGENT_RESPONSE_INVALID")
                    }
                    type in setOf("conversation.message.accepted", "conversation.message.delta", "conversation.message.completed") -> Unit
                    else -> throw AgentSessionException("AGENT_RESPONSE_INVALID")
                }
            }
        }
        // 所有审批先验证，畸形关键事件绝不能被 history 刷新掩盖后 ACK。
        approvals.forEach { approvalEvent(it, conversationId) }
        val facts = (if (needsRefresh || snapshot || page.events.isNotEmpty()) refresh(page) else emptyList()) + lifecycle
        currentCoroutineContext().ensureActive()
        checkOwner()
        try { store.commitPage(conversationId, facts, approvals, page.cursor) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { throw NativeEventSyncFailure("LOCAL_SAVE_FAILED", failure) }
        com.openandroidintelligence.gateway.diagnostics.GatewayLog.protocolEvidence("native.page_saved", mapOf(
            "agentId" to agent.id, "conversationId" to conversationId, "eventCount" to facts.size))
        // 进程在这一行之后中断，下一次仍能从持久 pending ACK 与审批恢复。
        currentCoroutineContext().ensureActive()
        checkOwner()
        committed()
        needsRefresh = false
        facts.forEach { checkOwner(); publish(it) }
        store.loadApprovals(conversationId).forEach { publishApproval(it) }
        currentCoroutineContext().ensureActive()
        acknowledge(page.cursor)
        store.confirmAck(conversationId, page.cursor)
        page.hasMore
    }

    private suspend fun acknowledge(cursor: String) {
        checkOwner()
        try { client.ackEvents(agent, conversationId, cursor) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { throw NativeEventSyncFailure("ACK_FAILED", failure) }
    }

    companion object {
        fun approvalEvent(item: NativeEventApproval, conversationId: String): VerifiedConversationEvent {
            if (JsonFields.string(item.approval, "conversationId") != conversationId ||
                item.type !in setOf("approval_requested", "approval_resolved"))
                throw AgentSessionException("NATIVE_APPROVAL_SCOPE_MISMATCH")
            val type = if (item.type == "approval_requested") "conversation.approval.requested" else "conversation.approval.resolved"
            val id = JsonFields.string(item.approval, "approvalId")?.takeIf(String::isNotBlank)
                ?: throw AgentSessionException("AGENT_RESPONSE_INVALID")
            if (item.type == "approval_resolved" && JsonFields.string(item.approval, "decision") !in
                setOf("once", "session", "always", "deny", "timeout", "withdrawn"))
                throw AgentSessionException("AGENT_RESPONSE_INVALID")
            return GatewayEventDecoder.decode(GatewayEvent("native-approval:$id:${item.type}", type,
                Json.canonical(Json.of(mapOf("payload" to item.approval)))))
                ?: throw AgentSessionException("AGENT_RESPONSE_INVALID")
        }
    }
}
