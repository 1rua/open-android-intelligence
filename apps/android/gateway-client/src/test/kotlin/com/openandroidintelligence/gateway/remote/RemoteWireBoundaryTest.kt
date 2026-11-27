package com.openandroidintelligence.gateway.remote

import com.openandroidintelligence.gateway.remote.protocol.*
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class RemoteWireBoundaryTest {
    @Test
    fun 所有固定文件均匹配未改写来源摘要与协议清单() {
        val entries = String(RemoteFixtures.bytes("SHA256SUMS"), Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.toList()
        assertEquals(17, entries.size)
        val paths = mutableSetOf<String>()
        entries.forEach { line ->
            val (expected, path) = line.split("  ", limit = 2)
            assertTrue(paths.add(path))
            val actual = MessageDigest.getInstance("SHA-256").digest(RemoteFixtures.bytes(path)).joinToString("") { "%02x".format(it) }
            assertEquals(path, expected, actual)
        }
        val manifest = RemoteFixtures.json("fixtures/protocol/v1/manifest.json")
        RemoteFixtures.list(manifest, "suites").forEach { suite ->
            assertTrue("清单套件缺少摘要", "fixtures/protocol/v1/${RemoteFixtures.text(suite as JsonValue.JObject, "path")}" in paths)
        }
    }

    @Test
    fun 重复转义键非法数字与非法UTF8不会到达业务层() {
        listOf("{\"a\":1,\"a\":2}", "{\"a\":{\"n\\u006fnce\":1,\"nonce\":2}}", "01", "1+2", "1e", "1e309", "[1,]").forEach { input ->
            assertThrows(input, RemoteProtocolException::class.java) { RemoteWireJson.parse(input) }
        }
        assertThrows(RemoteProtocolException::class.java) { RemoteWireJson.decode(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertThrows(RemoteProtocolException::class.java) { RemoteWireJson.parse("[".repeat(129) + "0" + "]".repeat(129)) }
    }

    @Test
    fun 错误不携带凭据原文且帧日志隐藏payload() {
        val secret = "测试凭据不得进入异常或日志"
        val failure = assertThrows(RemoteProtocolException::class.java) { RemoteWireJson.parse("{\"accessToken\":\"$secret") }
        assertFalse(failure.toString().contains(secret))
        assertNull(failure.cause)
        val frame = RemoteControlCodec.create("hello", Json.of(mapOf("role" to "client", "deviceId" to "a", "accessToken" to secret,
            "protocols" to listOf(1), "capabilities" to listOf("transport.relay"))) as JsonValue.JObject, "frame", 1)
        assertFalse(frame.toString().contains(secret))
    }

    @Test
    fun payload保留上游剥除扩展行为且封套拒绝扩展() {
        val frame = RemoteControlCodec.create("ping", Json.of(mapOf("nonce" to "a", "future" to true)) as JsonValue.JObject, "id", 1)
        assertNull(JsonFields.field(frame.payload, "future"))
        val extra = JsonValue.JObject(frame.toJson().fields + ("future" to JsonValue.JBool(true)))
        assertThrows(RemoteProtocolException::class.java) { RemoteControlCodec.parse(extra) }
    }

    @Test
    fun Base64URL拒绝填充非法位且不改写值() {
        listOf("AQ=", "AR", "A", "AA+_", "").forEach { value ->
            assertThrows(RemoteProtocolException::class.java) { RemoteBase64Url.decode(value) }
        }
        assertArrayEquals(byteArrayOf(1), RemoteBase64Url.decode("AQ"))
    }
}
