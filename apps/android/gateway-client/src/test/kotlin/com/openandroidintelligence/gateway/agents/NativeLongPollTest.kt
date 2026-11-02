package com.openandroidintelligence.gateway.agents

import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NativeLongPollTest {
    private val agent = CoreAgent("hermes", "synthetic", "hermes", false, "account", AgentCapabilities(eventSync=true))

    @Test fun aFullTwentyFiveSecondJsonPollIsNotWrappedInTheSseIdleDeadline() = runTest {
        var streams = 0
        val requests = mutableListOf<WireRequest>()
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                requests += request
                delay(25_000)
                return WireResponse(200, emptyList(), """{"events":[],"cursor":"next","hasMore":false}""".toByteArray())
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>().also { streams++ }
        }
        val client = AgentSessionClient(GatewayHttpClient(GatewayProfile("account", "phone", "login", "https://core.example", accessToken="synthetic"),
            transport, { ByteArray(64) }, InMemoryEventCursorStore(), ioDispatcher=UnconfinedTestDispatcher(testScheduler)))
        assertEquals("next", withTimeout(30_000) { client.events(agent, "chat", cursor="base", waitMs=25_000) }.cursor)
        assertEquals(25_000L, testScheduler.currentTime)
        assertEquals(0, streams)
        assertEquals("GET", requests.single().method)
        assertTrue(requests.single().target.endsWith("cursor=base&limit=128&waitMs=25000"))
    }

    @Test fun nativeJsonUsesTheThirtySecondReadTimeoutOnTheRealHttpTransport() = runBlocking {
        val connection = object : HttpURLConnection(URL("http://core.example")) {
            override fun connect() { connected = true }
            override fun disconnect() { connected = false }
            override fun usingProxy() = false
            override fun getResponseCode() = 200
            override fun getHeaderFields(): Map<String, List<String>> = mapOf("Content-Type" to listOf("application/json"))
            override fun getInputStream() = ByteArrayInputStream("""{"events":[],"cursor":"next","hasMore":false}""".toByteArray())
        }
        val profile = GatewayProfile("account", "phone", "login", "http://core.example", accessToken="synthetic")
        val client = AgentSessionClient(GatewayHttpClient(profile, GatewayTransport(profile, GatewayConnectionFactory { connection }),
            { ByteArray(64) }, InMemoryEventCursorStore()))
        assertEquals("next", client.events(agent, "chat", cursor="base", waitMs=25_000).cursor)
        assertEquals(15_000, connection.connectTimeout)
        assertEquals(30_000, connection.readTimeout)
    }
}
