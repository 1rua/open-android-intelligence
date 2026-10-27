package com.openandroidintelligence.mobile

import com.openandroidintelligence.gateway.agents.AgentSessionClient
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.GatewayByteTransport
import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.GatewayProfile
import com.openandroidintelligence.gateway.http.WireRequest
import com.openandroidintelligence.gateway.http.WireResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NativeAgentsControllerTest {
    @Test fun lateHistoryFromOneAgentCannotAppearInAnotherAgentsSessionWithTheSameId() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                val body = when {
                    request.target == AgentSessionClient.ROOT -> agents
                    request.target.endsWith("/hermes/sessions") -> sessions("Hermes")
                    request.target.endsWith("/dsh/sessions") -> sessions("DSH")
                    else -> { gate.await(); """{"messages":[{"id":"private","role":"assistant","text":"Hermes private history"}]}""" }
                }
                return WireResponse(200, emptyList(), body.toByteArray())
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = NativeAgentsController(client(transport, dispatcher), backgroundScope, dispatcher)
        runCurrent()
        assertEquals("Hermes", controller.state.value.sessions.single().title)
        controller.selectSession("same-native-id")
        runCurrent()
        controller.editDraft("private draft")
        controller.selectAgent("dsh")
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals("dsh", controller.state.value.selectedAgentId)
        assertEquals("DSH", controller.state.value.sessions.single().title)
        assertNull(controller.state.value.selectedSessionId)
        assertTrue(controller.state.value.messages.isEmpty())
        assertEquals("", controller.state.value.draft)
        controller.close()
    }

    @Test fun onlyDiscoveryNotFoundUsesTheLegacyWorkbenchFallback() = runTest {
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest) =
                if (request.target == AgentSessionClient.ROOT) WireResponse(200, emptyList(), agents.toByteArray())
                else WireResponse(404, emptyList(), """{"error":{"code":"NATIVE_SESSION_NOT_FOUND"}}""".toByteArray())
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = NativeAgentsController(client(transport, dispatcher), backgroundScope, dispatcher)
        runCurrent()
        assertEquals(true, controller.state.value.supported)
        assertEquals("NATIVE_SESSION_NOT_FOUND", controller.state.value.error)
        controller.close()
        val legacy = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest) = WireResponse(404, emptyList(), "{}".toByteArray())
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        val oldController = NativeAgentsController(client(legacy, dispatcher), backgroundScope, dispatcher)
        runCurrent()
        assertEquals(false, oldController.state.value.supported)
        oldController.close()
    }

    @Test fun closingAnAccountCancelsDiscoveryAndDiscardsItsSessionState() = runTest {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                calls++
                gate.await()
                return WireResponse(200, emptyList(), agents.toByteArray())
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = NativeAgentsController(client(transport, dispatcher), backgroundScope, dispatcher)
        runCurrent()
        controller.close()
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, calls)
        assertTrue(controller.state.value.agents.isEmpty())
    }

    @Test fun lastSelectedAgentIdIsRestoredOnInit() = runTest {
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                val body = if (request.target == AgentSessionClient.ROOT) agents else sessions("DSH")
                return WireResponse(200, emptyList(), body.toByteArray())
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val controller = NativeAgentsController(
            client(transport, dispatcher),
            backgroundScope,
            dispatcher,
            lastSelectedAgentId = "dsh",
        )
        // Initial state retains the remembered agent before network returns
        assertEquals("dsh", controller.state.value.selectedAgentId)
        runCurrent()
        // After discovery finishes, remembered selection is confirmed
        assertEquals("dsh", controller.state.value.selectedAgentId)
        assertTrue(controller.state.value.selectionConfirmed)
        assertEquals("DSH", controller.state.value.sessions.single().title)
        controller.close()
    }

    private fun client(transport: GatewayByteTransport, dispatcher: CoroutineDispatcher) = AgentSessionClient(GatewayHttpClient(
        GatewayProfile("account", "device", "session", "https://core.example"), transport,
        { ByteArray(64) }, InMemoryEventCursorStore(), ioDispatcher = dispatcher))
    private fun sessions(name: String) = """{"sessions":[{"id":"same-native-id","title":"$name"}]}"""
    private val agents = """{"agents":[{"id":"hermes","name":"Hermes","kind":"hermes","online":true,"sessionScope":"account","capabilities":{"list":true,"history":true}},{"id":"dsh","name":"DSH","kind":"dsh","online":true,"sessionScope":"deployment","capabilities":{"list":true,"history":true}}]}"""
}
