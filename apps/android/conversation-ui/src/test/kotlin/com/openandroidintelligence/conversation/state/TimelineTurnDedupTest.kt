package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.CatalogVersion
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.ports.AgentCommandCatalog
import com.openandroidintelligence.conversation.ports.AgentCommandCatalogRepository
import com.openandroidintelligence.conversation.ports.BatchAcceptance
import com.openandroidintelligence.conversation.ports.CancelGenerationOutcome
import com.openandroidintelligence.conversation.ports.CancelGenerationResult
import com.openandroidintelligence.conversation.ports.Conversation
import com.openandroidintelligence.conversation.ports.ConversationPage
import com.openandroidintelligence.conversation.ports.ConversationRepository
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.ports.ConversationSummary
import com.openandroidintelligence.conversation.ports.MessageAcceptance
import com.openandroidintelligence.conversation.ports.MessageBatch
import com.openandroidintelligence.conversation.ports.OutgoingMessage
import com.openandroidintelligence.conversation.ports.PageRequest
import com.openandroidintelligence.conversation.ports.TimelineMessage
import com.openandroidintelligence.conversation.ports.TimelinePage
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One reply is one row.
 *
 * A host can publish the same reply under several message ids (once per
 * `send()`/finalize), and a reconnect can replay them; the ids genuinely differ,
 * so a reader cannot deduplicate on identity alone. What both sides can agree on
 * is the turn: same text, no user message in between, one message.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TimelineTurnDedupTest {

    @Test
    fun identicalRepliesPublishedUnderDifferentIdsRenderOnce() = runTest {
        val entries = rendered(
            user("现在几点", timestamp = 1_000L),
            assistant(REPLY, id = "msg_a", timestamp = 2_000L),
            assistant(REPLY, id = "msg_b", timestamp = 2_100L),
            assistant(REPLY, id = "msg_c", timestamp = 3_000L),
        )

        assertEquals(
            "同一条回复无论宿主发布几次，界面都只能渲染一条：$entries",
            1,
            entries.count { it.text == REPLY },
        )
        assertEquals("用户消息必须保留", 1, entries.count { it.isUser })
    }

    @Test
    fun identicalRepliesInDifferentTurnsAreStillKept() = runTest {
        val entries = rendered(
            user("先说一句", timestamp = 1_000L),
            assistant("好的", id = "msg_a", timestamp = 2_000L),
            user("再说一句", timestamp = 3_000L),
            assistant("好的", id = "msg_b", timestamp = 4_000L),
        )

        assertEquals("不同轮的同内容回复是两条消息", 2, entries.count { it.text == "好的" })
    }

    @Test
    fun aToolCardIsNotCollapsedIntoItsReply() = runTest {
        val entries = rendered(
            user("现在几点", timestamp = 1_000L),
            assistant("💻 terminal date", id = "msg_tool", timestamp = 2_000L),
            assistant(REPLY, id = "msg_a", timestamp = 3_000L),
            assistant(REPLY, id = "msg_b", timestamp = 3_100L),
        )

        assertEquals("工具卡片与最终回复内容不同，必须各自保留", 1, entries.count { it.text.startsWith("💻") })
        assertEquals(1, entries.count { it.text == REPLY })
        assertEquals(3, entries.size)
    }

    @Test
    fun aReplyPublishedLongAfterItsToolRoundStillCollapses() = runTest {
        // The tool round took minutes: a clock window would leave the second copy
        // in place, the turn boundary must not.
        val entries = rendered(
            user("现在几点", timestamp = 1_000L),
            assistant("💻 terminal date", id = "msg_tool", timestamp = 2_000L),
            assistant(REPLY, id = "msg_a", timestamp = 200_000L),
            assistant(REPLY, id = "msg_b", timestamp = 200_100L),
        )

        assertEquals(1, entries.count { it.text == REPLY })
    }

    private suspend fun TestScope.rendered(vararg messages: TimelineMessage): List<TimelineEntry> {
        val controller = WorkbenchController(
            scope = this,
            repository = FixedTimelineRepository(messages.toList()),
            catalogRepository = object : AgentCommandCatalogRepository {
                override suspend fun get(gatewayId: String, languageCode: String) =
                    AgentCommandCatalog(CatalogVersion("v1"), emptyList())
            },
            scopeFactory = { ConversationScope("profile", "gateway", "account", "install") },
            replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false),
            newConversationTimeouts = WorkbenchController.NewConversationTimeouts(enabled = false),
        )
        try {
            controller.openThread(CONVERSATION_ID)
            advanceUntilIdle()
            return (controller.state.value.timeline as Loadable.Ready).value
        } finally {
            controller.cancel()
        }
    }

    private fun user(text: String, timestamp: Long): TimelineMessage = TimelineMessage(
        id = "msg_user_$timestamp",
        sender = "user",
        parts = listOf(MessagePart.Text(text)),
        timestamp = timestamp,
        state = "CONFIRMED",
        conversationId = ConversationId(CONVERSATION_ID),
    )

    private fun assistant(text: String, id: String, timestamp: Long): TimelineMessage = TimelineMessage(
        id = id,
        sender = "assistant",
        parts = listOf(MessagePart.Text(text)),
        timestamp = timestamp,
        state = "CONFIRMED",
        conversationId = ConversationId(CONVERSATION_ID),
    )

    /** A repository that only has to serve one conversation's stored history. */
    private class FixedTimelineRepository(private val messages: List<TimelineMessage>) : ConversationRepository {
        override suspend fun listConversations(scope: ConversationScope, page: PageRequest): ConversationPage =
            ConversationPage(listOf(ConversationSummary(ConversationId(CONVERSATION_ID), "会话", 1L)), null)

        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation =
            Conversation(ConversationId("conv_created"), "新对话", 0L)

        override suspend fun timeline(conversationId: String, page: PageRequest): TimelinePage =
            TimelinePage(messages, null)

        override suspend fun submitBatch(batch: MessageBatch): BatchAcceptance =
            BatchAcceptance(batch.batchId, emptyMap())

        override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance =
            MessageAcceptance("msg_sent", message.clientMessageId.value)

        override suspend fun submitMessage(conversationId: String, message: OutgoingMessage): MessageAcceptance =
            MessageAcceptance("msg_sent", message.clientMessageId.value)

        override fun observeEvents(scope: ConversationScope): Flow<VerifiedConversationEvent> = emptyFlow()

        override suspend fun cancelGeneration(generationId: String, requestId: String): CancelGenerationResult =
            CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)

        override suspend fun readConversation(conversationId: String): Conversation? = null
    }

    private companion object {
        const val CONVERSATION_ID = "conv_dedup"
        const val REPLY = "现在是晚上 21:20 啦 (｡•̀ᴗ-)✧"
    }
}
