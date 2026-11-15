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
        private val keys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        var failOpen = false
        var failSeal = false
        var failInstallationSeal = false
        @Volatile var beforeOpen: (() -> Unit)? = null
        override fun hasKey(scopeKey: String) = scopeKey in keys
        override fun seal(scopeKey: String, plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            if (failSeal) throw java.io.IOException("模拟凭据持久化失败")
            if (failInstallationSeal && scopeKey == "installation-identity-v1") throw java.io.IOException("模拟安装身份持久化失败")
            return plaintext.copyOf().also { keys += scopeKey }
        }
        override fun open(scopeKey: String, ciphertext: ByteArray, associatedData: ByteArray): ByteArray {
            beforeOpen?.also { beforeOpen = null }?.invoke()
            if (failOpen) throw java.io.IOException("模拟凭据读取失败")
            return ciphertext.copyOf()
        }
    }

    @Test fun 冷启动未恢复直接退出仍清原账号授权并保存两角色安装身份() = runBlocking {
        val directory = Files.createTempDirectory("remote-cold-logout").toFile()
        val protector = Protector(); val vault = RemoteCredentialVault(directory, protector)
        val store = RemoteIdentityStore(vault, protector)
        val identities = seedAccount(vault, store, "https://remote.example", "旧账号")
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = RemoteAccountRuntime(RuntimeEnvironment.getApplication(), parent, vault = vault, protector = protector)
        try {
            assertNull(runtime.account.value)
            runtime.logout()
            assertNull(vault.activeAccount())
            RemoteRole.entries.forEach { assertNull(store.load(RemoteIdentityScope("https://remote.example", "旧账号", it))) }
            val saved = vault.installationIdentityStore { "测试手机" }.loadOrCreate()
            assertTrue(identities[0].sameIdentity(saved.client)); assertTrue(identities[1].sameIdentity(saved.host))
            assertNull(runtime.restore())
        } finally { parent.cancel() }
    }

    @Test fun 冷启动直接登录同账号或新账号不继承旧令牌与pin但保留两安装身份() = runBlocking {
        for (nextAccount in listOf("旧账号", "新账号")) {
            MockWebServer().use { server ->
                server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                        "/api/v1/auth/login" -> MockResponse().setBody(Json.canonical(obj("account" to nextAccount,
                            "token" to "l".repeat(16), "expiresAt" to System.currentTimeMillis() + 600000)))
                        "/api/v1/devices/register" -> MockResponse().setBody(Json.canonical(obj("accessToken" to "n".repeat(16),
                            "accessTokenExpiresAt" to System.currentTimeMillis() + 600000, "refreshToken" to "s".repeat(16),
                            "refreshTokenExpiresAt" to System.currentTimeMillis() + 1200000)))
                        else -> MockResponse().setResponseCode(404)
                    }
                }
                server.start()
                val origin = server.url("/").toString().removeSuffix("/")
                val protector = Protector(); val vault = RemoteCredentialVault(Files.createTempDirectory("remote-cold-login").toFile(), protector)
                val store = RemoteIdentityStore(vault, protector)
                val original = seedAccount(vault, store, origin, "旧账号")
                val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val runtime = RemoteAccountRuntime(RuntimeEnvironment.getApplication(), parent, vault = vault, protector = protector)
                try {
                    runtime.login(origin, nextAccount, "password")
                    assertEquals(nextAccount, runtime.account.value!!.accountId)
                    for ((index, role) in RemoteRole.entries.withIndex()) {
                        val saved = store.load(RemoteIdentityScope(origin, nextAccount, role))!!
                        assertTrue(original[index].sameIdentity(saved.identity))
                        assertEquals("n".repeat(16), saved.tokens.accessToken)
                        assertEquals("s".repeat(16), saved.tokens.refreshToken)
                        assertTrue("重新授权不能继承旧账号或旧登录的pin", saved.peers.isEmpty())
                        if (nextAccount != "旧账号") assertNull(store.load(RemoteIdentityScope(origin, "旧账号", role)))
                    }
                } finally { parent.cancel(); runtime.logout() }
            }
        }
    }

    @Test fun 冷启动退出迁移安装身份失败仍撤销账号但保留唯一旧密钥且报告未完成() = runBlocking {
        val protector = Protector(); val vault = RemoteCredentialVault(Files.createTempDirectory("remote-cold-migration-failure").toFile(), protector)
        val store = RemoteIdentityStore(vault, protector)
        val original = seedAccount(vault, store, "https://remote.example", "旧账号")
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = RemoteAccountRuntime(RuntimeEnvironment.getApplication(), parent, vault = vault, protector = protector)
        protector.failInstallationSeal = true
        try {
            val failure = runCatching { runtime.logout() }.exceptionOrNull() as RemoteRpcException
            assertEquals("REMOTE_LOGOUT_INCOMPLETE", failure.code)
            assertNull(vault.activeAccount()); assertNull(runtime.account.value); assertNull(runtime.restore())
            for ((index, role) in RemoteRole.entries.withIndex()) {
                assertTrue(original[index].sameIdentity(store.load(RemoteIdentityScope("https://remote.example", "旧账号", role))!!.identity))
            }
            assertEquals(RemoteAccountConnectionState.DISCONNECTED, runtime.state.value)
            protector.failInstallationSeal = false
            assertThrows(RemoteIdentityCorruptedException::class.java) {
                vault.installationIdentityStore { "不可重建身份" }.loadOrCreate()
            }
            val restarted = RemoteAccountRuntime(RuntimeEnvironment.getApplication(), parent, vault = vault, protector = protector)
            val relogin = runCatching { restarted.login("https://remote.example", "旧账号", "password") }.exceptionOrNull()
            assertTrue("再次登录必须在联网前拒绝把失败迁移当首次安装", relogin is RemoteIdentityCorruptedException)
            assertNull(restarted.account.value)
        } finally { parent.cancel() }
    }

    @Test fun 登录迁移安装身份失败也先撤销旧授权且不联网不删除唯一旧密钥() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val origin = server.url("/").toString().removeSuffix("/")
            val protector = Protector()
            val vault = RemoteCredentialVault(Files.createTempDirectory("remote-login-migration-failure").toFile(), protector)
            val store = RemoteIdentityStore(vault, protector)
            val original = seedAccount(vault, store, origin, "旧账号")
            val old = vault.activeAccount()!!
            val epoch = vault.authorizationEpoch()
            val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val runtime = RemoteAccountRuntime(RuntimeEnvironment.getApplication(), parent, vault = vault, protector = protector)
            protector.failInstallationSeal = true
            try {
                val failure = runCatching { runtime.login(origin, "新账号", "password") }.exceptionOrNull() as RemoteRpcException
                assertEquals("REMOTE_LOGOUT_INCOMPLETE", failure.code)
                assertNull(vault.activeAccount()); assertNull(runtime.account.value)
                assertTrue(vault.authorizationEpoch() > epoch)
                assertThrows(IllegalStateException::class.java) { vault.requireCurrent(old) }
                assertEquals(0, server.requestCount)
                assertTrue(runCatching { runtime.connect("旧电脑") }.exceptionOrNull() is RemoteRpcException)
                assertTrue(runCatching { runtime.authenticatedPeer("旧连接") }.exceptionOrNull() is RemoteRpcException)
                for ((index, role) in RemoteRole.entries.withIndex()) {
                    assertTrue(original[index].sameIdentity(store.load(RemoteIdentityScope(origin, "旧账号", role))!!.identity))
                    assertNull(store.load(RemoteIdentityScope(origin, "新账号", role)))
                }
                protector.failInstallationSeal = false
                assertThrows(RemoteIdentityCorruptedException::class.java) {
                    vault.installationIdentityStore { "不可自动替换" }.loadOrCreate()
                }
            } finally { parent.cancel() }
        }
    }

    private fun seedAccount(vault: RemoteCredentialVault, store: RemoteIdentityStore, origin: String, account: String): List<RemoteStaticIdentity> {
        val identities = RemoteRole.entries.map { RemoteStaticIdentityFactory.generate(RemoteDeviceId(java.util.UUID.randomUUID().toString())) }
        val peer = RemoteStaticIdentityFactory.generate(RemoteDeviceId(java.util.UUID.randomUUID().toString()))
        for ((index, role) in RemoteRole.entries.withIndex()) store.save(RemoteIdentityScope(origin, account, role), RemoteRoleCredential(
            identities[index], RemoteTokenPair("a".repeat(16), System.currentTimeMillis() + 600000, "r".repeat(16), System.currentTimeMillis() + 1200000),
            listOf(RemotePeerPin(if (role == RemoteRole.CLIENT) RemoteRole.HOST else RemoteRole.CLIENT, peer.deviceId, peer.publicKey))))
        val epoch = vault.revokeActive(); vault.activate(origin, account, epoch)
        return identities
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
