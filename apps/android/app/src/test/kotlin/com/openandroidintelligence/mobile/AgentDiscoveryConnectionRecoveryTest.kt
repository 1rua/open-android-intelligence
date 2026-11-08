package com.openandroidintelligence.mobile

import com.openandroidintelligence.mobile.conversations.CoreAgentConversationRepository
import com.openandroidintelligence.mobile.conversations.NativeEventSynchronizerTest
import com.openandroidintelligence.conversation.model.StreamHealth
import com.openandroidintelligence.gateway.agents.AgentSessionClient
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentDiscoveryConnectionRecoveryTest {
    @Test fun agents500MustNotInventHermesOfflineOrInterruptHealthyNativeStream() = runTest {
        var calls = 0
        val idle = CompletableDeferred<Unit>()
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                val body = when {
                    request.target == AgentSessionClient.ROOT -> {
                        calls++
                        if (calls == 2) return WireResponse(500, emptyList(), """{"error":{"code":"INTERNAL_ERROR"}}""".toByteArray())
                        """{"agents":[{"id":"hermes","name":"Hermes","kind":"hermes","online":true,"sessionScope":"account","capabilities":{"history":true,"eventSync":true}}]}"""
                    }
                    request.target.contains("/events?") -> {
                        if (request.target.contains("waitMs=")) idle.await()
                        """{"events":[],"cursor":"probe_cursor","hasMore":false,"approvals":[]}"""
                    }
                    request.target.endsWith("/history") -> """{"messages":[]}"""
                    request.target.endsWith("/events/ack") -> """{"cursor":"probe_cursor"}"""
                    else -> error("非预期请求: " + request.target)
                }
                return WireResponse(200, emptyList(), body.toByteArray())
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val http = GatewayHttpClient(GatewayProfile("account","phone","session","https://core.example"),
            transport, { ByteArray(64) }, InMemoryEventCursorStore(), ioDispatcher = dispatcher)
        val client = AgentSessionClient(http)
        val controller = NativeAgentsController(client, backgroundScope, dispatcher)
        runCurrent()
        val storage = NativeEventSynchronizerTest.Storage()
        val repository = CoreAgentConversationRepository(client, { controller.state.value.agent }, { "chat" },
            backgroundScope, dispatcher, storeForAgent = { storage }, nativeRetryJitter = { it })
        try {
            repository.activeConversationChanged(); runCurrent()
            val initial = controller.state.value.agent?.online
            val initialHealth = repository.streamHealth.first()
            advanceTimeBy(10000); runCurrent()
            val afterReadFailure = controller.state.value.agent?.online
            val failure = controller.state.value.error
            val failedDiscoveryHealth = repository.streamHealth.first()
            advanceTimeBy(10000); runCurrent()
            val afterRecovery = controller.state.value.agent?.online
            val recoveredHealth = repository.streamHealth.first()
            println("发现刷新次数=$calls；HTTP状态=200 → 500 → 200；Hermes在线=$initial → $afterReadFailure → $afterRecovery；Native健康=$initialHealth → $failedDiscoveryHealth → $recoveredHealth；发现错误=$failure")
            assertEquals("第一次成功发现确认在线", true, initial)
            assertEquals("第一次事件同步成功", StreamHealth.LIVE, initialHealth)
            assertEquals("下一次成功发现仍确认在线", true, afterRecovery)
            assertEquals("发现错误不应破坏健康 Native 流", StreamHealth.LIVE, failedDiscoveryHealth)
            assertEquals("恢复发现后 Native 流仍健康", StreamHealth.LIVE, recoveredHealth)
            assertEquals("一次 /agents 500 不能制造 Hermes 已离线事实", true, afterReadFailure)
            assertEquals("发现错误仍须如实保留", "INTERNAL_ERROR", failure)
            assertNull("下一次成功发现清除读取错误", controller.state.value.error)
        } finally { repository.close(); controller.close() }
    }
}
