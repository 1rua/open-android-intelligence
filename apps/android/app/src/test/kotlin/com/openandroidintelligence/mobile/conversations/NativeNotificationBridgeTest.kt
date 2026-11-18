package com.openandroidintelligence.mobile.conversations

import com.openandroidintelligence.conversation.model.AttachmentDraftId
import com.openandroidintelligence.conversation.model.ClientMessageId
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.ports.AgentMessageErrorCode
import com.openandroidintelligence.conversation.ports.AgentMessageStatus
import com.openandroidintelligence.conversation.ports.TimelineMessage
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.mobile.NativeNotificationKind
import com.openandroidintelligence.mobile.ReplyNotificationTarget
import org.junit.Assert.*
import org.junit.Test

/** 已校验事实直接进入提醒层，保留端点、时间、正文和修订，不编码旧平台帧。 */
class NativeNotificationBridgeTest {
    private val conversation = ConversationId("session-1")

    @Test fun `助手正文直接携带原事实和端点身份`() {
        for ((state, expected) in listOf("STREAMING" to NativeNotificationKind.REPLY_DELTA,
            "CONFIRMED" to NativeNotificationKind.REPLY_COMPLETED, "COMPLETED" to NativeNotificationKind.REPLY_COMPLETED)) {
            val event = upsert(state)
            val input = requireNotNull(nativeNotificationInput("profile", "account-1", "hermes", event))
            assertEquals(expected, input.kind)
            assertEquals(ReplyNotificationTarget("profile", "account-1", "session-1", "hermes"), input.target)
            assertEquals("hermes", input.endpointId)
            assertSame("已校验对象不能经过旧协议重建", event, input.event)
            assertEquals(1_000L, input.occurredAt)
            assertEquals("generation-1", input.generationId)
            assertEquals(900L, (input.event as VerifiedConversationEvent.TimelineUpsert).message.timestamp)
        }
    }

    @Test fun `状态取消标题与删除保持原领域事实`() {
        val events = listOf(
            VerifiedConversationEvent.MessageStatus("status-1", 1_000L, conversation, "user-1",
                ClientMessageId("client-1"), AgentMessageStatus.COMPLETED, 4L, null, "generation-1") to NativeNotificationKind.MESSAGE_STATUS,
            VerifiedConversationEvent.GenerationCancelled("cancel-1", 1_000L, "generation-1", conversation) to NativeNotificationKind.GENERATION_CANCELLED,
            VerifiedConversationEvent.TitleUpdated("title-1", 1_000L, conversation, "新标题") to NativeNotificationKind.TITLE_UPDATED,
            VerifiedConversationEvent.ConversationsChanged("delete-1", 1_000L, conversation, deleted = true) to NativeNotificationKind.CONVERSATION_DELETED,
        )
        for ((event, kind) in events) {
            val input = requireNotNull(nativeNotificationInput("profile", "account-1", "hermes", event))
            assertSame(event, input.event)
            assertEquals(kind, input.kind)
        }
        val failed = VerifiedConversationEvent.MessageStatus("status-2", 1_000L, conversation, "user-1",
            ClientMessageId("client-1"), AgentMessageStatus.FAILED, 5L, AgentMessageErrorCode.AGENT_UNAVAILABLE, "generation-1")
        val input = requireNotNull(nativeNotificationInput("profile", "account-1", "hermes", failed))
        assertEquals(AgentMessageErrorCode.AGENT_UNAVAILABLE, (input.event as VerifiedConversationEvent.MessageStatus).errorCode)
    }

    @Test fun userRowsApprovalsAndUnknownStatesNeverBecomeReminders() {
        val userSaid = upsert("CONFIRMED").copy(
            message = upsert("CONFIRMED").message.copy(id = "user-1", sender = "user"),
        )
        assertNull("用户自己的消息不能变成 Agent 回复提醒", nativeNotificationInput("profile", "account-1", "hermes", userSaid))
        assertNull(nativeNotificationInput("profile", "account-1", "hermes", upsert("CANCELLED")))
        assertNull(nativeNotificationInput("profile", "account-1", "hermes",
            VerifiedConversationEvent.ConversationsChanged("create-1", 1_000L, conversation, deleted = false)))
        assertNull(nativeNotificationInput("profile", "account-1", "hermes",
            VerifiedConversationEvent.ExecutionStepsUpdated("steps-1", 1_000L, conversation, "generation-1", 1L, emptyList())))
        assertNull("缺少会话身份的取消不能借用当前会话",
            nativeNotificationInput("profile", "account-1", "hermes",
                VerifiedConversationEvent.GenerationCancelled("cancel-2", 1_000L, "generation-1", null)))
    }

    @Test fun phoneSynthesisedHistoryAndReceiptsNeverBecomeReminders() {
        // 冷启动重放历史、回执与被取消轮次的回读都是手机自己补齐的，不是新发生的事实。
        for (prefix in listOf("native-history:", "native-read:", "native-receipt:", "native-status:", "native-cancel:", "history:")) {
            assertNull("$prefix 合成事件不能触发提醒",
                nativeNotificationInput("profile", "account-1", "hermes", upsert("CONFIRMED").copy(eventId = "$prefix-1")))
            assertNull("$prefix 合成状态不能触发提醒", nativeNotificationInput("profile", "account-1", "hermes",
                VerifiedConversationEvent.MessageStatus("$prefix-status", 1_000L, conversation, "user-1",
                    ClientMessageId("client-1"), AgentMessageStatus.COMPLETED, 4L, null, "generation-1")))
        }
        // 宿主真实发出的帧仍然照常翻译。
        assertNotNull(nativeNotificationInput("profile", "account-1", "hermes", upsert("CONFIRMED").copy(eventId = "evt_live")))
    }

    @Test fun attachmentsSurviveAsPartsWithoutBecomingText() {
        val withAttachment = upsert("CONFIRMED").copy(message = upsert("CONFIRMED").message.copy(
            parts = listOf(MessagePart.Attachment(AttachmentDraftId("attachment-1"), "report.pdf", "application/pdf", 12L, null)),
        ))
        val input = requireNotNull(nativeNotificationInput("profile", "account-1", "hermes", withAttachment))
        assertSame(withAttachment, input.event)
        assertEquals(listOf(MessagePart.Attachment(AttachmentDraftId("attachment-1"), "report.pdf", "application/pdf", 12L, null)),
            (input.event as VerifiedConversationEvent.TimelineUpsert).message.parts)
    }

    private fun upsert(state: String) = VerifiedConversationEvent.TimelineUpsert(
        eventId = "event-1", occurredAt = 1_000L, revision = 3L,
        message = TimelineMessage(
            id = "reply-1", sender = "assistant", parts = listOf(MessagePart.Text("最终回答")),
            timestamp = 900L, state = state, conversationId = conversation, generationId = "generation-1",
        ),
    )

}
