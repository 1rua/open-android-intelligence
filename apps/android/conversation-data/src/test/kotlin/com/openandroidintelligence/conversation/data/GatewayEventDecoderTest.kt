package com.openandroidintelligence.conversation.data

import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.gateway.events.SseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decoder turns real SSE frames into domain events and refuses everything
 * it does not model: unknown event names stay unknown instead of becoming a
 * plausible-looking fake state.
 */
class GatewayEventDecoderTest {

    private fun frame(id: String, event: String, data: String): String =
        "id: $id\nevent: $event\ndata: $data\n\n"

    @Test
    fun decodesTimelineUpsertIntoDomainEvent() {
        val parser = SseParser()
        val events = parser.feed(
            frame(
                id = "evt_1",
                event = "conversation.timeline.upsert",
                data = """
                    {"payload":{"messageId":"msg_1","sender":"assistant","state":"STREAMING","revision":3,"text":"你好"},"occurredAt":"2026-09-01T08:00:00.000Z"}
                """.trimIndent(),
            ),
        )
        assertEquals(1, events.size)
        val decoded = GatewayEventDecoder.decode(events.first())
        assertTrue(decoded is VerifiedConversationEvent.TimelineUpsert)
        val upsert = decoded as VerifiedConversationEvent.TimelineUpsert
        assertEquals("msg_1", upsert.message.id)
        assertEquals("STREAMING", upsert.message.state)
        assertEquals(3L, upsert.revision)
        assertEquals(1, upsert.message.parts.size)
        assertTrue(upsert.message.parts.first() is com.openandroidintelligence.conversation.model.MessagePart.Text)
    }

    @Test
    fun decodesTitleUpdateWithConversationBinding() {
        val parser = SseParser()
        val events = parser.feed(
            frame(
                id = "evt_2",
                event = "conversation.title.updated",
                data = """{"payload":{"conversationId":"conv_9","title":"晚餐计划"}}""",
            ),
        )
        val decoded = GatewayEventDecoder.decode(events.first())
        assertTrue(decoded is VerifiedConversationEvent.TitleUpdated)
        assertEquals("conv_9", (decoded as VerifiedConversationEvent.TitleUpdated).conversationId.value)
        assertEquals("晚餐计划", decoded.newTitle)
    }

    @Test
    fun decodesTitleUpdateWithNewTitleField() {
        val parser = SseParser()
        val events = parser.feed(
            frame(
                id = "evt_2b",
                event = "conversation.title.updated",
                data = """{"payload":{"conversationId":"conv_9","newTitle":"量子计算与经典物理的核心区别"}}""",
            ),
        )
        val decoded = GatewayEventDecoder.decode(events.first())
        assertTrue(decoded is VerifiedConversationEvent.TitleUpdated)
        assertEquals("conv_9", (decoded as VerifiedConversationEvent.TitleUpdated).conversationId.value)
        assertEquals("量子计算与经典物理的核心区别", decoded.newTitle)
    }

    @Test
    fun unknownEventNameStaysUnknown() {
        val parser = SseParser()
        val events = parser.feed(
            frame(id = "evt_3", event = "future.unknown.thing", data = """{"payload":{}}"""),
        )
        assertEquals(1, events.size)
        assertNull(GatewayEventDecoder.decode(events.first()))
    }

    @Test
    fun generationIdIsReadFromServerPayloadOnly() {
        val parser = SseParser()
        val events = parser.feed(
            frame(
                id = "evt_4",
                event = "conversation.message.completed",
                data = """{"payload":{"messageId":"msg_5","generationId":"gen_77"}}""",
            ),
        )
        assertEquals("gen_77", GatewayEventDecoder.generationIdOf(events.first()))
    }

    @Test
    fun malformedPayloadYieldsNullInsteadOfGuessing() {
        val parser = SseParser()
        val events = parser.feed(
            frame(id = "evt_5", event = "conversation.timeline.upsert", data = "{not-json"),
        )
        assertNotNull(events.firstOrNull())
        assertNull(GatewayEventDecoder.decode(events.first()))
    }

    @Test
    fun decodesAttachmentWithFilenameAndMediaType() {
        val parser = SseParser()
        val events = parser.feed(
            frame(
                id = "evt_att",
                event = "conversation.timeline.upsert",
                data = """
                    {"payload":{"messageId":"msg_att","sender":"user","state":"CONFIRMED","revision":1,"parts":[{"type":"attachment","attachmentId":"att_123","filename":"photo.png","mediaType":"image/png"}]},"occurredAt":"2026-09-01T08:00:00.000Z"}
                """.trimIndent(),
            ),
        )
        assertEquals(1, events.size)
        val decoded = GatewayEventDecoder.decode(events.first()) as VerifiedConversationEvent.TimelineUpsert
        assertEquals(1, decoded.message.parts.size)
        val part = decoded.message.parts.first() as com.openandroidintelligence.conversation.model.MessagePart.Attachment
        assertEquals("att_123", part.draftId.value)
        assertEquals("photo.png", part.filename)
        assertEquals("image/png", part.mediaType)
    }

    @Test
    fun decodesWithUnderscoreConversationIdInPayload() {
        val parser = SseParser()
        val events = parser.feed(
            frame(
                id = "evt_cid_1",
                event = "conversation.timeline.upsert",
                data = """{"payload":{"messageId":"msg_u","conversation_id":"conv_score_1","sender":"assistant","text":"hello"}}""",
            ),
        )
        val decoded = GatewayEventDecoder.decode(events.first()) as VerifiedConversationEvent.TimelineUpsert
        assertEquals("conv_score_1", decoded.message.conversationId?.value)
    }

    @Test
    fun decodesWithChatIdInPayload() {
        val parser = SseParser()
        val events = parser.feed(
            frame(
                id = "evt_cid_2",
                event = "conversation.timeline.upsert",
                data = """{"payload":{"messageId":"msg_c","chat_id":"chat_999","sender":"assistant","text":"hi"}}""",
            ),
        )
        val decoded = GatewayEventDecoder.decode(events.first()) as VerifiedConversationEvent.TimelineUpsert
        assertEquals("chat_999", decoded.message.conversationId?.value)
    }

    @Test
    fun decodesWithConversationIdInOuterBody() {
        val parser = SseParser()
        val events = parser.feed(
            frame(
                id = "evt_cid_3",
                event = "conversation.message.delta",
                data = """{"conversationId":"conv_body_1","payload":{"messageId":"msg_d","sender":"assistant","text":"streaming"}}""",
            ),
        )
        val decoded = GatewayEventDecoder.decode(events.first()) as VerifiedConversationEvent.TimelineUpsert
        assertEquals("conv_body_1", decoded.message.conversationId?.value)
        assertEquals("STREAMING", decoded.message.state)
    }

    @Test
    fun decodesWithUnderscoreConversationIdInOuterBody() {
        val parser = SseParser()
        val events = parser.feed(
            frame(
                id = "evt_cid_4",
                event = "conversation.message.completed",
                data = """{"conversation_id":"conv_body_2","payload":{"messageId":"msg_comp","sender":"assistant","text":"done"}}""",
            ),
        )
        val decoded = GatewayEventDecoder.decode(events.first()) as VerifiedConversationEvent.TimelineUpsert
        assertEquals("conv_body_2", decoded.message.conversationId?.value)
        assertEquals("CONFIRMED", decoded.message.state)
    }

    @Test
    fun decodesWithChatIdInOuterBody() {
        val parser = SseParser()
        val events = parser.feed(
            frame(
                id = "evt_cid_5",
                event = "conversation.timeline.tombstoned",
                data = """{"chat_id":"chat_body_3","payload":{"messageId":"msg_tomb","revision":5}}""",
            ),
        )
        val decoded = GatewayEventDecoder.decode(events.first()) as VerifiedConversationEvent.TimelineTombstoned
        assertEquals("chat_body_3", decoded.conversationId?.value)
        assertEquals("msg_tomb", decoded.messageId)
    }

    @Test
    fun decodesCommandResultWithTheClosedOutcomeAndItsSource() {
        val parser = SseParser()
        val events = parser.feed(
            frame(
                id = "evt_cmd_1",
                event = "conversation.command.result",
                data = """{"payload":{"command":"new","commandId":"new","outcome":"created-conversation","sourceConversationId":"conv_src","sourceMessageId":"msg_cmd","conversationId":"conv_created"}}""",
            ),
        )
        val decoded = GatewayEventDecoder.decode(events.first())
        assertTrue(decoded is VerifiedConversationEvent.CommandResult)
        val result = decoded as VerifiedConversationEvent.CommandResult
        assertEquals("new", result.command)
        assertEquals(com.openandroidintelligence.conversation.ports.CommandOutcome.CREATED_CONVERSATION, result.outcome)
        assertEquals("conv_src", result.sourceConversationId?.value)
        assertEquals("msg_cmd", result.sourceMessageId)
        assertEquals("conv_created", result.conversationId?.value)
    }

    @Test
    fun commandResultWithoutKnownOutcomeStaysUnknownInsteadOfBecomingASwitch() {
        val parser = SseParser()
        val events = parser.feed(
            frame(
                id = "evt_cmd_2",
                event = "conversation.command.result",
                data = """{"payload":{"command":"new","outcome":"something-we-do-not-model","conversationId":"conv_x"}}""",
            ),
        )
        val result = GatewayEventDecoder.decode(events.first()) as VerifiedConversationEvent.CommandResult
        assertEquals(com.openandroidintelligence.conversation.ports.CommandOutcome.OUTCOME_UNKNOWN, result.outcome)
    }

    @Test
    fun commandResultNamedClarifiesRefusals() {
        val parser = SseParser()
        val events = parser.feed(
            frame(
                id = "evt_cmd_3",
                event = "conversation.command.result",
                data = """{"payload":{"command":"new","outcome":"unsupported"}}""",
            ),
        )
        val result = GatewayEventDecoder.decode(events.first()) as VerifiedConversationEvent.CommandResult
        assertEquals(com.openandroidintelligence.conversation.ports.CommandOutcome.UNSUPPORTED, result.outcome)
        assertNull(result.conversationId)
        assertNull(result.sourceConversationId)
    }

    @Test
    fun decodesGenerationCancelledAndSnapshotInvalidatedWithOuterBodyConversationId() {
        val parser = SseParser()
        val cancelEvents = parser.feed(
            frame(
                id = "evt_can",
                event = "conversation.generation.cancelled",
                data = """{"conversation_id":"conv_can","payload":{"generationId":"gen_1"}}""",
            ),
        )
        val cancelDecoded = GatewayEventDecoder.decode(cancelEvents.first()) as VerifiedConversationEvent.GenerationCancelled
        assertEquals("conv_can", cancelDecoded.conversationId?.value)
        assertEquals("gen_1", cancelDecoded.generationId)

        val snapEvents = parser.feed(
            frame(
                id = "evt_snap",
                event = "conversation.snapshot.invalidated",
                data = """{"chat_id":"conv_snap","payload":{"snapshotRevision":10}}""",
            ),
        )
        val snapDecoded = GatewayEventDecoder.decode(snapEvents.first()) as VerifiedConversationEvent.SnapshotInvalidated
        assertEquals("conv_snap", snapDecoded.conversationId?.value)
        assertEquals(10L, snapDecoded.snapshotRevision)
    }
}
