package com.openandroidintelligence.gateway.http

import com.openandroidintelligence.gateway.diagnostics.GatewayLog
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayConnectionDiagnosticsTest {
    @Test
    fun recoveredEventsHaveStructuredDiagnosticsWithoutCredentialsOrPayloads() = runTest {
        val logs = mutableListOf<String>()
        val previous = GatewayLog.sink
        GatewayLog.sink = { _, message -> logs += message }
        try {
            var attempts = 0
            val transport = object : GatewayByteTransport {
                override suspend fun execute(request: WireRequest): WireResponse = error("本场景不发送普通请求")
                override fun eventStream(request: WireRequest): Flow<ByteArray> = flow {
                    if (++attempts == 1) throw IOException("secret_failure_body")
                    emit("id: first\ndata: secret_event_body\n\nid: next\ndata: {}\n\n".toByteArray())
                }
            }
            val client = GatewayHttpClient(
                GatewayProfile("account", "device", "session", "https://gateway.example", accessToken = "secret_access_token"),
                transport, { ByteArray(64) }, InMemoryEventCursorStore(),
                monotonicMillis = { testScheduler.currentTime }, delayFn = {}, jitterFn = { it },
            )
            client.events().take(2).toList()
            val required = listOf("connection.state_changed", "connection.reconnect_scheduled",
                "connection.reconnect_started", "connection.reconnect_succeeded", "connection.reconnect_failed",
                "connection.subscription_restored")
            required.forEach { type ->
                assertTrue("恢复链路必须产生结构化诊断：$type", logs.any {
                    it.contains("\"type\":\"$type\"") && it.contains("\"generation\":") &&
                        it.contains("\"reason\":") && it.contains("\"attempt\":") &&
                        it.contains("\"result\":") && it.contains("\"at\":")
                })
            }
            assertFalse("日志不得包含凭据、异常正文或事件正文", logs.joinToString().contains("secret_"))
        } finally {
            GatewayLog.sink = previous
        }
    }
}
