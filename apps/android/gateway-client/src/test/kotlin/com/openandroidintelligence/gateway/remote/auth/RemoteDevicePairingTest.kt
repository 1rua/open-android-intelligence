package com.openandroidintelligence.gateway.remote.auth

import com.openandroidintelligence.gateway.remote.identity.*
import com.openandroidintelligence.gateway.remote.network.RemoteServerApi
import com.openandroidintelligence.gateway.remote.protocol.RemoteBase64Url
import com.openandroidintelligence.gateway.remote.runtime.*
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class RemoteDevicePairingTest {
    private class Memory : RemoteIdentityPersistence {
        val records = mutableMapOf<String, ByteArray>()
        override fun read(scopeKey: String) = records[scopeKey]?.copyOf()
        override fun writeAtomic(scopeKey: String, value: ByteArray) { records[scopeKey] = value.copyOf() }
        override fun clear(scopeKey: String) { records.remove(scopeKey) }
    }
    private val privateKey = RemoteBase64Url.decode("AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA")
    private val serverKey = RemoteBase64Url.decode("WGmv9FBUlzLLqu1eXfmzCm2jHLDldCutWtShp2jxpns")
    private val hostKey = RemoteBase64Url.encode(RemoteStaticIdentityFactory.generate(RemoteDeviceId("host")).publicKey)
    private val id = "019a0000-0000-7000-8000-000000000001"
    private val ticket = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA"
    private fun session(origin: String, now: Long = 1000L) = obj("type" to "dsh.remote.pairing", "serverUrl" to origin, "v" to 1,
        "sessionId" to id, "expiresAt" to now + 180000, "serverPublicKey" to RemoteBase64Url.encode(serverKey), "scanSecret" to ticket,
        "host" to obj("deviceId" to "host", "name" to "测试电脑", "role" to "host", "platform" to "desktop", "identityKey" to hostKey))
    @Test fun 重新扫码使用同一安装身份且改名不改变密钥() {
        val memory = Memory()
        val identity = RemoteStaticIdentityFactory.generate(RemoteDeviceId("019a0000-0000-7000-8000-000000000002"))
        var name = "我的手机"
        fun pairing() = RemoteDevicePairing(memory, identityProvider = { identity }, deviceNameProvider = { name }, now = { 1000L })
        val first = pairing().prepareClaim(RemotePairingSession(session(RemoteServerApi.DEFAULT_ORIGIN)), 1, "首次登录")
        pairing().clear()
        name = "改名后的手机"
        val second = pairing().prepareClaim(RemotePairingSession(session(RemoteServerApi.DEFAULT_ORIGIN)), 2, "重新登录")
        assertTrue(first.identity.sameIdentity(second.identity))
        val descriptor = com.openandroidintelligence.gateway.remote.protocol.RemoteWireJson.decode(second.rawClaim.encodeToByteArray())
            .objectValue().value("device").objectValue()
        assertEquals(name, descriptor.requiredText("name"))
        assertEquals(identity.deviceId.value, descriptor.requiredText("deviceId"))
        assertEquals(RemoteBase64Url.encode(identity.publicKey), descriptor.requiredText("identityKey"))
    }
    @Test fun 手机证明与上游公开固定向量一致且绑定实际请求字节() {
        val path = "/api/v1/device-pairing/sessions/$id/mobile/status"
        assertEquals("3izg2tEapIkukkVFqJc7cPhHHtGLUEgHzs8f_O0r-Ag", RemoteDevicePairing.proof(privateKey, serverKey, id, path, "{}"))
        assertNotEquals(RemoteDevicePairing.proof(privateKey, serverKey, id, path, "{}"), RemoteDevicePairing.proof(privateKey, serverKey, id, path, "{ }"))
        assertThrows(Exception::class.java) { RemoteDevicePairing.proof(privateKey, ByteArray(32), id, path, "{}") }
    }
    @Test fun 扫码拒绝外域用户信息错误路径重复属性过期或角色篡改() {
        val pairing = RemoteDevicePairing(Memory(), now = { 1000L })
        assertNotNull(pairing.parseQr("https://dsh.r2049.cn/pair#v1.$ticket").ticket)
        assertNotNull(pairing.parseQr(Json.canonical(session(RemoteServerApi.DEFAULT_ORIGIN))).session)
        for (raw in listOf("http://dsh.r2049.cn/pair#v1.$ticket", "https://attacker.example/pair#v1.$ticket",
            "https://attacker@dsh.r2049.cn/pair#v1.$ticket", "https://dsh.r2049.cn/pair?x=1#v1.$ticket",
            "https://dsh.r2049.cn/pair/path#v1.$ticket", "https://dsh.r2049.cn/pair#v1.${"a".repeat(43)}",
            Json.canonical(session("https://attacker.example")),
            Json.canonical(session(RemoteServerApi.DEFAULT_ORIGIN)).replace("\"role\":\"host\"", "\"role\":\"client\""),
            Json.canonical(session(RemoteServerApi.DEFAULT_ORIGIN, -200000)),
            Json.canonical(session(RemoteServerApi.DEFAULT_ORIGIN)).replace("\"v\":1", "\"v\":1,\"v\":1"))) {
            assertThrows(Exception::class.java) { pairing.parseQr(raw) }
        }
    }
    @Test fun claim重启保留相同设备和准确正文且状态返回的Host必须匹配() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val origin = server.url("/").toString().removeSuffix("/")
            val memory = Memory(); val api = RemoteServerApi(origin)
            val pairing = RemoteDevicePairing(memory, api) { 1000L }
            val value = pairing.parseQr(Json.canonical(session(origin))).session!!
            val pending = pairing.prepareClaim(value, 2, java.util.UUID.randomUUID().toString())
            val restored = RemoteDevicePairing(memory, api) { 1000L }.restore()!!
            assertTrue(restored.identity.sameIdentity(pending.identity)); assertEquals(pending.rawClaim, restored.rawClaim)
            server.enqueue(MockResponse().setBody("{\"state\":\"claimed\",\"verificationCode\":\"00123456\",\"expiresAt\":181000}"))
            assertEquals("00123456", pairing.claim(restored).verificationCode)
            val request = server.takeRequest()
            assertEquals(pending.rawClaim, request.body.readUtf8()); assertNull(request.getHeader("Authorization"))
            assertNotNull(request.getHeader("X-DSH-Pairing-Proof"))
            server.enqueue(MockResponse().setBody(Json.canonical(obj("v" to 1, "sessionId" to id, "expiresAt" to 181000,
                "state" to "claimed", "host" to obj("deviceId" to "attacker", "role" to "host", "identityKey" to hostKey),
                "mobile" to null, "credentials" to null))))
            assertTrue(runCatching { pairing.status(restored) }.isFailure)
            pairing.clear(); assertNull(pairing.restore())
        }
    }
}
