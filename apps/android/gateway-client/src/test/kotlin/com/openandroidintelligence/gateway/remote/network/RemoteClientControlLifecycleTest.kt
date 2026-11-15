package com.openandroidintelligence.gateway.remote.network

import com.openandroidintelligence.gateway.remote.auth.RemoteIdentityProtector
import com.openandroidintelligence.gateway.remote.authproduction.RemoteCredentialVault
import com.openandroidintelligence.gateway.remote.protocol.RemoteControlCodec
import com.openandroidintelligence.gateway.remote.runtime.*
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class RemoteClientControlLifecycleTest {
    private class Protector : RemoteIdentityProtector {
        override fun hasKey(scopeKey: String) = true
        override fun seal(scopeKey: String, plaintext: ByteArray, associatedData: ByteArray) = plaintext.copyOf()
        override fun open(scopeKey: String, ciphertext: ByteArray, associatedData: ByteArray) = ciphertext.copyOf()
    }
    private class Fixture : AutoCloseable {
        val server = MockWebServer()
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sockets = CopyOnWriteArrayList<WebSocket>()
        val registrations = CopyOnWriteArrayList<JsonValue.JObject>()
        val connectRequests = AtomicInteger()
        val pongs = AtomicInteger()
        val presenceCalls = AtomicInteger()
        val online = AtomicBoolean(true)
        val presenceDenied = AtomicBoolean(false)
        val runtime: RemoteAccountRuntime
        init {
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/api/v1/auth/login" -> json(obj("token" to "l".repeat(16), "account" to "owner", "expiresAt" to System.currentTimeMillis() + 600_000))
                    request.path == "/api/v1/devices/register" -> {
                        registrations += com.openandroidintelligence.gateway.remote.protocol.RemoteWireJson.parse(request.body.readUtf8()).objectValue().value("device").objectValue()
                        json(obj("accessToken" to "a".repeat(16), "accessTokenExpiresAt" to System.currentTimeMillis() + 600_000,
                            "refreshToken" to "r".repeat(16), "refreshTokenExpiresAt" to System.currentTimeMillis() + 1_200_000))
                    }
                    request.path?.startsWith("/api/v1/devices?") == true -> {
                        // 固定 plugin/tests/server-api.test.ts 的真实 host(0) 目录形状，刻意不含 online。
                        json(obj("items" to listOf(obj("deviceId" to "host-0", "role" to "host", "name" to "Host 0",
                            "platform" to "linux", "membershipId" to "member-0")), "nextCursor" to null))
                    }
                    request.path == "/api/v1/devices/host-0/presence" -> {
                        presenceCalls.incrementAndGet()
                        if (presenceDenied.get()) json(obj("error" to obj("code" to "MEMBERSHIP_REQUIRED"))).setResponseCode(403)
                        else json(obj("deviceId" to "host-0", "online" to online.get()))
                    }
                    request.path == "/ws/v1/connect" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onMessage(socket: WebSocket, text: String) {
                            val frame = RemoteControlCodec.decode(text.encodeToByteArray())
                            when (frame.type) {
                                "hello" -> {
                                    assertEquals("client", frame.payload.requiredText("role"))
                                    sockets += socket
                                    // 来自固定 server/gateway.ts 的 hello.ack 字段；短间隔只压缩反馈周期。
                                    socket.send(Json.canonical(obj("v" to 1, "id" to "ack", "type" to "hello.ack", "timestamp" to 1,
                                        "payload" to obj("protocol" to 1, "serverVersion" to "self-hosted/0.1.0", "connectionSessionId" to "session-${sockets.size}",
                                            "heartbeatIntervalMs" to 50, "maxControlFrameBytes" to 65536, "maxRelayFrameBytes" to 1048576,
                                            "capabilities" to listOf("transport.relay")))))
                                    socket.send(Json.canonical(obj("v" to 1, "id" to "ping", "type" to "ping", "timestamp" to 1, "payload" to obj("nonce" to "server-heartbeat"))))
                                }
                                "ping" -> socket.send(Json.canonical(obj("v" to 1, "id" to "pong", "type" to "pong", "timestamp" to 1, "payload" to frame.payload)))
                                "pong" -> pongs.incrementAndGet()
                                "connect.request" -> connectRequests.incrementAndGet()
                            }
                        }
                        override fun onClosing(socket: WebSocket, code: Int, reason: String) { socket.close(code, null) }
                    })
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val protector = Protector()
            runtime = RemoteAccountRuntime(RuntimeEnvironment.getApplication(), parent,
                vault = RemoteCredentialVault(Files.createTempDirectory("remote-control-lifecycle").toFile(), protector), protector = protector)
        }
        suspend fun login() { runtime.login(server.url("/").toString().removeSuffix("/"), "owner", "password") }
        override fun close() { runBlocking { runtime.logout() }; parent.cancel(); server.shutdown() }
        private fun json(value: JsonValue) = MockResponse().setHeader("Content-Type", "application/json").setBody(Json.canonical(value))
    }
    private suspend fun await(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(5) }

    @Test fun 登录不选Host也立即注册Client并维持服务器心跳和在线刷新() = runBlocking {
        Fixture().use { fixture ->
            fixture.login()
            await { fixture.runtime.state.value == RemoteAccountConnectionState.CONNECTED && fixture.runtime.hosts.value.singleOrNull()?.online == true && fixture.pongs.get() > 0 }
            assertEquals(0, fixture.connectRequests.get())
            assertEquals(1, fixture.sockets.size)
            fixture.online.set(false)
            await { fixture.runtime.hosts.value.singleOrNull()?.online == false }
            fixture.presenceDenied.set(true)
            val before = fixture.presenceCalls.get()
            await { fixture.presenceCalls.get() > before }
            assertNotNull(fixture.runtime.account.value)
            assertEquals(RemoteAccountConnectionState.CONNECTED, fixture.runtime.state.value)
            fixture.presenceDenied.set(false); fixture.online.set(true)
            await { fixture.runtime.hosts.value.singleOrNull()?.online == true }
            fixture.sockets.last().close(1012, "服务重启")
            await { fixture.runtime.state.value == RemoteAccountConnectionState.DISCONNECTED }
            assertNotNull(fixture.runtime.account.value)
            await { fixture.sockets.size == 2 && fixture.runtime.state.value == RemoteAccountConnectionState.CONNECTED }
            fixture.runtime.logout()
            assertNull(fixture.runtime.account.value)
            assertEquals(RemoteAccountConnectionState.SIGNED_OUT, fixture.runtime.state.value)
            await { fixture.parent.coroutineContext[Job]!!.children.none() }
            assertEquals(2, fixture.sockets.size)
        }
    }

    @Test fun 真实账号退出再登录复用安装的两角色身份但建立新的控制连接() = runBlocking {
        Fixture().use { fixture ->
            fixture.login()
            await { fixture.runtime.state.value == RemoteAccountConnectionState.CONNECTED }
            val first = fixture.registrations.take(2)
            fixture.runtime.logout()
            fixture.login()
            await { fixture.runtime.state.value == RemoteAccountConnectionState.CONNECTED && fixture.sockets.size == 2 }
            val second = fixture.registrations.drop(2)
            assertEquals(first.map { it.requiredText("deviceId") }, second.map { it.requiredText("deviceId") })
            assertEquals(first.map { it.requiredText("identityKey") }, second.map { it.requiredText("identityKey") })
            assertNotEquals(first[0].requiredText("identityKey"), first[1].requiredText("identityKey"))
        }
    }
}
