package com.openandroidintelligence.mobile.conversations

import com.openandroidintelligence.conversation.model.ClientMessageId
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.ports.CancelGenerationOutcome
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.ports.OutgoingMessage
import com.openandroidintelligence.conversation.ports.PageRequest
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.gateway.agents.AgentCapabilities
import com.openandroidintelligence.gateway.agents.AgentSessionClient
import com.openandroidintelligence.gateway.agents.CoreAgent
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.GatewayByteTransport
import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.GatewayProfile
import com.openandroidintelligence.gateway.http.RawHeader
import com.openandroidintelligence.gateway.http.WireRequest
import com.openandroidintelligence.gateway.http.WireResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CoreAgentConversationRepositoryTest {

    private val sampleAgent = CoreAgent(
        id = "hermes",
        name = "Hermes",
        kind = "hermes",
        online = true,
        sessionScope = "account",
        capabilities = AgentCapabilities(
            list = true,
            history = true,
            create = true,
            send = true,
            rename = true,
            archive = true,
            delete = true,
            cancel = true,
            stream = true,
        ),
    )

    @Test
    fun listConversationsMapsSessionsToSummaryCorrectly() = runTest {
        val json = """{"sessions":[{"id":"s_1","title":"关于量子计算","updatedAt":"2026-10-26T12:00:00Z","archived":false},{"id":"s_2","title":"","updatedAt":null,"archived":true}]}"""
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                return WireResponse(200, listOf(RawHeader("Content-Type", "application/json")), json.toByteArray())
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        val client = client(transport)
        val repo = CoreAgentConversationRepository(
            client = client,
            currentAgent = { sampleAgent },
            activeConversationId = { "s_1" },
            parentScope = backgroundScope,
        )

        val page = repo.listConversations(ConversationScope("p1", "http://core", "a1", "i1"), PageRequest(null, 20))
        assertEquals(2, page.conversations.size)
        assertEquals("关于量子计算", page.conversations[0].title)
        assertEquals(ConversationId("s_1"), page.conversations[0].id)
        assertFalse(page.conversations[0].archived)
        // Empty title should fallback to "新对话"
        assertEquals("新对话", page.conversations[1].title)
        assertEquals(ConversationId("s_2"), page.conversations[1].id)
        assertTrue(page.conversations[1].archived)
    }

    @Test
    fun createConversationEmitsEventAndReturnsDomainObject() = runTest {
        val json = """{"session":{"id":"s_created","title":"新对话","updatedAt":"2026-10-26T12:30:00Z","archived":false}}"""
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                return WireResponse(200, listOf(RawHeader("Content-Type", "application/json")), json.toByteArray())
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        val client = client(transport)
        val repo = CoreAgentConversationRepository(
            client = client,
            currentAgent = { sampleAgent },
            activeConversationId = { null },
            parentScope = backgroundScope,
        )

        val emitted = mutableListOf<VerifiedConversationEvent>()
        val job = launch { repo.observeEvents(ConversationScope("p1", "http://core", "a1", "i1")).toList(emitted) }

        val conv = repo.createConversation(ConversationScope("p1", "http://core", "a1", "i1"), "client_1")
        assertEquals(ConversationId("s_created"), conv.id)
        assertEquals("新对话", conv.title)
        runCurrent()
        job.cancel()

        assertTrue(emitted.any { it is VerifiedConversationEvent.ConversationsChanged && it.conversationId?.value == "s_created" })
    }

    @Test
    fun timelineMapsNativeMessagesToTimelineMessages() = runTest {
        val json = """{"messages":[{"id":"m_1","role":"user","text":"你好","createdAt":"2026-10-26T10:00:00Z"},{"id":"m_2","role":"assistant","text":"你好！很高兴为你服务。","createdAt":"2026-10-26T10:00:05Z"}]}"""
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                return WireResponse(200, listOf(RawHeader("Content-Type", "application/json")), json.toByteArray())
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        val client = client(transport)
        val repo = CoreAgentConversationRepository(
            client = client,
            currentAgent = { sampleAgent },
            activeConversationId = { "s_1" },
            parentScope = backgroundScope,
        )

        val page = repo.timeline("s_1", PageRequest(null, 50))
        assertEquals(2, page.messages.size)
        assertEquals("user", page.messages[0].sender)
        assertEquals("assistant", page.messages[1].sender)
        assertEquals("你好！很高兴为你服务。", (page.messages[1].parts[0] as com.openandroidintelligence.conversation.model.MessagePart.Text).value)
    }

    @Test
    fun updateTitleCallsRenameAndEmitsTitleUpdatedEvent() = runTest {
        var requestedPatch = false
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                if (request.method == "PATCH" && request.target.endsWith("/sessions/s_1")) {
                    requestedPatch = true
                }
                return WireResponse(200, listOf(RawHeader("Content-Type", "application/json")), """{"data":{"status":"ok"}}""".toByteArray())
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        val client = client(transport)
        val repo = CoreAgentConversationRepository(
            client = client,
            currentAgent = { sampleAgent },
            activeConversationId = { "s_1" },
            parentScope = backgroundScope,
        )

        val emitted = mutableListOf<VerifiedConversationEvent>()
        val job = launch { repo.observeEvents(ConversationScope("p1", "http://core", "a1", "i1")).toList(emitted) }

        val success = repo.updateTitle("s_1", "新的量子计算对话")
        assertTrue(success)
        assertTrue(requestedPatch)

        runCurrent()
        job.cancel()

        assertTrue(emitted.any { it is VerifiedConversationEvent.TitleUpdated && it.newTitle == "新的量子计算对话" })
    }

    @Test
    fun cancelGenerationCallsCancelAndSettlesGenerationState() = runTest {
        var requestedCancel = false
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                if (request.method == "POST" && request.target.endsWith("/cancel")) {
                    requestedCancel = true
                }
                return WireResponse(200, listOf(RawHeader("Content-Type", "application/json")), """{"data":{"status":"ok"}}""".toByteArray())
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        val client = client(transport)
        val repo = CoreAgentConversationRepository(
            client = client,
            currentAgent = { sampleAgent },
            activeConversationId = { "s_1" },
            parentScope = backgroundScope,
        )

        val result = repo.cancelGeneration("s_1", "gen_1", "req_1")
        assertEquals(CancelGenerationOutcome.CANCELLED, result.outcome)
        assertTrue(requestedCancel)
        assertEquals(GenerationState.IDLE, repo.generationState.value)
    }

    @Test
    fun submitMessageEmitsConsistentTimestampGenerationIdAndCorrectStatusMessageId() = runTest {
        val ssePayload = "event: delta\ndata: {\"delta\":\"Hello \"}\n\nevent: delta\ndata: {\"delta\":\"World!\"}\n\nevent: done\ndata: {}\n\n"
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                return WireResponse(200, listOf(RawHeader("Content-Type", "application/json")), ByteArray(0))
            }
            override fun eventStream(request: WireRequest) = kotlinx.coroutines.flow.flowOf(ssePayload.toByteArray())
        }
        val client = client(transport)
        val repo = CoreAgentConversationRepository(
            client = client,
            currentAgent = { sampleAgent },
            activeConversationId = { "s_1" },
            parentScope = backgroundScope,
        )

        val emitted = mutableListOf<VerifiedConversationEvent>()
        val job = launch { repo.observeEvents(ConversationScope("p1", "http://core", "a1", "i1")).toList(emitted) }

        val clientMsgId = ClientMessageId("client_msg_123")
        val acceptance = repo.submitMessage("s_1", OutgoingMessage(clientMsgId, "Hi"))

        runCurrent()
        job.cancel()

        val upserts = emitted.filterIsInstance<VerifiedConversationEvent.TimelineUpsert>()
        assertTrue("Should emit timeline upserts", upserts.isNotEmpty())

        val timestamps = upserts.map { it.message.timestamp }
        val firstTimestamp = timestamps.first()
        assertTrue("All TimelineMessage upserts must share the exact same timestamp", timestamps.all { it == firstTimestamp })

        assertTrue("All TimelineMessage upserts must include generationId", upserts.all { it.message.generationId == acceptance.generationId })

        val statusEvents = emitted.filterIsInstance<VerifiedConversationEvent.MessageStatus>()
        assertEquals(1, statusEvents.size)
        val statusEvent = statusEvents.first()
        assertEquals(acceptance.messageId, statusEvent.messageId)
        assertEquals(clientMsgId, statusEvent.clientMessageId)
        assertEquals(acceptance.generationId, statusEvent.generationId)
    }

    private fun client(transport: GatewayByteTransport): AgentSessionClient {
        return AgentSessionClient(
            GatewayHttpClient(
                GatewayProfile("account", "device", "session", "https://core.example", accessToken = "access"),
                transport,
                { ByteArray(64) },
                InMemoryEventCursorStore(),
            )
        )
    }
}
