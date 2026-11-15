package com.openandroidintelligence.gateway.remote.network

import com.openandroidintelligence.gateway.remote.identity.*
import com.openandroidintelligence.gateway.remote.protocol.RemoteWireJson
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcException
import com.openandroidintelligence.gateway.remote.runtime.*
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RemoteServerApiTest {
    private class Server : AutoCloseable {
        val requests = CopyOnWriteArrayList<Triple<String, String, JsonValue>>()
        private val routes = mutableMapOf<String, Pair<Int, (JsonValue) -> JsonValue>>()
        val server = MockWebServer()
        val origin get() = server.url("/").toString().removeSuffix("/")
        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val input = request.body.readUtf8().let { if (it.isEmpty()) JsonValue.JNull else RemoteWireJson.parse(it) }
                    requests += Triple(request.path!!, request.getHeader("Authorization").orEmpty(), input)
                    val route = routes[request.requestUrl!!.encodedPath] ?: return MockResponse().setResponseCode(404)
                    return MockResponse().setResponseCode(route.first).setHeader("Content-Type", "application/json")
                        .apply { if (route.first != 204) setBody(Json.canonical(route.second(input))) }
                }
            }
            server.start()
        }
        fun route(path: String, status: Int = 200, block: (JsonValue) -> JsonValue) { routes[path] = status to block }
        override fun close() { server.shutdown() }
    }
    @Test fun 真实HTTP登录双角色注册刷新目录和撤销使用固定线格式() = runBlocking {
        Server().use { fixture ->
            val token = obj("accessToken" to "a".repeat(16), "accessTokenExpiresAt" to System.currentTimeMillis()+60_000,
                "refreshToken" to "r".repeat(16), "refreshTokenExpiresAt" to System.currentTimeMillis()+600_000)
            fixture.route("/api/v1/auth/login") { obj("token" to "l".repeat(16), "account" to "owner", "expiresAt" to System.currentTimeMillis()+60_000, "isAdmin" to false) }
            fixture.route("/api/v1/devices/register") { token }
            fixture.route("/api/v1/auth/refresh") { token }
            fixture.route("/api/v1/devices/self", 204) { JsonValue.JNull }
            fixture.route("/api/v1/devices") { obj("items" to listOf(obj("deviceId" to "host", "name" to "电脑", "platform" to "linux", "membershipId" to "membership", "online" to true))) }
            fixture.route("/api/v1/devices/host/presence") { obj("online" to true) }
            val api = RemoteServerApi(fixture.origin); val login = api.login("owner", "password")
            for (role in RemoteRole.entries) {
                val identity = RemoteStaticIdentityFactory.generate(RemoteDeviceId(java.util.UUID.randomUUID().toString()))
                val result = api.registerAccountRole(RemoteIdentityScope(fixture.origin, "owner", role), identity, login.token)
                assertEquals(identity.deviceId, result.identity.deviceId)
            }
            api.refresh(RemoteIdentityScope(fixture.origin, "owner", RemoteRole.CLIENT), java.util.UUID.randomUUID().toString(), "r".repeat(16))
            assertEquals("host", api.listHosts("a".repeat(16)).single().deviceId)
            api.revoke("a".repeat(16))
            val registrations = fixture.requests.filter { it.first == "/api/v1/devices/register" }
            assertEquals(2, registrations.size); assertTrue(registrations.all { it.second == "Bearer ${"l".repeat(16)}" })
            assertTrue(registrations.all { it.third.objectValue().value("device").objectValue().requiredText("clientVersion") == com.openandroidintelligence.gateway.client.BuildConfig.OAI_REMOTE_CLIENT_VERSION })
            assertEquals(setOf("client", "host"), registrations.map { it.third.objectValue().value("device").objectValue().requiredText("role") }.toSet())
            assertEquals(7, fixture.requests.size)
        }
    }
    @Test fun 上游目录无online时必须查询presence且以独立在线事实为准() = runBlocking {
        Server().use { fixture ->
            // 来自固定 ea1c0925 packages/plugin/tests/server-api.test.ts 的 host(0)，没有 online 字段。
            fixture.route("/api/v1/devices") { obj("items" to listOf(obj("deviceId" to "host-0", "role" to "host",
                "name" to "Host 0", "platform" to "linux", "membershipId" to "member-0")), "nextCursor" to null) }
            var online = true
            fixture.route("/api/v1/devices/host-0/presence") { obj("online" to online) }
            val api = RemoteServerApi(fixture.origin)
            assertTrue(api.listHosts("a".repeat(16)).single().online)
            online = false
            assertFalse(api.listHosts("a".repeat(16)).single().online)
            assertEquals(2, fixture.requests.count { it.first == "/api/v1/devices/host-0/presence" })
            assertTrue(fixture.requests.all { it.second == "Bearer ${"a".repeat(16)}" })
        }
    }
    @Test fun presence失败不得回退到目录中已经过期的在线值() = runBlocking {
        Server().use { fixture ->
            fixture.route("/api/v1/devices") { obj("items" to listOf(obj("deviceId" to "host-0", "name" to "Host 0",
                "platform" to "linux", "membershipId" to "member-0", "online" to true))) }
            fixture.route("/api/v1/devices/host-0/presence", 503) { obj("error" to obj("code" to "CONNECTION_FAILED")) }
            assertFalse(RemoteServerApi(fixture.origin).listHosts("a".repeat(16)).single().online)
        }
    }
    @Test fun 私网HTTP只接受字面地址且规范化IDN与默认端口() {
        assertEquals("https://xn--fsqu00a.xn--0zwm56d", normalizeRemoteOrigin("https://例子.测试:443/"))
        assertEquals("http://192.168.1.2", normalizeRemoteOrigin("http://192.168.1.2:80"))
        assertEquals("http://[::1]", normalizeRemoteOrigin("http://[::1]"))
        assertThrows(IllegalArgumentException::class.java) { normalizeRemoteOrigin("http://10.attacker.example") }
        assertThrows(IllegalArgumentException::class.java) { normalizeRemoteOrigin("http://192.168.attacker.example") }
    }
    @Test fun 错误响应只保留固定代码且不自动重发写入() = runBlocking {
        Server().use { fixture ->
            fixture.route("/api/v1/auth/login", 401) { obj("error" to obj("code" to "AUTH_INVALID", "message" to "secret", "retryable" to false)) }
            val failure = runCatching { RemoteServerApi(fixture.origin).login("owner", "password") }.exceptionOrNull() as RemoteRpcException
            assertEquals("AUTH_INVALID", failure.code); assertFalse(failure.toString().contains("secret")); assertEquals(1, fixture.requests.size)
        }
    }
}
