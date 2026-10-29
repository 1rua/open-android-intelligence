package com.openandroidintelligence.mobile.conversations

import com.openandroidintelligence.conversation.model.ClientMessageId
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.ports.CancelGenerationOutcome
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.ports.MessageDeliveryMode
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.filterNotNull
import java.io.ByteArrayInputStream
import java.io.IOException
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

    @Test fun observationErrorsRecoverTheOriginalIdentityWithoutMarkingExecutionFailed() = runTest {
        for (code in listOf("AGENT_STREAM_TOO_LARGE", "AGENT_TIMEOUT", "AGENT_STREAM_INTERRUPTED", "AGENT_UNAVAILABLE")) {
            val requests = mutableListOf<WireRequest>()
            val stream = listOf(
                """{"type":"generation","generationId":"gen_error","clientMessageId":"cm_error","userMessageId":"user_error"}""",
                """{"type":"delta","messageId":"answer_error","eventId":"text_1","contentRevision":1,"text":"partial"}""",
                """{"type":"error","code":"$code"}""",
            ).joinToString("") { "event: message\ndata: $it\n\n" }
            val transport = object : GatewayByteTransport {
                override suspend fun execute(request: WireRequest): WireResponse {
                    requests += request
                    val body = when {
                        request.method == "POST" -> stream
                        request.target.contains("/generation?") -> """{"generation":{"generationId":"gen_error","conversationId":"s_1","clientMessageId":"cm_error","userMessageId":"user_error","state":"completed","revision":7}}"""
                        request.target.endsWith("/history") -> """{"messages":[{"id":"user_error","role":"user","text":"prompt","clientMessageId":"cm_error"},{"id":"answer_error","role":"assistant","text":"complete","clientMessageId":"cm_error","generationId":"gen_error","contentRevision":7}]}"""
                        else -> error("Unexpected recovery read")
                    }
                    return WireResponse(200, listOf(RawHeader("Content-Type", if (request.method == "POST") "text/event-stream" else "application/json")), body.toByteArray())
                }
                override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
            }
            val agent = sampleAgent.copy(capabilities = sampleAgent.capabilities.copy(generationRecovery = true))
            val repo = CoreAgentConversationRepository(client(transport), { agent }, { "s_1" }, backgroundScope, StandardTestDispatcher(testScheduler))
            val events = mutableListOf<VerifiedConversationEvent>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                repo.observeEvents(ConversationScope("p1", "https://core", "a1", "i1")).toList(events)
            }
            repo.submitMessage("s_1", OutgoingMessage(ClientMessageId("cm_error"), "prompt"))
            runCurrent()
            assertEquals("$code is an observation failure, so query the accepted turn", 1, requests.count { it.target.contains("/generation?") })
            assertEquals(1, requests.count { it.method == "POST" })
            assertEquals(GenerationState.COMPLETED, repo.generationState.value)
            assertTrue(events.filterIsInstance<VerifiedConversationEvent.MessageStatus>().none {
                it.status == com.openandroidintelligence.conversation.ports.AgentMessageStatus.FAILED
            })
            collector.cancel()
        }
    }

    @Test fun nativeExecutionSnapshotsUseIndependentEventsAndNeverInventAssistantRows() = runTest {
        val stream = listOf(
            """{"type":"generation","generationId":"gen_tools","clientMessageId":"cm_tools","userMessageId":"user_tools"}""",
            """{"type":"delta","generationId":"gen_tools","messageId":"answer_tools","eventId":"text_9","contentRevision":9,"text":"answer"}""",
            """{"type":"generation","generationId":"gen_tools","clientMessageId":"cm_tools","userMessageId":"user_tools","eventId":"tools_1","executionRevision":1,"executionSteps":[{"stepId":"call","eventId":"native:2","toolName":"read","arguments":"{}","status":"running","revision":1}]}""",
            """{"type":"generation","generationId":"gen_tools","clientMessageId":"cm_tools","userMessageId":"user_tools","eventId":"tools_2","executionRevision":2,"executionSteps":[{"stepId":"call","eventId":"native:2","toolName":"read","arguments":"{}","output":"result","status":"completed","revision":2}]}""",
            """{"type":"done","generationId":"gen_tools","messageId":"answer_tools","eventId":"text_9","contentRevision":9,"text":"answer"}""",
        ).joinToString("") { "event: message\ndata: $it\n\n" }
        val repo = CoreAgentConversationRepository(client(object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest) = WireResponse(200, listOf(RawHeader("Content-Type", "text/event-stream")), stream.toByteArray())
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }), { sampleAgent }, { "s_1" },
            backgroundScope, StandardTestDispatcher(testScheduler))
        val events = mutableListOf<VerifiedConversationEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.observeEvents(ConversationScope("p1", "https://core", "a1", "i1")).toList(events)
        }
        repo.submitMessage("s_1", OutgoingMessage(ClientMessageId("cm_tools"), "prompt"))
        runCurrent()
        val execution = events.filterIsInstance<VerifiedConversationEvent.ExecutionStepsUpdated>().last()
        assertEquals("gen_tools", execution.generationId)
        assertEquals(2L, execution.executionRevision)
        assertEquals("cm_tools", execution.clientMessageId)
        assertEquals("user_tools", execution.userMessageId)
        assertEquals(com.openandroidintelligence.conversation.model.NativeExecutionStatus.COMPLETED, execution.steps.single().status)
        assertEquals(setOf("answer_tools"), events.filterIsInstance<VerifiedConversationEvent.TimelineUpsert>().map { it.message.id }.toSet())
        assertTrue(events.filterIsInstance<VerifiedConversationEvent.TimelineUpsert>().all { it.message.contentRevision == 9L })
    }

    @Test fun localSubmissionCannotInventAServerAcceptance() = runTest {
        val response = CompletableDeferred<WireResponse>()
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest) = response.await()
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        val repo = CoreAgentConversationRepository(client(transport), { sampleAgent }, { "s_1" },
            backgroundScope, StandardTestDispatcher(testScheduler))
        val events = mutableListOf<VerifiedConversationEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.observeEvents(ConversationScope("p1", "https://core", "a1", "i1")).toList(events)
        }
        repo.submitMessage("s_1", OutgoingMessage(ClientMessageId("cm_wait"), "问题"))
        runCurrent()
        assertTrue("A suspended HTTP request is not a server receipt",
            events.filterIsInstance<VerifiedConversationEvent.MessageAccepted>().isEmpty())
    }

    @Test fun droppedDoneAutomaticallyRestoresHistoryAndDurableTerminalWithoutResending() = runTest {
        val requests = mutableListOf<WireRequest>()
        val stream = listOf(
            """{"type":"generation","generationId":"gen_lost","userMessageId":"user_lost","clientMessageId":"cm_lost"}""",
            """{"type":"delta","messageId":"assistant_lost","text":"收到的部分"}"""
        ).joinToString("") { "event: message\ndata: $it\n\n" }
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                requests += request
                val body = when {
                    request.method == "POST" -> stream
                    request.target.contains("/generation?") -> """{"generation":{"generationId":"gen_lost","conversationId":"s_1","clientMessageId":"cm_lost","userMessageId":"user_lost","state":"completed","revision":7}}"""
                    request.target.endsWith("/history") -> """{"messages":[{"id":"user_lost","role":"user","text":"问题","clientMessageId":"cm_lost"},{"id":"assistant_lost","role":"assistant","text":"完整回答","clientMessageId":"cm_lost","generationId":"gen_lost","contentRevision":7}]}"""
                    else -> error("Unexpected request ${request.target}")
                }
                return WireResponse(200, listOf(RawHeader("Content-Type", if (request.method == "POST") "text/event-stream" else "application/json")), body.toByteArray())
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        val agent = sampleAgent.copy(capabilities = sampleAgent.capabilities.copy(generationRecovery = true))
        val repo = CoreAgentConversationRepository(client(transport), { agent }, { "s_1" }, backgroundScope,
            StandardTestDispatcher(testScheduler))
        val events = mutableListOf<VerifiedConversationEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.observeEvents(ConversationScope("p1", "https://core", "a1", "i1")).toList(events)
        }
        repo.submitMessage("s_1", OutgoingMessage(ClientMessageId("cm_lost"), "问题"))
        runCurrent()
        assertEquals("断流后自动查询原 generation 的持久回执", 1, requests.count { it.target.contains("/generation?") })
        assertEquals("恢复禁止重新提交", 1, requests.count { it.method == "POST" })
        assertEquals(GenerationState.COMPLETED, repo.generationState.value)
        val answer = events.filterIsInstance<VerifiedConversationEvent.TimelineUpsert>()
            .last { it.message.id == "assistant_lost" }.message
        assertEquals("完整回答", (answer.parts.single() as com.openandroidintelligence.conversation.model.MessagePart.Text).value)
        assertEquals("CONFIRMED", answer.state)
        assertEquals(1, events.filterIsInstance<VerifiedConversationEvent.MessageStatus>()
            .count { it.status == com.openandroidintelligence.conversation.ports.AgentMessageStatus.COMPLETED })
    }

    @Test fun historyKeepsMillisecondsOffsetsAndNativeNumericTimestamps() = runTest {
        val dates = listOf("1780000000.123", "1780000000124", "\"2026-05-28T20:26:40.125Z\"",
            "\"2026-05-28T22:26:40.126+02:00\"", "\"invalid\"", "null")
        val body = "{\"messages\":[" + dates.mapIndexed { index, date ->
            """{"id":"m_$index","role":"assistant","text":"$index","createdAt":$date}"""
        }.joinToString(",") + "]}"
        val repo = CoreAgentConversationRepository(client(responseTransport(body)), { sampleAgent }, { "s_1" },
            backgroundScope, StandardTestDispatcher(testScheduler))
        val messages = repo.timeline("s_1", PageRequest(null, 50)).messages
        assertEquals(listOf(1780000000123L, 1780000000124L, 1780000000125L, 1780000000126L),
            messages.take(4).map { it.timestamp })
        assertEquals("Missing dates must have a deterministic fallback", messages[4].timestamp, messages[5].timestamp)
    }

    @Test fun slowUiCannotBlockSseReadingAndStillReceivesTheCompleteFinalReply() = runTest {
        val deltaText = "字".repeat(256)
        val frames = (1..4096).map { """{"type":"delta","text":"$deltaText"}""" } +
            """{"type":"done","outcome":"completed"}"""
        val readFinished = CompletableDeferred<Unit>()
        val body = frames.joinToString("") { "event: message\ndata: $it\n\n" }.toByteArray()
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("stream only")
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
            override suspend fun <T> consumeResponse(request: WireRequest,
                consume: suspend (Int, List<RawHeader>, InputStream) -> T): T {
                val result = consume(200, listOf(RawHeader("Content-Type", "text/event-stream")), ByteArrayInputStream(body))
                readFinished.complete(Unit)
                return result
            }
        }
        val repo = CoreAgentConversationRepository(client(transport), { sampleAgent }, { "s_1" },
            backgroundScope, StandardTestDispatcher(testScheduler))
        val releaseUi = CompletableDeferred<Unit>()
        val events = mutableListOf<VerifiedConversationEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.observeEvents(ConversationScope("p1", "https://core", "a1", "i1")).collect {
                events += it
                releaseUi.await()
            }
        }
        repo.submitMessage("s_1", OutgoingMessage(ClientMessageId("c_long"), "长回答"))
        runCurrent()
        assertTrue("Network reader must finish while UI is blocked", readFinished.isCompleted)
        releaseUi.complete(Unit)
        runCurrent()
        val final = events.filterIsInstance<VerifiedConversationEvent.TimelineUpsert>().last().message
        assertEquals("CONFIRMED", final.state)
        assertEquals(deltaText.repeat(4096), (final.parts.single() as com.openandroidintelligence.conversation.model.MessagePart.Text).value)
        assertEquals(1, events.filterIsInstance<VerifiedConversationEvent.MessageStatus>().size)
    }

    private fun responseTransport(body: String) = object : GatewayByteTransport {
        override suspend fun execute(request: WireRequest) = WireResponse(200,
            listOf(RawHeader("Content-Type", "application/json")), body.toByteArray())
        override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
    }

    @Test fun nativeIdentityAndSeparateAssistantMessagesSurviveRefresh() = runTest {
        val scope = ConversationScope("p1", "https://core", "a1", "i1")
        val frames = listOf(
            """{"type":"generation","generationId":"native_round","userMessageId":"native_user","clientMessageId":"cm_turn"}""",
            """{"type":"delta","text":"过程说明","messageId":"native_step"}""",
            """{"type":"delta","text":"最终回答草稿","messageId":"native_answer"}""",
            """{"type":"done","outcome":"completed","messageId":"native_answer","text":"最终回答"}""")
        val stream = frames.joinToString("") { "event: message\ndata: $it\n\n" }
        val history = """{"messages":[{"id":"native_user","role":"user","text":"问题","createdAt":1780000000.123,"clientMessageId":"cm_turn"},{"id":"native_step","role":"assistant","text":"过程说明","createdAt":1780000000.124},{"id":"native_answer","role":"assistant","text":"最终回答","createdAt":1780000000.125,"clientMessageId":"cm_turn"}]}"""
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest) = WireResponse(200,
                listOf(RawHeader("Content-Type", if (request.method == "POST") "text/event-stream" else "application/json")),
                (if (request.method == "POST") stream else history).toByteArray())
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        val repo = CoreAgentConversationRepository(client(transport), { sampleAgent }, { "s_1" }, backgroundScope,
            StandardTestDispatcher(testScheduler))
        val events = mutableListOf<VerifiedConversationEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repo.observeEvents(scope).toList(events) }
        repo.submitMessage("s_1", OutgoingMessage(ClientMessageId("cm_turn"), "问题"))
        runCurrent()
        val confirmed = events.filterIsInstance<VerifiedConversationEvent.TimelineUpsert>()
            .map { it.message }.filter { it.state == "CONFIRMED" }.associateBy { it.id }
        assertEquals(setOf("native_step", "native_answer"), confirmed.keys)
        assertEquals("最终回答", (confirmed.getValue("native_answer").parts.single() as com.openandroidintelligence.conversation.model.MessagePart.Text).value)
        assertEquals("过程说明", (confirmed.getValue("native_step").parts.single() as com.openandroidintelligence.conversation.model.MessagePart.Text).value)
        assertEquals(ClientMessageId("assistant_cm_turn"), confirmed.getValue("native_answer").clientMessageId)
        assertEquals("native_user", events.filterIsInstance<VerifiedConversationEvent.MessageStatus>().single().messageId)
        assertEquals(confirmed.keys, repo.timeline("s_1", PageRequest(null, 50)).messages.filter { it.sender == "assistant" }.map { it.id }.toSet())
    }

    @Test fun missingDeleteCapabilityFailsExplicitlyWithoutPretendingToDelete() = runTest {
        val repo = CoreAgentConversationRepository(client(responseTransport("{}")),
            { sampleAgent.copy(capabilities = sampleAgent.capabilities.copy(delete = false)) }, { "s_1" }, backgroundScope,
            StandardTestDispatcher(testScheduler))
        assertEquals("UNSUPPORTED_CAPABILITY", runCatching { repo.deleteConversation("s_1") }.exceptionOrNull()?.message)
    }

    @Test fun singleMemberBatchPreservesAttachmentsAndClientIdentityAndRejectsSilentTruncation() = runTest {
        val requests = mutableListOf<WireRequest>()
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                requests += request
                return WireResponse(200, listOf(RawHeader("Content-Type", "text/event-stream")),
                    "event: message\ndata: {\"type\":\"done\"}\n\n".toByteArray())
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        val repo = CoreAgentConversationRepository(client(transport),
            { sampleAgent.copy(capabilities = sampleAgent.capabilities.copy(attachments = true)) }, { "s_1" }, backgroundScope,
            StandardTestDispatcher(testScheduler))
        val message = OutgoingMessage(ClientMessageId("cm_files"), "", listOf("att_image", "att_docx"))
        val accepted = repo.submitBatch(com.openandroidintelligence.conversation.ports.MessageBatch("batch_files", listOf(message), "s_1"))
        runCurrent()
        assertEquals(setOf("cm_files"), accepted.memberIds.keys)
        val payload = com.openandroidintelligence.gateway.schema.JsonFields.obj(com.openandroidintelligence.gateway.schema.Json.parse(requests.single().body.decodeToString()))!!
        assertEquals(listOf("att_image", "att_docx"), com.openandroidintelligence.gateway.schema.JsonFields.strings(payload, "attachmentIds"))
        assertEquals("cm_files", com.openandroidintelligence.gateway.schema.JsonFields.string(payload, "clientMessageId"))
        assertEquals("UNSUPPORTED_CAPABILITY", runCatching { repo.submitBatch(
            com.openandroidintelligence.conversation.ports.MessageBatch("batch_two", listOf(message, message.copy(clientMessageId = ClientMessageId("cm_second"))), "s_1")) }.exceptionOrNull()?.message)
        assertEquals(1, requests.size)
    }

    @Test fun validationCapabilityAndProtocolFailuresAreNotReportedAsAgentOffline() = runTest {
        val cases = mapOf("INVALID_MESSAGE" to "INVALID_MESSAGE", "UNSUPPORTED_CAPABILITY" to "UNSUPPORTED_CAPABILITY",
            "ATTACHMENT_NOT_FOUND" to "ATTACHMENT_READ_FAILED", "HOST_INCOMPATIBLE" to "PROTOCOL_ERROR")
        for ((remote, expected) in cases) {
            val transport = object : GatewayByteTransport {
                override suspend fun execute(request: WireRequest) = WireResponse(400,
                    listOf(RawHeader("Content-Type", "application/json")), """{"error":{"code":"$remote"}}""".toByteArray())
                override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
            }
            val failing = CoreAgentConversationRepository(client(transport), { sampleAgent }, { "s_1" }, backgroundScope,
                StandardTestDispatcher(testScheduler))
            val events = mutableListOf<VerifiedConversationEvent>()
            val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                failing.observeEvents(ConversationScope("p1", "https://core", "a1", "i1")).toList(events)
            }
            failing.submitMessage("s_1", OutgoingMessage(ClientMessageId("cm_error"), "内容"))
            runCurrent()
            assertEquals(expected, events.filterIsInstance<VerifiedConversationEvent.MessageStatus>().single().errorCode?.wireValue)
            job.cancel()
        }
    }

    @Test fun confirmedRemoteDeletionStillSucceedsIfLocalPreferenceCleanupFails() = runTest {
        val store = object : NativeConversationOrganizationStore {
            override fun read(agentId: String) = com.openandroidintelligence.conversation.ports.ConversationOrganization()
            override fun update(agentId: String, expectedRevision: Long, organization: com.openandroidintelligence.conversation.ports.ConversationOrganization) = error("storage full")
            override fun removeConversation(agentId: String, conversationId: String): Unit = error("storage full")
        }
        val repo = CoreAgentConversationRepository(client(responseTransport("{}")), { sampleAgent }, { "s_1" }, backgroundScope,
            StandardTestDispatcher(testScheduler), organizationStore = store)
        val events = mutableListOf<VerifiedConversationEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.observeEvents(ConversationScope("p1", "https://core", "a1", "i1")).toList(events)
        }
        repo.deleteConversation("s_1")
        assertTrue(events.filterIsInstance<VerifiedConversationEvent.ConversationsChanged>().single().deleted)
    }

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
            steer = true,
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

    @Test fun malformedApprovalFallsBackToTextWithoutFailingTheRunningTurn() = runTest {
        val frames = listOf(
            """{"type":"generation","generationId":"native-round"}""",
            """{"type":"approval_requested","approval":{"approvalId":"apr_broken","conversationId":"s_1","command":"rm -rf /tmp/dangerous_test_backup","reason":"清理测试备份","options":[{"choice":"allow-once"}],"timeoutSeconds":300,"requestedAt":1000}}""",
            """{"type":"done","outcome":"completed","generationId":"native-round","text":"已停止等待审批"}""",
        )
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest) = WireResponse(
                200,
                listOf(RawHeader("Content-Type", "text/event-stream")),
                frames.joinToString("") { "event: message\ndata: $it\n\n" }.toByteArray(),
            )
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        val repo = CoreAgentConversationRepository(
            client(transport),
            { sampleAgent.copy(capabilities = sampleAgent.capabilities.copy(approvals = true)) },
            { "s_1" },
            backgroundScope,
            StandardTestDispatcher(testScheduler),
            supportsApprovalCards = true,
        )
        val events = mutableListOf<VerifiedConversationEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.observeEvents(ConversationScope("p1", "https://core", "a1", "i1")).toList(events)
        }

        repo.submitMessage("s_1", OutgoingMessage(ClientMessageId("client_fallback"), "run command"))
        runCurrent()

        val fallback = events.filterIsInstance<VerifiedConversationEvent.ApprovalTextFallback>().single()
        assertEquals("rm -rf /tmp/dangerous_test_backup", fallback.command)
        assertTrue("/approve" in fallback.commands)
        assertTrue("/deny" in fallback.commands)
        assertEquals(GenerationState.COMPLETED, repo.generationState.value)
        assertTrue(events.filterIsInstance<VerifiedConversationEvent.MessageStatus>().none {
            it.status == com.openandroidintelligence.conversation.ports.AgentMessageStatus.FAILED
        })
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
        val ssePayload = "event: message\ndata: {\"type\":\"delta\",\"text\":\"Hello World!\"}\n\nevent: message\ndata: {\"type\":\"done\"}\n\n"
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                return WireResponse(200, listOf(RawHeader("Content-Type", "text/event-stream")), ssePayload.toByteArray())
            }
            override fun eventStream(request: WireRequest) = kotlinx.coroutines.flow.flowOf(ssePayload.toByteArray())
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

    @Test
    fun streamingSnapshotsCarryFreshContentAndEndWithTheFinalText() = runTest {
        val deltas = listOf("你", "好", "", "世界")
        val ssePayload = deltas.joinToString("") { """event: message
data: {"type":"delta","text":"$it"}

""" } + """event: message
data: {"type":"done","outcome":"completed","text":"你好世界"}

"""
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                return WireResponse(200, listOf(RawHeader("Content-Type", "text/event-stream")), ssePayload.toByteArray())
            }
            override fun eventStream(request: WireRequest) = kotlinx.coroutines.flow.flowOf(ssePayload.toByteArray())
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
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.observeEvents(ConversationScope("p1", "http://core", "a1", "i1")).toList(emitted)
        }

        repo.submitMessage("s_1", OutgoingMessage(ClientMessageId("c_stream_test"), "测试问答"))
        runCurrent()
        job.cancel()

        val texts = emitted.filterIsInstance<VerifiedConversationEvent.TimelineUpsert>()
            .map { it.message }
            .filter { it.sender == "assistant" }
            .map { (it.parts.single() as com.openandroidintelligence.conversation.model.MessagePart.Text).value }

        // 断言：
        // 1) texts 至少包含帧
        assertTrue("Should receive streaming snapshots", texts.isNotEmpty())
        // 2) 每一帧都是前一帧的超集（单调递增，当前帧以过去一帧开头）
        for (i in 1 until texts.size) {
            assertTrue("Snapshot frame must extend previous frame: ${texts[i-1]} -> ${texts[i]}",
                texts[i].startsWith(texts[i - 1]))
        }
        // 3) 不存在相邻两帧文本完全相同（去重有效）
        for (i in 1 until texts.size) {
            assertTrue("No adjacent snapshots should have identical text: ${texts[i]}", texts[i] != texts[i - 1])
        }
        // 4) 最后一帧等于完整正文
        assertEquals("你好世界", texts.last())
    }

    @Test fun aSendDuringAnActiveTurnIsQueuedAndSentAfterwards() = runTest {
        val releaseFirst = CompletableDeferred<Unit>()
        val posts = mutableListOf<String>()
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("stream only")
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
            override suspend fun <T> consumeResponse(
                request: WireRequest,
                consume: suspend (Int, List<RawHeader>, InputStream) -> T,
            ): T {
                posts += request.target
                val index = posts.size
                if (index == 1) releaseFirst.await()
                val text = if (index == 1) "第一轮" else "第二轮"
                val frames = """{"type":"delta","text":"$text"}""" + "\n" + """{"type":"done","outcome":"completed"}"""
                val body = frames.split("\n").filter { it.isNotBlank() }
                    .joinToString("") { "event: message\ndata: $it\n\n" }
                return consume(200, listOf(RawHeader("Content-Type", "text/event-stream")), ByteArrayInputStream(body.toByteArray()))
            }
        }
        val repo = CoreAgentConversationRepository(client(transport), { sampleAgent }, { "s_1" },
            backgroundScope, StandardTestDispatcher(testScheduler))

        repo.submitMessage("s_1", OutgoingMessage(ClientMessageId("cm_one"), "第一条"))
        runCurrent()
        assertEquals("首轮请求应已开始", 1, posts.size)

        // 第一轮还没结束时发送第二条：必须排队，而不是把 SESSION_BUSY 抛给用户。
        val second = repo.submitMessage("s_1", OutgoingMessage(ClientMessageId("cm_two"), "第二条", delivery = MessageDeliveryMode.QUEUE))
        assertEquals("cm_two", second.messageId)
        assertEquals("首轮结束前排队的消息不能并发发送", 1, posts.size)

        releaseFirst.complete(Unit)
        // runCurrent 会级联执行本轮结束后的排队发送（与其它 SSE 测试的驱动方式一致）。
        runCurrent()
        assertEquals("首轮结束后应自动发出排队的第二条", 2, posts.size)
    }

    @Test fun aSlashCommandSkipsTheQueueSoAnApprovalCanBeAnswered() = runTest {
        val bodies = mutableListOf<String>()
        val releaseFirst = CompletableDeferred<Unit>()
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                assertTrue(request.target.endsWith("/steer"))
                bodies += request.body.toString(Charsets.UTF_8)
                return WireResponse(200, listOf(RawHeader("Content-Type", "application/json")),
                    """{"steering":{"generationId":"host_current","outcome":"accepted"}}""".toByteArray())
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
            override suspend fun <T> consumeResponse(
                request: WireRequest,
                consume: suspend (Int, List<RawHeader>, InputStream) -> T,
            ): T {
                bodies += request.body.toString(Charsets.UTF_8)
                if (bodies.size == 1) releaseFirst.await()
                val frames = """{"type":"done","outcome":"completed"}"""
                val body = "event: message\ndata: $frames\n\n"
                return consume(200, listOf(RawHeader("Content-Type", "text/event-stream")), ByteArrayInputStream(body.toByteArray()))
            }
        }
        val repo = CoreAgentConversationRepository(client(transport), { sampleAgent }, { "s_1" },
            backgroundScope, StandardTestDispatcher(testScheduler))

        repo.submitMessage("s_1", OutgoingMessage(ClientMessageId("cm_one"), "第一条"))
        runCurrent()
        assertEquals(1, bodies.size)

        // 生成中回复 /approve：必须立刻发出，否则原轮次一直卡在审批上。
        repo.submitMessage("s_1", OutgoingMessage(ClientMessageId("cm_approve"), "/approve once"))
        runCurrent()
        assertEquals("命令应插队直发", 2, bodies.size)
        assertTrue("插队发送的应是命令本身", bodies[1].contains("/approve"))

        releaseFirst.complete(Unit)
        runCurrent()
        assertEquals(2, bodies.size)
    }

    @Test fun steeringReachesTheHostWithoutALocalTurnOrAdvertisedCapability() = runTest {
        val requests = mutableListOf<WireRequest>()
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                requests += request
                return WireResponse(200, listOf(RawHeader("Content-Type", "application/json")),
                    """{"steering":{"generationId":"host_current","outcome":"accepted"}}""".toByteArray())
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        val repo = CoreAgentConversationRepository(client(transport),
            { sampleAgent.copy(capabilities = sampleAgent.capabilities.copy(steer = false)) },
            { "s_1" }, backgroundScope, StandardTestDispatcher(testScheduler))
        val text = " \t/steer 保留原文\n'quoted'  "
        val accepted = repo.steerMessage("s_1", OutgoingMessage(ClientMessageId("cm_immediate"), text,
            delivery = MessageDeliveryMode.STEER))
        assertEquals(MessageDeliveryMode.STEER, accepted.delivery)
        assertEquals("host_current", accepted.generationId)
        assertEquals("/open-android-intelligence/v2/agents/hermes/sessions/s_1/steer", requests.single().target)
        val json = com.openandroidintelligence.gateway.schema.JsonFields.obj(
            com.openandroidintelligence.gateway.schema.Json.parse(requests.single().body.decodeToString()))
        assertEquals(text, com.openandroidintelligence.gateway.schema.JsonFields.string(json, "text"))
        assertEquals(null, com.openandroidintelligence.gateway.schema.JsonFields.field(json, "generationId"))
        assertTrue(repo.pendingDeliveries("s_1").isEmpty())
    }

    @Test fun queueAndSteeringUseDifferentHostPathsAndKeepTheirOrder() = runTest {
        val messageBodies = mutableListOf<String>()
        val steerBodies = mutableListOf<String>()
        val releaseFirst = CompletableDeferred<Unit>()
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                if (request.target.endsWith("/steer")) {
                    val body = request.body.toString(Charsets.UTF_8)
                    steerBodies += body
                    val generationId = Regex(""""generationId":"([^"]+)"""")
                        .find(body)?.groupValues?.get(1) ?: "host_current"
                    return WireResponse(
                        200,
                        listOf(RawHeader("Content-Type", "application/json")),
                        """{"steering":{"generationId":"$generationId","outcome":"accepted"}}""".toByteArray(),
                    )
                }
                error("stream only")
            }

            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
            override suspend fun <T> consumeResponse(
                request: WireRequest,
                consume: suspend (Int, List<RawHeader>, InputStream) -> T,
            ): T {
                messageBodies += request.body.toString(Charsets.UTF_8)
                val index = messageBodies.size
                if (index == 1) {
                    releaseFirst.await()
                }
                val frames = "event: message\ndata: {\"type\":\"done\",\"outcome\":\"completed\"}\n\n"
                return consume(
                    200,
                    listOf(RawHeader("Content-Type", "text/event-stream")),
                    ByteArrayInputStream(frames.toByteArray()),
                )
            }
        }
        val repo = CoreAgentConversationRepository(
            client(transport),
            { sampleAgent.copy(kind = "codex", capabilities = sampleAgent.capabilities.copy(steer = false)) },
            { "s_1" },
            backgroundScope,
            StandardTestDispatcher(testScheduler),
        )

        repo.submitMessage("s_1", OutgoingMessage(ClientMessageId("cm_first"), "first"))
        runCurrent()
        assertEquals(1, messageBodies.size)

        val queued = repo.submitMessage(
            "s_1",
            OutgoingMessage(ClientMessageId("cm_queue"), "queued follow-up", delivery = MessageDeliveryMode.QUEUE),
        )
        assertEquals(MessageDeliveryMode.QUEUE, queued.delivery)
        repo.submitMessage(
            "s_1",
            OutgoingMessage(ClientMessageId("cm_queue_two"), "second queued follow-up", delivery = MessageDeliveryMode.QUEUE),
        )
        assertEquals(
            listOf("cm_queue", "cm_queue_two"),
            repo.pendingDeliveries("s_1").map { it.clientMessageId.value },
        )
        assertEquals("queue must not post a second turn", 1, messageBodies.size)

        val steered = repo.steerMessage(
            "s_1",
            OutgoingMessage(ClientMessageId("cm_steer"), "check the failing test first", delivery = MessageDeliveryMode.STEER),
        )
        assertEquals(MessageDeliveryMode.STEER, steered.delivery)
        assertEquals("host_current", steered.generationId)
        assertEquals("steering must not create a message turn", 1, messageBodies.size)
        assertEquals(1, steerBodies.size)
        assertTrue(steerBodies.single().contains("check the failing test first"))
        assertFalse("the server resolves a round the client has not observed", steerBodies.single().contains("generationId"))

        releaseFirst.complete(Unit)
        runCurrent()
        assertEquals("queued text sends only after the first turn finishes", 3, messageBodies.size)
        assertTrue(messageBodies[1].contains("queued follow-up"))
        assertTrue(messageBodies[2].contains("second queued follow-up"))
        assertTrue(repo.pendingDeliveries("s_1").isEmpty())
        assertEquals("steering must never be replayed as a queued message", 1, steerBodies.size)
    }

    @Test fun reconnectDoesNotDrainQueueUntilTheHostGenerationSnapshotIsIdle() = runTest {
        val messageBodies = mutableListOf<String>()
        val releaseFirst = CompletableDeferred<Unit>()
        var generationState: String? = "running"
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                if (request.target.endsWith("/generation")) {
                    val generation = generationState?.let {
                        """{"generationId":"gen_live","conversationId":"s_1","state":"$it"}"""
                    } ?: "null"
                    return WireResponse(
                        200,
                        listOf(RawHeader("Content-Type", "application/json")),
                        """{"generation":$generation}""".toByteArray(),
                    )
                }
                error("stream only")
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
            override suspend fun <T> consumeResponse(
                request: WireRequest,
                consume: suspend (Int, List<RawHeader>, InputStream) -> T,
            ): T {
                messageBodies += request.body.toString(Charsets.UTF_8)
                if (messageBodies.size == 1) {
                    releaseFirst.await()
                    throw IOException("stream interrupted")
                }
                return consume(
                    200,
                    listOf(RawHeader("Content-Type", "text/event-stream")),
                    ByteArrayInputStream(
                        "event: message\ndata: {\"type\":\"done\",\"outcome\":\"completed\"}\n\n".toByteArray(),
                    ),
                )
            }
        }
        val repo = CoreAgentConversationRepository(
            client(transport),
            { sampleAgent },
            { "s_1" },
            backgroundScope,
            StandardTestDispatcher(testScheduler),
        )
        repo.submitMessage("s_1", OutgoingMessage(ClientMessageId("cm_active"), "active"))
        runCurrent()
        repo.submitMessage(
            "s_1",
            OutgoingMessage(ClientMessageId("cm_waiting"), "wait for a safe turn", delivery = MessageDeliveryMode.QUEUE),
        )
        assertEquals(1, messageBodies.size)

        releaseFirst.complete(Unit)
        runCurrent()
        assertEquals(GenerationState.OUTCOME_UNKNOWN, repo.generationState.value)
        assertEquals(1, repo.pendingDeliveries("s_1").size)
        assertEquals(1, messageBodies.size)

        repo.retryPendingDeliveries("s_1")
        runCurrent()
        assertEquals("running generation keeps the queue parked", 1, messageBodies.size)

        generationState = null
        repo.retryPendingDeliveries("s_1")
        runCurrent()
        assertEquals(2, messageBodies.size)
        assertTrue(messageBodies[1].contains("wait for a safe turn"))
        assertTrue(repo.pendingDeliveries("s_1").isEmpty())
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
