package com.openandroidintelligence.conversation.data

import com.openandroidintelligence.conversation.model.ApprovalChoice
import com.openandroidintelligence.conversation.model.ApprovalOptionStyle
import com.openandroidintelligence.conversation.model.ApprovalOutcome
import com.openandroidintelligence.conversation.model.ApprovalSeverity
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.gateway.events.SseParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    fun oneFrameYieldsItsEventAndItsGenerationIdFromASingleRead() {
        val parser = SseParser()
        val events = parser.feed(
            frame(
                id = "evt_gen_1",
                event = "conversation.message.delta",
                data = """
                    {"payload":{"messageId":"msg_gen","generationId":"gen_42","sender":"assistant","text":"流式"},"occurredAt":"2026-09-01T08:00:00.000Z"}
                """.trimIndent(),
            ),
        )
        val decoded = GatewayEventDecoder.decodeWithGenerationId(events.first())
        // 热路径每帧只读一次，两半必须同时给出；且各自要与它取代的单用途读取器逐字一致。
        assertTrue(decoded.event is VerifiedConversationEvent.TimelineUpsert)
        val upsert = decoded.event as VerifiedConversationEvent.TimelineUpsert
        assertEquals("msg_gen", upsert.message.id)
        assertEquals("STREAMING", upsert.message.state)
        assertEquals("gen_42", decoded.generationId)
        assertEquals(GatewayEventDecoder.decode(events.first()), decoded.event)
        assertEquals(GatewayEventDecoder.generationIdOf(events.first()), decoded.generationId)
    }

    @Test
    fun anUnmodelledEventNameStillCarriesItsGenerationIdOutOfThatSameRead() {
        val parser = SseParser()
        val events = parser.feed(
            frame(
                id = "evt_gen_2",
                event = "future.unknown.thing",
                data = """{"payload":{"generationId":"gen_43"}}""",
            ),
        )
        val decoded = GatewayEventDecoder.decodeWithGenerationId(events.first())
        assertNull(
            "未建模的事件名不得被改写成事件，但它携带的 generationId 不能随之一并丢掉",
            decoded.event,
        )
        assertEquals("gen_43", decoded.generationId)
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

    @Test
    fun decodesApprovalRequestWithTheTiersTheGatewayOffered() {
        val parser = SseParser()
        val events = parser.feed(
            frame(
                id = "evt_apr_1",
                event = "conversation.approval.requested",
                data = """{"payload":{"approvalId":"apr_1","conversationId":"conv_1","command":"python3 -c \"print(1)\"","reason":"内联解释器执行","severity":"elevated","options":[{"choice":"once","style":"primary"},{"choice":"always","style":"secondary"},{"choice":"deny","style":"danger"}],"timeoutSeconds":300,"requestedAt":1780000000000,"expiresAt":1780000300000}}""",
            ),
        )
        val decoded = GatewayEventDecoder.decode(events.first())
        assertTrue(decoded is VerifiedConversationEvent.ApprovalRequested)
        val request = (decoded as VerifiedConversationEvent.ApprovalRequested).request
        assertEquals("apr_1", request.approvalId.value)
        assertEquals("conv_1", request.conversationId?.value)
        assertEquals("内联解释器执行", request.reason)
        assertEquals(ApprovalSeverity.ELEVATED, request.severity)
        assertEquals(300L, request.timeoutSeconds)
        assertEquals(1780000000000L, request.requestedAt)
        assertEquals(1780000300000L, request.expiresAt)
        // The tiers are the Gateway's own list: nothing is added, nothing reordered.
        assertEquals(
            listOf(ApprovalChoice.ONCE, ApprovalChoice.ALWAYS, ApprovalChoice.DENY),
            request.options.map { it.choice },
        )
        assertEquals(ApprovalOptionStyle.PRIMARY, request.options[0].style)
        assertEquals(ApprovalOptionStyle.DANGER, request.options[2].style)
    }

    @Test
    fun approvalRequestWithoutAUsableTierIsNotACard() {
        val parser = SseParser()
        val events = parser.feed(
            frame(
                id = "evt_apr_2",
                event = "conversation.approval.requested",
                data = """{"payload":{"approvalId":"apr_2","conversationId":"conv_1","command":"rm -rf /tmp/x","reason":"递归删除","options":[{"choice":"allow-once"}],"timeoutSeconds":300,"requestedAt":1780000000000}}""",
            ),
        )
        assertNull(
            "未知档位不得被改写成看起来能点的按钮",
            GatewayEventDecoder.decode(events.first()),
        )
    }

    @Test
    fun theGatewaysOwnDeadlineWinsOverLocallyDerivedArithmetic() {
        val parser = SseParser()
        val events = parser.feed(
            frame(
                id = "evt_apr_deadline",
                event = "conversation.approval.requested",
                data = """{"payload":{"approvalId":"apr_1","conversationId":"conv_1","command":"ls","reason":"只读","options":[{"choice":"once"}],"timeoutSeconds":300,"requestedAt":1780000000000,"expiresAt":1780000060000}}""",
            ),
        )
        val request = (GatewayEventDecoder.decode(events.first()) as VerifiedConversationEvent.ApprovalRequested).request
        assertEquals(1780000060000L, request.expiresAt)
        // 300s would have been a whole minute later: counting to it while the
        // Gateway already refuses answers would offer a press that cannot land.
        assertNotEquals(request.requestedAt + request.timeoutSeconds * 1_000L, request.expiresAt)
    }

    @Test
    fun approvalRequestFallsBackToTheDocumentedTimeoutOnlyWhenTheGatewayOmitsIt() {
        val parser = SseParser()
        val events = parser.feed(
            frame(
                id = "evt_apr_3",
                event = "conversation.approval.requested",
                data = """{"payload":{"approvalId":"apr_3","conversationId":"conv_1","command":"ls","reason":"只读","options":[{"choice":"once"}],"requestedAt":1780000000000}}""",
            ),
        )
        val request = (GatewayEventDecoder.decode(events.first()) as VerifiedConversationEvent.ApprovalRequested).request
        assertEquals(GatewayEventDecoder.DEFAULT_APPROVAL_TIMEOUT_SECONDS, request.timeoutSeconds)
    }

    @Test
    fun decodesApprovalResolvedIncludingTheOutcomesTheGatewayOwns() {
        val parser = SseParser()
        val events = parser.feed(
            frame(
                id = "evt_apr_4",
                event = "conversation.approval.resolved",
                data = """{"payload":{"approvalId":"apr_1","conversationId":"conv_1","decision":"timeout","decidedAt":1780000300000}}""",
            ),
        )
        val decoded = GatewayEventDecoder.decode(events.first()) as VerifiedConversationEvent.ApprovalResolved
        assertEquals("apr_1", decoded.approvalId.value)
        assertEquals(ApprovalOutcome.TIMED_OUT, decoded.outcome)
        assertEquals(1780000300000L, decoded.decidedAt)
        assertEquals("conv_1", decoded.conversationId?.value)
    }

    @Test
    fun approvalResolvedWithoutAnApprovalIdIsNotAnAnswer() {
        val parser = SseParser()
        val events = parser.feed(
            frame(
                id = "evt_apr_5",
                event = "conversation.approval.resolved",
                data = """{"payload":{"conversationId":"conv_1","decision":"once"}}""",
            ),
        )
        assertNull(GatewayEventDecoder.decode(events.first()))
    }

    @Test
    fun approvalResolvedWithAnUnknownDecisionStaysUnknownInsteadOfBecomingAnAllow() {
        val parser = SseParser()
        val events = parser.feed(
            frame(
                id = "evt_apr_6",
                event = "conversation.approval.resolved",
                data = """{"payload":{"approvalId":"apr_1","conversationId":"conv_1","decision":"approved"}}""",
            ),
        )
        val decoded = GatewayEventDecoder.decode(events.first()) as VerifiedConversationEvent.ApprovalResolved
        assertEquals(ApprovalOutcome.UNKNOWN, decoded.outcome)
    }
}
