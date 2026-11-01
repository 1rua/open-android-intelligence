package com.openandroidintelligence.mobile.push

import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.SignedGatewayRequest
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.format.DateTimeFormatterBuilder

data class PushEndpointDiscovery(val connection: PushGatewayConnection, val endpoints: List<PushEndpointOption>)

/** A verified phone connection. Core ID and account scope come from signed endpoint discovery. */
data class PushGatewayConnection(
    val coreInstanceId: String,
    val accountScope: String,
    val http: GatewayHttpClient,
    val isCurrent: () -> Boolean,
)

/** Supplies only existing authenticated phone connections, never arbitrary URLs or credentials. */
fun interface PushGatewayConnections {
    fun current(coreInstanceId: String, accountScope: String): PushGatewayConnection?
}

class PushHttpFailure(val code: String) : IllegalStateException("PUSH_HTTP_FAILURE:$code")

/** Production adapter for the actual Core endpoint implementation in oai_core/endpoints.py. */
class GatewayPushTransport(
    private val connections: PushGatewayConnections,
    private val now: () -> Instant = Instant::now,
) : PushTransport {
    override suspend fun verifyEndpoint(endpoint: PushEndpoint): EndpointVerification {
        val connection = connection(endpoint) ?: return EndpointVerification.UNAVAILABLE
        val data = request(connection, "GET", "$PREFIX/endpoints", stillAuthorized = { connection.isCurrent() })
        val options = descriptors(data, connection)
        if (!connection.isCurrent()) return EndpointVerification.UNAVAILABLE
        return when {
            options.any { it.endpoint == endpoint && it.available } -> EndpointVerification.VALID
            // The active listing is not a revocation ledger. Absence or lease expiry can recover.
            options.any { it.available && it.endpoint.replaces(endpoint) } -> EndpointVerification.REVOKED
            else -> EndpointVerification.UNAVAILABLE
        }
    }

    /** Bootstrap identity from the authenticated Core response, including an empty endpoint list. */
    suspend fun discover(http: GatewayHttpClient, isCurrent: () -> Boolean): PushEndpointDiscovery {
        val bootstrap = http.execute(SignedGatewayRequest("GET", "$PREFIX/endpoints", beforeSend = {
            if (!isCurrent()) throw CancellationException("PUSH_CONNECTION_CHANGED")
        }))
        if (bootstrap.status !in 200..299 || bootstrap.body.size > 256 * 1_024) throw PushHttpFailure("DISCOVERY_FAILED")
        val envelope = JsonFields.obj(Json.parse(bootstrap.body.toString(Charsets.UTF_8)))
        val data = JsonFields.obj(JsonFields.field(envelope, "data")) ?: throw PushHttpFailure("RESPONSE_INVALID")
        if (!isCurrent()) throw CancellationException("PUSH_CONNECTION_CHANGED")
        val first = endpointObjects(data).firstOrNull()
            ?.let { route(JsonFields.obj(JsonFields.field(it, "route"))) }
        val core = JsonFields.string(data, "coreInstanceId") ?: first?.coreInstanceId ?: throw PushHttpFailure("CORE_IDENTITY_MISSING")
        val account = JsonFields.string(data, "accountScope") ?: first?.accountScope ?: throw PushHttpFailure("ACCOUNT_SCOPE_MISSING")
        val connection = PushGatewayConnection(core, account, http, isCurrent)
        return PushEndpointDiscovery(connection, descriptors(data, connection))
    }

    /** Authenticated discovery for the settings picker. Never manufactures a route from a label. */
    suspend fun discover(connection: PushGatewayConnection): List<PushEndpointOption> {
        if (!connection.isCurrent()) return emptyList()
        val data = request(connection, "GET", "$PREFIX/endpoints", stillAuthorized = connection.isCurrent)
        return descriptors(data, connection)
    }

    private fun descriptors(data: JsonValue.JObject, connection: PushGatewayConnection): List<PushEndpointOption> =
        endpointObjects(data).map { descriptor ->
            val endpoint = route(JsonFields.obj(JsonFields.field(descriptor, "route"))) ?: throw PushHttpFailure("ROUTE_INVALID")
            check(endpoint.coreInstanceId == connection.coreInstanceId && endpoint.accountScope == connection.accountScope)
            val expiry = JsonFields.string(descriptor, "leaseExpiresAt")?.let(Instant::parse) ?: throw PushHttpFailure("LEASE_INVALID")
            PushEndpointOption(endpoint, JsonFields.string(descriptor, "label").orEmpty(), expiry.isAfter(now()))
        }

    override suspend fun send(delivery: PushDelivery, stillAuthorized: () -> Boolean): PushReceipt {
        val connection = connection(delivery.endpoint) ?: throw PushHttpFailure("CONNECTION_UNAVAILABLE")
        val event = delivery.event
        val payload = Json.parse(event.payloadJson)
        val body = Json.canonical(Json.of(mapOf(
            "eventId" to event.eventId,
            "route" to routeMap(delivery.endpoint),
            "eventType" to if (event.kind == PushKind.SMS) "sms.received" else "notification.posted",
            "occurredAt" to iso(event.capturedAtEpochMs),
            "expiresAt" to iso(event.expiresAtEpochMs),
            "payload" to payload,
        ))).toByteArray()
        val data = request(connection, "POST", "$PREFIX/deliveries", body, stillAuthorized)
        return receipt(data)
    }

    override suspend fun poll(delivery: PushDelivery, deliveryId: String, stillAuthorized: () -> Boolean): PushReceipt {
        require(deliveryId.matches(Regex("delivery_[0-9a-f]{32}"))) { "PUSH_DELIVERY_ID_INVALID" }
        val connection = connection(delivery.endpoint) ?: throw PushHttpFailure("CONNECTION_UNAVAILABLE")
        return receipt(request(connection, "GET", "$PREFIX/deliveries/$deliveryId", stillAuthorized = stillAuthorized))
    }

    /** Exact event cleanup. Failure leaves the durable runtime batch pending for reconnect/retry. */
    suspend fun revoke(revocation: PushRevocation) {
        val batch = revocation.frozen()
        val connection = connection(batch.endpoint) ?: throw PushHttpFailure("CONNECTION_UNAVAILABLE")
        val body = Json.canonical(Json.of(mapOf("route" to routeMap(batch.endpoint),
            "eventIds" to batch.eventIds.sorted()))).toByteArray()
        val response = request(connection, "POST", "$PREFIX/deliveries/revoke", body, connection.isCurrent)
        if (JsonFields.bool(response, "revoked") != true ||
            route(JsonFields.obj(JsonFields.field(response, "route"))) != batch.endpoint ||
            (JsonFields.field(response, "eventIds") != null && JsonFields.strings(response, "eventIds").toSet() != batch.eventIds)
        ) throw PushHttpFailure("REVOCATION_RECEIPT_INVALID")
    }

    private fun connection(endpoint: PushEndpoint): PushGatewayConnection? = connections.current(endpoint.coreInstanceId, endpoint.accountScope)
        ?.takeIf { it.coreInstanceId == endpoint.coreInstanceId && it.accountScope == endpoint.accountScope && it.isCurrent() }

    private suspend fun request(
        connection: PushGatewayConnection,
        method: String,
        path: String,
        body: ByteArray = ByteArray(0),
        stillAuthorized: () -> Boolean,
    ): JsonValue.JObject {
        val response = connection.http.execute(SignedGatewayRequest(method, path, body, beforeSend = {
            if (!connection.isCurrent() || !stillAuthorized()) throw CancellationException("PUSH_AUTHORIZATION_CHANGED")
        }))
        if (response.body.size > 256 * 1_024) throw PushHttpFailure("RESPONSE_TOO_LARGE")
        val envelope = JsonFields.obj(Json.parse(response.body.toString(Charsets.UTF_8))) ?: throw PushHttpFailure("RESPONSE_INVALID")
        if (response.status !in 200..299) {
            val error = JsonFields.obj(JsonFields.field(envelope, "error"))
            throw PushHttpFailure(JsonFields.string(error, "code") ?: "HTTP_${response.status}")
        }
        return JsonFields.obj(JsonFields.field(envelope, "data")) ?: throw PushHttpFailure("RESPONSE_INVALID")
    }

    private fun endpointObjects(data: JsonValue.JObject): List<JsonValue.JObject> {
        val array = JsonFields.array(JsonFields.field(data, "endpoints")) ?: throw PushHttpFailure("ENDPOINT_LIST_INVALID")
        if (array.items.size > 64) throw PushHttpFailure("ENDPOINT_LIST_TOO_LARGE")
        return array.items.map { it as? JsonValue.JObject ?: throw PushHttpFailure("ENDPOINT_DESCRIPTOR_INVALID") }
    }

    private fun receipt(data: JsonValue.JObject): PushReceipt {
        val state = when (JsonFields.string(data, "state")) {
            "pending" -> PushReceiptState.ACCEPTED
            "acknowledged" -> {
                if (JsonFields.string(data, "acknowledgedAt").isNullOrBlank()) throw PushHttpFailure("ACK_INVALID")
                PushReceiptState.CONSUMED
            }
            "payload_expired" -> PushReceiptState.MISSING
            "revoked", "expired" -> PushReceiptState.RETRY
            else -> throw PushHttpFailure("RECEIPT_STATE_INVALID")
        }
        return PushReceipt(
            route(JsonFields.obj(JsonFields.field(data, "route"))) ?: throw PushHttpFailure("ROUTE_INVALID"),
            JsonFields.string(data, "eventId") ?: throw PushHttpFailure("EVENT_ID_MISSING"),
            JsonFields.string(data, "deliveryId") ?: throw PushHttpFailure("DELIVERY_ID_MISSING"), state,
        )
    }

    private fun route(value: JsonValue.JObject?): PushEndpoint? {
        value ?: return null
        val core = JsonFields.string(value, "coreInstanceId") ?: return null
        val account = JsonFields.string(value, "accountScope") ?: return null
        val endpoint = JsonFields.string(value, "endpointId") ?: return null
        val generation = JsonFields.long(value, "generation") ?: return null
        return runCatching { PushEndpoint(core, account, endpoint, generation) }.getOrNull()
    }

    private fun routeMap(endpoint: PushEndpoint) = mapOf("coreInstanceId" to endpoint.coreInstanceId,
        "accountScope" to endpoint.accountScope, "endpointId" to endpoint.endpointId, "generation" to endpoint.generation)
    private fun iso(epochMs: Long) = DateTimeFormatterBuilder().appendInstant(3).toFormatter().format(Instant.ofEpochMilli(epochMs))

    companion object { const val PREFIX = "/open-android-intelligence/v2/push" }
}
