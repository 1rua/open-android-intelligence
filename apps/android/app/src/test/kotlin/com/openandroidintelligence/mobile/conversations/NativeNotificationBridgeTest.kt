package com.openandroidintelligence.mobile.conversations

import com.openandroidintelligence.conversation.model.AttachmentDraftId
import com.openandroidintelligence.conversation.model.ClientMessageId
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.ports.AgentMessageErrorCode
import com.openandroidintelligence.conversation.ports.AgentMessageStatus
import com.openandroidintelligence.conversation.ports.TimelineMessage
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import org.junit.Assert.*
import org.junit.Test

/**
 * 原生会话事件必须被翻译成提醒层认识、且带 Agent 身份的平台帧。
 *
 * 这是“原生会话为什么完全没有完成提醒”的根因所在：Core 的平台事件库里只有
 * 设备事件，原生正文与状态只存在于宿主账本里。翻译一旦少了 agentId 或写错
 * 事件名，提醒层就会静默丢弃整条事实。
 */
class NativeNotificationBridgeTest {
    private val conversation = ConversationId("session-1")

    @Test fun assistantStreamsBecomeDeltaAndCompletedFramesCarryingTheAgent() {
        for ((state, expected) in listOf("STREAMING" to "conversation.message.delta", "CONFIRMED" to "conversation.message.completed")) {
            val frame = requireNotNull(nativeNotificationFrame("account-1", "hermes", upsert(state)))
            assertEquals(expected, frame.event)
            val payload = payload(frame)
            assertEquals("hermes", JsonFields.string(payload, "agentId"))
            assertEquals("session-1", JsonFields.string(payload, "conversationId"))
            assertEquals("reply-1", JsonFields.string(payload, "messageId"))
            assertEquals("assistant", JsonFields.string(payload, "sender"))
            assertEquals("最终回答", JsonFields.string(payload, "text"))
            assertEquals("generation-1", JsonFields.string(payload, "generationId"))
            assertEquals(3L, JsonFields.long(payload, "revision"))
        }
    }

    @Test fun statusCancellationAndTitleFramesKeepTheSameShapeTheDecoderExpects() {
        val status = requireNotNull(nativeNotificationFrame("account-1", "hermes",
            VerifiedConversationEvent.MessageStatus("status-1", 1_000L, conversation, "user-1",
                ClientMessageId("client-1"), AgentMessageStatus.COMPLETED, 4L, null, "generation-1")))
        assertEquals("conversation.message.status", status.event)
        val statusPayload = payload(status)
        assertEquals("hermes", JsonFields.string(statusPayload, "agentId"))
        assertEquals("completed", JsonFields.string(statusPayload, "status"))
        assertEquals(4L, JsonFields.long(statusPayload, "revision"))
        assertEquals("generation-1", JsonFields.string(statusPayload, "generationId"))

        val cancelled = requireNotNull(nativeNotificationFrame("account-1", "hermes",
            VerifiedConversationEvent.GenerationCancelled("cancel-1", 1_000L, "generation-1", conversation)))
        assertEquals("conversation.generation.cancelled", cancelled.event)
        assertEquals("hermes", JsonFields.string(payload(cancelled), "agentId"))

        val title = requireNotNull(nativeNotificationFrame("account-1", "hermes",
            VerifiedConversationEvent.TitleUpdated("title-1", 1_000L, conversation, "新标题")))
        assertEquals("conversation.title.updated", title.event)
        assertEquals("新标题", JsonFields.string(payload(title), "title"))
    }

    @Test fun failuresCarryTheErrorCodeAndDeletionRetiresTheTarget() {
        val failed = requireNotNull(nativeNotificationFrame("account-1", "hermes",
            VerifiedConversationEvent.MessageStatus("status-2", 1_000L, conversation, "user-1",
                ClientMessageId("client-1"), AgentMessageStatus.FAILED, 5L,
                AgentMessageErrorCode.AGENT_UNAVAILABLE, "generation-1")))
        assertEquals("AGENT_UNAVAILABLE", JsonFields.string(payload(failed), "errorCode"))

        val deleted = requireNotNull(nativeNotificationFrame("account-1", "hermes",
            VerifiedConversationEvent.ConversationsChanged("delete-1", 1_000L, conversation, deleted = true)))
        assertEquals("conversation.deleted", deleted.event)
        assertEquals(true, JsonFields.bool(payload(deleted), "deleted"))
    }

    @Test fun userRowsApprovalsAndUnknownStatesNeverBecomeReminders() {
        val userSaid = upsert("CONFIRMED").copy(
            message = upsert("CONFIRMED").message.copy(id = "user-1", sender = "user"),
        )
        assertNull("用户自己的消息不能变成 Agent 回复提醒", nativeNotificationFrame("account-1", "hermes", userSaid))
        assertNull(nativeNotificationFrame("account-1", "hermes", upsert("CANCELLED")))
        assertNull(nativeNotificationFrame("account-1", "hermes",
            VerifiedConversationEvent.ConversationsChanged("create-1", 1_000L, conversation, deleted = false)))
        assertNull(nativeNotificationFrame("account-1", "hermes",
            VerifiedConversationEvent.ExecutionStepsUpdated("steps-1", 1_000L, conversation, "generation-1", 1L, emptyList())))
        assertNull("缺少会话身份的取消不能借用当前会话",
            nativeNotificationFrame("account-1", "hermes",
                VerifiedConversationEvent.GenerationCancelled("cancel-2", 1_000L, "generation-1", null)))
    }

    @Test fun phoneSynthesisedHistoryAndReceiptsNeverBecomeReminders() {
        // 冷启动重放历史、回执与被取消轮次的回读都是手机自己补齐的，不是新发生的事实。
        for (prefix in listOf("native-history:", "native-read:", "native-receipt:", "native-status:", "native-cancel:", "history:")) {
            assertNull("$prefix 合成事件不能触发提醒",
                nativeNotificationFrame("account-1", "hermes", upsert("CONFIRMED").copy(eventId = "$prefix-1")))
            assertNull("$prefix 合成状态不能触发提醒", nativeNotificationFrame("account-1", "hermes",
                VerifiedConversationEvent.MessageStatus("$prefix-status", 1_000L, conversation, "user-1",
                    ClientMessageId("client-1"), AgentMessageStatus.COMPLETED, 4L, null, "generation-1")))
        }
        // 宿主真实发出的帧仍然照常翻译。
        assertNotNull(nativeNotificationFrame("account-1", "hermes", upsert("CONFIRMED").copy(eventId = "evt_live")))
    }

    @Test fun attachmentsSurviveAsPartsWithoutBecomingText() {
        val withAttachment = upsert("CONFIRMED").copy(message = upsert("CONFIRMED").message.copy(
            parts = listOf(MessagePart.Attachment(AttachmentDraftId("attachment-1"), "report.pdf", "application/pdf", 12L, null)),
        ))
        val payload = payload(requireNotNull(nativeNotificationFrame("account-1", "hermes", withAttachment)))
        val parts = JsonFields.array(JsonFields.field(payload, "parts"))!!.items
        assertEquals(1, parts.size)
        assertEquals("attachment", JsonFields.string(JsonFields.obj(parts.single())!!, "type"))
        assertEquals("", JsonFields.string(payload, "text"))
    }

    private fun upsert(state: String) = VerifiedConversationEvent.TimelineUpsert(
        eventId = "event-1", occurredAt = 1_000L, revision = 3L,
        message = TimelineMessage(
            id = "reply-1", sender = "assistant", parts = listOf(MessagePart.Text("最终回答")),
            timestamp = 900L, state = state, conversationId = conversation, generationId = "generation-1",
        ),
    )

    private fun payload(frame: com.openandroidintelligence.gateway.events.GatewayEvent) =
        JsonFields.obj(JsonFields.field(JsonFields.obj(Json.parse(frame.data)), "payload"))!!
}
