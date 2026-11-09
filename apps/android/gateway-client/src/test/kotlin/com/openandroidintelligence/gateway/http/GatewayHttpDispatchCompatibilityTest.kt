package com.openandroidintelligence.gateway.http

import com.openandroidintelligence.gateway.agents.AgentCapabilities
import com.openandroidintelligence.gateway.agents.AgentSessionClient
import com.openandroidintelligence.gateway.agents.CoreAgent
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class GatewayHttpDispatchCompatibilityTest {
    @Test fun anOrdinarySignedRequestKeepsTheSingleArgumentDecoratorEntry() = runBlocking {
        val fixture = Fixture()
        val target = "/open-android-intelligence/v2/snapshot"
        assertEquals(200, fixture.http.execute(SignedGatewayRequest("GET", target)).status)
        assertEquals("没有围栏的请求必须保留原传输观察入口", listOf(target), fixture.observed.map { it.target })
        assertEquals(1, fixture.connections.single().connects)
    }

    @Test fun nativeReadsAndDefaultCreationKeepTheSingleArgumentDecoratorEntry() = runBlocking {
        val fixture = Fixture()
        val client = AgentSessionClient(fixture.http)
        assertTrue(client.agents().isEmpty())
        assertEquals("created", client.create(agent).id)
        assertEquals(listOf(AgentSessionClient.ROOT, "${AgentSessionClient.ROOT}/hermes/sessions"),
            fixture.observed.map { it.target })
    }

    @Test fun anExplicitCreationFenceStillCompletesBeforeTheRealConnectionStarts() = runBlocking {
        val fixture = Fixture()
        var durable = false
        fixture.onConnect = { assertTrue("显式创建围栏必须在连接之前完成", durable) }
        val created = AgentSessionClient(fixture.http).create(agent, requestId = "original-create",
            beforeDispatch = { durable = true })
        assertEquals("created", created.id)
        assertEquals(1, fixture.connections.single().connects)
    }

    @Test fun aFailedExplicitCreationFenceStillPreventsTheRealConnection() = runBlocking {
        val fixture = Fixture()
        val failure = runCatching {
            AgentSessionClient(fixture.http).create(agent, requestId = "original-create",
                beforeDispatch = { throw IOException("DURABLE_SAVE_FAILED") })
        }.exceptionOrNull()
        assertEquals("DURABLE_SAVE_FAILED", failure?.message)
        assertEquals(0, fixture.connections.single().connects)
    }

    private class Fixture {
        val connections = mutableListOf<Connection>()
        val observed = mutableListOf<WireRequest>()
        var onConnect: () -> Unit = {}
        private val profile = GatewayProfile("account", "phone", "session", "http://core.example", accessToken = "synthetic")
        private val production = GatewayTransport(profile, GatewayConnectionFactory { url ->
            Connection(url) { onConnect() }.also { connections += it }
        })
        // 已有装饰器仅覆盖单参入口；新增可选 API 不能让普通请求绕过它。
        private val decorated = object : GatewayByteTransport by production {
            override suspend fun execute(request: WireRequest): WireResponse {
                observed += request
                return production.execute(request)
            }
        }
        val http = GatewayHttpClient(profile, decorated, { ByteArray(64) }, InMemoryEventCursorStore(),
            ioDispatcher = Dispatchers.Unconfined)
    }

    private class Connection(url: URL, private val onConnect: () -> Unit) : HttpURLConnection(url) {
        var connects = 0
        override fun connect() { onConnect(); connects++ }
        override fun disconnect() = Unit
        override fun usingProxy() = false
        override fun getResponseCode() = 200
        override fun getHeaderFields(): Map<String, List<String>> = emptyMap()
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream("""{"agents":[],"session":{"id":"created"}}""".toByteArray())
    }

    private val agent = CoreAgent("hermes", "Hermes", "hermes", true, "account", AgentCapabilities(create = true))
}
