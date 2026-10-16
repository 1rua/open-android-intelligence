package com.openandroidintelligence.conversation.data

import com.openandroidintelligence.conversation.model.CatalogVersion
import com.openandroidintelligence.conversation.ports.AgentCommand
import com.openandroidintelligence.gateway.events.EventCursorStore
import com.openandroidintelligence.gateway.http.GatewayByteTransport
import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.GatewayProfile
import com.openandroidintelligence.gateway.http.RawHeader
import com.openandroidintelligence.gateway.http.WireRequest
import com.openandroidintelligence.gateway.http.WireResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayCommandCatalogRepositoryTest {

    @Test
    fun mapsHostCommandHintsAndKeepsUnavailableInvocationsSelectable() = runBlocking {
        val transport = CatalogTransport()
        val http = GatewayHttpClient(
            GatewayProfile("account", "device", "session", "https://gateway.example.test"),
            transport,
            { ByteArray(64) },
            MemoryCursorStore(),
        )
        val repository = GatewayCommandCatalogRepository(
            com.openandroidintelligence.gateway.commands.CommandCatalogClient(http),
        )

        val catalog = repository.get("gateway", "en")

        assertEquals(CatalogVersion("host-v2"), catalog.version)
        assertEquals(
            AgentCommand("/restart", "Restart this service", "[service]"),
            catalog.commands.single(),
        )
    }

    private class CatalogTransport : GatewayByteTransport {
        override suspend fun execute(request: WireRequest): WireResponse = WireResponse(
            status = 200,
            headers = listOf(RawHeader("content-type", "application/json")),
            body = """{"protocol":"2.1","data":{"catalogVersion":"host-v2","commands":[{"invocation":" /restart ","title":"Restart","description":"Restart this service","acceptsArguments":true,"availability":"unavailable","argumentHint":"[service]"}]}}""".toByteArray(Charsets.UTF_8),
        )

        override fun eventStream(request: WireRequest): Flow<ByteArray> = emptyFlow()
    }

    private class MemoryCursorStore : EventCursorStore {
        override fun load(accountId: String): String? = null
        override fun save(accountId: String, cursor: String) = Unit
        override fun clear(accountId: String) = Unit
    }
}
