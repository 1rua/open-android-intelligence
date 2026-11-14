package com.openandroidintelligence.mobile

import com.openandroidintelligence.conversation.model.ClientMessageId
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.ports.AgentMessageStatus
import com.openandroidintelligence.conversation.ports.TimelineMessage
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.mobile.conversations.nativeNotificationInput

internal fun notificationReply(
    time: Long,
    endpoint: String = "endpoint",
    generation: String = "generation",
    revision: Long = 1,
    text: String = "真实回答",
    completed: Boolean = true,
    profile: String = "profile",
    account: String = "account",
    eventId: String = "body-$generation-$revision-$completed",
    parts: List<MessagePart> = listOf(MessagePart.Text(text)),
): NativeNotificationInput = requireNotNull(nativeNotificationInput(profile, account, endpoint,
    VerifiedConversationEvent.TimelineUpsert(eventId, time, revision,
        TimelineMessage("reply", "assistant", parts, time, if (completed) "CONFIRMED" else "STREAMING",
            ConversationId("conversation"), generationId = generation))))

internal fun notificationStatus(
    time: Long,
    endpoint: String = "endpoint",
    generation: String = "generation",
    revision: Long = 1,
    status: AgentMessageStatus = AgentMessageStatus.COMPLETED,
    profile: String = "profile",
    account: String = "account",
    eventId: String = "status-$generation-$revision-$status",
): NativeNotificationInput = requireNotNull(nativeNotificationInput(profile, account, endpoint,
    VerifiedConversationEvent.MessageStatus(eventId, time, ConversationId("conversation"), "user", ClientMessageId("client"),
        status, revision, null, generation)))

/** 测试直接创建领域事实，避免为了通知状态机启动已退休的 Gateway 编解码。 */
internal fun notificationFact(
    profile: String,
    account: String,
    eventId: String,
    time: Long,
    type: String,
    payload: String,
): NativeNotificationInput? {
    val body = com.openandroidintelligence.gateway.schema.JsonFields.obj(
        com.openandroidintelligence.gateway.schema.Json.parse(payload)) ?: error("测试事实格式无效")
    fun text(name: String) = com.openandroidintelligence.gateway.schema.JsonFields.string(body, name)
    fun number(name: String) = com.openandroidintelligence.gateway.schema.JsonFields.long(body, name)
    val conversation = ConversationId(text("conversationId") ?: error("测试会话身份缺失"))
    val fact: VerifiedConversationEvent = when (type) {
        "conversation.message.delta", "conversation.message.completed" -> {
            if (text("channel") !in setOf(null, "final", "commentary") || text("visibility") !in setOf(null, "public") ||
                com.openandroidintelligence.gateway.schema.JsonFields.bool(body, "isPrivate") == true) return null
            val parts = com.openandroidintelligence.gateway.schema.JsonFields.array(
                com.openandroidintelligence.gateway.schema.JsonFields.field(body, "parts"))?.items?.mapNotNull { value ->
                val part = com.openandroidintelligence.gateway.schema.JsonFields.obj(value) ?: return@mapNotNull null
                when (com.openandroidintelligence.gateway.schema.JsonFields.string(part, "type")) {
                    "text" -> MessagePart.Text(com.openandroidintelligence.gateway.schema.JsonFields.string(part, "text").orEmpty())
                    "attachment" -> MessagePart.Attachment(com.openandroidintelligence.conversation.model.AttachmentDraftId(
                        com.openandroidintelligence.gateway.schema.JsonFields.string(part, "attachmentId") ?: return@mapNotNull null))
                    else -> null
                }
            } ?: listOfNotNull(text("text")?.let { MessagePart.Text(it) })
            VerifiedConversationEvent.TimelineUpsert(eventId, time, number("revision") ?: 0,
                TimelineMessage(text("messageId") ?: "reply", text("sender") ?: "assistant", parts, number("timestamp") ?: time,
                    if (type.endsWith("delta")) "STREAMING" else "CONFIRMED", conversation, generationId = text("generationId")))
        }
        "conversation.message.status" -> VerifiedConversationEvent.MessageStatus(eventId, time, conversation,
            text("messageId") ?: "user", ClientMessageId(text("clientMessageId") ?: "client"),
            AgentMessageStatus.entries.first { it.wireValue == text("status") }, number("revision") ?: 0, null, text("generationId"))
        "conversation.generation.cancelled" -> VerifiedConversationEvent.GenerationCancelled(eventId, time, text("generationId").orEmpty(), conversation)
        "conversation.title.updated" -> VerifiedConversationEvent.TitleUpdated(eventId, time, conversation, text("title") ?: text("newTitle").orEmpty())
        "conversation.deleted", "conversation.updated" -> VerifiedConversationEvent.ConversationsChanged(eventId, time, conversation,
            deleted = type.endsWith("deleted") || com.openandroidintelligence.gateway.schema.JsonFields.bool(body, "deleted") == true)
        else -> return null
    }
    return nativeNotificationInput(profile, account, text("agentId"), fact)
}

internal fun ReplyCompletionObserver.accept(input: NativeNotificationInput?) { input?.let { accept(it) } }

internal fun NativeNotificationInput.at(time: Long): NativeNotificationInput = copy(event = when (val fact = event) {
    is VerifiedConversationEvent.TimelineUpsert -> fact.copy(occurredAt = time)
    is VerifiedConversationEvent.MessageStatus -> fact.copy(occurredAt = time)
    is VerifiedConversationEvent.GenerationCancelled -> fact.copy(occurredAt = time)
    is VerifiedConversationEvent.TitleUpdated -> fact.copy(occurredAt = time)
    is VerifiedConversationEvent.ConversationsChanged -> fact.copy(occurredAt = time)
    else -> error("非通知测试事实")
})
