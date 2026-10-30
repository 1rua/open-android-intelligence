package com.openandroidintelligence.mobile.conversations

import com.openandroidintelligence.conversation.model.ClientMessageId
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.gateway.agents.*
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CodexNativeCancellationTest {
    @Test fun codexCancellationTargetsTheRealObservedTurnAndValidatesTheReceipt() = runTest {
        scenario(observed=true, matching=true)
    }
    @Test fun aDifferentCodexTurnReceiptCannotConfirmCancellation() = runTest {
        scenario(observed=true, matching=false)
    }
    @Test fun aLocalPlaceholderCannotBeSentAsACodexCancellationTarget() = runTest {
        scenario(observed=false, matching=true)
    }

    private suspend fun TestScope.scenario(observed: Boolean, matching: Boolean) {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val requests = mutableListOf<WireRequest>()
        lateinit var repository: CoreAgentConversationRepository
        var localId: String? = null
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                requests += request
                if (request.target.endsWith("/messages")) {
                    localId = repository.generationId.value
                    val frame = if (observed) """{"type":"generation","generationId":"codex-real-turn","state":"running","revision":1}"""
                        else """{"type":"done","outcome":"completed"}"""
                    return WireResponse(200,listOf(RawHeader("Content-Type","text/event-stream")),"event: message\ndata: $frame\n\n".toByteArray())
                }
                check(request.target.endsWith("/cancel"))
                val id = if (matching) "codex-real-turn" else "later-turn"
                return WireResponse(200,emptyList(),"""{"session":{"outcome":"cancelled","generationId":"$id"}}""".toByteArray())
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        val client = AgentSessionClient(GatewayHttpClient(GatewayProfile("account","phone","login","https://core.example",accessToken="synthetic"),
            transport,{ByteArray(64)},InMemoryEventCursorStore(),ioDispatcher=dispatcher))
        val agent = CoreAgent("codex","test","codex",true,"deployment",AgentCapabilities(send=true,stream=true,cancel=true))
        repository = CoreAgentConversationRepository(client,{agent},{"chat"},backgroundScope,dispatcher)
        repository.submitMessage("chat",OutgoingMessage(ClientMessageId("original"),"original")); runCurrent()
        if (!observed) assertTrue(requireNotNull(localId).startsWith("local_"))
        val result = repository.cancelGeneration("chat", if(observed) "codex-real-turn" else requireNotNull(localId), "cancel-original")
        val cancellation = requests.filter { it.target.endsWith("/cancel") }
        if (observed) {
            assertEquals(1,cancellation.size)
            assertEquals("${AgentSessionClient.ROOT}/codex/sessions/chat/cancel",cancellation.single().target)
            assertEquals("""{"generationId":"codex-real-turn"}""",cancellation.single().body.decodeToString())
            assertEquals(if(matching) CancelGenerationOutcome.CANCELLED else CancelGenerationOutcome.OUTCOME_UNKNOWN,result.outcome)
        } else {
            assertEquals(CancelGenerationOutcome.OUTCOME_UNKNOWN,result.outcome)
            assertTrue(cancellation.isEmpty())
        }
        assertEquals("原输入始终只发送一次",1,requests.count { it.target.endsWith("/messages") })
    }
}
