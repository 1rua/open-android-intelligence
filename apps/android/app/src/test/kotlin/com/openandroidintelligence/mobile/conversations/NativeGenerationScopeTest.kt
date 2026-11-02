package com.openandroidintelligence.mobile.conversations

import com.openandroidintelligence.conversation.ports.CancelGenerationOutcome
import com.openandroidintelligence.gateway.agents.*
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NativeGenerationScopeTest {
    @Test fun identicalConversationAndGenerationIdsInDifferentAgentsCannotRedirectCancellation() = runTest {
        val idle = CompletableDeferred<Unit>()
        val requests = mutableListOf<WireRequest>()
        val agentA = CoreAgent("agent-a", "A", "hermes", true, "account",
            AgentCapabilities(history=true, send=true, cancel=true, generationRecovery=true, eventSync=true))
        var selected = agentA
        val stores = listOf("agent-a", "agent-b").associateWith {
            NativeEventSynchronizerTest.Storage().apply { cursor = null }
        }
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                requests += request
                val agent = if (request.target.contains("/agent-a/")) "agent-a" else "agent-b"
                val body = when {
                    request.target.contains("snapshot=true") -> """{"events":[],"cursor":"baseline","hasMore":false,"approvals":[]}"""
                    request.target.contains("/events?") -> { idle.await(); error("cancelled") }
                    request.target.endsWith("/events/ack") -> """{"cursor":"baseline"}"""
                    request.target.endsWith("/history") -> """{"messages":[{"id":"user-$agent","role":"user","text":"synthetic","clientMessageId":"client-$agent","generationId":"shared-gen","contentRevision":1}]}"""
                    request.target.contains("/generation?") -> """{"generation":{"conversationId":"chat","generationId":"shared-gen","clientMessageId":"client-$agent","userMessageId":"user-$agent","state":"running","revision":1}}"""
                    request.target.endsWith("/cancel") -> """{"session":{"generationId":"shared-gen","outcome":"cancelled"}}"""
                    else -> error("Unexpected request")
                }
                return WireResponse(200, listOf(RawHeader("Content-Type", "application/json")), body.toByteArray())
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val client = AgentSessionClient(GatewayHttpClient(GatewayProfile("account", "phone", "login", "https://core.example", accessToken="synthetic"),
            transport, { ByteArray(64) }, InMemoryEventCursorStore(), ioDispatcher=dispatcher))
        val repository = CoreAgentConversationRepository(client, { selected }, { "chat" }, backgroundScope, dispatcher,
            storeForAgent={ stores.getValue(it.id) }, nativeRetryJitter={ it })
        repository.activeConversationChanged(); runCurrent()
        selected = agentA.copy(id="agent-b")
        repository.activeConversationChanged(); runCurrent()
        selected = agentA
        repository.activeConversationChanged(); runCurrent()
        assertEquals(CancelGenerationOutcome.CANCELLED, repository.cancelGeneration("chat", "shared-gen", "cancel-a").outcome)
        val cancel = requests.single { it.target.endsWith("/cancel") }
        assertTrue(cancel.target.contains("/agent-a/sessions/chat/"))
        assertTrue(requests.none { it.target.endsWith("/messages") })
    }
}
