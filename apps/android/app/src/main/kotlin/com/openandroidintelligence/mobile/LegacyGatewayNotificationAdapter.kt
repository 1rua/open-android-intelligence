package com.openandroidintelligence.mobile

import com.openandroidintelligence.conversation.data.GatewayEventDecoder
import com.openandroidintelligence.gateway.events.GatewayEvent
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue
import com.openandroidintelligence.mobile.conversations.nativeNotificationInput
import java.time.Instant

/** 仅旧链路在此解码；Remote 直接交付领域事实，不经过此适配器。 */
internal fun NativeConversationNotifications.accept(profileId: String, accountId: String, event: GatewayEvent) {
    legacyGatewayNotificationInput(profileId, accountId, event)?.let(::accept)
}

internal fun ReplyCompletionObserver.accept(event: GatewayEvent) {
    legacyGatewayNotificationInput(profileId, accountId, event)?.let(::accept)
}

private fun legacyGatewayNotificationInput(profileId: String, accountId: String, event: GatewayEvent): NativeNotificationInput? {
    if (event.event !in LEGACY_NOTIFICATION_EVENTS) return null
    val body = runCatching { JsonFields.obj(Json.parse(event.data)) }.getOrNull() ?: return null
    if (JsonFields.string(body, "accountId")?.let { it != accountId } == true) return null
    val payload = JsonFields.obj(JsonFields.field(body, "payload")) ?: return null
    val occurredAt = JsonFields.string(body, "occurredAt")
        ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: return null
    if (event.event in setOf("conversation.message.delta", "conversation.message.completed") && !isPublicAssistantPayload(payload)) return null
    val decoded = runCatching { GatewayEventDecoder.decode(event) }.getOrNull() ?: return null
    return nativeNotificationInput(profileId, accountId, JsonFields.string(payload, "agentId"), decoded)
        ?.takeIf { it.occurredAt == occurredAt }
}

internal fun isPublicAssistantPayload(payload: JsonValue.JObject): Boolean =
    JsonFields.string(payload, "sender") == "assistant" &&
        JsonFields.string(payload, "channel") in setOf(null, "final", "commentary") &&
        JsonFields.string(payload, "visibility") in setOf(null, "public") &&
        JsonFields.bool(payload, "isPrivate") != true

private val LEGACY_NOTIFICATION_EVENTS = setOf("conversation.message.delta", "conversation.message.completed",
    "conversation.message.status", "conversation.generation.cancelled", "conversation.title.updated", "conversation.updated", "conversation.deleted")

/** 仅旧回读入口转换时间线；共享通知状态机不再消费旧协议类型。 */
internal fun NativeConversationNotifications.reconcile(
    expected: NotificationReconciliationRequest,
    currentGeneration: Pair<String, String>?,
    messages: List<com.openandroidintelligence.gateway.conversations.GatewayTimelineMessage>,
): Boolean = reconcile(expected, currentGeneration, messages.map { it.notificationTimelineMessage(expected.session.target.conversationId) })

internal fun com.openandroidintelligence.gateway.conversations.GatewayTimelineMessage.notificationTimelineMessage(
    conversationId: String,
): com.openandroidintelligence.conversation.ports.TimelineMessage = com.openandroidintelligence.conversation.ports.TimelineMessage(
    id = messageId,
    sender = sender,
    parts = parts.map { part ->
        when (part) {
            is com.openandroidintelligence.gateway.conversations.MessagePart.Text -> com.openandroidintelligence.conversation.model.MessagePart.Text(part.text)
            is com.openandroidintelligence.gateway.conversations.MessagePart.AttachmentRef -> com.openandroidintelligence.conversation.model.MessagePart.Attachment(
                com.openandroidintelligence.conversation.model.AttachmentDraftId(part.attachmentId), part.filename, part.mediaType, part.sizeBytes, part.sha256)
        }
    },
    timestamp = timestamp ?: 0L,
    state = state,
    conversationId = com.openandroidintelligence.conversation.model.ConversationId(conversationId),
    clientMessageId = clientMessageId?.let { com.openandroidintelligence.conversation.model.ClientMessageId(it) },
    contentRevision = contentRevision,
)
