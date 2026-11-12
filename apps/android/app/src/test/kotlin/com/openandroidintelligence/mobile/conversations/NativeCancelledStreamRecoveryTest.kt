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
class NativeCancelledStreamRecoveryTest {
    @Test fun cancelledErrorQueriesTheOriginalIdentityBeforeDrainingTheQueuedSuccessor() = runTest {
        val h = Harness(this)
        try {
            h.queuePair("a")
            h.releaseFirst("a")
            runCurrent()

            assertTrue("取消错误必须查询原身份，不能直接丢掉恢复所有者", h.firstGenerationReads("a") > 0)
            assertEquals("查询完成前不得投递后继", listOf("first-a"), h.postedClients("a"))
            assertTrue("错误码本身不能伪造权威取消", h.events.none { it is VerifiedConversationEvent.GenerationCancelled })

            h.releaseAuthority.complete(Unit)
            runCurrent()
            repeat(4) { h.repository.connectionRestored(expediteRetry = true, confirmedTransportRebuild = it + 1L); runCurrent() }

            assertEquals(listOf("first-a", "next-a"), h.postedClients("a"))
            assertTrue(h.repository.pendingDeliveries("chat").isEmpty())
            assertTrue(h.events.filterIsInstance<VerifiedConversationEvent.GenerationCancelled>().any { it.generationId == "g-a-first-a" })
            assertFalse("权威取消不应被落成普通失败", h.events.filterIsInstance<VerifiedConversationEvent.MessageStatus>().any {
                it.clientMessageId.value == "first-a" && it.status == com.openandroidintelligence.conversation.ports.AgentMessageStatus.FAILED
            })
            assertTrue("恢复读取必须绑定原client和generation", h.requests.filter { it.target.contains("clientMessageId=first-a") }.all {
                it.target.contains("generationId=g-a-first-a")
            })
        } finally { h.close() }
    }

    @Test fun aFailedAuthorityReadKeepsTheOriginalIdentityAndUnsentQueueUntilRecovery() = runTest {
        val h = Harness(this).apply { authorityUnavailable = true; releaseAuthority.complete(Unit) }
        try {
            h.queuePair("a"); h.releaseFirst("a"); runCurrent()
            assertTrue(h.firstGenerationReads("a") > 0)
            assertEquals(listOf("first-a"), h.postedClients("a"))
            assertEquals(1, h.repository.pendingDeliveries("chat").size)
            assertTrue(h.events.none { it is VerifiedConversationEvent.GenerationCancelled })

            h.authorityUnavailable = false
            h.repository.connectionRestored(expediteRetry = true, confirmedTransportRebuild = 1)
            advanceTimeBy(10); runCurrent()

            assertEquals(listOf("first-a", "next-a"), h.postedClients("a"))
            assertTrue(h.repository.pendingDeliveries("chat").isEmpty())
        } finally { h.close() }
    }

    @Test fun anUnknownReceiptNeverResubmitsTheCancelledRequestOrDrainsItsSuccessor() = runTest {
        val h = Harness(this).apply { authorityState = "unknown"; releaseAuthority.complete(Unit) }
        try {
            h.queuePair("a"); h.releaseFirst("a"); runCurrent()
            advanceTimeBy(10); runCurrent()
            assertTrue("必须实际读取原身份的不确定回执", h.firstGenerationReads("a") > 0)
            repeat(3) { h.repository.connectionRestored(); runCurrent() }
            h.repository.retryPendingDeliveries("chat"); runCurrent()

            assertEquals(listOf("first-a"), h.postedClients("a"))
            assertEquals(1, h.repository.pendingDeliveries("chat").size)
            assertEquals(GenerationState.OUTCOME_UNKNOWN, h.repository.generationState.value)
            assertTrue(h.events.none { it is VerifiedConversationEvent.GenerationCancelled })
        } finally { h.close() }
    }

    @Test fun cancellationRecoveryKeepsTheSameConversationIsolatedBetweenAgents() = runTest {
        val h = Harness(this).apply { releaseAuthority.complete(Unit) }
        try {
            h.queuePair("a")
            h.queuePair("b")
            h.releaseFirst("a"); runCurrent()

            assertEquals(listOf("first-a", "next-a"), h.postedClients("a"))
            assertEquals("另一个Agent的活动轮次和队列必须保留", listOf("first-b"), h.postedClients("b"))
            assertEquals(1, h.repository.pendingDeliveries("chat").size)

            h.releaseFirst("b"); runCurrent()
            assertEquals(listOf("first-b", "next-b"), h.postedClients("b"))
            assertTrue(h.repository.pendingDeliveries("chat").isEmpty())
        } finally { h.close() }
    }

    @Test fun aHostWithoutGenerationRecoveryDoesNotClaimACancelledTerminal() = runTest {
        val h = Harness(this, generationRecovery = false).apply { releaseAuthority.complete(Unit) }
        try {
            h.queuePair("a"); h.releaseFirst("a"); runCurrent()
            repeat(3) { h.repository.connectionRestored(); runCurrent() }

            assertEquals(0, h.firstGenerationReads("a"))
            assertEquals(listOf("first-a"), h.postedClients("a"))
            assertEquals(1, h.repository.pendingDeliveries("chat").size)
            assertEquals(StreamHealth.FAILED, h.repository.streamHealth.first())
            assertTrue(h.events.none { it is VerifiedConversationEvent.GenerationCancelled })
        } finally { h.close() }
    }

    private class Harness(private val test: TestScope, generationRecovery: Boolean = true) {
        private val dispatcher = StandardTestDispatcher(test.testScheduler)
        private val owner = CoroutineScope(SupervisorJob() + dispatcher)
        private val firstReleases = mutableMapOf<String, CompletableDeferred<Unit>>()
        private val posts = mutableListOf<Pair<String, String>>()
        val requests = mutableListOf<WireRequest>()
        val events = mutableListOf<VerifiedConversationEvent>()
        val releaseAuthority = CompletableDeferred<Unit>()
        var authorityUnavailable = false
        var authorityState = "cancelled"
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
                            releaseAuthority.await()
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
                posts += agent to client
                if (client.startsWith("first-")) firstReleases.getValue(agent).await()
                val generation = "g-$agent-$client"
                val identity = "\"generationId\":\"$generation\",\"clientMessageId\":\"$client\",\"userMessageId\":\"u-$agent-$client\""
                val frames = listOf(
                    "{\"type\":\"generation\",$identity,\"state\":\"running\",\"revision\":1}",
                    if (client.startsWith("first-")) "{\"type\":\"error\",\"code\":\"CANCELLED\",$identity}"
                    else "{\"type\":\"done\",\"outcome\":\"completed\",$identity,\"revision\":7}",
                ).joinToString("") { "event: message\ndata: $it\n\n" }
                return consume(200, listOf(RawHeader("Content-Type", "text/event-stream")), ByteArrayInputStream(frames.toByteArray()))
            }
        }
        private val client = AgentSessionClient(GatewayHttpClient(
            GatewayProfile("synthetic-account", "synthetic-device", "synthetic-session", "http://synthetic.invalid", accessToken = "synthetic"),
            transport, { ByteArray(64) }, InMemoryEventCursorStore(), ioDispatcher = dispatcher))
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

        fun releaseFirst(agent: String) { firstReleases.getValue(agent).complete(Unit) }
        fun postedClients(agent: String): List<String> = posts.filter { it.first == agent }.map { it.second }
        fun firstGenerationReads(agent: String): Int = requests.count { it.target.contains("clientMessageId=first-$agent") }
        fun close() { subscription.cancel(); repository.close(); owner.cancel() }
    }
}
