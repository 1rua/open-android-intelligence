package com.openandroidintelligence.mobile.conversations

import com.openandroidintelligence.conversation.ports.ConversationPage
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.gateway.agents.NativeEventApproval
import com.openandroidintelligence.gateway.agents.NativeAgentSession

data class NativeCreationReceipt(val session: NativeAgentSession, val eventId: String, val occurredAt: Long)

data class NativeRecoveryIdentity(val clientMessageId: String, val generationId: String? = null)

/** 一个固定账号与 Agent 的持久消费边界；ACK 位置额外绑定当前手机及配对代次。 */
interface NativeConversationStore {
    suspend fun creation(requestId: String): NativeCreationReceipt?
    suspend fun commitCreation(requestId: String, receipt: NativeCreationReceipt)
    suspend fun saveConversations(page: ConversationPage)
    suspend fun recoveryIdentities(conversationId: String): List<NativeRecoveryIdentity>
    suspend fun commitEvents(events: List<VerifiedConversationEvent>)
    suspend fun commitApprovals(conversationId: String, approvals: List<NativeEventApproval>)
    suspend fun loadApprovals(conversationId: String): List<NativeEventApproval>
    suspend fun pendingAck(conversationId: String): String?
    suspend fun eventCursor(conversationId: String): String?
    suspend fun commitPage(conversationId: String, events: List<VerifiedConversationEvent>,
        approvals: List<NativeEventApproval>, cursor: String)
    suspend fun confirmAck(conversationId: String, cursor: String)
}
