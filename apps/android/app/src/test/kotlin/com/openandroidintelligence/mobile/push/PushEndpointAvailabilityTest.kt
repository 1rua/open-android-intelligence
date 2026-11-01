package com.openandroidintelligence.mobile.push

import com.openandroidintelligence.encrypted.store.InMemoryOutboxPersistence
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.GatewayByteTransport
import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.GatewayProfile
import com.openandroidintelligence.gateway.http.WireRequest
import com.openandroidintelligence.gateway.http.WireResponse
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant
import javax.crypto.spec.SecretKeySpec

/** Exercises the actual GatewayPushTransport discovery/lease/route parser, not a VALID stub. */
class PushEndpointAvailabilityTest {
    private val selected = PushEndpoint("core_" + "a".repeat(32), "acct_" + "b".repeat(32), "endpoint_" + "c".repeat(32), 1)
    private val clock = TestPushClock(System.currentTimeMillis())
    private val key = SecretKeySpec(ByteArray(32) { 4 }, "AES")
    private fun configuration() = PushConfiguration(sms = SmsPushSettings(true, SmsPushScope(allSenders = true), setOf(selected)))
    private fun runtime(memory: InMemoryOutboxPersistence = InMemoryOutboxPersistence()) = DevicePushRuntime(
        EncryptedPushStateStore("availability-installation", memory, key), PushSystemAccess { true }, clock,
    )
    private fun ready(memory: InMemoryOutboxPersistence = InMemoryOutboxPersistence()) = runtime(memory).also {
        it.configure(configuration()); it.onLoginStateChanged(true)
        assertEquals(PushCaptureStatus.ENQUEUED, it.onSms(PushSms(1, clock.epoch, "sender", "queued sample")).status)
    }

    @Test fun expiredLeaseRetainsSelectionAndExistingReceiptThenResumesTheSameGeneration() = runBlocking {
        val runtime = ready()
        val peer = DiscoveryPeer()
        val transport = peer.transport()
        assertEquals(1, runtime.deliver(transport).accepted)
        assertEquals(1, peer.posts)
        peer.leaseExpiresAt = clock.epoch - 1
        assertEquals(EndpointVerification.UNAVAILABLE, transport.verifyEndpoint(selected))
        assertEquals(1, runtime.deliver(transport).pending)
        assertEquals(configuration(), runtime.snapshot().configuration)
        assertEquals(1, runtime.snapshot().queuedEvents)
        assertTrue(runtime.pendingRevocations().isEmpty())
        assertEquals(0, peer.polls)
        // The same verified generation becomes usable again; poll its exact persisted receipt.
        peer.leaseExpiresAt = clock.epoch + PUSH_HOUR_MS
        peer.state = "acknowledged"
        assertEquals(1, runtime.deliver(transport).acknowledged)
        assertEquals(1, peer.posts)
        assertEquals(1, peer.polls)
        assertEquals(configuration(), runtime.snapshot().configuration)
    }

    @Test fun absentEndpointAndNetworkOutagePreserveEncryptedQueueAcrossRestart() = runBlocking {
        val memory = InMemoryOutboxPersistence()
        var runtime = ready(memory)
        val peer = DiscoveryPeer()
        val transport = peer.transport()
        peer.present = false
        assertEquals(EndpointVerification.UNAVAILABLE, transport.verifyEndpoint(selected))
        runtime.deliver(transport)
        assertEquals(0, peer.posts)
        assertEquals(1, runtime.snapshot().queuedEvents)
        assertEquals(configuration(), runtime.snapshot().configuration)
        assertTrue(runtime.pendingRevocations().isEmpty())
        runtime = runtime(memory)
        runtime.onLoginStateChanged(true)
        peer.networkAvailable = false
        assertEquals(1, runtime.deliver(transport).pending)
        assertEquals(configuration(), runtime.snapshot().configuration)
        peer.networkAvailable = true
        peer.present = true
        peer.state = "acknowledged"
        assertEquals(1, runtime.deliver(transport).acknowledged)
        assertEquals(1, peer.posts)
        assertEquals(0, runtime.snapshot().queuedEvents)
    }

    @Test fun positivelyNewerGenerationDoesNotRewriteConsentOrSendOldEventsToIt() = runBlocking {
        val runtime = ready()
        val peer = DiscoveryPeer()
        peer.advertised = selected.copy(generation = 2)
        val transport = peer.transport()
        assertEquals(EndpointVerification.REVOKED, transport.verifyEndpoint(selected))
        assertEquals(1, runtime.deliver(transport).pending)
        assertEquals(0, peer.posts)
        assertEquals(configuration(), runtime.snapshot().configuration)
        assertTrue(runtime.pendingRevocations().isEmpty())
        // Explicitly selecting a new generation still cannot retarget an already queued event.
        runtime.configure(configuration().let { it.copy(sms = it.sms.copy(endpoints = setOf(selected, peer.advertised))) })
        runtime.deliver(transport)
        assertEquals(0, peer.posts)
        val newEvent = runtime.onSms(PushSms(2, clock.epoch, "sender", "new sample"))
        peer.state = "acknowledged"
        runtime.deliver(transport)
        assertEquals(listOf(newEvent.eventId), peer.postedEvents)
        assertEquals(listOf(2L), peer.postedGenerations)
        assertEquals(2, runtime.snapshot().queuedEvents) // Both old-generation target records remain pending.
    }

    @Test fun aSameNameDifferentEndpointOrStaleDiscoveryIsUnavailableRatherThanProofOfRevocation() = runBlocking {
        val peer = DiscoveryPeer()
        val transport = peer.transport()
        peer.advertised = selected.copy(endpointId = "endpoint_" + "f".repeat(32), generation = 8)
        assertEquals(EndpointVerification.UNAVAILABLE, transport.verifyEndpoint(selected))
        peer.advertised = selected
        assertEquals(EndpointVerification.UNAVAILABLE, transport.verifyEndpoint(selected.copy(generation = 4)))
        peer.leaseExpiresAt = clock.epoch
        assertEquals(EndpointVerification.UNAVAILABLE, transport.verifyEndpoint(selected))
        assertEquals(0, peer.posts)
    }

    @Test fun explicitUserRemovalAfterAnOutageStillPurgesOnlyThatSelectedRoute() = runBlocking {
        val runtime = ready()
        val peer = DiscoveryPeer().apply { present = false }
        runtime.deliver(peer.transport())
        val pending = runtime.snapshot().queuedEvents
        assertEquals(1, pending)
        runtime.configure(PushConfiguration())
        assertEquals(0, runtime.snapshot().queuedEvents)
        assertTrue(runtime.snapshot().configuration.sms.endpoints.isEmpty())
        assertEquals(selected, runtime.pendingRevocations().single().endpoint)
    }

    private inner class DiscoveryPeer : GatewayByteTransport {
        var present = true
        var networkAvailable = true
        var advertised = selected
        var leaseExpiresAt = clock.epoch + PUSH_HOUR_MS
        var state = "pending"
        var posts = 0
        var polls = 0
        val postedEvents = mutableListOf<String>()
        val postedGenerations = mutableListOf<Long>()
        private val deliveryId = "delivery_" + "d".repeat(32)
        private var lastEventId: String? = null
        private var lastRoute: Map<String, Any?>? = null

        fun transport(): GatewayPushTransport {
            val http = GatewayHttpClient(GatewayProfile("alice", "device", "session", "http://127.0.0.1:8080"),
                this, { ByteArray(64) }, InMemoryEventCursorStore())
            val connection = PushGatewayConnection(selected.coreInstanceId, selected.accountScope, http) { true }
            return GatewayPushTransport(PushGatewayConnections { core, scope ->
                connection.takeIf { it.coreInstanceId == core && it.accountScope == scope }
            }, now = { Instant.ofEpochMilli(clock.epoch) })
        }

        override suspend fun execute(request: WireRequest): WireResponse {
            if (!networkAvailable) throw IOException("isolated endpoint connection unavailable")
            val result = when {
                request.method == "GET" && request.target.endsWith("/endpoints") -> mapOf(
                    "coreInstanceId" to selected.coreInstanceId, "accountScope" to selected.accountScope,
                    "endpoints" to if (present) listOf(mapOf("route" to route(advertised), "label" to "same agent label",
                        "leaseExpiresAt" to Instant.ofEpochMilli(leaseExpiresAt).toString())) else emptyList<Any>(),
                )
                request.method == "POST" && request.target.endsWith("/deliveries") -> {
                    val body = JsonFields.obj(Json.parse(request.body.toString(Charsets.UTF_8)))!!
                    val actualRoute = JsonFields.obj(JsonFields.field(body, "route"))!!
                    val generation = JsonFields.long(actualRoute, "generation")!!
                    // The complete route is asserted at the byte boundary, never silently rewritten.
                    assertEquals(advertised.generation, generation)
                    assertEquals(advertised.endpointId, JsonFields.string(actualRoute, "endpointId"))
                    lastEventId = JsonFields.string(body, "eventId")!!
                    lastRoute = route(advertised)
                    posts++
                    postedEvents += lastEventId!!
                    postedGenerations += generation
                    receipt()
                }
                request.method == "GET" && request.target.endsWith("/deliveries/$deliveryId") -> { polls++; receipt() }
                else -> error("Unexpected push request: ${request.method} ${request.target}")
            }
            return WireResponse(200, emptyList(), Json.canonical(Json.of(mapOf("protocol" to "2.1", "data" to result))).toByteArray())
        }
        override fun eventStream(request: WireRequest): Flow<ByteArray> = error("No streaming connection in bounded push tests")
        private fun receipt() = mapOf("route" to lastRoute, "eventId" to lastEventId, "deliveryId" to deliveryId,
            "state" to state, "acknowledgedAt" to if (state == "acknowledged") Instant.ofEpochMilli(clock.epoch).toString() else null)
        private fun route(endpoint: PushEndpoint): Map<String, Any?> = mapOf("coreInstanceId" to endpoint.coreInstanceId,
            "accountScope" to endpoint.accountScope, "endpointId" to endpoint.endpointId, "generation" to endpoint.generation)
    }
}
