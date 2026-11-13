package com.openandroidintelligence.mobile.conversations

import com.openandroidintelligence.mobile.LoopbackGatewayStub
import com.openandroidintelligence.gateway.agents.AgentSessionClient
import com.openandroidintelligence.gateway.agents.CoreAgent
import com.openandroidintelligence.gateway.agents.AgentCapabilities
import com.openandroidintelligence.conversation.ports.PageRequest
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.conversation.ports.ConversationScope
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** 真正经过仓储、签名 HTTP 和回环 TCP；服务端已经创建，但关闭连接丢失响应。 */
class NativeCreationRecoveryTest {
    @Test fun lostCreateResponseUsesOriginalRequestIdentityAndAuthoritativeOperationReceipt() = runBlocking {
        scenario("resolved", true)
    }
    @Test fun pendingOperationUsesBoundedGetWaitsUntilTheReceiptResolves() = runBlocking {
        scenario("pending", true)
    }
    @Test fun unknownOperationNeverRepostsTheUncertainCreation() = runBlocking {
        scenario("unknown", true)
    }
    @Test fun oldCoreKeepsTheRealUnknownOutcomeWithoutReposting() = runBlocking {
        scenario("unknown", false)
    }
    @Test fun aDurableExplicitRejectionIsNotChangedIntoUnknownAndNeverReposts() = runBlocking {
        scenario("revoked", true)
    }
    @Test fun missingOperationDoesNotAuthorizeAnotherPost() = runBlocking {
        scenario("missing", true)
    }

    @Test fun authoritativeCreateReturnsWhileRefreshConsumerIsBlockedAndNotificationsStayDurable() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val listStarted = CompletableDeferred<Unit>()
        val releaseList = CompletableDeferred<Unit>()
        val allDelivered = CompletableDeferred<Unit>()
        val requests = java.util.concurrent.CopyOnWriteArrayList<WireRequest>()
        val scope = ConversationScope("profile", "https://core.example", "account", "install")
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                requests += request
                val body = if (request.method == "POST") """{"session":{"id":"authoritative-session"}}"""
                else { listStarted.complete(Unit); releaseList.await(); """{"sessions":[]}""" }
                return WireResponse(200, emptyList(), body.toByteArray())
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        val profile = GatewayProfile("account", "phone", "login", "https://core.example", accessToken = "synthetic")
        val client = AgentSessionClient(GatewayHttpClient(profile, transport, { ByteArray(64) }, InMemoryEventCursorStore()))
        val agent = CoreAgent("hermes", "test", "hermes", true, "account", AgentCapabilities(create=true, list=true))
        val store = NativeEventSynchronizerTest.Storage()
        val repository = CoreAgentConversationRepository(client, { agent }, parentScope=owner, storeForAgent={store})
        var delivered = 0
        owner.launch(start = CoroutineStart.UNDISPATCHED) {
            repository.observeEvents(scope).collect {
                delivered++
                if (delivered == 1) repository.listConversations(scope, PageRequest())
                if (delivered == 8) allDelivered.complete(Unit)
            }
        }
        try {
            assertEquals("authoritative-session", repository.createConversation(scope, "create_0").id.value)
            withTimeout(2000) { listStarted.await() }
            withTimeout(2000) {
                repeat(7) { n -> assertEquals("authoritative-session", repository.createConversation(scope, "create_${n+1}").id.value) }
            }
            assertEquals("消费者阻塞不能阻止创建事件可靠保存", 8, store.creations.size)
            assertEquals(8, requests.count { it.method == "POST" })
            releaseList.complete(Unit)
            withTimeout(2000) { allDelivered.await() }
            assertEquals("解除阻塞后通知仍按序送达", 8, delivered)
        } finally { owner.cancel(); releaseList.complete(Unit) }
    }

    private suspend fun scenario(state: String, supported: Boolean) {
        val gateway = LoopbackGatewayStub()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val requestId = "cconv_original"
        val target = "${AgentSessionClient.ROOT}/hermes/sessions"
        gateway.respond(AgentSessionClient.ROOT, """{"agents":[{"id":"hermes","name":"test","kind":"hermes","online":true,"sessionScope":"account","capabilities":{"create":true,"operationRecovery":$supported}}]}""")
        gateway.respond(target, "", status = 0)
        var pendingReads = 0
        gateway.respond("${AgentSessionClient.ROOT}/hermes/operations/$requestId",
            if (state == "resolved") """{"state":"resolved","receipt":{"session":{"id":"authoritative-session","title":"created"}}}"""
            else if (state == "revoked") """{"state":"resolved","receipt":{"error":{"code":"SESSION_REVOKED"}}}"""
            else """{"state":"unknown"}""", status = if (state == "missing") 404 else 200)
        if (state == "pending") gateway.respond("${AgentSessionClient.ROOT}/hermes/operations/$requestId") {
            pendingReads++
            200 to if (pendingReads < 3) """{"state":"pending"}"""
                else """{"state":"resolved","receipt":{"session":{"id":"authoritative-session"}}}"""
        }
        try {
            val profile = GatewayProfile("account", "phone", "login", gateway.baseUrl, accessToken = "synthetic")
            val client = AgentSessionClient(GatewayHttpClient(profile, GatewayTransport(profile), { ByteArray(64) }, InMemoryEventCursorStore()), traceEnabled = true)
            val agent = client.agents().single()
            val repository = CoreAgentConversationRepository(client, { agent }, parentScope = owner)
            val scope = ConversationScope("profile", gateway.baseUrl, "account", "install")
            val result = runCatching { repository.createConversation(scope, requestId) }
            if (state in setOf("resolved", "pending") && supported) assertEquals("authoritative-session", result.getOrThrow().id.value)
            else {
                assertTrue(result.isFailure)
                if (state == "revoked") assertEquals("SESSION_REVOKED", (result.exceptionOrNull() as com.openandroidintelligence.gateway.agents.AgentSessionException).code)
                assertTrue(runCatching { repository.createConversation(scope, requestId) }.isFailure)
            }
            val posts = gateway.requests.filter { it.method == "POST" }
            assertEquals("未知创建不能重新 POST", 1, posts.size)
            assertTrue(posts.single().headers.any { it == "Idempotency-Key: $requestId" })
            assertEquals(if (supported) if (state == "resolved") 1 else if (state == "pending") 3 else 2 else 0,
                gateway.requests.count { it.target.contains("/operations/") })
            val traces = gateway.requests.filter { it.method == "POST" || it.target.contains("/operations/") }
                .mapNotNull { request -> request.headers.firstOrNull { it.startsWith("traceparent:", true) }?.substringAfter(": ") }
            assertTrue(traces.all { it.matches(Regex("00-[0-9a-f]{32}-[0-9a-f]{16}-00")) })
            assertEquals(1, traces.map { it.split('-')[1] }.distinct().size)
            assertEquals(traces.size, traces.distinct().size)
        } finally { owner.cancel(); gateway.closed() }
    }
}
