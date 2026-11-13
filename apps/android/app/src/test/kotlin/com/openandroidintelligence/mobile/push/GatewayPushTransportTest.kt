package com.openandroidintelligence.mobile.push

import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.GatewayByteTransport
import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.GatewayProfile
import com.openandroidintelligence.gateway.http.WireRequest
import com.openandroidintelligence.gateway.http.WireResponse
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class GatewayPushTransportTest {
    private val endpoint = PushEndpoint("core_" + "a".repeat(32), "acct_" + "b".repeat(32), "endpoint_" + "c".repeat(32), 3)
    private fun route() = mapOf("coreInstanceId" to endpoint.coreInstanceId, "accountScope" to endpoint.accountScope,
        "endpointId" to endpoint.endpointId, "generation" to endpoint.generation)
    private val event get() = PushEvent("push_" + "d".repeat(64), PushKind.SMS, 1_800_000_000_000, 1_800_003_600_000, "{\"body\":\"test only\"}")
    private fun http(block: (WireRequest) -> Map<String, Any?>) = GatewayHttpClient(
        GatewayProfile("alice", "device", "session", "http://127.0.0.1:8080"),
        object : GatewayByteTransport {
            override suspend fun execute(request: WireRequest): WireResponse = WireResponse(200, emptyList(),
                Json.canonical(Json.of(mapOf("protocol" to "2.1", "data" to block(request)))).toByteArray())
            override fun eventStream(request: WireRequest): Flow<ByteArray> = error("NO_EVENT_STREAM")
        }, { ByteArray(64) }, InMemoryEventCursorStore(),
    )

    @Test fun bootstrapReadsServerIdentityEvenWithAnEmptyDiscoveryList() = runBlocking {
        val http = http { request ->
            assertTrue(request.target.endsWith("/v2/push/endpoints"))
            mapOf("coreInstanceId" to endpoint.coreInstanceId, "accountScope" to endpoint.accountScope, "endpoints" to emptyList<Any>())
        }
        val transport = GatewayPushTransport(PushGatewayConnections { _, _ -> null })
        val discovered = transport.discover(http) { true }
        assertEquals(endpoint.coreInstanceId, discovered.connection.coreInstanceId)
        assertEquals(endpoint.accountScope, discovered.connection.accountScope)
        assertTrue(discovered.endpoints.isEmpty())
    }

    @Test fun productionAdapterUsesExactRouteAndDoesNotMistakeHttpAcceptanceForAck() = runBlocking {
        val sent = mutableListOf<WireRequest>()
        val http = http { request ->
            sent += request
            if (request.target.endsWith("/endpoints")) mapOf("endpoints" to listOf(mapOf(
                "route" to route(), "label" to "same label", "leaseExpiresAt" to Instant.now().plusSeconds(600).toString())))
            else mapOf("deliveryId" to "delivery_" + "e".repeat(32), "eventId" to event.eventId, "route" to route(), "state" to "pending")
        }
        val connection = PushGatewayConnection(endpoint.coreInstanceId, endpoint.accountScope, http) { true }
        val transport = GatewayPushTransport(PushGatewayConnections { core, account -> if (core == endpoint.coreInstanceId && account == endpoint.accountScope) connection else null })
        assertEquals(EndpointVerification.VALID, transport.verifyEndpoint(endpoint))
        val receipt = transport.send(PushDelivery(event, endpoint)) { true }
        assertEquals(PushReceiptState.ACCEPTED, receipt.state)
        val body = JsonFields.obj(Json.parse(sent.last().body.toString(Charsets.UTF_8)))!!
        assertEquals("sms.received", JsonFields.string(body, "eventType"))
        assertEquals("2027-01-15T08:00:00.000Z", JsonFields.string(body, "occurredAt"))
        assertEquals(route()["accountScope"], JsonFields.string(JsonFields.obj(JsonFields.field(body, "route")), "accountScope"))
    }

    @Test fun payloadExpiredMeansResendSameEventAndReceiverAckRequiresItsTimestamp() = runBlocking {
        var state = "payload_expired"
        var ack: String? = null
        val http = http { mapOf("deliveryId" to "delivery_" + "e".repeat(32), "eventId" to event.eventId,
            "route" to route(), "state" to state, "acknowledgedAt" to ack) }
        val connection = PushGatewayConnection(endpoint.coreInstanceId, endpoint.accountScope, http) { true }
        val transport = GatewayPushTransport(PushGatewayConnections { _, _ -> connection })
        assertEquals(PushReceiptState.MISSING, transport.send(PushDelivery(event, endpoint)) { true }.state)
        state = "acknowledged"
        try { transport.send(PushDelivery(event, endpoint)) { true }; fail("missing receiver ACK must fail") }
        catch (failure: PushHttpFailure) { assertEquals("ACK_INVALID", failure.code) }
        ack = Instant.now().toString()
        assertEquals(PushReceiptState.CONSUMED, transport.send(PushDelivery(event, endpoint)) { true }.state)
    }

    @Test fun beforeSendFencePreventsEgressAfterLocalRevocation() = runBlocking {
        var calls = 0
        val http = http { calls++; error("MUST_NOT_SEND") }
        val connection = PushGatewayConnection(endpoint.coreInstanceId, endpoint.accountScope, http) { true }
        val transport = GatewayPushTransport(PushGatewayConnections { _, _ -> connection })
        try { transport.send(PushDelivery(event, endpoint)) { false }; fail("must cancel") }
        catch (_: CancellationException) { assertEquals(0, calls) }
    }
    @Test fun malformedDiscoveryDoesNotMasqueradeAsEndpointRevocation() = runBlocking {
        val http = http { emptyMap() }
        val connection = PushGatewayConnection(endpoint.coreInstanceId, endpoint.accountScope, http) { true }
        val transport = GatewayPushTransport(PushGatewayConnections { _, _ -> connection })
        try { transport.verifyEndpoint(endpoint); fail("malformed list must remain a retryable failure") }
        catch (failure: PushHttpFailure) { assertEquals("ENDPOINT_LIST_INVALID", failure.code) }
    }

    @Test fun cleanupPostsOnlyTheSelectedEventIdsAndKeepsTheStableRoute() = runBlocking {
        val sent = mutableListOf<WireRequest>()
        val http = http { request ->
            sent += request
            mapOf("route" to route(), "revoked" to true)
        }
        val connection = PushGatewayConnection(endpoint.coreInstanceId, endpoint.accountScope, http) { true }
        val transport = GatewayPushTransport(PushGatewayConnections { _, _ -> connection })
        val batch = PushRevocation(endpoint, setOf("event_sms_b", "event_sms_a"))
        transport.revoke(batch)
        assertEquals(1, sent.size)
        assertTrue(sent.single().target.endsWith("/v2/push/deliveries/revoke"))
        val body = JsonFields.obj(Json.parse(sent.single().body.toString(Charsets.UTF_8)))!!
        assertEquals(listOf("event_sms_a", "event_sms_b"), JsonFields.strings(body, "eventIds"))
        assertEquals(endpoint.endpointId, JsonFields.string(JsonFields.obj(JsonFields.field(body, "route")), "endpointId"))
        assertEquals(setOf("route", "eventIds"), body.fields.map { it.first }.toSet())
    }

    @Test fun cleanupRejectsAMismatchedReceiptAndCannotUseAnEmptyRouteWideBatch() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) { PushRevocation(endpoint, emptySet()) }
        val http = http { mapOf("route" to route(), "revoked" to true, "eventIds" to listOf("another_event")) }
        val connection = PushGatewayConnection(endpoint.coreInstanceId, endpoint.accountScope, http) { true }
        val transport = GatewayPushTransport(PushGatewayConnections { _, _ -> connection })
        try { transport.revoke(PushRevocation(endpoint, setOf("selected_event"))); fail("must reject a mismatched cleanup receipt") }
        catch (failure: PushHttpFailure) { assertEquals("REVOCATION_RECEIPT_INVALID", failure.code) }
    }

}
