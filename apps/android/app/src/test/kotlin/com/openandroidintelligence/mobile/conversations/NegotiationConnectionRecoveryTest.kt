package com.openandroidintelligence.mobile.conversations

import com.openandroidintelligence.conversation.model.StreamHealth
import com.openandroidintelligence.gateway.agents.*
import com.openandroidintelligence.gateway.auth.GatewaySessionRenewal
import com.openandroidintelligence.gateway.events.*
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.gateway.negotiation.NegotiationClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import javax.net.ssl.SSLHandshakeException

@OptIn(ExperimentalCoroutinesApi::class)
class NegotiationConnectionRecoveryTest {
    private val negotiatedBody = """{"protocol":"2.1","data":{"protocol":{"major":2,"minor":1},"features":{"auth":["password","refresh"],"messages":"chat-v1","attachments":"staged-sha256-v1","events":"sse-cursor-v1","deviceRequests":"risk-queue-v1"},"limits":{"attachmentTtlSeconds":3600,"eventRetentionSeconds":86400,"maxClockSkewSeconds":120},"gatewayIdentity":{"deploymentId":"probe","tlsSpkiSha256":null}}}""".toByteArray()

    @Test fun negotiation500DuringPlatformRenewalMustRemainRetryable() = runTest {
        val profile = GatewayProfile("account","phone","login","https://core.example",accessToken="synthetic")
        var negotiationCalls=0
        var serviceRecovered=false
        val negotiation = NegotiationClient({
            negotiationCalls++
            if(serviceRecovered) GatewayResponse(200,emptyList(),negotiatedBody)
            else GatewayResponse(500,emptyList(),ByteArray(0))
        },"phone","test",34)
        val renewal = GatewaySessionRenewal(profile, backgroundScope) {
            negotiation.negotiate("neg_probe")
            it.copy(sessionId="rotated")
        }
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request:WireRequest):WireResponse = error("非预期 HTTP 请求")
            override fun eventStream(request:WireRequest):Flow<ByteArray> = flow {
                if(!serviceRecovered) throw EventSessionRejectedException(profile)
                emit(": heartbeat\n\n".toByteArray());awaitCancellation()
            }
        }
        val status = EventStreamStatusSink()
        val client = GatewayHttpClient(profile,transport,{ByteArray(64)},InMemoryEventCursorStore(),
            sessionRenewal=renewal,statusSink=status,ioDispatcher=StandardTestDispatcher(testScheduler),delayFn={delay(it)})
        var failure:Throwable? = null
        try {
            val observing = backgroundScope.launch { try { client.events().collect() } catch (cause:Exception) { failure=cause } }
            runCurrent()
            val unavailableStatus = status.status.value
            println("Platform首次协商500；状态=${status.status.value}；观察任务活跃=${observing.isActive}；错误=${failure?.javaClass?.simpleName}:${failure?.message}；协商次数=$negotiationCalls")
            serviceRecovered=true
            advanceTimeBy(120000);runCurrent()
            println("服务恢复后推进120秒；Platform状态=${status.status.value}；观察任务活跃=${observing.isActive}；协商次数=$negotiationCalls")
            assertTrue("服务恢复后自动重连应再次抵达协商入口",negotiationCalls>=2)
            assertEquals("协商 500 期间保持可恢复状态", EventStreamStatus.RECONNECTING, unavailableStatus)
            assertEquals("服务器恢复后通道应恢复",EventStreamStatus.LIVE,status.status.value)
            assertTrue("服务器500不应让事件观察永久结束",observing.isActive)
            assertNull(failure)
        } finally { renewal.close() }
    }

    @Test fun negotiation500DuringNativeRenewalMustRemainRetryable() = runTest {
        val profile=GatewayProfile("account","phone","login","https://core.example",accessToken="synthetic")
        var negotiationCalls=0
        var serviceRecovered=false
        val negotiation=NegotiationClient({
            negotiationCalls++
            if(serviceRecovered) GatewayResponse(200,emptyList(),negotiatedBody)
            else GatewayResponse(500,emptyList(),ByteArray(0))
        },"phone","test",34)
        val renewal=GatewaySessionRenewal(profile,backgroundScope) {
            negotiation.negotiate("neg_probe")
            it.copy(sessionId="rotated")
        }
        val idle=CompletableDeferred<Unit>()
        val transport=object:GatewayByteTransport {
            override suspend fun execute(request:WireRequest):WireResponse {
                if(request.headers.any { it.name == "X-Open-Android-Intelligence-Session" && it.value == "login" }) return WireResponse(401,emptyList(),ByteArray(0))
                val body=when {
                    request.target.contains("/events?") -> {
                        if(request.target.contains("waitMs=")) idle.await()
                        """{"events":[],"cursor":"next","hasMore":false,"approvals":[]}"""
                    }
                    request.target.endsWith("/history") -> """{"messages":[]}"""
                    request.target.endsWith("/events/ack") -> """{"cursor":"next"}"""
                    else -> error("非预期请求")
                }
                return WireResponse(200,emptyList(),body.toByteArray())
            }
            override fun eventStream(request:WireRequest)=emptyFlow<ByteArray>()
        }
        val client=AgentSessionClient(GatewayHttpClient(profile,transport,{ByteArray(64)},InMemoryEventCursorStore(),
            sessionRenewal=renewal,ioDispatcher=StandardTestDispatcher(testScheduler)))
        val agent=CoreAgent("hermes","Hermes","hermes",true,"account",AgentCapabilities(history=true,eventSync=true))
        val storage=NativeEventSynchronizerTest.Storage()
        val repo=CoreAgentConversationRepository(client,{agent},{"chat"},backgroundScope,StandardTestDispatcher(testScheduler),
            storeForAgent={storage},nativeRetryJitter={it})
        try {
            repo.activeConversationChanged();runCurrent()
            val unavailableStatus = repo.streamHealth.first()
            println("Native首次协商500；状态=${repo.streamHealth.first()}；错误=${repo.streamFailure.first()}；协商次数=$negotiationCalls")
            serviceRecovered=true
            repeat(5) { repo.connectionRestored();runCurrent() }
            advanceTimeBy(120000);runCurrent()
            println("服务恢复后5次连接唤醒并推进120秒；Native状态=${repo.streamHealth.first()}；协商次数=$negotiationCalls")
            assertEquals("500后恢复应再次抵达协商入口",2,negotiationCalls)
            assertEquals("协商 500 期间保持可恢复状态", StreamHealth.RECONNECTING, unavailableStatus)
            assertEquals("服务器恢复后应继续原生同步",StreamHealth.LIVE,repo.streamHealth.first())
        } finally { repo.close();renewal.close() }
    }

    @Test fun platformRenewalKeepsAuthenticationTlsAndProtocolRejectionsBlocked() = runTest {
        // 200 空正文表示协议内容损坏；0 用于模拟 TLS 身份验证失败，均不可重试。
        for (statusCode in listOf(401, 403, 406, 409, 200, 0, 503, 502)) {
            val profile = GatewayProfile("account", "phone", "login", "https://core.example", accessToken = "synthetic")
            var negotiationCalls = 0
            val negotiation = rejectedNegotiation(statusCode) { negotiationCalls++ }
            val renewal = GatewaySessionRenewal(profile, backgroundScope) {
                negotiation.negotiate("neg_rejected")
                it.copy(sessionId = "forbidden_rotation")
            }
            val transport = object : GatewayByteTransport {
                override suspend fun execute(request: WireRequest): WireResponse = error("非预期 HTTP 请求")
                override fun eventStream(request: WireRequest): Flow<ByteArray> = flow { throw EventSessionRejectedException(profile) }
            }
            val status = EventStreamStatusSink()
            val client = GatewayHttpClient(profile, transport, { ByteArray(64) }, InMemoryEventCursorStore(),
                sessionRenewal = renewal, statusSink = status, ioDispatcher = StandardTestDispatcher(testScheduler))
            var failure: Exception? = null
            try {
                val observing = backgroundScope.launch {
                    try { client.events().collect() } catch (cause: Exception) { failure = cause }
                }
                runCurrent()
                assertEquals("真实拒绝必须终止自动重试：$statusCode", EventStreamStatus.FAILED, status.status.value)
                assertFalse(observing.isActive)
                assertNotNull(failure)
                advanceTimeBy(120_000); runCurrent()
                assertEquals(1, negotiationCalls)
                assertEquals(profile, renewal.current)
            } finally { renewal.close() }
        }
    }

    @Test fun nativeRenewalKeepsAuthenticationTlsAndProtocolRejectionsBlocked() = runTest {
        for (statusCode in listOf(401, 403, 406, 409, 200, 0, 503, 502)) {
            val profile = GatewayProfile("account", "phone", "login", "https://core.example", accessToken = "synthetic")
            var negotiationCalls = 0
            val negotiation = rejectedNegotiation(statusCode) { negotiationCalls++ }
            val renewal = GatewaySessionRenewal(profile, backgroundScope) {
                negotiation.negotiate("neg_rejected")
                it.copy(sessionId = "forbidden_rotation")
            }
            val transport = object : GatewayByteTransport {
                override suspend fun execute(request: WireRequest) = WireResponse(401, emptyList(), ByteArray(0))
                override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
            }
            val client = AgentSessionClient(GatewayHttpClient(profile, transport, { ByteArray(64) }, InMemoryEventCursorStore(),
                sessionRenewal = renewal, ioDispatcher = StandardTestDispatcher(testScheduler)))
            val agent = CoreAgent("hermes", "Hermes", "hermes", true, "account", AgentCapabilities(history = true, eventSync = true))
            val storage = NativeEventSynchronizerTest.Storage()
            val repository = CoreAgentConversationRepository(client, { agent }, { "chat" }, backgroundScope,
                StandardTestDispatcher(testScheduler), storeForAgent = { storage }, nativeRetryJitter = { it })
            try {
                repository.activeConversationChanged(); runCurrent()
                assertEquals("真实拒绝必须保留 Native 恢复栅栏：$statusCode", StreamHealth.FAILED, repository.streamHealth.first())
                val failure = repository.streamFailure.first()
                repeat(5) { repository.connectionRestored(); runCurrent() }
                advanceTimeBy(120_000); runCurrent()
                assertEquals(1, negotiationCalls)
                assertEquals(failure, repository.streamFailure.first())
                assertEquals(profile, renewal.current)
                assertEquals("base", storage.cursor)
            } finally { repository.close(); renewal.close() }
        }
    }

    private fun rejectedNegotiation(status: Int, onAttempt: () -> Unit) = NegotiationClient({
        onAttempt()
        if (status == 0) throw SSLHandshakeException("合成证书身份拒绝")
        // 即使 HTTP 状态是 5xx，明确的身份或协议拒绝仍然必须终止。
        val body = when (status) {
            503 -> """{"error":{"code":"SESSION_REVOKED","retryable":true}}"""
            502 -> """{"error":{"code":"PROTOCOL_INCOMPATIBLE","retryable":true}}"""
            else -> ""
        }
        GatewayResponse(status, emptyList(), body.toByteArray())
    }, "phone", "test", 34)
}
