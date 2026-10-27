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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.filterNotNull
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
            ioDispatcher = StandardTestDispatcher(testScheduler),
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
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val emitted = mutableListOf<VerifiedConversationEvent>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.observeEvents(ConversationScope("p1", "http://core", "a1", "i1")).toList(emitted) }

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
            ioDispatcher = StandardTestDispatcher(testScheduler),
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
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val emitted = mutableListOf<VerifiedConversationEvent>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.observeEvents(ConversationScope("p1", "http://core", "a1", "i1")).toList(emitted) }

        val success = repo.updateTitle("s_1", "新的量子计算对话")
        assertTrue(success)
        assertTrue(requestedPatch)

        runCurrent()
        job.cancel()

        assertTrue(emitted.any { it is VerifiedConversationEvent.TitleUpdated && it.newTitle == "新的量子计算对话" })
    }

    @Test fun cancelGenerationRequiresTheHostReceiptBeforeSettlingItsExactRound(): Unit = runBlocking {
        exerciseLiveCancellation("cancelled", CancelGenerationOutcome.CANCELLED)
    }

    @Test fun lateCancelAfterAgentSelectionStillTargetsTheOriginalHost(): Unit = runBlocking {
        exerciseLiveCancellation("cancelled", CancelGenerationOutcome.CANCELLED, switchAgent = true)
    }

    @Test
    fun unknownHostCancellationDoesNotBecomeSuccessfulLocalCancellation() = runTest {
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest) = WireResponse(200,
                listOf(RawHeader("Content-Type", "application/json")),
                """{"session":{"outcome":"outcome_unknown","generationId":"gen_1"}}""".toByteArray())
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        val repo = CoreAgentConversationRepository(client(transport), { sampleAgent }, { "s_1" }, backgroundScope,
            StandardTestDispatcher(testScheduler))
        assertEquals(CancelGenerationOutcome.OUTCOME_UNKNOWN, repo.cancelGeneration("s_1", "gen_1", "req_1").outcome)
    }

    @Test fun nativeApprovalsReachExistingCardsAndDecisionKeepsItsOriginalAgentRoute() = runTest {
        val frames = listOf(
            """{"type":"generation","generationId":"native-round"}""",
            """{"type":"approval_requested","approval":{"approvalId":"apr_1","conversationId":"s_1","command":"echo hello","reason":"确认命令","severity":"elevated","options":[{"choice":"once","label":"允许一次"},{"choice":"deny","label":"拒绝"}],"timeoutSeconds":300,"requestedAt":1000,"expiresAt":301000}}""",
            """{"type":"approval_resolved","approval":{"approvalId":"apr_1","conversationId":"s_1","decision":"once","decidedAt":2000}}""",
            """{"type":"done","outcome":"completed","generationId":"native-round"}""")
        val requests = mutableListOf<WireRequest>()
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                requests += request
                val stream = request.target.endsWith("/messages")
                return WireResponse(200, listOf(RawHeader("Content-Type", if (stream) "text/event-stream" else "application/json")),
                    (if (stream) frames.joinToString("") { "event: message\ndata: $it\n\n" }
                    else """{"approval":{"approvalId":"apr_1","conversationId":"s_1","decision":"once"}}""").toByteArray())
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        var selected = sampleAgent.copy(capabilities = sampleAgent.capabilities.copy(approvals = true))
        val repo = CoreAgentConversationRepository(client(transport), { selected }, { "s_1" }, backgroundScope,
            StandardTestDispatcher(testScheduler), supportsApprovalCards = true)
        val events = mutableListOf<VerifiedConversationEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.observeEvents(ConversationScope("p1", "https://core", "a1", "i1")).toList(events)
        }
        val acceptance = repo.submitMessage("s_1", OutgoingMessage(ClientMessageId("client_1"), "hello"))
        assertEquals(null, acceptance.generationId)
        runCurrent()
        val card = events.filterIsInstance<VerifiedConversationEvent.ApprovalRequested>().single().request
        assertEquals("echo hello", card.command)
        assertEquals(listOf("once", "deny"), card.options.map { it.choice.wireValue })
        assertEquals(ConversationId("s_1"), card.conversationId)
        assertEquals(com.openandroidintelligence.conversation.model.ApprovalOutcome.ALLOWED_ONCE,
            events.filterIsInstance<VerifiedConversationEvent.ApprovalResolved>().single().outcome)
        selected = selected.copy(id = "other-agent")
        val result = repo.submitApprovalDecision(card.approvalId, com.openandroidintelligence.conversation.model.ApprovalChoice.ONCE)
        assertEquals(com.openandroidintelligence.conversation.model.ApprovalSubmissionOutcome.SUBMITTED, result.outcome)
        assertEquals("/open-android-intelligence/v2/agents/hermes/sessions/s_1/approvals/apr_1/decisions", requests.last().target)
        assertEquals(com.openandroidintelligence.conversation.model.ApprovalSubmissionOutcome.UNSUPPORTED,
            repo.submitApprovalDecision(card.approvalId, com.openandroidintelligence.conversation.model.ApprovalChoice.ALWAYS).outcome)
    }

    @Test fun cancelledNativeDoneKeepsPartialReplyAndNeverEmitsCompleted() = runTest {
        val frames = listOf("""{"type":"generation","generationId":"native-round"}""",
            """{"type":"delta","text":"partial"}""", """{"type":"done","outcome":"cancelled","generationId":"native-round"}""")
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest) = WireResponse(200,
                listOf(RawHeader("Content-Type", "text/event-stream")),
                frames.joinToString("") { "event: message\ndata: $it\n\n" }.toByteArray())
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        val repo = CoreAgentConversationRepository(client(transport), { sampleAgent }, { "s_1" }, backgroundScope,
            StandardTestDispatcher(testScheduler))
        val events = mutableListOf<VerifiedConversationEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.observeEvents(ConversationScope("p1", "https://core", "a1", "i1")).toList(events)
        }
        repo.submitMessage("s_1", OutgoingMessage(ClientMessageId("client_1"), "hello"))
        runCurrent()
        assertEquals(GenerationState.CANCELLED, repo.generationState.value)
        assertEquals("native-round", events.filterIsInstance<VerifiedConversationEvent.GenerationCancelled>().single().generationId)
        assertFalse(events.filterIsInstance<VerifiedConversationEvent.MessageStatus>().any { it.status == com.openandroidintelligence.conversation.ports.AgentMessageStatus.COMPLETED })
        assertEquals("CANCELLED", events.filterIsInstance<VerifiedConversationEvent.TimelineUpsert>().last().message.state)
    }

    @Test fun uncertainHostCancellationKeepsTheLiveReaderAndWaitsForItsRealTerminalFrame(): Unit = runBlocking {
        exerciseLiveCancellation("outcome_unknown", CancelGenerationOutcome.OUTCOME_UNKNOWN)
    }

    private suspend fun exerciseLiveCancellation(outcome: String, expected: CancelGenerationOutcome, switchAgent: Boolean = false) {
        val finish = CompletableFuture<Unit>()
        val readerClosed = AtomicBoolean(false)
        var selected = sampleAgent
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                assertEquals("/open-android-intelligence/v2/agents/hermes/sessions/s_1/cancel", request.target)
                assertEquals("""{"generationId":"native-round"}""", request.body.decodeToString())
                return WireResponse(200, listOf(RawHeader("Content-Type", "application/json")),
                    """{"session":{"outcome":"$outcome","generationId":"native-round"}}""".toByteArray())
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
            override suspend fun <T> consumeResponse(request: WireRequest, consume: suspend (Int, List<RawHeader>, InputStream) -> T): T {
                val prefix = ByteArrayInputStream(listOf("""{"type":"generation","generationId":"native-round"}""",
                    """{"type":"delta","text":"partial"}""").joinToString("") { "event: message\ndata: $it\n\n" }.toByteArray())
                val suffix = ByteArrayInputStream("event: message\ndata: {\"type\":\"done\",\"outcome\":\"completed\",\"generationId\":\"native-round\"}\n\n".toByteArray())
                val input = object : InputStream() {
                    override fun read(): Int = error("Buffered read required")
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        if (prefix.available() > 0) return prefix.read(buffer, offset, length)
                        finish.get(5, TimeUnit.SECONDS)
                        return suffix.read(buffer, offset, length)
                    }
                    override fun close() { readerClosed.set(true) }
                }
                return input.use { consume(200, listOf(RawHeader("Content-Type", "text/event-stream")), it) }
            }
        }
        val repo = CoreAgentConversationRepository(client(transport), { selected }, { "s_1" }, parent)
        try {
            repo.submitMessage("s_1", OutgoingMessage(ClientMessageId("client_1"), "hello"))
            val generation = withTimeout(3_000) { repo.generationId.filterNotNull().first() }
            assertEquals("native-round", generation)
            if (switchAgent) {
                selected = sampleAgent.copy(id = "other", kind = "codex")
                repo.activeConversationChanged()
            }
            val result = repo.cancelGeneration("s_1", generation, "req_precise")
            assertEquals(expected, result.outcome)
            assertEquals(when {
                switchAgent -> GenerationState.IDLE
                expected == CancelGenerationOutcome.CANCELLED -> GenerationState.CANCELLED
                else -> GenerationState.RUNNING
            }, repo.generationState.value)
            assertFalse("A cancellation result must not prematurely close the host reader", readerClosed.get())
            finish.complete(Unit)
            if (!switchAgent) withTimeout(3_000) { repo.generationState.first {
                it == if (expected == CancelGenerationOutcome.CANCELLED) GenerationState.CANCELLED else GenerationState.COMPLETED
            } }
        } finally {
            finish.complete(Unit)
            parent.cancel()
        }
    }

    @Test fun nativeEventsFromAnotherAgentCannotEnterASameNamedSession() = runTest {
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest) = WireResponse(200,
                listOf(RawHeader("Content-Type", "application/json")), "{}".toByteArray())
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        var selected = sampleAgent
        val repo = CoreAgentConversationRepository(client(transport), { selected }, { "s_1" }, backgroundScope,
            StandardTestDispatcher(testScheduler))
        val first = mutableListOf<VerifiedConversationEvent>()
        val second = mutableListOf<VerifiedConversationEvent>()
        val scope = ConversationScope("p1", "https://core", "a1", "i1")
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.observeEvents(scope).toList(first) }
        selected = sampleAgent.copy(id = "other")
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.observeEvents(scope).toList(second) }
        assertTrue(repo.updateTitle("s_1", "Only the other Agent"))
        runCurrent()
        assertTrue(first.isEmpty())
        assertEquals("Only the other Agent", second.filterIsInstance<VerifiedConversationEvent.TitleUpdated>().single().newTitle)
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
                ioDispatcher = Dispatchers.Unconfined,
            )
        )
    }
}
