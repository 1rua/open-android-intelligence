package com.openandroidintelligence.mobile.conversations

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
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CoreAgentCommandCatalogRepositoryTest {
    @Test fun catalogChangesWithTheSelectedAgentAndPreservesItsParameterPlaceholder() = runBlocking {
        val requests = mutableListOf<WireRequest>()
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse {
                requests += request
                val invocation = if (request.target.contains("/hermes/")) "/model" else "/status"
                return WireResponse(200, listOf(RawHeader("Content-Type", "application/json")),
                    """{"format":"agent-command-catalog-1.0","catalogVersion":"host-v1","languageCode":"zh-CN","commands":[{"invocation":"$invocation","description":"宿主命令","argumentHint":"<name>","acceptsArguments":true}]}""".toByteArray())
            }
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        var selected = CoreAgent("hermes", "Hermes", "hermes", true, "account", AgentCapabilities(commands = true))
        val repository = CoreAgentCommandCatalogRepository(client(transport), { selected }, true)
        assertEquals("/model", repository.get("https://core", "zh-CN").commands.single().command)
        selected = selected.copy(id = "other")
        val command = repository.get("https://core", "zh-CN").commands.single()
        assertEquals("/status", command.command)
        assertEquals("<name>", command.argumentHint)
        assertEquals("宿主命令", command.description)
        assertEquals(listOf("/open-android-intelligence/v2/agents/hermes/commands?languageCode=zh-CN",
            "/open-android-intelligence/v2/agents/other/commands?languageCode=zh-CN"), requests.map { it.target })
    }

    @Test fun missingNegotiationDoesNotFetchOrInventHostCommands() = runBlocking {
        val transport = object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = error("A disabled catalog must not request the server")
            override fun eventStream(request: WireRequest) = emptyFlow<ByteArray>()
        }
        val agent = CoreAgent("hermes", "Hermes", "hermes", true, "account", AgentCapabilities(commands = true))
        val failure = runCatching { CoreAgentCommandCatalogRepository(client(transport), { agent }, false).get("https://core", "zh-CN") }.exceptionOrNull()
        assertEquals("COMMAND_CATALOG_UNSUPPORTED", failure?.message)
    }

    private fun client(transport: GatewayByteTransport) = AgentSessionClient(GatewayHttpClient(
        GatewayProfile("account", "device", "session", "https://core.example", accessToken = "access"),
        transport, { ByteArray(64) }, InMemoryEventCursorStore()))
}
