package com.openandroidintelligence.gateway.remote

import com.openandroidintelligence.gateway.remote.protocol.*
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class RemoteProtocolFixtureTest(private val name: String, private val row: JsonValue.JObject) {
    @Test
    fun 固定协议接受拒绝与归一化输出() {
        val expected = RemoteFixtures.obj(row, "expect")
        val accepted = JsonFields.bool(expected, "accepted")!!
        val result = try { execute(RemoteFixtures.text(row, "operation"), JsonFields.field(row, "input")!!) }
        catch (failure: RemoteProtocolException) {
            if (accepted) throw AssertionError("$name 应接受，实际 ${failure.code}")
            return
        }
        assertTrue("$name 应拒绝", accepted)
        JsonFields.field(expected, "value")?.let { assertEquals(name, Json.canonical(it), Json.canonical(result)) }
        JsonFields.field(expected, "selected")?.let { assertEquals(name, Json.canonical(it), Json.canonical(result)) }
    }

    private fun execute(operation: String, input: JsonValue): JsonValue {
        val value = input as JsonValue.JObject
        val limits = JsonFields.obj(JsonFields.field(value, "limits"))?.let {
            RemoteFrameLimits(JsonFields.int(it, "maxControlFrameBytes"), JsonFields.int(it, "maxRelayFrameBytes"))
        } ?: RemoteFrameLimits()
        return when (operation) {
            "parseControlFrame" -> RemoteControlCodec.parse(input).toJson()
            "encodeControlFrameWithLimits" -> RemoteWireJson.decode(RemoteControlCodec.encode(RemoteControlCodec.parse(JsonFields.field(value, "frame")!!), limits))
            "decodeControlFrameWithLimits" -> RemoteControlCodec.decode(RemoteFixtures.text(value, "data").toByteArray(), limits).toJson()
            "decodeRelayDataFrameWithLimits" -> RemoteControlCodec.decodeRelayData(RemoteFixtures.text(value, "data").toByteArray(), limits).toJson()
            "parseDeviceRegistrationRequest" -> RemoteAccountCodec.parseRegistration(input)
            "parseHostRegistrationCodeRequest" -> RemoteAccountCodec.parseHostRegistrationCode(input)
            "parseDeviceRefreshRequest" -> RemoteAccountCodec.parseRefresh(input)
            "parseDeviceTokenPair" -> RemoteAccountCodec.parseTokenPair(input)
            "parseBrowserAuthorizationExchangeRequest" -> RemoteAccountCodec.parseBrowserExchangeRequest(input)
            "parseBrowserAuthorizationExchangeResponse" -> RemoteAccountCodec.parseBrowserExchangeResponse(input)
            "parseRpcErrorPayload" -> RemoteControlCodec.parseRpcError(input)
            "selectProtocolVersion" -> Json.of(RemoteNegotiation.selectProtocolVersion(longs(value, "offered"), longs(value, "supported")))
            "selectCapabilities" -> Json.of(RemoteNegotiation.selectCapabilities(strings(value, "offered"), strings(value, "supported")))
            "acceptNegotiatedCapabilities" -> Json.of(RemoteNegotiation.acceptNegotiatedCapabilities(strings(value, "offered"),
                JsonFields.field(value, "negotiated")?.let { strings(value, "negotiated") }))
            else -> error("未实现的上游向量操作：$operation")
        }
    }

    private fun strings(value: JsonValue.JObject, key: String): List<String> = RemoteFixtures.list(value, key).map { (it as JsonValue.JString).value }
    private fun longs(value: JsonValue.JObject, key: String): List<Long> = RemoteFixtures.list(value, key).map { (it as JsonValue.JNumber).raw.toLong() }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> {
            val manifest = RemoteFixtures.json("fixtures/protocol/v1/manifest.json")
            assertEquals("dsh-remote.protocol-conformance", RemoteFixtures.text(manifest, "fixtureFormat"))
            assertEquals(1, JsonFields.int(manifest, "fixtureFormatVersion"))
            assertEquals(1, JsonFields.int(manifest, "protocolVersion"))
            return RemoteFixtures.list(manifest, "suites").flatMap { item ->
                val suite = item as JsonValue.JObject
                val file = RemoteFixtures.json("fixtures/protocol/v1/${RemoteFixtures.text(suite, "path")}")
                RemoteFixtures.list(file, "cases").map { row ->
                    val test = row as JsonValue.JObject
                    arrayOf(RemoteFixtures.text(test, "name"), test)
                }
            }
        }
    }
}
