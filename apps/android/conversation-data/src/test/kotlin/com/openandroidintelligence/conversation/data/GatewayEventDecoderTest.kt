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
}
