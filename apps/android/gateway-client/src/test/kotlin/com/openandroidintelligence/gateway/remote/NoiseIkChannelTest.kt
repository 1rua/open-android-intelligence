package com.openandroidintelligence.gateway.remote

import com.openandroidintelligence.gateway.remote.crypto.NoiseChannelException
import com.openandroidintelligence.gateway.remote.crypto.NoiseIkChannel
import com.openandroidintelligence.gateway.remote.crypto.NoiseRole
import com.openandroidintelligence.gateway.remote.crypto.RemoteSecureChannel
import com.openandroidintelligence.gateway.remote.protocol.RemoteBase64Url
import com.openandroidintelligence.gateway.remote.protocol.RemoteConnectionBinding
import com.openandroidintelligence.gateway.remote.protocol.RemoteControlCodec
import com.openandroidintelligence.gateway.remote.protocol.RemoteProtocol
import com.openandroidintelligence.gateway.remote.protocol.RemoteProtocolException
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue
import org.junit.Assert.*
import org.junit.Test

class NoiseIkChannelTest {
    private val vector = RemoteFixtures.noise
    private val prologue = RemoteFixtures.obj(vector, "prologue")
    private val binding = RemoteConnectionBinding(
        RemoteFixtures.text(prologue, "connectionId"), RemoteFixtures.text(prologue, "hostDeviceId"), RemoteFixtures.text(prologue, "clientDeviceId"),
    )

    @Test
    fun 固定向量格式与编码不可静默升级() {
        val file = RemoteFixtures.json("fixtures/crypto/v1/noise-ik.json")
        assertEquals("dsh-remote.noise-vectors", RemoteFixtures.text(file, "fixtureFormat"))
        assertEquals(1, JsonFields.int(file, "fixtureFormatVersion"))
        assertEquals(1, JsonFields.int(file, "protocolVersion"))
        assertEquals("base64url-no-padding", RemoteFixtures.text(file, "encoding"))
    }

    @Test
    fun 固定向量验证密码套件静态键握手双向传输及计数器() {
        val file = RemoteFixtures.json("fixtures/crypto/v1/noise-ik.json")
        assertEquals(RemoteProtocol.NOISE_SUITE, RemoteFixtures.text(file, "suite"))
        assertEquals(RemoteFixtures.text(prologue, "bytes"), RemoteBase64Url.encode(binding.prologue()))
        listOf("initiator", "responder").forEach { role ->
            val keys = keys(role)
            assertArrayEquals(decoded(keys, "publicKey"), NoiseIkChannel.publicKeyFor(decoded(keys, "privateKey")))
        }
        val client = channel("initiator")
        val host = channel("responder")
        try {
            val handshake = RemoteFixtures.obj(vector, "handshake")
            val first = RemoteFixtures.obj(handshake, "message1")
            val second = RemoteFixtures.obj(handshake, "message2")
            assertTrue(client.canWriteHandshake)
            assertFalse(host.canWriteHandshake)
            assertArrayEquals(decoded(first, "message"), client.writeHandshake(decoded(first, "payload")))
            assertArrayEquals(decoded(first, "payload"), host.readHandshake(decoded(first, "message")))
            assertArrayEquals(decoded(second, "message"), host.writeHandshake(decoded(second, "payload")))
            assertArrayEquals(decoded(second, "payload"), client.readHandshake(decoded(second, "message")))
            assertTrue(client.complete)
            assertTrue(host.complete)
            assertCounters(client, host, RemoteFixtures.obj(vector, "countersAfterHandshake"))
            RemoteFixtures.list(vector, "transport").forEach { item ->
                val row = item as JsonValue.JObject
                val source = if (RemoteFixtures.text(row, "sender") == "initiator") client else host
                val target = if (source === client) host else client
                val encrypted = source.encrypt(decoded(row, "plaintext"))
                assertArrayEquals(decoded(row, "ciphertext"), encrypted)
                assertArrayEquals(decoded(row, "plaintext"), target.decrypt(encrypted))
                assertCounters(client, host, RemoteFixtures.obj(row, "countersAfter"))
            }
        } finally { client.close(); host.close() }
    }

    @Test
    fun 生产随机临时键可以双向通信且不会重用向量握手() {
        val a = channel("initiator", fixture = false)
        val b = channel("responder", fixture = false)
        try {
            val first = a.writeHandshake()
            val expected = RemoteFixtures.obj(RemoteFixtures.obj(vector, "handshake"), "message1")
            assertFalse(first.contentEquals(decoded(expected, "message")))
            b.readHandshake(first)
            a.readHandshake(b.writeHandshake())
            assertArrayEquals("双向".toByteArray(), b.decrypt(a.encrypt("双向".toByteArray())))
            assertArrayEquals(ByteArray(0), a.decrypt(b.encrypt(ByteArray(0))))
        } finally { a.close(); b.close() }
    }

    @Test
    fun Host必须核对实际Client静态公钥() {
        val a = channel("initiator")
        val wrongPin = decoded(keys("initiator"), "publicKey").also { it[0] = (it[0].toInt() xor 1).toByte() }
        val b = NoiseIkChannel.create(NoiseRole.HOST_RESPONDER, binding, decoded(keys("responder"), "privateKey"), wrongPin)
        try {
            val failure = assertThrows(NoiseChannelException::class.java) { b.readHandshake(a.writeHandshake()) }
            assertEquals("PEER_IDENTITY_MISMATCH", failure.code)
            assertFalse(b.complete)
            assertThrows(NoiseChannelException::class.java) { b.writeHandshake() }
        } finally { a.close(); b.close() }
    }

    @Test
    fun prologue绑定连接与双方设备且不能跨连接握手() {
        val a = channel("initiator")
        val other = RemoteConnectionBinding("另一条连接", binding.hostDeviceId, binding.clientDeviceId)
        val b = NoiseIkChannel.create(NoiseRole.HOST_RESPONDER, other, decoded(keys("responder"), "privateKey"), decoded(keys("initiator"), "publicKey"))
        try { assertThrows(NoiseChannelException::class.java) { b.readHandshake(a.writeHandshake()) } }
        finally { a.close(); b.close() }
        assertThrows(RemoteProtocolException::class.java) { RemoteConnectionBinding("a\u0000b", "host", "client") }
    }

    @Test
    fun 篡改与重放均关闭通道() {
        listOf(false, true).forEach { replay ->
            val (a, b) = ready()
            try {
                val encrypted = a.encrypt("只执行一次".toByteArray())
                if (replay) b.decrypt(encrypted) else encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
                assertThrows(NoiseChannelException::class.java) { b.decrypt(encrypted) }
                assertFalse(b.complete)
                assertThrows(NoiseChannelException::class.java) { b.decrypt(encrypted) }
            } finally { a.close(); b.close() }
        }
    }

    @Test
    fun 未握手不允许业务且握手顺序错误不能继续() {
        val a = channel("initiator")
        try {
            assertThrows(NoiseChannelException::class.java) { a.encrypt(byteArrayOf(1)) }
            assertThrows(NoiseChannelException::class.java) { a.readHandshake(byteArrayOf(1)) }
            assertThrows(NoiseChannelException::class.java) { a.writeHandshake() }
        } finally { a.close() }
    }

    @Test
    fun 原始帧超限或认证标签不足时关闭() {
        listOf(false, true).forEach { oversized ->
            val (a, b) = ready()
            try {
                if (oversized) assertThrows(NoiseChannelException::class.java) { a.encrypt(ByteArray(65_520)) }
                else assertThrows(NoiseChannelException::class.java) { a.decrypt(ByteArray(15)) }
                assertFalse(a.complete)
            } finally { a.close(); b.close() }
        }
    }

    @Test
    fun 安全通道核对握手帧归属并支持大消息分块() {
        val a = RemoteSecureChannel(channel("initiator"))
        val b = RemoteSecureChannel(channel("responder"))
        try {
            assertEquals(binding, a.binding)
            assertEquals(binding, b.binding)
            b.readHandshakeFrame(a.writeHandshakeFrame("first", 1))
            a.readHandshakeFrame(b.writeHandshakeFrame("second", 2))
            val bytes = ByteArray(150_000) { (it % 251).toByte() }
            val frames = a.encryptMessage(bytes)
            assertTrue(frames.size > 1)
            frames.dropLast(1).forEach { assertNull(b.decryptMessage(it)) }
            assertArrayEquals(bytes, b.decryptMessage(frames.last()))
        } finally { a.close(); b.close() }
        val wrong = RemoteSecureChannel(channel("responder"))
        val payload = Json.of(mapOf("connectionId" to binding.connectionId, "targetDeviceId" to "其他设备", "step" to 1, "data" to "AQ")) as JsonValue.JObject
        assertThrows(RemoteProtocolException::class.java) {
            wrong.readHandshakeFrame(RemoteControlCodec.create("secure.handshake", payload, "wrong", 1))
        }
        assertFalse(wrong.complete)
    }

    private fun ready(): Pair<NoiseIkChannel, NoiseIkChannel> {
        val a = channel("initiator")
        val b = channel("responder")
        b.readHandshake(a.writeHandshake())
        a.readHandshake(b.writeHandshake())
        return a to b
    }

    private fun keys(role: String): JsonValue.JObject = RemoteFixtures.obj(RemoteFixtures.obj(vector, "staticKeys"), role)
    private fun decoded(value: JsonValue.JObject, field: String): ByteArray = RemoteBase64Url.decode(RemoteFixtures.text(value, field))
    private fun channel(role: String, fixture: Boolean = true): NoiseIkChannel {
        val kind = if (role == "initiator") NoiseRole.CLIENT_INITIATOR else NoiseRole.HOST_RESPONDER
        val local = decoded(keys(role), "privateKey")
        val remote = decoded(keys(if (role == "initiator") "responder" else "initiator"), "publicKey")
        return if (fixture) NoiseIkChannel.forFixture(kind, binding, local, remote, decoded(RemoteFixtures.obj(vector, "ephemeralPrivateKeys"), role))
        else NoiseIkChannel.create(kind, binding, local, remote)
    }

    private fun assertCounters(a: NoiseIkChannel, b: NoiseIkChannel, expected: JsonValue.JObject) {
        assertEquals(RemoteFixtures.text(expected, "initiatorSending"), a.sendingCounter().toString())
        assertEquals(RemoteFixtures.text(expected, "initiatorReceiving"), a.receivingCounter().toString())
        assertEquals(RemoteFixtures.text(expected, "responderSending"), b.sendingCounter().toString())
        assertEquals(RemoteFixtures.text(expected, "responderReceiving"), b.receivingCounter().toString())
    }
}
