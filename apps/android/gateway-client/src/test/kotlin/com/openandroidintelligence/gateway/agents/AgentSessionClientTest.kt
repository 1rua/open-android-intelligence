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

    @Test fun durableGenerationReceiptsRemainQueryableOfflineAndKeepExactIdentity() = runBlocking {
        val recoverable = agent.copy(online = false, capabilities = agent.capabilities.copy(generationRecovery = true))
        val transport = RecordingTransport("""{"generation":{"generationId":"gen_old","conversationId":"native","clientMessageId":"cm_old","userMessageId":"user_old","state":"completed","revision":9}}""")
        val receipt = client(transport).generation(recoverable, "native", "cm_old", "gen_old", 8, 25000)
        assertEquals("completed", receipt.state)
        assertEquals(9, receipt.revision)
        assertEquals("GET", transport.requests.single().method)
        assertEquals("${AgentSessionClient.ROOT}/codex/sessions/native/generation?afterRevision=8&clientMessageId=cm_old&generationId=gen_old&waitMs=25000", transport.requests.single().target)
        assertTrue(transport.requests.single().body.isEmpty())
    }

    @Test fun generationRecoveryRejectsForeignOrMalformedReceipts() = runBlocking {
        val recoverable = agent.copy(capabilities = agent.capabilities.copy(generationRecovery = true))
        for (bad in listOf("\"conversationId\":\"other\"", "\"clientMessageId\":\"other\"", "\"generationId\":\"other\"", "\"revision\":-1", "\"state\":\"invented\"")) {
            val base = """{"generationId":"gen_old","conversationId":"native","clientMessageId":"cm_old","userMessageId":"user_old","state":"completed","revision":9}"""
            val key = bad.substringBefore(':')
            val fields = base.removeSurrounding("{", "}").split(',').filterNot { it.startsWith(key) } + bad
            val transport = RecordingTransport("{\"generation\":{${fields.joinToString(",")}}}")
            assertEquals("AGENT_RESPONSE_INVALID", runCatching {
                client(transport).generation(recoverable, "native", "cm_old", "gen_old")
            }.exceptionOrNull()?.message)
        }
    }

    @Test fun nativeDeltaIdentityRejectsReplayedEventsAndLowerContentRevisionsWithoutMergingRealMessages() = runBlocking {
        val frames = listOf(
            """{"type":"generation","generationId":"g","userMessageId":"u","clientMessageId":"cm"}""",
            """{"type":"delta","messageId":"stream_real_one","eventId":"evt_two","contentRevision":2,"text":"A"}""",
            """{"type":"delta","messageId":"stream_real_one","eventId":"evt_two","contentRevision":2,"text":"A"}""",
            """{"type":"delta","messageId":"stream_real_one","eventId":"evt_old","contentRevision":1,"text":"stale"}""",
            """{"type":"delta","messageId":"stream_real_one","eventId":"evt_three","contentRevision":3,"text":"B"}""",
            """{"type":"delta","messageId":"stream_real_one","eventId":"evt_three","contentRevision":4,"text":"replayed"}""",
            """{"type":"delta","messageId":"stream_real_two","eventId":"evt_four","contentRevision":1,"text":"AB"}""",
            """{"type":"delta","messageId":"stream_real_two","eventId":"evt_four","contentRevision":1,"text":"AB"}""",
            """{"type":"done","messageId":"stream_real_two","eventId":"evt_four","contentRevision":1,"generationId":"g","text":"AB","outcome":"completed"}""",
        )
        val output = linkedMapOf<String, StringBuilder>()
        var messageId: String? = null
        val result = client(streamTransport(frames)).send(agent, "native", "synthetic", clientMessageId = "cm",
            onEvent = { if (it is NativeAgentStreamEvent.MessageIdentity) messageId = it.messageId }) {
            output.getOrPut(requireNotNull(messageId)) { StringBuilder() }.append(it)
        }
        assertEquals(mapOf("stream_real_one" to "AB", "stream_real_two" to "AB"), output.mapValues { it.value.toString() })
        assertEquals("completed", result.outcome)
        assertEquals("AB", result.text)
    }

    @Test fun oldDoneContentCannotReplaceANewerMessageButStillConfirmsTheLifecycle() = runBlocking {
        val events = mutableListOf<NativeAgentStreamEvent>()
        val output = StringBuilder()
        val result = client(streamTransport(listOf(
            """{"type":"generation","generationId":"g"}""",
            """{"type":"delta","messageId":"answer","eventId":"new","contentRevision":2,"text":"newer"}""",
            """{"type":"done","generationId":"g","messageId":"answer","eventId":"old","contentRevision":1,"text":"stale","outcome":"completed"}""",
        ))).send(agent, "native", "synthetic", onEvent = { events += it }) { output.append(it) }
        assertEquals("newer", output.toString())
        assertEquals("completed", result.outcome)
        assertNull("A lower-version final snapshot cannot overwrite newer accepted text", result.text)
        assertEquals(2L, events.filterIsInstance<NativeAgentStreamEvent.MessageIdentity>().last().contentRevision)
    }

    @Test fun eventOnlyReplayBudgetStopsObservationInsteadOfEvictingIdentityProtection() = runBlocking {
        val frames = (1..4097).map { """{"type":"delta","messageId":"answer","eventId":"evt_$it","text":"x"}""" } +
            """{"type":"done","messageId":"answer","text":"complete"}"""
        try {
            client(streamTransport(frames)).send(agent, "native", "synthetic") {}
            fail("A bounded replay ledger must reconcile rather than forget unversioned event identities")
        } catch (failure: AgentSessionException) {
            assertEquals("OUTCOME_UNKNOWN", failure.code)
        }
    }

    @Test fun legacyDeltasWithoutReliableMetadataKeepTheirOriginalAppendSemantics() = runBlocking {
        val output = StringBuilder()
        client(streamTransport(listOf("""{"type":"delta","text":"same"}""",
            """{"type":"delta","text":"same"}""", """{"type":"done","text":"samesame"}""")))
            .send(agent, "native", "synthetic") { output.append(it) }
        assertEquals("samesame", output.toString())
    }

    @Test fun generationQueryEncodesNativeIdentityAsAQueryValueWhileKeepingCanonicalSignedOrder() = runBlocking {
        val nativeGeneration = "gen/one?x=中 &"
        val recoverable = agent.copy(capabilities = agent.capabilities.copy(generationRecovery = true))
        val transport = RecordingTransport("""{"generation":{"generationId":"$nativeGeneration","conversationId":"native","clientMessageId":"cm","userMessageId":"u","state":"completed","revision":2}}""")
        val receipt = client(transport).generation(recoverable, "native", "cm", nativeGeneration, 1, 25000)
        assertEquals(nativeGeneration, receipt.generationId)
        assertEquals("${AgentSessionClient.ROOT}/codex/sessions/native/generation?afterRevision=1&clientMessageId=cm&generationId=gen%2Fone%3Fx%3D%E4%B8%AD%20%26&waitMs=25000", transport.requests.single().target)
    }

    @Test fun terminalGenerationKeepsItsDurableLifecycleRevisionApartFromMessageContent() = runBlocking {
        val result = client(streamTransport(listOf(
            """{"type":"generation","generationId":"g","state":"queued","revision":0}""",
            """{"type":"generation","generationId":"g","state":"running","revision":1}""",
            """{"type":"delta","generationId":"g","messageId":"answer","eventId":"content-99","contentRevision":99,"text":"answer"}""",
            """{"type":"done","generationId":"g","messageId":"answer","eventId":"content-99","contentRevision":99,"revision":2,"text":"answer","outcome":"completed"}""",
        ))).send(agent, "native", "synthetic") {}
        assertEquals(2L, result.revision)
        assertEquals("completed", result.outcome)
    }

    @Test fun generationLifecycleKeepsQueuedRunningAndToolRevisionsIndependent() = runBlocking {
        val observed = mutableListOf<NativeAgentStreamEvent.Generation>()
        val frames = listOf(
            """{"type":"generation","generationId":"g","state":"queued","revision":1,"clientMessageId":"cm","userMessageId":"u"}""",
            """{"type":"generation","generationId":"g","state":"running","revision":2,"clientMessageId":"cm","userMessageId":"u","eventId":"received"}""",
            """{"type":"generation","generationId":"g","state":"queued","revision":1,"clientMessageId":"cm","userMessageId":"u"}""",
            """{"type":"generation","generationId":"g","executionRevision":30,"eventId":"tools","executionSteps":[{"stepId":"call","eventId":"native:2","toolName":"read","arguments":"{}","status":"running","revision":30}]}""",
            """{"type":"done","generationId":"g","outcome":"completed"}""",
        )
        client(streamTransport(frames)).send(agent, "native", "synthetic", clientMessageId = "cm", onEvent = {
            if (it is NativeAgentStreamEvent.Generation) observed += it
        }) {}
        assertEquals(listOf("queued", "running"), observed.mapNotNull { it.state })
        assertEquals(listOf(1L, 2L), observed.filter { it.state != null }.map { it.revision })
        assertEquals(30L, observed.single { it.executionSteps != null }.executionRevision)
        assertNull(observed.single { it.executionSteps != null }.state)
    }

    @Test fun nativeExecutionSnapshotsKeepTheirOwnRevisionAndDoNotResetAssistantContentIdentity() = runBlocking {
        val frames = listOf(
            """{"type":"generation","generationId":"g","userMessageId":"u","clientMessageId":"cm"}""",
            """{"type":"delta","generationId":"g","messageId":"answer","eventId":"text_one","contentRevision":9,"text":"private body"}""",
            """{"type":"generation","generationId":"g","userMessageId":"u","clientMessageId":"cm","eventId":"tool_one","executionRevision":1,"executionSteps":[{"stepId":"call","eventId":"tool_one","toolName":"read","arguments":"{}","output":null,"status":"running","revision":1}]}""",
            """{"type":"generation","generationId":"g","userMessageId":"u","clientMessageId":"cm","eventId":"tool_two","executionRevision":2,"executionSteps":[{"stepId":"call","eventId":"tool_two","toolName":"read","arguments":"{}","output":"synthetic output","status":"completed","revision":2}]}""",
            """{"type":"done","generationId":"g","messageId":"answer","eventId":"text_one","contentRevision":9,"text":"private body"}""",
        )
        val events = mutableListOf<NativeAgentStreamEvent>()
        val result = client(streamTransport(frames)).send(agent, "native", "synthetic", clientMessageId = "cm", onEvent = { events += it }) {}
        val tools = events.filterIsInstance<NativeAgentStreamEvent.Generation>().filter { it.executionSteps != null }
        assertEquals(listOf(1L, 2L), tools.map { it.executionRevision })
        assertEquals(listOf("running", "completed"), tools.map { it.executionSteps!!.single().status })
        assertTrue("Tool snapshots must not reset the live assistant content version",
            events.filterIsInstance<NativeAgentStreamEvent.MessageIdentity>().filter { it.messageId == "answer" }.all { it.contentRevision == 9L })
        assertEquals("answer", result.messageId)
    }

    @Test fun networkEvidenceRecordsOnlyTheFirstDeltaAndControlBoundariesWithoutPrivateBody() = runBlocking {
        val previousEnabled = com.openandroidintelligence.gateway.diagnostics.GatewayLog.protocolEvidenceEnabled
        val previousSink = com.openandroidintelligence.gateway.diagnostics.GatewayLog.sink
        val logs = mutableListOf<String>()
        com.openandroidintelligence.gateway.diagnostics.GatewayLog.protocolEvidenceEnabled = true
        com.openandroidintelligence.gateway.diagnostics.GatewayLog.sink = { _, message -> logs += message }
        try {
            val frames = listOf("""{"type":"generation","generationId":"g","clientMessageId":"cm"}""") +
                (1..20).map { """{"type":"delta","messageId":"answer","eventId":"evt_$it","contentRevision":$it,"text":"private token"}""" } +
                """{"type":"done","generationId":"g","messageId":"answer","text":"private token"}"""
            client(streamTransport(frames)).send(agent, "native", "synthetic", clientMessageId = "cm") {}
            assertEquals(1, logs.count { "T1.request_started" in it })
            assertEquals(3, logs.count { "T6.sse_read" in it })
            assertFalse(logs.any { "private token" in it || "Bearer access" in it })
        } finally {
            com.openandroidintelligence.gateway.diagnostics.GatewayLog.protocolEvidenceEnabled = previousEnabled
            com.openandroidintelligence.gateway.diagnostics.GatewayLog.sink = previousSink
        }
    }

    private fun streamTransport(frames: List<String>) = RecordingTransport(
        frames.joinToString("") { "event: message\ndata: $it\n\n" }, "text/event-stream")

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
