package com.openandroidintelligence.mobile.conversations

import com.openandroidintelligence.conversation.model.ClientMessageId
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.model.StreamHealth
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.ports.MessageDeliveryMode
import com.openandroidintelligence.conversation.ports.OutgoingMessage
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.gateway.agents.AgentCapabilities
import com.openandroidintelligence.gateway.agents.AgentSessionClient
import com.openandroidintelligence.gateway.agents.CoreAgent
import com.openandroidintelligence.gateway.auth.GatewaySessionRenewal
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.GatewayByteTransport
import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.GatewayProfile
import com.openandroidintelligence.gateway.http.RawHeader
import com.openandroidintelligence.gateway.http.WireRequest
import com.openandroidintelligence.gateway.http.WireResponse
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeAuthenticationStreamRecoveryTest {
    @Test fun revokedCredentialRetainsTheUnsentQueueWithoutReadingOrPostingAgain() = runTest {
        val h = Harness(this)
        try {
            h.queuePair("a"); h.releaseFirst("a"); runCurrent()
            repeat(3) { h.repository.connectionRestored(); runCurrent() }
            advanceTimeBy(90_000); runCurrent()
            assertEquals(listOf("first-a"), h.postedClients("a"))
            assertEquals(0, h.firstGenerationReads("a"))
            assertEquals(1, h.repository.pendingDeliveries("chat").size)
            assertEquals(GenerationState.OUTCOME_UNKNOWN, h.repository.generationState.value)
            assertEquals(StreamHealth.FAILED, h.repository.streamHealth.first())
        } finally { h.close() }
    }

    @Test fun aLegitimateCredentialChangeAfterTheErrorRecoversTheOriginalIdentityOnce() = runTest {
        for (code in listOf("SESSION_REVOKED", "SESSION_EXPIRED")) {
            val h = Harness(this, errorCode = code)
            try {
                h.queuePair("a"); h.releaseFirst("a"); runCurrent()
                assertEquals("旧凭据不能继续读取：$code", 0, h.firstGenerationReads("a"))
                h.rotateCredential(); h.repository.connectionRestored(); runCurrent()
                repeat(3) { h.repository.connectionRestored(); runCurrent() }
                assertEquals(listOf("first-a", "next-a"), h.postedClients("a"))
                assertTrue(h.repository.pendingDeliveries("chat").isEmpty())
                assertTrue(h.firstGenerationReads("a") > 0)
                assertTrue(h.requests.filter { it.target.contains("clientMessageId=first-a") }.all {
                    it.target.contains("generationId=g-a-first-a") && h.sessionOf(it) == "credential-new"
                })
            } finally { h.close() }
        }
    }

    @Test fun aCredentialChangedBeforeTheOldStreamFailsDoesNotGetMistakenForTheRejectedOne() = runTest {
        val h = Harness(this)
        try {
            h.queuePair("a")
            h.rotateCredential()
            h.releaseFirst("a"); runCurrent()
            assertEquals(listOf("first-a", "next-a"), h.postedClients("a"))
            assertTrue(h.repository.pendingDeliveries("chat").isEmpty())
            assertTrue("旧流错误后的恢复必须使用新代次", h.requests.filter { it.target.contains("clientMessageId=first-a") }
                .all { h.sessionOf(it) == "credential-new" })
        } finally { h.close() }
    }

    @Test fun anHttpRefreshBeforeTheAcceptedStreamUsesItsActualRejectedCredential() = runTest {
        val h = Harness(this).apply { rejectInitialHttp = true }
        try {
            h.queuePair("a"); h.releaseFirst("a"); runCurrent()
            assertEquals("HTTP401只允许既有刷新一次", 1, h.refreshCount)
            repeat(3) { h.repository.connectionRestored(); runCurrent() }
            advanceTimeBy(90_000); runCurrent()
            assertEquals("被拒绝的是刷新后真正发送的流，不能误当旧代次已更换", 0, h.firstGenerationReads("a"))
            assertEquals(listOf("first-a"), h.postedClients("a"))
            assertEquals(1, h.repository.pendingDeliveries("chat").size)
        } finally { h.close() }
    }

    @Test fun authenticationStorageUnavailabilityKeepsRecoveryAndWaitsForAuthority() = runTest {
        val h = Harness(this, errorCode = "AUTHENTICATION_STORAGE_UNAVAILABLE").apply { authorityUnavailable = true }
        try {
            h.queuePair("a"); h.releaseFirst("a"); runCurrent()
            assertTrue("认证存储临时故障仍需保留原身份读取", h.firstGenerationReads("a") > 0)
            assertEquals(listOf("first-a"), h.postedClients("a"))
            h.authorityUnavailable = false
            h.repository.connectionRestored(expediteRetry = true, confirmedTransportRebuild = 1)
            advanceTimeBy(10); runCurrent()
            assertEquals(listOf("first-a", "next-a"), h.postedClients("a"))
            assertTrue(h.repository.pendingDeliveries("chat").isEmpty())
        } finally { h.close() }
    }

    @Test fun aNewCredentialCannotTurnAnUnknownReceiptIntoPermissionToResend() = runTest {
        val h = Harness(this).apply { authorityState = "unknown" }
        try {
            h.queuePair("a"); h.releaseFirst("a"); runCurrent()
            h.rotateCredential(); h.repository.connectionRestored(); runCurrent()
            advanceTimeBy(10); runCurrent()
            repeat(3) { h.repository.connectionRestored(); runCurrent() }
            h.repository.retryPendingDeliveries("chat"); runCurrent()
            assertTrue(h.firstGenerationReads("a") > 0)
            assertEquals(listOf("first-a"), h.postedClients("a"))
            assertEquals(1, h.repository.pendingDeliveries("chat").size)
            assertEquals(GenerationState.OUTCOME_UNKNOWN, h.repository.generationState.value)
        } finally { h.close() }
    }

    @Test fun theCredentialWakeupKeepsSameNamedSessionsIsolatedByAgent() = runTest {
        val h = Harness(this)
        try {
            h.queuePair("a"); h.queuePair("b")
            h.releaseFirst("a"); runCurrent()
            h.rotateCredential(); h.repository.connectionRestored(); runCurrent()
            assertEquals(listOf("first-a", "next-a"), h.postedClients("a"))
            assertEquals(listOf("first-b"), h.postedClients("b"))
            assertEquals(1, h.repository.pendingDeliveries("chat").size)
            h.releaseFirst("b"); runCurrent()
            assertEquals(listOf("first-b", "next-b"), h.postedClients("b"))
            assertTrue(h.repository.pendingDeliveries("chat").isEmpty())
        } finally { h.close() }
    }

    @Test fun aMissingAcceptedFrameStillKeepsTheOriginalClientForAuthenticatedReadback() = runTest {
        val h = Harness(this).apply { includeAcceptedFrame = false }
        try {
            h.queuePair("a"); h.releaseFirst("a"); runCurrent()
            h.rotateCredential(); h.repository.connectionRestored(); runCurrent()
            assertEquals(listOf("first-a", "next-a"), h.postedClients("a"))
            assertTrue(h.requests.any { it.target.contains("clientMessageId=first-a") })
        } finally { h.close() }
    }

    @Test fun aLateRejectedAuthorityReadUsesTheCredentialOfThatRead() = runTest {
        val h = Harness(this, errorCode = "AUTHENTICATION_STORAGE_UNAVAILABLE").apply {
            holdAuthority = true
            rejectAuthorityOnce = true
        }
        try {
            h.queuePair("a"); h.releaseFirst("a"); runCurrent()
            assertEquals(1, h.firstGenerationReads("a"))
            h.rotateCredential()
            h.repository.connectionRestored(); runCurrent()
            h.releaseAuthority.complete(Unit); runCurrent()
            assertEquals("晚到的旧代次403不能封锁新凭据", listOf("first-a", "next-a"), h.postedClients("a"))
            assertTrue(h.repository.pendingDeliveries("chat").isEmpty())
            val reads = h.requests.filter { it.target.contains("clientMessageId=first-a") }
            assertEquals("credential-old", h.sessionOf(reads.first()))
            assertEquals("credential-new", h.sessionOf(reads.last()))
        } finally { h.close() }
    }

    @Test fun aCredentialChangeInOneAccountCannotWakeAnotherAccountQueue() = runTest {
        val first = Harness(this, accountId = "synthetic-first")
        val second = Harness(this, accountId = "synthetic-second")
        try {
            first.queuePair("a"); second.queuePair("a")
            first.releaseFirst("a"); second.releaseFirst("a"); runCurrent()
            first.rotateCredential()
            first.repository.connectionRestored(); second.repository.connectionRestored(); runCurrent()
            assertEquals(listOf("first-a", "next-a"), first.postedClients("a"))
            assertEquals(listOf("first-a"), second.postedClients("a"))
            assertEquals(0, second.firstGenerationReads("a"))
            assertEquals(1, second.repository.pendingDeliveries("chat").size)
        } finally { first.close(); second.close() }
    }

    @Test fun closingTheOwnerPreventsCredentialChangesFromRestartingReads() = runTest {
        val h = Harness(this)
        h.queuePair("a"); h.releaseFirst("a"); runCurrent()
        h.repository.close()
        h.rotateCredential(); h.repository.connectionRestored(); runCurrent()
        advanceTimeBy(90_000); runCurrent()
        assertEquals(0, h.firstGenerationReads("a"))
        assertEquals(listOf("first-a"), h.postedClients("a"))
        h.close()
    }

    @Test fun anAdapterWithoutRecoveryNeverClaimsAuthoritativeCompletion() = runTest {
        val h = Harness(this, generationRecovery = false)
        try {
            h.queuePair("a"); h.releaseFirst("a"); runCurrent()
            h.rotateCredential(); h.repository.connectionRestored(); runCurrent()
            assertEquals(0, h.firstGenerationReads("a"))
            assertEquals(listOf("first-a"), h.postedClients("a"))
            assertEquals(1, h.repository.pendingDeliveries("chat").size)
            assertEquals(StreamHealth.FAILED, h.repository.streamHealth.first())
        } finally { h.close() }
    }

    private class Harness(private val test: TestScope, generationRecovery: Boolean = true, private val errorCode: String = "SESSION_REVOKED", accountId: String = "synthetic-account") {
        private val dispatcher = StandardTestDispatcher(test.testScheduler)
        private val owner = CoroutineScope(SupervisorJob() + dispatcher)
        private val firstReleases = mutableMapOf<String, CompletableDeferred<Unit>>()
        private val posts = mutableListOf<Pair<String, String>>()
        val requests = mutableListOf<WireRequest>()
        val events = mutableListOf<VerifiedConversationEvent>()
        val releaseAuthority = CompletableDeferred<Unit>()
        var holdAuthority = false
        var rejectAuthorityOnce = false
        var includeAcceptedFrame = true
        var rejectInitialHttp = false
        var refreshCount = 0
        var authorityUnavailable = false
        var authorityState = "completed"
        private var selectedAgent = "a"
        private val agents = listOf("a", "b").associateWith { id ->
            CoreAgent(id, "合成Agent$id", "dsh", true, "deployment", AgentCapabilities(
                send = true, stream = true, history = true, generationRecovery = generationRecovery))
        }
        private val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                requests += request
                val agent = request.target.substringAfter("/agents/").substringBefore('/')
                val body = when {
                    request.target.contains("/generation?") -> {
                        val client = request.target.substringAfter("clientMessageId=").substringBefore('&')
                        if (client.startsWith("first-")) {
                            if (holdAuthority) releaseAuthority.await()
                            if (rejectAuthorityOnce) {
                                rejectAuthorityOnce = false
                                return WireResponse(403, emptyList(), """{"error":{"code":"SESSION_EXPIRED"}}""".toByteArray())
                            }
                            if (authorityUnavailable) return WireResponse(503, emptyList(), """{"error":{"code":"AGENT_UNAVAILABLE"}}""".toByteArray())
                        }
                        val state = if (client.startsWith("first-")) authorityState else "completed"
                        """{"generation":{"generationId":"g-$agent-$client","conversationId":"chat","clientMessageId":"$client","userMessageId":"u-$agent-$client","state":"$state","revision":7}}"""
                    }
                    request.target.endsWith("/generation") -> """{"generation":null}"""
                    request.target.endsWith("/history") -> """{"messages":[]}"""
                    else -> error("非预期合成请求")
                }
                return WireResponse(200, listOf(RawHeader("Content-Type", "application/json")), body.toByteArray())
            }

            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()

            override suspend fun <T> consumeResponse(request: WireRequest, consume: suspend (Int, List<RawHeader>, InputStream) -> T): T {
                val agent = request.target.substringAfter("/agents/").substringBefore('/')
                val client = requireNotNull(JsonFields.string(JsonFields.obj(Json.parse(request.body.decodeToString())), "clientMessageId"))
                if (rejectInitialHttp && sessionOf(request) == "credential-old") {
                    return consume(401, emptyList(), ByteArrayInputStream("{}".toByteArray()))
                }
                posts += agent to client
                if (client.startsWith("first-")) firstReleases.getValue(agent).await()
                val generation = "g-$agent-$client"
                val identity = "\"generationId\":\"$generation\",\"clientMessageId\":\"$client\",\"userMessageId\":\"u-$agent-$client\""
                val errorIdentity = if (includeAcceptedFrame) ",$identity" else ""
                val frames = buildList {
                    if (includeAcceptedFrame || !client.startsWith("first-"))
                        add("{\"type\":\"generation\",$identity,\"state\":\"running\",\"revision\":1}")
                    add(if (client.startsWith("first-")) "{\"type\":\"error\",\"code\":\"$errorCode\"$errorIdentity}"
                    else "{\"type\":\"done\",\"outcome\":\"completed\",$identity,\"revision\":7}")
                }.joinToString("") { "event: message\ndata: $it\n\n" }
                return consume(200, listOf(RawHeader("Content-Type", "text/event-stream")), ByteArrayInputStream(frames.toByteArray()))
            }
        }
        private val profile = GatewayProfile(accountId, "synthetic-device", "credential-old", "http://synthetic.invalid", accessToken = "synthetic")
        private val renewal = GatewaySessionRenewal(profile, owner) {
            refreshCount++
            it.copy(sessionId = "credential-new", accessToken = "synthetic-new")
        }
        private val client = AgentSessionClient(GatewayHttpClient(profile,
            transport, { ByteArray(64) }, InMemoryEventCursorStore(), sessionRenewal = renewal, ioDispatcher = dispatcher))
        val repository = CoreAgentConversationRepository(client, { agents.getValue(selectedAgent) }, { "chat" }, owner, dispatcher,
            nativeRetryJitter = { 1L })
        private val subscription = owner.launch {
            repository.observeEvents(ConversationScope("合成配置", "http://synthetic.invalid", "合成账号", "合成安装")).collect { events += it }
        }

        suspend fun queuePair(agent: String) {
            selectedAgent = agent
            repository.activeConversationChanged()
            firstReleases[agent] = CompletableDeferred()
            repository.submitMessage("chat", OutgoingMessage(ClientMessageId("first-$agent"), "合成首条"))
            test.runCurrent()
            repository.submitMessage("chat", OutgoingMessage(ClientMessageId("next-$agent"), "合成后继", delivery = MessageDeliveryMode.QUEUE))
            test.runCurrent()
            assertEquals(listOf("first-$agent"), postedClients(agent))
            assertEquals(1, repository.pendingDeliveries("chat").size)
        }

        suspend fun rotateCredential() { renewal.refresh() }
        fun sessionOf(request: WireRequest): String? = request.headers.firstOrNull { it.name == "X-Open-Android-Intelligence-Session" }?.value
        fun releaseFirst(agent: String) { firstReleases.getValue(agent).complete(Unit) }
        fun postedClients(agent: String): List<String> = posts.filter { it.first == agent }.map { it.second }
        fun firstGenerationReads(agent: String): Int = requests.count { it.target.contains("clientMessageId=first-$agent") }
        fun close() { subscription.cancel(); repository.close(); renewal.close(); owner.cancel() }
    }
}
