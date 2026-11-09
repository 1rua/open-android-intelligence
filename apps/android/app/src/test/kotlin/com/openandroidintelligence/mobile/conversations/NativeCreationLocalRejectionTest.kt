package com.openandroidintelligence.mobile.conversations

import com.openandroidintelligence.gateway.agents.*
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.state.*
import com.openandroidintelligence.mobile.LoopbackGatewayStub
import com.openandroidintelligence.mobile.NativeAgentsController
import kotlinx.coroutines.test.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeCreationLocalRejectionTest {
    @Test fun localOfflineRejectionCanCreateAfterOnlineRecovery() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val requests = mutableListOf<WireRequest>()
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                requests += request
                return if (request.method == "POST") WireResponse(200, emptyList(), """{"session":{"id":"created-after-online"}}""".toByteArray())
                else WireResponse(404, emptyList(), """{"error":{"code":"OPERATION_NOT_FOUND"}}""".toByteArray())
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        var agent = CoreAgent("hermes", "Hermes", "hermes", false, "account", AgentCapabilities(create=true, list=true, operationRecovery=true))
        val profile = GatewayProfile("account", "phone", "login", "https://core.example", accessToken="synthetic")
        val client = AgentSessionClient(GatewayHttpClient(profile, transport, { ByteArray(64) }, InMemoryEventCursorStore()))
        val repository = CoreAgentConversationRepository(client, { agent }, parentScope=owner, ioDispatcher=Dispatchers.Unconfined)
        val scope = ConversationScope("profile", "https://core.example", "account", "install")
        try {
            val first = runCatching { repository.createConversation(scope, "cconv_offline_probe") }
            assertTrue(first.isFailure)
            assertEquals(0, requests.count { it.method == "POST" })
            agent = agent.copy(online=true)
            val retry = runCatching { repository.createConversation(scope, "cconv_offline_probe") }
            println("离线首次=${first.exceptionOrNull()?.message}; 恢复后重试=${retry.exceptionOrNull()?.message}; POST=${requests.count { it.method == "POST" }}; GET=${requests.count { it.method == "GET" }}")
            assertTrue("首次离线在本机拒绝，未产生任何 POST；恢复在线后应允许首次真正创建", retry.isSuccess)
            assertEquals(1, requests.count { it.method == "POST" })
        } finally { owner.cancel() }
    }

    @Test fun localCapabilityRejectionCanCreateOnceAfterTheCapabilityReturns() = runBlocking {
        val harness = Harness()
        try {
            harness.agent = harness.agent.copy(capabilities = harness.agent.capabilities.copy(create = false))
            val repository = harness.repository()
            assertTrue(runCatching { repository.createConversation(harness.scope, "capability") }.isFailure)
            assertEquals(0, harness.posts.size)
            assertEquals(0, harness.operationReads)
            harness.agent = harness.agent.copy(capabilities = harness.agent.capabilities.copy(create = true))
            assertEquals("created", repository.createConversation(harness.scope, "capability").id.value)
            assertEquals(1, harness.posts.size)
        } finally { harness.close() }
    }

    @Test fun localTransportLifecycleRejectionRemainsUnsentAndCanUseItsFirstPostLater() = runBlocking {
        val gateway = LoopbackGatewayStub()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var rejected = true
        gateway.respond("${AgentSessionClient.ROOT}/hermes/sessions", """{"session":{"id":"created"}}""")
        try {
            val profile = GatewayProfile("account", "phone", "login", gateway.baseUrl, accessToken = "synthetic")
            val transport = GatewayTransport(profile, beforeConnect = { check(!rejected) { "LOCAL_OWNER_NOT_READY" } })
            val client = AgentSessionClient(GatewayHttpClient(profile, transport, { ByteArray(64) }, InMemoryEventCursorStore()))
            val agent = CoreAgent("hermes", "Hermes", "hermes", true, "account", AgentCapabilities(create = true, operationRecovery = true))
            val repository = CoreAgentConversationRepository(client, { agent }, parentScope = owner)
            val scope = ConversationScope("profile", gateway.baseUrl, "account", "install")
            assertEquals("LOCAL_OWNER_NOT_READY", runCatching { repository.createConversation(scope, "local-owner") }.exceptionOrNull()?.message)
            assertTrue(gateway.requests.isEmpty())
            rejected = false
            assertEquals("created", repository.createConversation(scope, "local-owner").id.value)
            assertEquals(1, gateway.requests.count { it.method == "POST" })
        } finally { owner.cancel(); gateway.closed() }
    }

    @Test fun anOfflineCreationKeepsItsIdentityAndFirstSendPermissionAcrossControllerAndRepositoryRestart() = runBlocking {
        val harness = Harness()
        val saved = MemoryPersistence()
        try {
            harness.agent = harness.agent.copy(online = false)
            val first = harness.controller(saved)
            first.createThread()
            first.flushPendingPersistence()
            val pending = requireNotNull(saved.checkpoint?.pendingConversationCreation)
            assertFalse("本机离线拒绝必须保存明确未发送", pending.mayHaveBeenSent)
            assertEquals(0, harness.posts.size)
            assertEquals(0, harness.operationReads)
            first.closeAndAwait()
            harness.agent = harness.agent.copy(online = true)
            val restarted = harness.controller(saved)
            restarted.createThread()
            restarted.flushPendingPersistence()
            assertEquals("created", restarted.state.value.activeThreadId)
            assertEquals(1, harness.posts.size)
            assertEquals(pending.clientId, RawHeaders.single(harness.posts.single().headers, "Idempotency-Key"))
            assertNull(saved.checkpoint?.pendingConversationCreation)
            restarted.closeAndAwait()
        } finally { harness.close() }
    }

    @Test fun aLostResponsePersistsTheDispatchFenceAndRestartOnlyQueriesTheOriginalIdentity() = runBlocking {
        val harness = Harness()
        val saved = MemoryPersistence()
        try {
            harness.failPost = true
            val first = harness.controller(saved)
            first.createThread()
            first.flushPendingPersistence()
            val pending = requireNotNull(saved.checkpoint?.pendingConversationCreation)
            assertTrue(pending.mayHaveBeenSent)
            assertEquals(1, harness.posts.size)
            first.closeAndAwait()
            harness.failPost = false
            val restarted = harness.controller(saved)
            restarted.createThread()
            restarted.createThread()
            assertEquals("CONVERSATION_CREATE_FAILED:OUTCOME_UNKNOWN", restarted.state.value.notice)
            assertEquals("404 不能授权第二次 POST", 1, harness.posts.size)
            assertEquals(3, harness.operationReads)
            assertTrue(harness.requests.filter { "/operations/" in it.target }.all { it.target.endsWith(pending.clientId) })
            restarted.closeAndAwait()
        } finally { harness.close() }
    }

    @Test fun aLegacyCheckpointWithoutDispatchFactsStaysConservativeOnEvery404() = runBlocking {
        val harness = Harness()
        val saved = MemoryPersistence().apply {
            checkpoint = checkpoint(SavedConversationCreation("legacy-creation", null))
        }
        try {
            val restarted = harness.controller(saved)
            restarted.createThread()
            restarted.createThread()
            restarted.flushPendingPersistence()
            assertEquals(0, harness.posts.size)
            assertEquals(2, harness.operationReads)
            assertEquals("legacy-creation", saved.checkpoint?.pendingConversationCreation?.clientId)
            assertTrue(requireNotNull(saved.checkpoint?.pendingConversationCreation).mayHaveBeenSent)
            assertEquals("CONVERSATION_CREATE_FAILED:OUTCOME_UNKNOWN", restarted.state.value.notice)
            restarted.closeAndAwait()
        } finally { harness.close() }
    }

    @Test fun cancellingBeforeDispatchLeavesTheSameIdentityAvailableForItsFirstSend() = runBlocking {
        val harness = Harness()
        val saved = MemoryPersistence()
        val dispatchGate = CompletableDeferred<Unit>()
        harness.beforeTransportDispatch = { dispatchGate.await() }
        try {
            val controller = harness.controller(saved)
            controller.createThread()
            controller.flushPendingPersistence()
            val pending = requireNotNull(saved.checkpoint?.pendingConversationCreation)
            assertTrue(controller.state.value.creatingThread)
            assertFalse(pending.mayHaveBeenSent)
            controller.cancelThreadCreation()
            dispatchGate.complete(Unit)
            controller.createThread()
            controller.flushPendingPersistence()
            assertEquals("created", controller.state.value.activeThreadId)
            assertEquals(1, harness.posts.size)
            assertEquals(pending.clientId, RawHeaders.single(harness.posts.single().headers, "Idempotency-Key"))
            controller.closeAndAwait()
        } finally { dispatchGate.complete(Unit); harness.close() }
    }

    @Test fun aCrashBetweenTheDurableDispatchFenceAndConnectionCannotAuthorizeAnotherPost() = runBlocking {
        val harness = Harness()
        val saved = MemoryPersistence()
        harness.afterDispatchFence = { throw CancellationException("CRASH_AFTER_DURABLE_FENCE") }
        try {
            val first = harness.controller(saved)
            first.createThread()
            val durableAtCrash = requireNotNull(saved.checkpoint)
            assertTrue(requireNotNull(durableAtCrash.pendingConversationCreation).mayHaveBeenSent)
            assertEquals(0, harness.posts.size)
            first.closeAndAwait()
            harness.afterDispatchFence = {}
            val restarted = harness.controller(MemoryPersistence().apply { checkpoint = durableAtCrash })
            restarted.createThread()
            assertEquals(0, harness.posts.size)
            assertEquals(1, harness.operationReads)
            assertEquals("CONVERSATION_CREATE_FAILED:OUTCOME_UNKNOWN", restarted.state.value.notice)
            restarted.closeAndAwait()
        } finally { harness.close() }
    }

    @Test fun aFailedDurableDispatchFenceCannotOpenTheConnectionAndCanRetryTheFirstSend() = runBlocking {
        val harness = Harness()
        val saved = MemoryPersistence().apply { rejectDispatchFence = true }
        try {
            val controller = harness.controller(saved)
            controller.createThread()
            assertEquals(0, harness.posts.size)
            assertEquals(0, harness.operationReads)
            saved.rejectDispatchFence = false
            controller.createThread()
            assertEquals("created", controller.state.value.activeThreadId)
            assertEquals(1, harness.posts.size)
            controller.closeAndAwait()
        } finally { harness.close() }
    }

    @Test fun discovery500AndLocalCapabilityRejectionRecoverToOneFirstPostWithTheOriginalIdentity() = runTest {
        var discoveryReads = 0
        val requests = mutableListOf<WireRequest>()
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                requests += request
                val body = when {
                    request.target == AgentSessionClient.ROOT -> {
                        discoveryReads++
                        if (discoveryReads == 2) return WireResponse(500, emptyList(), """{"error":{"code":"INTERNAL_ERROR"}}""".toByteArray())
                        val canCreate = discoveryReads >= 3
                        """{"agents":[{"id":"hermes","name":"Hermes","kind":"hermes","online":true,"sessionScope":"account","capabilities":{"list":true,"history":true,"create":$canCreate,"operationRecovery":true}}]}"""
                    }
                    request.method == "POST" -> """{"session":{"id":"created-after-discovery"}}"""
                    "/operations/" in request.target -> return WireResponse(404, emptyList(), "{}".toByteArray())
                    request.target.endsWith("/history") -> """{"messages":[]}"""
                    else -> """{"sessions":[]}"""
                }
                return WireResponse(200, emptyList(), body.toByteArray())
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val profile = GatewayProfile("account", "phone", "login", "https://core.example", accessToken = "synthetic")
        val client = AgentSessionClient(GatewayHttpClient(profile, transport, { ByteArray(64) }, InMemoryEventCursorStore(), ioDispatcher = dispatcher))
        val discovery = NativeAgentsController(client, backgroundScope, dispatcher)
        val repository = CoreAgentConversationRepository(client, { discovery.state.value.agent }, parentScope = backgroundScope, ioDispatcher = dispatcher)
        val saved = MemoryPersistence()
        val controller = WorkbenchController(backgroundScope, repository, object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) = AgentCommandCatalog(CatalogVersion("v1"), emptyList())
        }, { ConversationScope("profile", profile.gatewayBaseUrl, "account", "install") }, persistence = saved,
            persistenceDispatcher = dispatcher, restoreLastThread = false, replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false))
        try {
            runCurrent()
            assertEquals(true, discovery.state.value.agent?.online)
            advanceTimeBy(10_000); runCurrent()
            assertEquals("INTERNAL_ERROR", discovery.state.value.error)
            controller.createThread(); runCurrent(); controller.flushPendingPersistence()
            val pending = requireNotNull(saved.checkpoint?.pendingConversationCreation)
            assertFalse(pending.mayHaveBeenSent)
            assertEquals(0, requests.count { it.method == "POST" })
            assertEquals(0, requests.count { "/operations/" in it.target })
            advanceTimeBy(10_000); runCurrent()
            assertEquals(true, discovery.state.value.agent?.online)
            controller.createThread(); runCurrent()
            val post = requests.single { it.method == "POST" }
            assertEquals(pending.clientId, RawHeaders.single(post.headers, "Idempotency-Key"))
            assertEquals("created-after-discovery", controller.state.value.activeThreadId)
            assertNull(discovery.state.value.error)
            assertNull(saved.checkpoint?.pendingConversationCreation)
        } finally { controller.close(); repository.close(); discovery.close() }
    }

    @Test fun aDurableAuthoritativeReceiptSurvivesRepositoryRestartWithoutPostOrOperationLookup() = runBlocking {
        val harness = Harness()
        val store = NativeEventSynchronizerTest.Storage()
        try {
            assertEquals("created", harness.repository(store).createConversation(harness.scope, "resolved-original").id.value)
            val receipt = requireNotNull(store.creations["resolved-original"])
            assertEquals("created", harness.repository(store).recoverConversationCreation(harness.scope, "resolved-original").id.value)
            assertEquals(receipt, store.creations["resolved-original"])
            assertEquals(1, harness.posts.size)
            assertEquals(0, harness.operationReads)
        } finally { harness.close() }
    }

    private class Harness {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val scope = ConversationScope("profile", "https://core.example", "account", "install")
        var agent = CoreAgent("hermes", "Hermes", "hermes", true, "account",
            AgentCapabilities(create = true, list = true, history = true, operationRecovery = true))
        val requests = mutableListOf<WireRequest>()
        val posts get() = requests.filter { it.method == "POST" }
        val operationReads get() = requests.count { "/operations/" in it.target }
        var failPost = false
        var beforeTransportDispatch: suspend () -> Unit = {}
        var afterDispatchFence: suspend () -> Unit = {}
        private val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest, beforeDispatch: suspend () -> Unit): WireResponse {
                if (request.method == "POST") beforeTransportDispatch()
                beforeDispatch()
                if (request.method == "POST") afterDispatchFence()
                return execute(request)
            }
            override suspend fun execute(request: WireRequest): WireResponse {
                requests += request
                val (status, body) = when {
                    request.method == "POST" -> {
                        if (failPost) throw IOException("RESPONSE_LOST")
                        200 to """{"session":{"id":"created"}}"""
                    }
                    "/operations/" in request.target -> 404 to """{"error":{"code":"OPERATION_NOT_FOUND"}}"""
                    request.target.endsWith("/history") -> 200 to """{"messages":[]}"""
                    else -> 200 to """{"sessions":[]}"""
                }
                return WireResponse(status, emptyList(), body.toByteArray())
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        private val profile = GatewayProfile("account", "phone", "login", scope.gatewayId, accessToken = "synthetic")
        private val client = AgentSessionClient(GatewayHttpClient(profile, transport, { ByteArray(64) }, InMemoryEventCursorStore(),
            ioDispatcher = Dispatchers.Unconfined))
        fun repository(store: NativeConversationStore? = null) = CoreAgentConversationRepository(client, { agent }, parentScope = owner,
            ioDispatcher = Dispatchers.Unconfined, storeForAgent = store?.let { saved -> { _: CoreAgent -> saved } })
        fun controller(saved: MemoryPersistence) = WorkbenchController(owner, repository(), object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) = AgentCommandCatalog(CatalogVersion("v1"), emptyList())
        }, { scope }, persistence = saved, persistenceDispatcher = Dispatchers.Unconfined, restoreLastThread = false,
            replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false))
        fun close() = owner.cancel()
    }

    private class MemoryPersistence : WorkbenchPersistence {
        var checkpoint: WorkbenchCheckpoint? = null
        var rejectDispatchFence = false
        override fun load() = checkpoint
        override fun save(checkpoint: WorkbenchCheckpoint) {
            if (rejectDispatchFence && checkpoint.pendingConversationCreation?.mayHaveBeenSent == true) throw IOException("DISK_UNAVAILABLE")
            this.checkpoint = checkpoint
        }
        override fun loadSendAtoms(conversationId: String) = emptyList<LocalSendAtom>()
        override fun saveSendAtoms(conversationId: String, atoms: List<LocalSendAtom>) = Unit
    }

    private fun checkpoint(pending: SavedConversationCreation) = WorkbenchCheckpoint(emptyList(), null, "", emptyList(),
        emptyMap(), "", 0, emptyList(), null, emptySet(), emptySet(), pendingConversationCreation = pending)
}
