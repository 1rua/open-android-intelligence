package com.openandroidintelligence.gateway.http

import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayEventHealthCheckTest {
    @Test
    fun aForegroundStreamWithAnAdvertisedHeartbeatRecoversWithoutAnyWakeup() = runTest {
        var attempts = 0
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("健康检测不加载会话")
            override fun eventHeartbeatMillis(): Long? = 1_000
            override fun eventStream(request: WireRequest): Flow<ByteArray> = flow {
                if (++attempts == 1) {
                    emit(": heartbeat\n\n".toByteArray())
                    awaitCancellation()
                } else emit("id: restored\ndata: {}\n\n".toByteArray())
            }
        }
        val client = GatewayHttpClient(
            GatewayProfile("account", "device", "session", "https://gateway.example", accessToken = "test"),
            transport, { ByteArray(64) }, InMemoryEventCursorStore(), monotonicMillis = { testScheduler.currentTime },
            jitterFn = { it },
        )
        client.setAppForeground(true)
        val received = async { client.events().take(1).toList() }
        runCurrent()
        advanceTimeBy(9_000)
        runCurrent()
        assertTrue("持续前台不能依赖生命周期或会话操作才检测静默连接", received.isCompleted)
        assertEquals("restored", received.await().single().id)
        assertEquals(2, attempts)
    }
    @Test
    fun localMessageCommitDoesNotCountAsANetworkHeartbeatTimeout() = runTest {
        var connections = 0
        val logs = mutableListOf<String>()
        val previous = com.openandroidintelligence.gateway.diagnostics.GatewayLog.sink
        com.openandroidintelligence.gateway.diagnostics.GatewayLog.sink = { _, line -> logs += line }
        try {
            val transport = object : GatewayByteTransport {
                override suspend fun execute(request: WireRequest): WireResponse = error("本场景只接收事件")
                override fun eventStream(request: WireRequest): Flow<ByteArray> = flow {
                    connections++
                    emit("id: saved\ndata: {}\n\n".toByteArray())
                    while (true) { emit(": heartbeat\n\n".toByteArray()); kotlinx.coroutines.delay(100) }
                }
            }
            val client = GatewayHttpClient(
                GatewayProfile("account", "device", "session", "https://gateway.example", accessToken = "test"),
                transport, { ByteArray(64) }, InMemoryEventCursorStore(), monotonicMillis = { testScheduler.currentTime },
            )
            val collecting = backgroundScope.launch {
                client.events().collect { kotlinx.coroutines.delay(5_000) }
            }
            runCurrent()
            client.requestEventHealthCheck("foreground")
            advanceTimeBy(5_100)
            runCurrent()
            assertEquals("本地消息提交阻塞不能误判网络断线", 1, connections)
            assertTrue("本地提交不得产生网络心跳超时", logs.none { it.contains("connection.heartbeat_timeout") })
            collecting.cancel()
        } finally { com.openandroidintelligence.gateway.diagnostics.GatewayLog.sink = previous }
    }
    @Test
    fun foregroundValidationReplacesASilentStreamAndResumesTheCommittedCursor() = runTest {
        val cursors = InMemoryEventCursorStore().apply { save("account", "committed") }
        var connections = 0
        var active = 0
        val targets = mutableListOf<String>()
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("健康检测不读取或创建会话")
            override fun eventStream(request: WireRequest): Flow<ByteArray> = flow {
                targets += request.target
                connections++
                active++
                assertEquals("同一时刻只能保留一条正式订阅", 1, active)
                try {
                    if (connections == 1) {
                        emit(": heartbeat\n\n".toByteArray())
                        awaitCancellation()
                    } else emit("id: recovered\ndata: {}\n\n".toByteArray())
                } finally { active-- }
            }
        }
        val client = GatewayHttpClient(
            GatewayProfile("account", "device", "session", "https://gateway.example", accessToken = "test"),
            transport, { ByteArray(64) }, cursors, monotonicMillis = { testScheduler.currentTime }, jitterFn = { it },
        )
        val received = async { client.events().take(1).toList() }
        runCurrent()
        client.setAppForeground(true)
        client.requestEventHealthCheck("foreground")
        repeat(5) { client.requestEventHealthCheck("foreground") }
        advanceTimeBy(9_000)
        runCurrent()
        assertTrue("静默连接必须在十秒内恢复真实事件，重复唤醒不得累加等待", received.isCompleted)
        assertEquals("recovered", received.await().single().id)
        assertEquals(2, connections)
        assertTrue(targets.all { it.endsWith("?cursor=committed") })
    }
}
