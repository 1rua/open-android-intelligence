package com.openandroidintelligence.gateway.remote.protocol

import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import java.net.URI
import kotlin.math.floor

/** 帧本身不在 toString 中打印 payload，避免 hello 的 bearer 泄露到诊断输出。 */
class RemoteControlFrame internal constructor(
    val id: String,
    val type: String,
    val timestamp: Long,
    val payload: JsonValue.JObject,
) {
    fun toJson(): JsonValue.JObject = Json.of(linkedMapOf(
        "v" to RemoteProtocol.VERSION, "id" to id, "type" to type, "timestamp" to timestamp, "payload" to payload,
    )) as JsonValue.JObject

    override fun toString(): String = "RemoteControlFrame(type=$type,payload=已隐藏)"
}

data class RemoteFrameLimits(val maxControlFrameBytes: Int? = null, val maxRelayFrameBytes: Int? = null)

/** 与固定上游 control/relay-data schema 同步，保留 payload 的前向兼容字段剥除行为。 */
object RemoteControlCodec {
    fun create(type: String, payload: JsonValue.JObject, id: String, timestamp: Long): RemoteControlFrame =
        parse(Json.of(mapOf("v" to 1, "type" to type, "payload" to payload, "id" to id, "timestamp" to timestamp)))

    fun parse(value: JsonValue): RemoteControlFrame = parseEnvelope(value, relayData = false)

    fun decode(bytes: ByteArray, limits: RemoteFrameLimits = RemoteFrameLimits()): RemoteControlFrame {
        if (bytes.size > RemoteProtocol.MAX_RELAY_FRAME_BYTES) invalid("FRAME_TOO_LARGE")
        return parse(RemoteWireJson.decode(bytes)).also { checkSize(bytes.size, it.type == "relay", limits) }
    }

    fun encode(frame: RemoteControlFrame, limits: RemoteFrameLimits = RemoteFrameLimits()): ByteArray {
        val checked = parse(frame.toJson())
        return Json.canonical(checked.toJson()).toByteArray(Charsets.UTF_8).also { checkSize(it.size, checked.type == "relay", limits) }
    }

    fun decodeRelayData(bytes: ByteArray, limits: RemoteFrameLimits = RemoteFrameLimits()): RemoteControlFrame {
        checkSize(bytes.size, true, limits)
        return parseEnvelope(RemoteWireJson.decode(bytes), relayData = true)
    }

    fun encodeRelayData(frame: RemoteControlFrame, limits: RemoteFrameLimits = RemoteFrameLimits()): ByteArray {
        val checked = parseEnvelope(frame.toJson(), relayData = true)
        return Json.canonical(checked.toJson()).toByteArray(Charsets.UTF_8).also { checkSize(it.size, true, limits) }
    }

    fun parseRpcError(value: JsonValue): JsonValue.JObject = schema(value) {
        required("requestId", string())
        required("code", string())
        required("message", string())
        optional("retryable", boolean())
        optional("details") { it }
    }

    private fun parseEnvelope(value: JsonValue, relayData: Boolean): RemoteControlFrame {
        RemoteWireJson.validate(value)
        val envelope = schema(value, strict = true) {
            required("v", literalNumber(1))
            required("id", string())
            required("type", string())
            required("timestamp", integer(1))
            required("payload") { it as? JsonValue.JObject ?: invalid() }
        }
        val type = text(envelope, "type")
        if (!relayData && type !in controlTypes) invalid()
        val payload = field(envelope, "payload")!!
        val parsed = if (!relayData || type == "relay" || type == "secure.handshake") parsePayload(type, payload)
            else payload as JsonValue.JObject
        return RemoteControlFrame(text(envelope, "id"), type, number(envelope, "timestamp"), parsed)
    }

    private fun parsePayload(type: String, value: JsonValue): JsonValue.JObject = schema(value) {
        when (type) {
            "hello" -> {
                optional("recoveryTickets", array(string(1, 4096), 0, 64))
                required("role", oneOf("host", "client"))
                required("deviceId", string())
                required("accessToken", string())
                required("protocols", array(integer(0), 1, 16, unique = true))
                required("capabilities", array(string(), 0, 128, unique = true))
                optional("clientVersion", string(0))
                optional("harnessVersion", string(0))
            }
            "hello.ack" -> {
                required("protocol", literalNumber(1))
                required("serverVersion", string())
                required("connectionSessionId", string())
                required("heartbeatIntervalMs", integer(1))
                required("maxControlFrameBytes", integer(1, RemoteProtocol.MAX_CONTROL_FRAME_BYTES.toLong()))
                required("maxRelayFrameBytes", integer(1, RemoteProtocol.MAX_RELAY_FRAME_BYTES.toLong()))
                optional("capabilities", array(string(), 0, 128, unique = true))
                optional("webrtcEnabled", boolean())
                optional("webrtcFallbackTimeoutMs", integer(1))
            }
            "connect.request" -> {
                required("hostDeviceId", string())
                required("preferredTransports", transports())
            }
            "connect.incoming", "connect.accepted" -> {
                optional("relayRecoveryTicket", string(1, 4096))
                optional("recoveryTicket", string(1, 4096))
                required("connectionId", string())
                if (type == "connect.incoming") {
                    required("clientDeviceId", string())
                    required("clientIdentityKey", identityKey())
                    required("authorization", oneOf("account"))
                    required("preferredTransports", transports())
                }
                optional("relayEndpoint") {
                    string()(it).also { v ->
                        val uri = try { URI((v as JsonValue.JString).value) } catch (_: Exception) { invalid() }
                        if (!uri.isAbsolute) invalid()
                    }
                }
                optional("relayTicket", string())
                optional("relayNodeId", string())
                optional("relayEpoch", integer(0))
            }
            "connect.rejected" -> {
                required("connectionId", string())
                optional("code", string(0))
                optional("message", string(0))
            }
            "secure.handshake" -> {
                connectionTarget()
                required("step") { v -> integer(1, 2)(v) }
                required("data", base64())
            }
            "relay" -> {
                connectionTarget()
                required("counter", integer(0))
                required("ciphertext", base64())
            }
            "signal.offer", "signal.answer" -> {
                connectionTarget()
                required("sdp", string())
            }
            "signal.ice" -> {
                connectionTarget()
                required("candidate") { candidate -> schema(candidate) {
                    optional("candidate", string(0))
                    optional("sdpMid", nullable(string(0)))
                    optional("sdpMLineIndex") { index ->
                        if (index is JsonValue.JNumber && index.raw.toDouble() < 0) JsonValue.JNull
                        else nullable(integer(0))(index)
                    }
                    optional("usernameFragment", nullable(string(0)))
                } }
            }
            "transport.selected" -> {
                connectionTarget()
                required("transport", oneOf("lan", "p2p", "turn", "relay"))
                optional("turnNode", string(1, 253))
            }
            "connection.lease.request", "connection.lease.ack" -> {
                required("connectionId", string(1, 128))
                required("requestId", string(1, 128))
                required("ticket", string(1, 4096))
                if (type == "connection.lease.ack") required("leaseMs", literalNumber(300_000))
            }
            "connection.lease.close" -> required("connectionId", string(1, 128))
            "ping", "pong" -> required("nonce", string())
            "error" -> {
                required("code", string())
                required("message", string())
                optional("retryable", boolean())
                optional("connectionId", string())
            }
            else -> invalid()
        }
    }

    private fun ObjectSchema.connectionTarget() {
        required("connectionId", string())
        required("targetDeviceId", string())
    }

    private fun checkSize(bytes: Int, relay: Boolean, limits: RemoteFrameLimits) {
        val protocolMaximum = if (relay) RemoteProtocol.MAX_RELAY_FRAME_BYTES else RemoteProtocol.MAX_CONTROL_FRAME_BYTES
        val configured = (if (relay) limits.maxRelayFrameBytes else limits.maxControlFrameBytes) ?: protocolMaximum
        if (configured <= 0) invalid("INVALID_FRAME_LIMIT")
        if (bytes > minOf(protocolMaximum, configured)) invalid("FRAME_TOO_LARGE")
    }

    private val controlTypes = setOf("hello", "hello.ack", "connect.request", "connect.incoming", "connect.accepted",
        "connect.rejected", "secure.handshake", "relay", "signal.offer", "signal.answer", "signal.ice", "transport.selected",
        "connection.lease.request", "connection.lease.ack", "connection.lease.close", "ping", "pong", "error")
}

internal typealias ValueRule = (JsonValue) -> JsonValue

internal class ObjectSchema(private val source: JsonValue.JObject) {
    private val output = mutableListOf<Pair<String, JsonValue>>()
    fun required(name: String, rule: ValueRule) {
        output += name to rule(field(source, name) ?: invalid())
    }
    fun optional(name: String, rule: ValueRule) {
        field(source, name)?.let { output += name to rule(it) }
    }
    fun finish(strict: Boolean): JsonValue.JObject {
        if (strict && source.fields.any { original -> output.none { it.first == original.first } }) invalid()
        return JsonValue.JObject(output.toList())
    }
}

internal fun schema(value: JsonValue, strict: Boolean = false, block: ObjectSchema.() -> Unit): JsonValue.JObject {
    RemoteWireJson.validate(value)
    return ObjectSchema(value as? JsonValue.JObject ?: invalid()).apply(block).finish(strict)
}

internal fun field(source: JsonValue.JObject, name: String): JsonValue? = source.fields.firstOrNull { it.first == name }?.second
internal fun text(source: JsonValue.JObject, name: String): String = (field(source, name) as? JsonValue.JString)?.value ?: invalid()
internal fun number(source: JsonValue.JObject, name: String): Long = (field(source, name) as? JsonValue.JNumber)?.raw?.toLongOrNull() ?: invalid()
internal fun invalid(code: String = "INVALID_MESSAGE"): Nothing = throw RemoteProtocolException(code)

internal fun string(min: Int = 1, max: Int = Int.MAX_VALUE, utf8: Boolean = false): ValueRule = { value ->
    val s = (value as? JsonValue.JString)?.value ?: invalid()
    val size = if (utf8) s.toByteArray(Charsets.UTF_8).size else s.length
    if (size !in min..max) invalid()
    value
}
internal fun boolean(): ValueRule = { it as? JsonValue.JBool ?: invalid() }
internal fun integer(min: Long, max: Long = RemoteProtocol.MAX_SAFE_INTEGER): ValueRule = { value ->
    val n = (value as? JsonValue.JNumber)?.raw?.toDoubleOrNull() ?: invalid()
    if (!n.isFinite() || floor(n) != n || n < min.toDouble() || n > max.toDouble()) invalid()
    JsonValue.JNumber(n.toLong().toString())
}
internal fun literalNumber(expected: Long): ValueRule = { value ->
    integer(expected, expected)(value)
}
internal fun oneOf(vararg allowed: String): ValueRule = { value ->
    if ((value as? JsonValue.JString)?.value !in allowed) invalid()
    value
}
internal fun nullable(rule: ValueRule): ValueRule = { if (it == JsonValue.JNull) it else rule(it) }
internal fun base64(): ValueRule = { value ->
    val s = (value as? JsonValue.JString)?.value ?: invalid()
    RemoteBase64Url.decode(s)
    value
}
internal fun identityKey(): ValueRule = { value ->
    val checked = base64()(value)
    if (RemoteBase64Url.decode((checked as JsonValue.JString).value).size != 32) invalid()
    checked
}
internal fun array(rule: ValueRule, min: Int, max: Int, unique: Boolean = false): ValueRule = { value ->
    val values = (value as? JsonValue.JArray)?.items ?: invalid()
    if (values.size !in min..max) invalid()
    val normalized = values.map(rule)
    if (unique && normalized.toSet().size != normalized.size) invalid()
    JsonValue.JArray(normalized)
}
internal fun transports(): ValueRule = array(oneOf("lan", "p2p", "turn", "relay"), 1, 4)
