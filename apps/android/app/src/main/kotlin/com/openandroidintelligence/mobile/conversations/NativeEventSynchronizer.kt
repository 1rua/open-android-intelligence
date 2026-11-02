package com.openandroidintelligence.mobile.conversations

import com.openandroidintelligence.conversation.data.GatewayEventDecoder
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.gateway.agents.AgentSessionClient
import com.openandroidintelligence.gateway.agents.AgentSessionException
import com.openandroidintelligence.gateway.agents.CoreAgent
import com.openandroidintelligence.gateway.agents.NativeEventApproval
import com.openandroidintelligence.gateway.agents.NativeEventPage
import com.openandroidintelligence.gateway.events.GatewayEvent
import com.openandroidintelligence.gateway.diagnostics.GatewayLog
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class NativeEventSyncFailure(val stage: String, cause: Exception) : Exception(stage, cause)
internal class NativeHistoryBehind : java.io.IOException("NATIVE_HISTORY_BEHIND")

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
    // 历史尚未覆盖当前事件页时保留原页，不能改取更晚游标来掩盖缺口。
    private var pendingPage: NativeEventPage? = null

    suspend fun synchronize(waitMs: Int = 0): Boolean = mutex.withLock {
        checkOwner()
        // 上次 ACK 响应丢失或本机确认失败：同一 cursor 重试，不能先跳到下一页。
        localStore { store.pendingAck(conversationId) }?.let { pending ->
            acknowledge(pending)
            localStore { store.confirmAck(conversationId, pending) }
            // 已确认的原页就是一次成功；不要把它藏在下一次长轮询等待之后。
            return@withLock true
        }
        val cursor = localStore { store.eventCursor(conversationId) }
        var snapshot = cursor == null
        val page = pendingPage ?: try {
            client.events(agent, conversationId, cursor, waitMs = if (snapshot || needsRefresh) 0 else waitMs, snapshot = snapshot)
        } catch (failure: AgentSessionException) {
            if (failure.code != "CURSOR_EXPIRED") throw failure
            snapshot = true
            client.events(agent, conversationId, snapshot = true)
        }
        pendingPage = page
        snapshot = snapshot || page.approvals != null
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
        requireHistoryCoverage(page, facts)
        localStore { store.commitPage(conversationId, facts, approvals, page.cursor) }
        pendingPage = null
        if (snapshot || page.events.isNotEmpty()) {
            val cursorDigest = java.security.MessageDigest.getInstance("SHA-256").digest(page.cursor.toByteArray())
                .take(8).joinToString("") { "%02x".format(it) }
            val contentRevision = facts.filterIsInstance<VerifiedConversationEvent.TimelineUpsert>()
                .maxOfOrNull { it.revision }
            GatewayLog.d("OaiNativeEvents", "stage=PAGE_SAVED agentId=${agent.id} conversationId=$conversationId " +
                "cursorDigest=$cursorDigest contentRevision=$contentRevision snapshot=$snapshot events=${page.events.size}")
        }
        com.openandroidintelligence.gateway.diagnostics.GatewayLog.protocolEvidence("native.page_saved", mapOf(
            "agentId" to agent.id, "conversationId" to conversationId, "eventCount" to facts.size))
        // 进程在这一行之后中断，下一次仍能从持久 pending ACK 与审批恢复。
        currentCoroutineContext().ensureActive()
        checkOwner()
        committed()
        needsRefresh = false
        facts.forEach { checkOwner(); publish(it) }
        localStore { store.loadApprovals(conversationId) }.forEach { checkOwner(); publishApproval(it) }
        currentCoroutineContext().ensureActive()
        acknowledge(page.cursor)
        localStore { store.confirmAck(conversationId, page.cursor) }
        page.hasMore
    }

    private suspend fun <T> localStore(operation: suspend () -> T): T {
        currentCoroutineContext().ensureActive()
        checkOwner()
        try { return operation() }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) { throw NativeEventSyncFailure("LOCAL_SAVE_FAILED", failure) }
    }

    private fun requireHistoryCoverage(page: NativeEventPage, facts: List<VerifiedConversationEvent>) {
        for (event in page.events) {
            if (JsonFields.string(event, "eventType") !in setOf("conversation.message.delta", "conversation.message.completed")) continue
            val payload = JsonFields.obj(JsonFields.field(event, "payload")) ?: throw AgentSessionException("AGENT_RESPONSE_INVALID")
            // 可选正文修订缺省时保持旧宿主行为；绝不拿生命周期或手机时间代替正文修订。
            if (JsonFields.field(payload, "revision") == null) continue
            val revision = JsonFields.long(payload, "revision")?.takeIf { it >= 0 }
                ?: throw AgentSessionException("AGENT_RESPONSE_INVALID")
            val messageId = JsonFields.string(payload, "messageId")?.takeIf(String::isNotBlank)
                ?: throw AgentSessionException("AGENT_RESPONSE_INVALID")
            val generationId = JsonFields.string(payload, "generationId")
            val covered = facts.filterIsInstance<VerifiedConversationEvent.TimelineUpsert>().any {
                it.message.id == messageId && it.message.conversationId?.value == conversationId &&
                    (generationId == null || it.message.generationId == null || it.message.generationId == generationId) &&
                    it.message.contentRevision?.let { saved -> saved >= revision } == true
            }
            if (!covered) throw NativeHistoryBehind()
        }
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
