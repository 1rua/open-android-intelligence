package com.openandroidintelligence.gateway.commands

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
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandCatalogClientTest {

    private class RecordingTransport(
        var response: WireResponse = json(
            200,
            """{"protocol":"2.1","data":{"format":"agent-command-catalog-1.0","catalogVersion":"host-v4","languageCode":"zh-CN","commands":[{"id":"restart","invocation":" /restart ","title":"Restart service","description":"Restart the current service","acceptsArguments":true,"availability":"available","argumentHint":"[service]"}]}}""",
        ),
    ) : GatewayByteTransport {
        var request: WireRequest? = null
        override suspend fun execute(request: WireRequest): WireResponse {
            this.request = request
            return response
        }
        override fun eventStream(request: WireRequest): Flow<ByteArray> = emptyFlow()
    }

    private class MemoryCursorStore : EventCursorStore {
        override fun load(accountId: String): String? = null
        override fun save(accountId: String, cursor: String) = Unit
        override fun clear(accountId: String) = Unit
    }

    private fun client(transport: RecordingTransport) = CommandCatalogClient(
        GatewayHttpClient(
            GatewayProfile("account", "device", "session", "https://gateway.example.test"),
            transport,
            { ByteArray(64) },
            MemoryCursorStore(),
        ),
    )

    @Test
    fun readsTheSelectedHostsCatalogAndPreservesItsCommandMetadata() = runBlocking {
        val transport = RecordingTransport()
        val catalog = client(transport).get("zh-CN")

        assertEquals("GET", transport.request?.method)
        assertEquals("/open-android-intelligence/v2/commands?languageCode=zh-CN", transport.request?.target)
        assertEquals("agent-command-catalog-1.0", catalog.format)
        assertEquals("host-v4", catalog.catalogVersion)
        assertEquals("zh-CN", catalog.languageCode)
        assertEquals(1, catalog.commands.size)
        assertEquals(
            CommandCatalogEntry(
                "restart", "/restart", "Restart service", "Restart the current service", true, "available", "[service]",
            ),
            catalog.commands.single(),
        )
    }

    @Test
    fun anAnsweredEmptyCatalogRemainsEmpty() = runBlocking {
        val transport = RecordingTransport(
            json(200, """{"protocol":"2.1","data":{"catalogVersion":"empty-v1","commands":[]}}"""),
        )

        assertTrue(client(transport).get("en").commands.isEmpty())
    }

    @Test
    fun nonSuccessResponseIsAnErrorAndDoesNotBecomeAnEmptyCatalog() = runBlocking {
        val transport = RecordingTransport(json(503, """{"protocol":"2.1","data":{}}"""))

        val error = runCatching { client(transport).get("en") }.exceptionOrNull()
        assertEquals("COMMAND_CATALOG_FAILED:503", error?.message)
    }

    @Test
    fun encodesLanguageCodeAndNormalizesAValidBareInvocation() = runBlocking {
        val transport = RecordingTransport(
            json(
                200,
                """{"protocol":"2.1","data":{"catalogVersion":"host-v4","commands":[{"invocation":" ping ","title":"Ping","argumentHint":"[target]"}]}}""",
            ),
        )

        val catalog = client(transport).get("zh-CN +/beta~*")

        assertEquals(
            "/open-android-intelligence/v2/commands?languageCode=zh-CN%20%2B%2Fbeta~%2A",
            transport.request?.target,
        )
        assertEquals("/ping", catalog.commands.single().invocation)
        assertEquals("[target]", catalog.commands.single().argumentHint)
    }

    @Test
    fun aCatalogContainingOnlyMalformedInvocationsFailsClearly() = runBlocking {
        val transport = RecordingTransport(
            json(
                200,
                """{"protocol":"2.1","data":{"catalogVersion":"host-v4","commands":[{"invocation":"   "},{"invocation":"/has spaces"},{"title":"Missing invocation"}]}}""",
            ),
        )

        val error = runCatching { client(transport).get("en") }.exceptionOrNull()
        assertEquals("COMMAND_CATALOG_FAILED:no-valid-commands", error?.message)
    }

    @Test
    fun malformedEntriesAreSkippedWhenTheCatalogHasValidCommands() = runBlocking {
        val transport = RecordingTransport(
            json(
                200,
                """{"protocol":"2.1","data":{"catalogVersion":"host-v4","commands":[{"invocation":"/valid"},{"invocation":"/invalid command"}]}}""",
            ),
        )

        assertEquals(listOf("/valid"), client(transport).get("en").commands.map { it.invocation })
    }

    private companion object {
        fun json(status: Int, body: String) = WireResponse(
            status = status,
            headers = listOf(RawHeader("content-type", "application/json")),
            body = body.toByteArray(Charsets.UTF_8),
        )
    }
}
