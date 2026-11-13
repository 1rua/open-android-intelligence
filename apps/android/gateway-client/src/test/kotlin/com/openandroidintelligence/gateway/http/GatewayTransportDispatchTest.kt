package com.openandroidintelligence.gateway.http

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class GatewayTransportDispatchTest {
    @Test fun localLifecycleRejectionDoesNotCrossTheDispatchFence() = runBlocking {
        var dispatched = false
        val transport = GatewayTransport(profile, beforeConnect = { error("OWNER_RETIRED") })
        assertEquals("OWNER_RETIRED", runCatching {
            transport.execute(request) { dispatched = true }
        }.exceptionOrNull()?.message)
        assertFalse(dispatched)
    }

    @Test fun aFailedDurableFencePreventsEvenTheConnectionFromStarting() = runBlocking {
        val connection = Connection()
        val transport = GatewayTransport(profile, GatewayConnectionFactory { connection })
        assertEquals("DISK_UNAVAILABLE", runCatching {
            transport.execute(request) { throw IOException("DISK_UNAVAILABLE") }
        }.exceptionOrNull()?.message)
        assertEquals(0, connection.connects)
        assertTrue(connection.disconnected)
    }

    @Test fun theDurableFenceCompletesBeforeTheActualConnectionStarts() = runBlocking {
        val connection = Connection()
        var durable = false
        connection.onConnect = { assertTrue("连接不能早于持久化完成", durable) }
        val transport = GatewayTransport(profile, GatewayConnectionFactory { connection })
        assertEquals(200, transport.execute(request) {
            assertEquals(0, connection.connects)
            durable = true
        }.status)
        assertEquals(1, connection.connects)
    }

    private class Connection : HttpURLConnection(URL("http://core.example")) {
        var connects = 0
        var disconnected = false
        var onConnect: () -> Unit = {}
        override fun connect() { onConnect(); connects++ }
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getResponseCode() = 200
        override fun getHeaderFields(): Map<String, List<String>> = emptyMap()
        override fun getInputStream() = ByteArrayInputStream("{}".toByteArray())
    }

    private val profile = GatewayProfile("account", "phone", "login", "http://core.example", accessToken = "synthetic")
    private val request = WireRequest("POST", "/agents/hermes/sessions")
}
