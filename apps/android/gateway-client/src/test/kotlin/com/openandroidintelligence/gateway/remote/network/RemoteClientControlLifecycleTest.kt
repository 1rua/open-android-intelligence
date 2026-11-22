package com.openandroidintelligence.gateway.remote.network

import com.openandroidintelligence.gateway.remote.auth.RemoteIdentityProtector
import com.openandroidintelligence.gateway.remote.auth.RemoteIdentityStore
import com.openandroidintelligence.gateway.remote.auth.RemoteLoginResult
import com.openandroidintelligence.gateway.remote.auth.RemoteRoleRegistrationState
import com.openandroidintelligence.gateway.remote.authproduction.RemoteCredentialVault
import com.openandroidintelligence.gateway.remote.authproduction.RemoteInstallationIdentityStore
import com.openandroidintelligence.gateway.remote.identity.RemoteIdentityScope
import com.openandroidintelligence.gateway.remote.identity.RemoteRole
import com.openandroidintelligence.gateway.remote.protocol.RemoteProtocol
import com.openandroidintelligence.gateway.remote.protocol.RemoteControlCodec
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcEvent
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcHostHandler
import com.openandroidintelligence.gateway.remote.runtime.*
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class RemoteClientControlLifecycleTest {
    private class Protector : RemoteIdentityProtector {
        private val keys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        override fun hasKey(scopeKey: String) = scopeKey in keys
        override fun seal(scopeKey: String, plaintext: ByteArray, associatedData: ByteArray) = plaintext.copyOf().also { keys += scopeKey }
        override fun open(scopeKey: String, ciphertext: ByteArray, associatedData: ByteArray) = ciphertext.copyOf()
    }
    private class EmptyHostHandler : RemoteRpcHostHandler {
        override fun events(scope: com.openandroidintelligence.gateway.remote.rpc.RemoteRpcConnectionScope) = emptyFlow<RemoteRpcEvent>()
        override suspend fun invoke(scope: com.openandroidintelligence.gateway.remote.rpc.RemoteRpcConnectionScope,
            method: String, params: JsonValue.JObject) = obj()
    }
    private data class Hello(val socket: WebSocket, val payload: JsonValue.JObject) {
        val role: String get() = payload.requiredText("role")
    }
    private class Fixture(private val hostHandler: RemoteRpcHostHandler? = null) : AutoCloseable {
        val server = MockWebServer()
        val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sockets = CopyOnWriteArrayList<WebSocket>()
        val registrations = CopyOnWriteArrayList<JsonValue.JObject>()
        val registrationAuthorizations = CopyOnWriteArrayList<String>()
        val registrationTokens = CopyOnWriteArrayList<Pair<String, String>>()
        val rejectedRegistrationRoles = CopyOnWriteArrayList<String>()
        val helloFrames = CopyOnWriteArrayList<Hello>()
        val acknowledgements = CopyOnWriteArrayList<String>()
        val refreshRequests = CopyOnWriteArrayList<JsonValue.JObject>()
        val accountTokenRequests = CopyOnWriteArrayList<String>()
        val directoryExtras = CopyOnWriteArrayList<JsonValue.JObject>()
        val connectRequests = AtomicInteger()
        val pongs = AtomicInteger()
        val passwordLoginCalls = AtomicInteger()
        val presenceCalls = AtomicInteger()
        val online = AtomicBoolean(true)
        val presenceDenied = AtomicBoolean(false)
        val includeRegisteredHost = AtomicBoolean(false)
        val rejectNextHostHelloWithTokenExpired = AtomicBoolean(false)
        @Volatile var rejectHostRegistration = false
        @Volatile var rejectClientRegistration = false
        @Volatile var expirePeerToken = false
        @Volatile var rejectRefreshAsReused = false
        @Volatile var peerKey = ""
        val peerAuthorizations = CopyOnWriteArrayList<String>()
        val peerReplies = CopyOnWriteArrayList<Pair<String, String?>>()
        private val socketRoles = java.util.concurrent.ConcurrentHashMap<WebSocket, String>()
        private val pingsByRole = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        private val pingsBySocket = java.util.concurrent.ConcurrentHashMap<WebSocket, AtomicInteger>()
        private val pongsByRole = java.util.concurrent.ConcurrentHashMap<String, AtomicInteger>()
        private val protector = Protector()
        val vault: RemoteCredentialVault
        val origin: String
        val deviceName: String
        var runtime: RemoteAccountRuntime
            private set
        private var accountScope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
        init {
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/api/v1/auth/login" -> {
                        passwordLoginCalls.incrementAndGet()
                        json(obj("token" to passwordAccountToken, "account" to "owner", "expiresAt" to System.currentTimeMillis() + 600_000))
                    }
                    request.path == "/api/v1/auth/me" -> {
                        accountTokenRequests += request.getHeader("Authorization").orEmpty()
                        json(obj("account" to "owner"))
                    }
                    request.path == "/api/v1/auth/refresh" -> {
                        val body = com.openandroidintelligence.gateway.remote.protocol.RemoteWireJson.parse(request.body.readUtf8()).objectValue()
                        refreshRequests += body
                        if (rejectRefreshAsReused) json(obj("error" to obj("code" to "TOKEN_REUSED"))).setResponseCode(401)
                        else json(obj("accessToken" to refreshedHostAccessToken, "accessTokenExpiresAt" to System.currentTimeMillis() + 600_000,
                            "refreshToken" to "refreshed-host-refresh-token", "refreshTokenExpiresAt" to System.currentTimeMillis() + 1_200_000))
                    }
                    request.path == "/api/v1/devices/register" -> {
                        val device = com.openandroidintelligence.gateway.remote.protocol.RemoteWireJson.parse(request.body.readUtf8()).objectValue().value("device").objectValue()
                        val role = device.requiredText("role")
                        registrations += device
                        registrationAuthorizations += request.getHeader("Authorization").orEmpty()
                        if (role == "host" && rejectHostRegistration || role == "client" && rejectClientRegistration) {
                            rejectedRegistrationRoles += role
                            json(obj("error" to obj("code" to "DEVICE_LIMIT_REACHED"))).setResponseCode(409)
                        } else {
                            val accessToken = registrationAccessToken(role)
                            registrationTokens += role to accessToken
                            json(obj("accessToken" to accessToken, "accessTokenExpiresAt" to System.currentTimeMillis() + 600_000,
                                "refreshToken" to "refresh-$role-token-0001", "refreshTokenExpiresAt" to System.currentTimeMillis() + 1_200_000))
                        }
                    }
                    request.path?.startsWith("/api/v1/devices?") == true -> {
                        val items = if (includeRegisteredHost.get()) {
                            registrations.filter { it.requiredText("role") == "host" }.map { device ->
                                obj("deviceId" to device.requiredText("deviceId"), "role" to "host", "name" to device.requiredText("name"),
                                    "platform" to device.requiredText("platform"), "membershipId" to "member-own")
                            } + directoryExtras
                        } else {
                            // 固定 plugin/tests/server-api.test.ts 的真实 host(0) 目录形状，刻意不含 online。
                            listOf(obj("deviceId" to "host-0", "role" to "host", "name" to "Host 0",
                                "platform" to "linux", "membershipId" to "member-0"))
                        }
                        json(obj("items" to items.distinctBy { it.requiredText("deviceId") }, "nextCursor" to null))
                    }
                    request.path.orEmpty().startsWith("/api/v1/devices/") && request.path.orEmpty().endsWith("/presence") -> {
                        presenceCalls.incrementAndGet()
                        if (presenceDenied.get()) json(obj("error" to obj("code" to "MEMBERSHIP_REQUIRED"))).setResponseCode(403)
                        else json(obj("deviceId" to request.path.orEmpty().substringAfter("/api/v1/devices/").substringBefore("/presence"),
                            "online" to online.get()))
                    }
                    request.path == "/api/v1/devices/desktop-client" -> {
                        val token = requireNotNull(request.getHeader("Authorization"))
                        peerAuthorizations += token
                        if (expirePeerToken && token == "Bearer $hostAccessToken") {
                            json(obj("error" to obj("code" to "TOKEN_EXPIRED"))).setResponseCode(401)
                        } else json(obj("deviceId" to "desktop-client", "role" to "client", "name" to "电脑",
                            "platform" to "linux", "membershipId" to "membership-client", "identityKey" to peerKey))
                    }
                    request.path == "/ws/v1/connect" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onMessage(socket: WebSocket, text: String) {
                            val frame = RemoteControlCodec.decode(text.encodeToByteArray())
                            when (frame.type) {
                                "hello" -> {
                                    val role = frame.payload.requiredText("role")
                                    socketRoles[socket] = role
                                    sockets += socket
                                    helloFrames += Hello(socket, frame.payload)
                                    if (role == "host" && rejectNextHostHelloWithTokenExpired.compareAndSet(true, false)) {
                                        socket.send(Json.canonical(obj("v" to 1, "id" to "token-expired", "type" to "error", "timestamp" to 1,
                                            "payload" to obj("code" to "TOKEN_EXPIRED", "message" to "TOKEN_EXPIRED", "retryable" to false))))
                                        return
                                    }
                                    // 来自固定 server/gateway.ts 的 hello.ack 字段；短间隔只压缩反馈周期。
                                    socket.send(Json.canonical(obj("v" to 1, "id" to "ack", "type" to "hello.ack", "timestamp" to 1,
                                        "payload" to obj("protocol" to 1, "serverVersion" to "self-hosted/0.1.0", "connectionSessionId" to "$role-session-${sockets.size}",
                                            "heartbeatIntervalMs" to 50, "maxControlFrameBytes" to 65536, "maxRelayFrameBytes" to 1048576,
                                            "capabilities" to listOf("transport.relay")))))
                                    acknowledgements += role
                                    socket.send(Json.canonical(obj("v" to 1, "id" to "ping", "type" to "ping", "timestamp" to 1, "payload" to obj("nonce" to "server-heartbeat"))))
                                }
                                "ping" -> {
                                    socketRoles[socket]?.let { pingsByRole.computeIfAbsent(it) { AtomicInteger() }.incrementAndGet() }
                                    pingsBySocket.computeIfAbsent(socket) { AtomicInteger() }.incrementAndGet()
                                    socket.send(Json.canonical(obj("v" to 1, "id" to "pong", "type" to "pong", "timestamp" to 1, "payload" to frame.payload)))
                                }
                                "pong" -> {
                                    socketRoles[socket]?.let { pongsByRole.computeIfAbsent(it) { AtomicInteger() }.incrementAndGet() }
                                    pongs.incrementAndGet()
                                }
                                "connect.request" -> connectRequests.incrementAndGet()
                                "connect.accepted", "connect.rejected" -> peerReplies += frame.type to frame.payload.optionalText("code")
                            }
                        }
                        override fun onClosing(socket: WebSocket, code: Int, reason: String) { socket.close(code, reason) }
                    })
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            origin = server.url("/").toString().removeSuffix("/")
            deviceName = RemoteInstallationIdentityStore.defaultDeviceName(RuntimeEnvironment.getApplication())
            vault = RemoteCredentialVault(Files.createTempDirectory("remote-control-lifecycle").toFile(), protector)
            runtime = newRuntime()
        }
        private fun newRuntime() = RemoteAccountRuntime(RuntimeEnvironment.getApplication(), accountScope,
            hostHandler, vault = vault, protector = protector)
        suspend fun restartAccountProcess() {
            accountScope.coroutineContext.job.cancelAndJoin()
            accountScope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
            runtime = newRuntime()
            runtime.restore()
        }
        fun corrupt(role: RemoteRole) {
            val active = requireNotNull(runtime.account.value)
            vault.writeAtomic(RemoteIdentityScope(active.origin, active.accountId, role).storageKey, byteArrayOf(0))
        }
        fun incoming(id: String) {
            val host = hellosFor("host").single().socket
            check(host.send(Json.canonical(obj("v" to 1, "type" to "connect.incoming", "id" to id, "timestamp" to 1,
                "payload" to obj("connectionId" to id, "clientDeviceId" to "desktop-client", "clientIdentityKey" to peerKey,
                    "authorization" to "account", "preferredTransports" to listOf("relay"))))))
        }
        val accountJobs get() = accountScope.coroutineContext.job.children
        suspend fun login(): RemoteLoginResult = runtime.login(origin, "owner", "password")
        suspend fun loginWithAccountToken(token: String): RemoteLoginResult = runtime.loginAccountToken(origin, token, vault.beginLoginAttempt())
        fun registrationAccessToken(role: String): String = when (role) {
            "client" -> clientAccessToken
            "host" -> hostAccessToken
            else -> error("未知角色: $role")
        }
        fun hellosFor(role: String): List<Hello> = helloFrames.filter { it.role == role }
        fun registrationFor(role: String): JsonValue.JObject = registrations.single { it.requiredText("role") == role }
        fun pingCount(role: String): Int = pingsByRole[role]?.get() ?: 0
        fun pingCount(socket: WebSocket): Int = pingsBySocket[socket]?.get() ?: 0
        fun pongCount(role: String): Int = pongsByRole[role]?.get() ?: 0
        fun expireHostAccessToken() {
            val active = requireNotNull(runtime.account.value)
            val store = RemoteIdentityStore(vault, protector)
            val scope = RemoteIdentityScope(active.origin, active.accountId, RemoteRole.HOST)
            val current = requireNotNull(store.load(scope))
            val aged = current.copy(tokens = current.tokens.copy(accessTokenExpiresAt = System.currentTimeMillis() + 1_000))
            assertNotNull("HOST 凭据过期时间更新失败", store.rotateTokens(scope, current, aged))
        }
        override fun close() { runBlocking { runtime.logout() }; parent.cancel(); server.shutdown() }
        private fun json(value: JsonValue) = MockResponse().setHeader("Content-Type", "application/json").setBody(Json.canonical(value))

        companion object {
            const val passwordAccountToken = "password-account-token"
            const val clientAccessToken = "client-access-token-0001"
            const val hostAccessToken = "host-access-token-0001"
            const val refreshedHostAccessToken = "refreshed-host-access-token-0001"
        }
    }
    private suspend fun await(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(5) }
    private suspend fun await(description: String, detail: () -> String, condition: () -> Boolean) {
        try {
            await(condition)
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError("$description：${detail()}", timeout)
        }
    }

    @Test fun 自部署密码登录注册两角色并在目录过滤本机后保持Host心跳(): Unit = runBlocking {
        Fixture(EmptyHostHandler()).use { fixture ->
            fixture.includeRegisteredHost.set(true)
            fixture.directoryExtras += obj("deviceId" to "android-peer-host", "role" to "host", "name" to "外部 Android Host",
                "platform" to "android", "membershipId" to "member-android")
            fixture.directoryExtras += obj("deviceId" to "linux-peer-host", "role" to "host", "name" to "外部 Linux Host",
                "platform" to "linux", "membershipId" to "member-linux")

            val result = fixture.login()
            assertEquals(RemoteRoleRegistrationState.REGISTERED, result.client.state)
            assertEquals(RemoteRoleRegistrationState.REGISTERED, result.host.state)
            await {
                fixture.runtime.state.value == RemoteAccountConnectionState.CONNECTED &&
                    fixture.runtime.hostState.value == RemoteHostConnectionState.ONLINE &&
                    fixture.hellosFor("host").size == 1 && fixture.pongCount("host") > 0 &&
                    fixture.pingCount("client") > 0 && fixture.pingCount("host") > 0 &&
                    fixture.runtime.hosts.value.map { it.deviceId }.toSet() == setOf("android-peer-host", "linux-peer-host")
            }

            val clientRegistration = fixture.registrationFor("client")
            val hostRegistration = fixture.registrationFor("host")
            assertEquals(1, fixture.passwordLoginCalls.get())
            assertEquals(2, fixture.registrations.size)
            assertEquals(setOf("client", "host"), fixture.registrations.map { it.requiredText("role") }.toSet())
            assertEquals(setOf("Bearer ${Fixture.passwordAccountToken}"), fixture.registrationAuthorizations.toSet())
            assertNotEquals(clientRegistration.requiredText("deviceId"), hostRegistration.requiredText("deviceId"))
            fixture.registrations.forEach { device ->
                assertEquals("android", device.requiredText("platform"))
                assertEquals(fixture.deviceName, device.requiredText("name"))
                assertEquals(RemoteProtocol.REMOTE_COMPATIBILITY_VERSION, device.requiredText("clientVersion"))
            }
            assertFalse(clientRegistration.fields.any { it.first == "harnessVersion" })
            assertEquals(RemoteProtocol.HARNESS_COMPATIBILITY_VERSION, hostRegistration.requiredText("harnessVersion"))
            assertEquals(mapOf("client" to Fixture.clientAccessToken, "host" to Fixture.hostAccessToken), fixture.registrationTokens.toMap())

            val clientHello = fixture.hellosFor("client").single()
            val hostHello = fixture.hellosFor("host").single()
            assertEquals(clientRegistration.requiredText("deviceId"), clientHello.payload.requiredText("deviceId"))
            assertEquals(hostRegistration.requiredText("deviceId"), hostHello.payload.requiredText("deviceId"))
            assertEquals(Fixture.clientAccessToken, clientHello.payload.requiredText("accessToken"))
            assertEquals(Fixture.hostAccessToken, hostHello.payload.requiredText("accessToken"))
            assertEquals(RemoteProtocol.REMOTE_COMPATIBILITY_VERSION, clientHello.payload.requiredText("clientVersion"))
            assertEquals(RemoteProtocol.REMOTE_COMPATIBILITY_VERSION, hostHello.payload.requiredText("clientVersion"))
            assertEquals(RemoteProtocol.HARNESS_COMPATIBILITY_VERSION, hostHello.payload.requiredText("harnessVersion"))
            assertEquals(setOf("client", "host"), fixture.acknowledgements.toSet())
            assertTrue(fixture.pingCount("client") > 0)
            assertTrue(fixture.pingCount("host") > 0)
            assertNull(fixture.runtime.hostFailure.value)

            val visibleHosts = fixture.runtime.hosts.value.associateBy { it.deviceId }
            assertFalse(hostRegistration.requiredText("deviceId") in visibleHosts)
            assertEquals("android", visibleHosts.getValue("android-peer-host").platform)
            assertEquals("linux", visibleHosts.getValue("linux-peer-host").platform)
        }
    }

    @Test fun OAuth令牌登录后Host断线自行刷新HOST凭据重连且退出不再hello(): Unit = runBlocking {
        Fixture(EmptyHostHandler()).use { fixture ->
            val oauthToken = "oauth-account-token-0001"
            val result = fixture.loginWithAccountToken(oauthToken)
            assertEquals(RemoteRoleRegistrationState.REGISTERED, result.client.state)
            assertEquals(RemoteRoleRegistrationState.REGISTERED, result.host.state)
            await {
                fixture.runtime.state.value == RemoteAccountConnectionState.CONNECTED &&
                    fixture.runtime.hostState.value == RemoteHostConnectionState.ONLINE &&
                    fixture.hellosFor("client").size == 1 && fixture.hellosFor("host").size == 1
            }
            assertEquals(0, fixture.passwordLoginCalls.get())
            assertEquals(listOf("Bearer $oauthToken"), fixture.accountTokenRequests)
            assertEquals(setOf("Bearer $oauthToken"), fixture.registrationAuthorizations.toSet())
            assertEquals(Fixture.hostAccessToken, fixture.hellosFor("host").single().payload.requiredText("accessToken"))

            fixture.expireHostAccessToken()
            fixture.hellosFor("host").single().socket.close(1012, "服务重启")
            await {
                val hostHellos = fixture.hellosFor("host")
                hostHellos.size == 2 && fixture.pingCount(hostHellos.last().socket) > 0 && fixture.refreshRequests.size == 1 &&
                    fixture.runtime.hostState.value == RemoteHostConnectionState.ONLINE &&
                    fixture.runtime.state.value == RemoteAccountConnectionState.CONNECTED
            }
            val hostRegistration = fixture.registrationFor("host")
            assertEquals(hostRegistration.requiredText("deviceId"), fixture.refreshRequests.single().requiredText("deviceId"))
            assertEquals(Fixture.refreshedHostAccessToken, fixture.hellosFor("host")[1].payload.requiredText("accessToken"))
            assertEquals(1, fixture.hellosFor("client").size)
            assertEquals(listOf("host", "host"), fixture.acknowledgements.filter { it == "host" })
            assertTrue(fixture.pingCount(fixture.hellosFor("host")[1].socket) > 0)
            assertNull(fixture.runtime.hostFailure.value)

            val helloCountBeforeLogout = fixture.hellosFor("host").size
            fixture.runtime.logout()
            await { fixture.accountJobs.none() }
            assertEquals(RemoteAccountConnectionState.SIGNED_OUT, fixture.runtime.state.value)
            assertEquals(RemoteHostConnectionState.STOPPED, fixture.runtime.hostState.value)
            assertEquals(helloCountBeforeLogout, fixture.hellosFor("host").size)
            assertEquals(RemoteHostConnectionState.STOPPED, fixture.runtime.hostState.value)
        }
    }

    @Test fun Host收到DEVICE_REVOKED终态后不重连且前台刷新不能复活(): Unit = runBlocking {
        Fixture(EmptyHostHandler()).use { fixture ->
            fixture.login()
            await { fixture.hellosFor("host").size == 1 && fixture.runtime.hostState.value == RemoteHostConnectionState.ONLINE }

            val serverReason = "server-internal-revocation-detail"
            fixture.hellosFor("host").single().socket.close(4004, serverReason)
            await("DEVICE_REVOKED 未进入终态", {
                "hostState=${fixture.runtime.hostState.value}, hostFailure=${fixture.runtime.hostFailure.value}, " +
                    "hostHelloCount=${fixture.hellosFor("host").size}"
            }) {
                fixture.runtime.hostState.value == RemoteHostConnectionState.FAILED &&
                    fixture.runtime.hostFailure.value?.stage == RemoteHostFailureStage.CONTROL &&
                    fixture.runtime.hostFailure.value?.code == "DEVICE_REVOKED"
            }
            val failure = requireNotNull(fixture.runtime.hostFailure.value)
            assertEquals("DEVICE_REVOKED", failure.code)
            assertFalse(failure.code.contains(serverReason))

            val helloCount = fixture.hellosFor("host").size
            fixture.runtime.refreshControlConnection()
            delay(1_200)
            assertEquals(helloCount, fixture.hellosFor("host").size)
            assertEquals(RemoteHostConnectionState.FAILED, fixture.runtime.hostState.value)
            assertEquals("DEVICE_REVOKED", fixture.runtime.hostFailure.value?.code)
        }
    }

    @Test fun Host被其他连接替代后进程重建仍停止直到显式登录(): Unit = runBlocking {
        Fixture(EmptyHostHandler()).use { f ->
            f.login()
            await { f.runtime.hostState.value == RemoteHostConnectionState.ONLINE }
            f.hellosFor("host").single().socket.close(4003, "replaced")
            await { f.runtime.hostFailure.value?.code == "CONNECTION_REPLACED" }
            f.restartAccountProcess()
            await { f.runtime.state.value == RemoteAccountConnectionState.CONNECTED }
            assertEquals("CONNECTION_REPLACED", f.runtime.hostFailure.value?.code)
            assertEquals(1, f.hellosFor("host").size)
            f.login()
            await { f.runtime.hostState.value == RemoteHostConnectionState.ONLINE && f.hellosFor("host").size == 2 }
        }
    }

    @Test fun 刷新票被判定重用后重启仍不提交旧票(): Unit = runBlocking {
        Fixture(EmptyHostHandler()).use { f ->
            f.rejectNextHostHelloWithTokenExpired.set(true)
            f.rejectRefreshAsReused = true
            f.login()
            await { f.runtime.hostFailure.value?.code == "TOKEN_REUSED" }
            f.restartAccountProcess()
            assertEquals(RemoteHostFailureStage.CREDENTIAL, f.runtime.hostFailure.value?.stage)
            assertEquals(1, f.refreshRequests.size)
            assertEquals(1, f.hellosFor("host").size)
        }
    }

    @Test fun 仅Host注册成功时前后台及进程恢复均不使用Client目录(): Unit = runBlocking {
        Fixture(EmptyHostHandler()).use { f ->
            f.rejectClientRegistration = true
            assertEquals(RemoteRoleRegistrationState.REGISTERED, f.login().host.state)
            repeat(2) { round ->
                if (round == 1) f.restartAccountProcess()
                await { f.runtime.hostState.value == RemoteHostConnectionState.ONLINE }
                assertNotNull(f.runtime.account.value)
                assertEquals(RemoteAccountConnectionState.DISCONNECTED, f.runtime.state.value)
                assertNotNull(f.runtime.clientFailure.value)
                f.runtime.refreshControlConnection()
                assertTrue(f.runtime.listHosts().isEmpty())
                assertEquals(0, f.presenceCalls.get())
                assertTrue(f.hellosFor("client").isEmpty())
            }
        }
    }

    @Test fun 单角色密文损坏时仍恢复另一侧的已登记身份(): Unit = runBlocking {
        for (bad in RemoteRole.entries) Fixture(EmptyHostHandler()).use { f ->
            f.login()
            await { f.runtime.hostState.value == RemoteHostConnectionState.ONLINE && f.runtime.state.value == RemoteAccountConnectionState.CONNECTED }
            f.corrupt(bad)
            f.restartAccountProcess()
            if (bad == RemoteRole.CLIENT) {
                await { f.runtime.hostState.value == RemoteHostConnectionState.ONLINE }
                assertEquals("REMOTE_IDENTITY_CORRUPTED", f.runtime.clientFailure.value)
            } else {
                await { f.runtime.state.value == RemoteAccountConnectionState.CONNECTED }
                assertTrue(f.runtime.listHosts().isNotEmpty())
                assertEquals("REMOTE_IDENTITY_CORRUPTED", f.runtime.hostFailure.value?.code)
            }
        }
    }

    @Test fun 在线Host核验来访设备时按被拒旧票续期且公钥变更单独拒绝(): Unit = runBlocking {
        Fixture(EmptyHostHandler()).use { f ->
            f.login()
            await { f.runtime.hostState.value == RemoteHostConnectionState.ONLINE }
            f.peerKey = f.registrationFor("client").requiredText("identityKey")
            f.expirePeerToken = true
            f.incoming("connection-1")
            await { f.peerReplies.isNotEmpty() }
            assertEquals("connect.accepted" to null, f.peerReplies.single())
            assertEquals(listOf("Bearer ${Fixture.hostAccessToken}", "Bearer ${Fixture.refreshedHostAccessToken}"), f.peerAuthorizations)
            f.peerKey = f.registrationFor("host").requiredText("identityKey")
            f.incoming("connection-2")
            await { f.peerReplies.size == 2 }
            assertEquals("connect.rejected" to "PEER_IDENTITY_MISMATCH", f.peerReplies.last())
            assertEquals(1, f.refreshRequests.size)
            assertEquals(1, f.hellosFor("host").size)
        }
    }

    @Test fun 本地未过期但Host首包被TOKEN_EXPIRED拒绝后刷新HOST票再上线(): Unit = runBlocking {
        Fixture(EmptyHostHandler()).use { fixture ->
            fixture.rejectNextHostHelloWithTokenExpired.set(true)
            val result = fixture.login()
            assertEquals(RemoteRoleRegistrationState.REGISTERED, result.host.state)
            await("TOKEN_EXPIRED 后未完成刷新重连", {
                val hostHellos = fixture.hellosFor("host")
                "hostState=${fixture.runtime.hostState.value}, hostFailure=${fixture.runtime.hostFailure.value}, " +
                    "hostHelloCount=${hostHellos.size}, " +
                    "refreshCount=${fixture.refreshRequests.size}, hostPongs=${fixture.pongCount("host")}"
            }) {
                val hostHellos = fixture.hellosFor("host")
                hostHellos.size == 2 && fixture.refreshRequests.size == 1 &&
                    fixture.pingCount(hostHellos.last().socket) > 0 &&
                    fixture.runtime.hostState.value == RemoteHostConnectionState.ONLINE
            }

            val hostHellos = fixture.hellosFor("host")
            assertEquals(Fixture.hostAccessToken, hostHellos[0].payload.requiredText("accessToken"))
            assertEquals(Fixture.refreshedHostAccessToken, hostHellos[1].payload.requiredText("accessToken"))
            assertEquals(fixture.registrationFor("host").requiredText("deviceId"), fixture.refreshRequests.single().requiredText("deviceId"))
            assertEquals(listOf("host"), fixture.acknowledgements.filter { it == "host" })
            assertNull(fixture.runtime.hostFailure.value)
        }
    }

    @Test fun Host注册失败发布REGISTRATION阶段错误且不建立Host控制连接(): Unit = runBlocking {
        Fixture(EmptyHostHandler()).use { fixture ->
            fixture.rejectHostRegistration = true
            val result = fixture.login()
            assertEquals(RemoteRoleRegistrationState.REGISTERED, result.client.state)
            assertEquals(RemoteRoleRegistrationState.FAILED, result.host.state)
            assertEquals("REMOTE_ROLE_REGISTRATION_FAILED", result.host.errorCode)
            await { fixture.runtime.hostState.value == RemoteHostConnectionState.FAILED && fixture.runtime.hostFailure.value != null }
            val failure = requireNotNull(fixture.runtime.hostFailure.value)
            assertEquals(RemoteHostFailureStage.REGISTRATION, failure.stage)
            assertEquals("REMOTE_ROLE_REGISTRATION_FAILED", failure.code)
            assertEquals(listOf("host"), fixture.rejectedRegistrationRoles)
            await { fixture.runtime.state.value == RemoteAccountConnectionState.CONNECTED && fixture.hellosFor("client").size == 1 }
            assertTrue(fixture.hellosFor("host").isEmpty())
        }
    }

    @Test fun 登录不选Host也立即注册Client并维持服务器心跳和在线刷新(): Unit = runBlocking {
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
            await { fixture.accountJobs.none() }
            assertEquals(2, fixture.sockets.size)
        }
    }

    @Test fun 真实账号退出再登录复用安装的两角色身份但建立新的控制连接(): Unit = runBlocking {
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
