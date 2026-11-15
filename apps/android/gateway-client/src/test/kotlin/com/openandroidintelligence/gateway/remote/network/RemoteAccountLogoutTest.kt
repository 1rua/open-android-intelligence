package com.openandroidintelligence.gateway.remote.network

import com.openandroidintelligence.gateway.remote.auth.*
import com.openandroidintelligence.gateway.remote.authproduction.*
import com.openandroidintelligence.gateway.remote.identity.*
import com.openandroidintelligence.gateway.remote.protocol.*
import com.openandroidintelligence.gateway.remote.rpc.*
import com.openandroidintelligence.gateway.remote.runtime.*
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 凭据读写失败也必须关闭真实的Host与Client控制连接，且不能由重启恢复旧账号。 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class RemoteAccountLogoutTest {
    private class Protector : RemoteIdentityProtector {
        var failOpen = false
        var failSeal = false
        @Volatile var beforeOpen: (() -> Unit)? = null
        override fun hasKey(scopeKey: String) = true
        override fun seal(scopeKey: String, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            if (failSeal) throw java.io.IOException("模拟凭据持久化失败")
            return plaintext.copyOf()
        }
        override fun open(scopeKey: String, ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
            beforeOpen?.also { beforeOpen = null }?.invoke()
            if (failOpen) throw java.io.IOException("模拟凭据读取失败")
            return ciphertext.copyOf()
        }
    }
    @Test fun 恢复读取旧账号期间退出必须串行且结束后不能发布旧身份或新Host() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {}))
        server.start()
        val directory = Files.createTempDirectory("remote-restore-logout-race").toFile()
        val protector = Protector(); val vault = RemoteCredentialVault(directory, protector)
        val origin = server.url("/").toString().removeSuffix("/")
        val store = RemoteIdentityStore(vault, protector)
        store.save(RemoteIdentityScope(origin, "owner", RemoteRole.HOST), RemoteRoleCredential(
            RemoteStaticIdentityFactory.generate(RemoteDeviceId("phone-host")),
            RemoteTokenPair("a".repeat(16), System.currentTimeMillis() + 600000, "r".repeat(16), System.currentTimeMillis() + 1200000)))
        val epoch = vault.revokeActive(); vault.activate(origin, "owner", epoch)
        val entered = java.util.concurrent.CountDownLatch(1); val release = java.util.concurrent.CountDownLatch(1)
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val handler = object : RemoteRpcHostHandler {
            override fun events(scope: RemoteRpcConnectionScope) = emptyFlow<RemoteRpcEvent>()
            override suspend fun invoke(scope: RemoteRpcConnectionScope, method: String, params: JsonValue.JObject) = obj()
        }
        val runtime = RemoteAccountRuntime(RuntimeEnvironment.getApplication(), parent, handler, vault, protector)
        protector.beforeOpen = { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
        try {
            val restoring = async(Dispatchers.Default) { runtime.restore() }
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val signingOut = async(start = CoroutineStart.UNDISPATCHED) { runtime.logout() }
            assertFalse("退出必须等待旧恢复退出同一生命周期锁", signingOut.isCompleted)
            assertNull(runtime.account.value)
            release.countDown()
            withTimeout(5000) { restoring.await(); signingOut.await() }
            assertNull(runtime.account.value)
            assertEquals(RemoteAccountConnectionState.SIGNED_OUT, runtime.state.value)
            assertEquals(RemoteHostConnectionState.STOPPED, runtime.hostState.value)
            assertNull(runtime.restore())
            withTimeout(5000) { while (parent.coroutineContext[Job]!!.children.any()) delay(1) }
        } finally { release.countDown(); runtime.logout(); parent.cancel(); server.shutdown() }
    }

    @Test fun 撤销读取或写入失败仍关闭两个真实角色并保留可重试错误() = runBlocking {
        for (readFailure in listOf(true, false)) {
            val target = RemoteStaticIdentityFactory.generate(RemoteDeviceId("target-host"))
            val requested = CompletableDeferred<Unit>()
            val sockets = java.util.concurrent.ConcurrentHashMap<WebSocket, String>()
            val closed = java.util.concurrent.ConcurrentHashMap.newKeySet<WebSocket>()
            val server = MockWebServer()
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.path?.startsWith("/api/v1/devices/") == true) return MockResponse().setBody(Json.canonical(obj(
                        "deviceId" to target.deviceId.value, "name" to "宿主", "platform" to "linux", "role" to "host",
                        "membershipId" to "member", "identityKey" to RemoteBase64Url.encode(target.publicKey), "online" to true)))
                    return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onClosing(socket: WebSocket, code: Int, reason: String) { closed += socket; socket.close(code, null) }
                        override fun onFailure(socket: WebSocket, failure: Throwable, response: Response?) { closed += socket }
                        override fun onMessage(socket: WebSocket, text: String) {
                            val frame = RemoteControlCodec.decode(text.encodeToByteArray())
                            if (frame.type == "hello") { sockets[socket] = frame.payload.requiredText("role"); socket.send(Json.canonical(obj("v" to 1, "id" to "ack", "type" to "hello.ack", "timestamp" to 1,
                                "payload" to obj("protocol" to 1, "serverVersion" to "fixture", "connectionSessionId" to "session",
                                    "heartbeatIntervalMs" to 25000, "maxControlFrameBytes" to 65536, "maxRelayFrameBytes" to 1048576,
                                    "capabilities" to listOf("transport.relay"))))) }
                            if (frame.type == "connect.request") requested.complete(Unit)
                        }
                    })
                }
            }
            server.start()
            val origin = server.url("/").toString().removeSuffix("/")
            val directory = Files.createTempDirectory("remote-logout-runtime").toFile()
            val protector = Protector(); val vault = RemoteCredentialVault(directory, protector)
            val store = RemoteIdentityStore(vault, protector)
            for (role in RemoteRole.entries) store.save(RemoteIdentityScope(origin, "owner", role), RemoteRoleCredential(
                RemoteStaticIdentityFactory.generate(RemoteDeviceId("local-${role.wireValue}")),
                RemoteTokenPair("a".repeat(16), System.currentTimeMillis() + 600000, "r".repeat(16), System.currentTimeMillis() + 1200000)))
            val epoch = vault.revokeActive(); val active = vault.activate(origin, "owner", epoch)
            val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val handler = object : RemoteRpcHostHandler {
                override fun events(scope: RemoteRpcConnectionScope) = emptyFlow<RemoteRpcEvent>()
                override suspend fun invoke(scope: RemoteRpcConnectionScope, method: String, params: JsonValue.JObject) = obj()
            }
            val runtime = RemoteAccountRuntime(RuntimeEnvironment.getApplication(), parent, handler, vault, protector)
            try {
                runtime.restore()
                val connecting = parent.async { runCatching { runtime.connect(target.deviceId.value) } }
                withTimeout(5000) { requested.await(); while (sockets.size < 2) delay(1) }
                assertEquals(setOf("host", "client"), sockets.values.toSet())
                protector.failOpen = readFailure; protector.failSeal = !readFailure
                val failure = runCatching { runtime.logout() }.exceptionOrNull() as RemoteRpcException
                assertEquals("REMOTE_LOGOUT_INCOMPLETE", failure.code)
                assertNull(runtime.account.value)
                assertEquals(RemoteAccountConnectionState.DISCONNECTED, runtime.state.value)
                assertEquals(RemoteHostConnectionState.STOPPED, runtime.hostState.value)
                assertTrue(withTimeout(3000) { connecting.await() }.isFailure)
                withTimeout(5000) { while (!closed.containsAll(sockets.keys) || parent.coroutineContext[Job]!!.children.any()) delay(1) }
                assertThrows(RemoteRpcException::class.java) { runBlocking { runtime.connect(target.deviceId.value) } }
                protector.failOpen = false; protector.failSeal = false
                val restarted = RemoteCredentialVault(directory, protector)
                assertNull(restarted.activeAccount())
                assertThrows(IllegalStateException::class.java) { restarted.requireCurrent(active) }
                assertNull(runtime.restore())
                runtime.logout()
                assertEquals(RemoteAccountConnectionState.SIGNED_OUT, runtime.state.value)
            } finally {
                protector.failOpen = false; protector.failSeal = false
                runCatching { runtime.logout() }; parent.cancel(); server.shutdown()
            }
        }
    }
}
