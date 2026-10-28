package com.openandroidintelligence.gateway.agents

import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.GatewayByteTransport
import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.GatewayProfile
import com.openandroidintelligence.gateway.http.GatewayTransport
import com.openandroidintelligence.gateway.http.RawHeader
import com.openandroidintelligence.gateway.http.WireRequest
import com.openandroidintelligence.gateway.http.WireResponse
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AgentSessionClientTest {
    private val agent = CoreAgent("codex", "Codex", "codex", true, "deployment",
        AgentCapabilities(list = true, history = true, create = true, send = true, stream = true))

    @Test fun pureAttachmentsAndMixedMessagesKeepIdsAndNativeReceipts() = runBlocking {
        for (text in listOf("", "看看这些文件")) {
            val transport = RecordingTransport("event: message\ndata: {\"type\":\"delta\",\"text\":\"收到\",\"messageId\":\"native-answer\",\"userMessageId\":\"native-question\",\"clientMessageId\":\"cm_files\"}\n\nevent: message\ndata: {\"type\":\"done\",\"outcome\":\"completed\"}\n\n", "text/event-stream")
            val identities = mutableListOf<NativeAgentStreamEvent>()
            val result = client(transport).send(agent.copy(capabilities = agent.capabilities.copy(attachments = true)),
                "native", text, listOf("att_image", "att_docx"), "cm_files", onEvent = { identities += it }) {}
            val payload = com.openandroidintelligence.gateway.schema.JsonFields.obj(
                com.openandroidintelligence.gateway.schema.Json.parse(transport.requests.single().body.decodeToString()))!!
            assertEquals(listOf("att_image", "att_docx"), com.openandroidintelligence.gateway.schema.JsonFields.strings(payload, "attachmentIds"))
            assertEquals(text, com.openandroidintelligence.gateway.schema.JsonFields.string(payload, "text"))
            assertEquals("cm_files", com.openandroidintelligence.gateway.schema.JsonFields.string(payload, "clientMessageId"))
            assertEquals("native-answer", result.messageId)
            assertEquals("native-question", result.userMessageId)
            assertEquals(1, identities.filterIsInstance<NativeAgentStreamEvent.MessageIdentity>().size)
        }
    }

    @Test fun attachmentsRequireAnExplicitCapabilityAndEmptyMessagesHaveAnAccurateError() = runBlocking {
        val transport = RecordingTransport("{}")
        assertEquals("UNSUPPORTED_CAPABILITY", runCatching { client(transport).send(agent, "native", "", listOf("att_image")) {} }.exceptionOrNull()?.message)
        assertEquals("INVALID_MESSAGE", runCatching { client(transport).send(agent, "native", "") {} }.exceptionOrNull()?.message)
        assertEquals("AGENT_OFFLINE", runCatching { client(transport).send(agent.copy(online = false), "native", "hello") {} }.exceptionOrNull()?.message)
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun numericHistoryDatesAndAttachmentMetadataSurviveDecoding() = runBlocking {
        val transport = RecordingTransport("""{"messages":[{"id":"m1","role":"user","text":"","createdAt":1780000000.123,"clientMessageId":"cm_files","attachments":[{"attachmentId":"att_docx","filename":"说明.docx","mediaType":"application/vnd.openxmlformats-officedocument.wordprocessingml.document","sizeBytes":123,"sha256":"sha256:${"a".repeat(64)}"}]}]}""")
        val message = client(transport).history(agent, "native").single()
        assertEquals("1780000000.123", message.createdAt)
        assertEquals("cm_files", message.clientMessageId)
        assertEquals("说明.docx", message.attachments.single().filename)
    }

    @Test fun discoveryUsesTheAuthenticatedCoreAndMissingCapabilitiesStayDisabled() = runBlocking {
        val transport = RecordingTransport("""{"agents":[{"id":"codex","name":"Codex","kind":"codex","online":false,"sessionScope":"deployment","capabilities":{"list":true,"history":true}}]}""")
        val agents = client(transport).agents()
        assertEquals("deployment", agents.single().sessionScope)
        assertFalse(agents.single().online)
        assertFalse(agents.single().capabilities.send)
        assertEquals(AgentSessionClient.ROOT, transport.requests.single().target)
        assertTrue(transport.requests.single().headers.any { it.name == "Authorization" && it.value == "Bearer access" })
    }

    @Test fun readOnlyAdaptersRejectMutationsBeforeSendingAnyRequest() = runBlocking {
        val transport = RecordingTransport("{}")
        val client = client(transport)
        val readOnly = agent.copy(capabilities = AgentCapabilities(list = true, history = true))
        val actions = listOf<suspend () -> Unit>(
            { client.create(readOnly); Unit }, { client.rename(readOnly, "native", "title") },
            { client.archive(readOnly, "native") }, { client.delete(readOnly, "native") },
            { client.cancel(readOnly, "native") }, { client.send(readOnly, "native", "hello") {} },
        )
        actions.forEach { assertEquals("UNSUPPORTED", runCatching { it() }.exceptionOrNull()?.message) }
        assertTrue(transport.requests.isEmpty())
    }

    @Test fun historyPreservesNativeIdentityAndNeverAddsAnAccountToThePayload() = runBlocking {
        val transport = RecordingTransport("""{"messages":[{"id":"native-message","role":"assistant","text":"terminal history","createdAt":"2026-10-25T01:00:00Z"}]}""")
        val messages = client(transport).history(agent, "native session 中文")
        assertEquals("native-message", messages.single().id)
        assertEquals("terminal history", messages.single().text)
        assertEquals(AgentSessionClient.ROOT + "/codex/sessions/native%20session%20%E4%B8%AD%E6%96%87/history", transport.requests.single().target)
        assertTrue(transport.requests.single().body.isEmpty())
        assertEquals("AGENT_IDENTIFIER_INVALID", runCatching { client(transport).history(agent, "../foreign") }.exceptionOrNull()?.message)
        assertEquals(1, transport.requests.size)
    }

    @Test fun anIncompleteSseStreamReportsAnUnknownOutcomeWithoutRetrying() = runBlocking {
        val transport = RecordingTransport("event: message\ndata: {\"type\":\"delta\",\"text\":\"partial\"}\n\n", "text/event-stream")
        val partial = StringBuilder()
        val failure = runCatching { client(transport).send(agent, "native", "hello") { partial.append(it) } }.exceptionOrNull()
        assertEquals("partial", partial.toString())
        assertEquals("OUTCOME_UNKNOWN", failure?.message)
        assertEquals(1, transport.requests.size)
        val request = transport.requests.single()
        assertEquals("""{"text":"hello"}""", request.body.decodeToString())
        assertEquals(request.headers.single { it.name == "Idempotency-Key" }.value,
            request.headers.single { it.name == "X-Open-Android-Intelligence-Request-Id" }.value)
    }

    @Test fun aRejectedOversizedMessageSurfacesTheJsonReasonBeforeAnySseBegins() = runBlocking {
        val transport = RecordingTransport("""{"error":{"code":"MESSAGE_TOO_LARGE"}}""", status = 413)
        val partial = StringBuilder()
        val failure = runCatching { client(transport).send(agent, "native", "hello") { partial.append(it) } }
            .exceptionOrNull() as? AgentSessionException
        assertEquals("MESSAGE_TOO_LARGE", failure?.code)
        assertEquals(413, failure?.status)
        assertEquals("", partial.toString())
        val request = transport.requests.single()
        assertEquals("POST", request.method)
        assertEquals(AgentSessionClient.ROOT + "/codex/sessions/native/messages", request.target)
        assertEquals("text/event-stream", request.headers.single { it.name == "Accept" }.value)
    }

    @Test fun aRejectedResponseCannotMakeTheStreamingClientBufferAnUnboundedErrorBody() = runBlocking {
        var readBytes = 0
        var sends = 0
        val body = """{"error":{"code":"MESSAGE_TOO_LARGE"},"details":"${"x".repeat(1_048_576)}"}""".toByteArray()
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
            override suspend fun <T> consumeResponse(request: WireRequest,
                consume: suspend (Int, List<RawHeader>, InputStream) -> T): T {
                sends++
                val stream = object : ByteArrayInputStream(body) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        super.read(buffer, offset, length).also { if (it > 0) readBytes += it }
                }
                return consume(413, listOf(RawHeader("Content-Type", "application/json")), stream)
            }
        }
        val failure = runCatching { client(transport).send(agent, "native", "hello") { fail("Rejected requests have no delta") } }
            .exceptionOrNull() as? AgentSessionException
        assertEquals("AGENT_SEND_FAILED", failure?.code)
        assertEquals(413, failure?.status)
        assertTrue("Error parsing must stop after a small bounded prefix", readBytes in 1..16_384)
        assertEquals(1, sends)
    }

    @Test fun signedPostStreamsTheFirstDeltaBeforeTheServerFinishesItsResponse(): Unit = runBlocking {
        ServerSocket(0).use { server ->
            val firstDeltaConsumed = CompletableFuture<Unit>()
            val serverDone = CompletableFuture<String>()
            thread(isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 10_000
                        val input = socket.getInputStream()
                        val headerBytes = ArrayList<Byte>()
                        while (true) {
                            val byte = input.read()
                            check(byte >= 0)
                            headerBytes += byte.toByte()
                            if (headerBytes.size >= 4 && headerBytes.takeLast(4) == listOf(13, 10, 13, 10).map(Int::toByte)) break
                        }
                        val headers = headerBytes.toByteArray().decodeToString()
                        check(headers.startsWith("POST ${AgentSessionClient.ROOT}/codex/sessions/native/messages HTTP/1.1"))
                        val length = Regex("(?im)^content-length: (\\d+)").find(headers)!!.groupValues[1].toInt()
                        val body = input.readNBytes(length).decodeToString()
                        val output = socket.getOutputStream()
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\nevent: message\ndata: {\"type\":\"delta\",\"text\":\"你好\"}\n\n".toByteArray())
                        output.flush()
                        firstDeltaConsumed.get(10, TimeUnit.SECONDS)
                        output.write("event: message\ndata: {\"type\":\"done\"}\n\n".toByteArray())
                        output.flush()
                        serverDone.complete(body)
                    }
                } catch (failure: Throwable) { serverDone.completeExceptionally(failure) }
            }
            val profile = GatewayProfile("account", "device", "session", "http://127.0.0.1:${server.localPort}", accessToken = "access")
            val client = AgentSessionClient(GatewayHttpClient(profile, GatewayTransport(profile), { ByteArray(64) }, InMemoryEventCursorStore()))
            val output = StringBuilder()
            client.send(agent, "native", "hello") { output.append(it); firstDeltaConsumed.complete(Unit) }
            assertEquals("你好", output.toString())
            assertEquals("""{"text":"hello"}""", serverDone.get(10, TimeUnit.SECONDS))
        }
    }

    @Test fun aReadFailureAfterNativeOutputPreservesThePartialReplyAndNeverResends() = runBlocking {
        var sends = 0
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("unused")
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
            override suspend fun <T> consumeResponse(request: WireRequest,
                consume: suspend (Int, List<RawHeader>, InputStream) -> T): T {
                sends++
                val partial = ByteArrayInputStream("event: message\ndata: {\"type\":\"delta\",\"text\":\"partial\"}\n\n".toByteArray())
                val interrupted = object : InputStream() {
                    override fun read(): Int = partial.read().also { if (it < 0) throw IOException("connection interrupted") }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                        partial.read(buffer, offset, length).also { if (it < 0) throw IOException("connection interrupted") }
                }
                return consume(200, listOf(RawHeader("Content-Type", "text/event-stream")), interrupted)
            }
        }
        val partial = StringBuilder()
        val failure = runCatching { client(transport).send(agent, "native", "hello") { partial.append(it) } }.exceptionOrNull()
        assertEquals("OUTCOME_UNKNOWN", failure?.message)
        assertEquals("partial", partial.toString())
        assertEquals(1, sends)
    }

    @Test fun cancellingTheCallerIsNotConvertedIntoAnUnknownOutcome() = runBlocking {
        val cancelled = CancellationException("account closed")
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = throw cancelled
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        val failure = runCatching { client(transport).send(agent, "native", "hello") {} }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals("account closed", failure?.message)
    }

    @Test fun nativeApprovalFramesDoNotBreakTheDeltaStream() = runBlocking {
        val transport = RecordingTransport("""event: message
 data: {"type":"approval_requested","approval":{"approvalId":"apr_1","conversationId":"native","command":"echo hello","options":[{"choice":"once"},{"choice":"deny"}],"timeoutSeconds":60,"requestedAt":1}}

 event: message
 data: {"type":"delta","text":"approved"}

 event: message
 data: {"type":"done","outcome":"completed"}

""".replace("\n ", "\n"), "text/event-stream")
        val partial = StringBuilder()
        client(transport).send(agent, "native", "hello") { partial.append(it) }
        assertEquals("approved", partial.toString())
    }

    @Test fun discoveryIncludesCommandsAndApprovalsWithoutEnablingMissingFlags() = runBlocking {
        val transport = RecordingTransport("""{"agents":[{"id":"hermes","name":"Hermes","kind":"hermes","online":true,"sessionScope":"account","capabilities":{"commands":true,"approvals":true,"cancel":true,"steer":true}}]}""")
        val capabilities = client(transport).agents().single().capabilities
        assertTrue(capabilities.commands)
        assertTrue(capabilities.approvals)
        assertTrue(capabilities.cancel)
        assertTrue(capabilities.steer)
        assertFalse(capabilities.send)
    }

    @Test fun selectedAgentCommandsRetainDescriptionsAndArgumentHints() = runBlocking {
        val transport = RecordingTransport("""{"format":"agent-command-catalog-1.0","catalogVersion":"remote-12","languageCode":"zh-CN","commands":[{"id":"model","invocation":"/model","title":"模型","description":"选择宿主模型","acceptsArguments":true,"argumentHint":"<model>","availability":"available"}]}""")
        val hermes = agent.copy(id = "hermes", capabilities = agent.capabilities.copy(commands = true))
        val catalog = client(transport).commands(hermes, "zh-CN")
        assertEquals("/model", catalog.commands.single().invocation)
        assertEquals("选择宿主模型", catalog.commands.single().description)
        assertEquals("<model>", catalog.commands.single().argumentHint)
        assertEquals("${AgentSessionClient.ROOT}/hermes/commands?languageCode=zh-CN", transport.requests.single().target)
    }

    @Test fun steeringUsesItsOwnEndpointWithoutAClientCapabilityDecision() = runBlocking {
        val transport = RecordingTransport(
            """{"steering":{"generationId":"native-round","outcome":"accepted"}}""",
        )
        val steerable = agent.copy(
            id = "hermes",
            kind = "hermes",
            capabilities = agent.capabilities.copy(steer = false),
        )
        val result = client(transport).steer(
            steerable,
            "native",
            "native-round",
            "focus on the failing test",
            "cm_steer",
        )
        assertEquals("native-round", result.generationId)
        assertEquals(
            AgentSessionClient.ROOT + "/hermes/sessions/native/steer",
            transport.requests.single().target,
        )
        val body = transport.requests.single().body.decodeToString()
        assertTrue(body.contains("\"generationId\":\"native-round\""))
        assertTrue(body.contains("\"clientMessageId\":\"cm_steer\""))
        assertTrue(
            runCatching {
                client(RecordingTransport("{}")).steer(
                    agent,
                    "native",
                    "native-round",
                    "unsupported",
                    "cm_unsupported",
                )
            }.exceptionOrNull() is AgentSessionException,
        )
    }

    @Test fun aLostSteeringReplyStaysUnknownAndRetryKeepsTheSameOperationIdentity() = runBlocking {
        val requests = mutableListOf<WireRequest>()
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                requests += request
                throw IOException("reply lost after host admission")
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        repeat(2) {
            val failure = runCatching { client(transport).steer(agent, "native", null, "original input", "cm_same") }
                .exceptionOrNull()
            assertEquals("OUTCOME_UNKNOWN", (failure as? AgentSessionException)?.code)
        }
        assertEquals(2, requests.size)
        requests.forEach { request ->
            assertEquals("cm_same", request.headers.single { it.name.equals("Idempotency-Key", true) }.value)
        }
    }

    @Test fun currentGenerationDistinguishesIdleFromARunningTurn() = runBlocking {
        val idle = RecordingTransport("""{"generation":null}""")
        assertNull(client(idle).currentGeneration(agent, "native"))
        assertEquals(
            AgentSessionClient.ROOT + "/codex/sessions/native/generation",
            idle.requests.single().target,
        )

        val running = RecordingTransport(
            """{"generation":{"generationId":"gen_live","conversationId":"native","state":"running"}}""",
        )
        val current = client(running).currentGeneration(agent, "native")
        assertEquals("gen_live", current?.generationId)
        assertEquals("native", current?.conversationId)
        assertEquals("running", current?.state)
    }

    @Test fun cancellationNamesTheExactHostGenerationAndChecksItsReceipt() = runBlocking {
        val cancellable = agent.copy(id = "hermes", kind = "hermes", capabilities = agent.capabilities.copy(cancel = true))
        val transport = RecordingTransport("""{"session":{"outcome":"cancelled","generationId":"native-round"}}""")
        val result = client(transport).cancel(cancellable, "native", "native-round")
        assertEquals("cancelled", result.outcome)
        assertEquals("""{"generationId":"native-round"}""", transport.requests.single().body.decodeToString())
        val wrongRound = RecordingTransport("""{"session":{"outcome":"cancelled","generationId":"later-round"}}""")
        assertEquals("OUTCOME_UNKNOWN", runCatching { client(wrongRound).cancel(cancellable, "native", "native-round") }.exceptionOrNull()?.message)
    }

    @Test fun nativeApprovalDecisionsUseTheOriginalSessionAndKeepRecordedTerminalFacts() = runBlocking {
        val transport = RecordingTransport("""{"error":{"code":"APPROVAL_EXPIRED","details":{"decision":"timeout"}}}""", status = 409)
        val hermes = agent.copy(id = "hermes", capabilities = agent.capabilities.copy(approvals = true))
        val result = client(transport).submitApprovalDecision(hermes, "native", "apr_1",
            com.openandroidintelligence.gateway.approvals.ApprovalDecision.ONCE)
        assertEquals(com.openandroidintelligence.gateway.approvals.ApprovalDecisionOutcome.EXPIRED, result.outcome)
        assertEquals("timeout", result.rawDecision)
        assertEquals("${AgentSessionClient.ROOT}/hermes/sessions/native/approvals/apr_1/decisions", transport.requests.single().target)
        assertEquals("""{"decision":"once"}""", transport.requests.single().body.decodeToString())
    }

    @Test fun hostGenerationAndCancelledCompletionAreObservedBeforeTheStreamReturns() = runBlocking {
        val frames = listOf("""{"type":"generation","generationId":"native-round"}""",
            """{"type":"delta","text":"partial"}""",
            """{"type":"done","outcome":"cancelled","generationId":"native-round"}""")
        val transport = RecordingTransport(frames.joinToString("") { "event: message\ndata: $it\n\n" }, "text/event-stream")
        val events = mutableListOf<NativeAgentStreamEvent>()
        val text = StringBuilder()
        val result = client(transport).send(agent, "native", "hello", onEvent = { events += it }) { text.append(it) }
        assertEquals(NativeAgentStreamEvent.Generation("native-round"), events.first())
        assertEquals("cancelled", result.outcome)
        assertEquals("native-round", result.generationId)
        assertEquals("partial", text.toString())
    }

    @Test fun nonHermesCancellationKeepsTheSessionRequestAndDoesNotInventAStoppedOutcome() = runBlocking {
        for (kind in listOf("codex", "dsh")) {
            val legacy = agent.copy(id = kind, kind = kind, capabilities = agent.capabilities.copy(cancel = true))
            val transport = RecordingTransport("""{"session":{"id":"native","action":"cancel"}}""")
            val result = client(transport).cancel(legacy, "native", "local-correlation")
            assertEquals("{}", transport.requests.single().body.decodeToString())
            assertEquals("${AgentSessionClient.ROOT}/$kind/sessions/native/cancel", transport.requests.single().target)
            assertEquals("outcome_unknown", result.outcome)
        }
    }

    private fun client(transport: GatewayByteTransport) = AgentSessionClient(GatewayHttpClient(
        GatewayProfile("account", "device", "session", "https://core.example", accessToken = "access"),
        transport, { ByteArray(64) }, InMemoryEventCursorStore()))

    private class RecordingTransport(private val body: String, private val contentType: String = "application/json",
        private val status: Int = 200) : GatewayByteTransport {
        val requests = mutableListOf<WireRequest>()
        override suspend fun execute(request: WireRequest): WireResponse {
            requests += request
            return WireResponse(status, listOf(RawHeader("Content-Type", contentType)), body.toByteArray())
        }
        override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
    }
}
