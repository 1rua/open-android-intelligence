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
