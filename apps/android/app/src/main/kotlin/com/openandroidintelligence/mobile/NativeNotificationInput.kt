package com.openandroidintelligence.mobile

import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent

/** 提醒层只消费这些事实，思考、工具、审批与历史补齐不属于提醒输入。 */
internal enum class NativeNotificationKind {
    REPLY_DELTA, REPLY_COMPLETED, MESSAGE_STATUS, GENERATION_CANCELLED, TITLE_UPDATED, CONVERSATION_DELETED,
}

/** 账号、端点与会话归属在入口固定，事件时间和正文直接使用校验后的领域事实。 */
internal data class NativeNotificationInput(
    val target: ReplyNotificationTarget,
    val kind: NativeNotificationKind,
    val event: VerifiedConversationEvent,
    val resolvedGenerationId: String? = null,
    val forceLegacy: Boolean = false,
) {
    val eventId: String get() = event.eventId
    val occurredAt: Long get() = event.occurredAt
    val endpointId: String? get() = target.agentId
    val generationId: String? get() = if (forceLegacy) null else (resolvedGenerationId ?: when (val fact = event) {
        is VerifiedConversationEvent.TimelineUpsert -> fact.message.generationId
        is VerifiedConversationEvent.MessageStatus -> fact.generationId
        is VerifiedConversationEvent.GenerationCancelled -> fact.generationId
        else -> null
    })?.takeIf { it.isNotBlank() }
}
