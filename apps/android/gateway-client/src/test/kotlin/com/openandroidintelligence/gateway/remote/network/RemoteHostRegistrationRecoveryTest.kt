package com.openandroidintelligence.gateway.remote.network

import com.openandroidintelligence.gateway.remote.auth.RemoteIdentityProtector
import com.openandroidintelligence.gateway.remote.auth.RemoteIdentityStore
import com.openandroidintelligence.gateway.remote.auth.RemotePairingImport
import com.openandroidintelligence.gateway.remote.auth.RemoteRoleRegistrationState
import com.openandroidintelligence.gateway.remote.authproduction.RemoteActiveAccount
import com.openandroidintelligence.gateway.remote.authproduction.RemoteCredentialVault
import com.openandroidintelligence.gateway.remote.identity.*
import com.openandroidintelligence.gateway.remote.protocol.RemoteBase64Url
import com.openandroidintelligence.gateway.remote.protocol.RemoteControlCodec
import com.openandroidintelligence.gateway.remote.protocol.RemoteWireJson
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcConnectionScope
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcEvent
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcHostHandler
import com.openandroidintelligence.gateway.remote.runtime.*
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class RemoteHostRegistrationRecoveryTest {
    private class Protector : RemoteIdentityProtector {
        private val keys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        override fun hasKey(scopeKey: String) = scopeKey in keys
        override fun seal(scopeKey: String, plaintext: ByteArray, associatedData: ByteArray) =
            plaintext.copyOf().also { keys += scopeKey }
        override fun open(scopeKey: String, ciphertext: ByteArray, associatedData: ByteArray) = ciphertext.copyOf()
    }

    private class EmptyHostHandler : RemoteRpcHostHandler {
        override fun events(scope: RemoteRpcConnectionScope) = emptyFlow<RemoteRpcEvent>()
        override suspend fun invoke(scope: RemoteRpcConnectionScope, method: String, params: JsonValue.JObject) = obj()
    }

    private class Fixture : AutoCloseable {
        val server = MockWebServer()
        private val parent = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private var runtimeScope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
        private val protector = Protector()
        val vault = RemoteCredentialVault(Files.createTempDirectory("remote-host-registration-recovery").toFile(), protector)
        val store = RemoteIdentityStore(vault, protector)
        val ownedRequests = CopyOnWriteArrayList<String>()
        val ownedAuthorizations = CopyOnWriteArrayList<String>()
        val websockets = CopyOnWriteArrayList<WebSocket>()
        val helloRoles = CopyOnWriteArrayList<String>()
        val registrationFailures = AtomicInteger()
        val inFlightOwnedRequests = AtomicInteger()
        val maxInFlightOwnedRequests = AtomicInteger()
        @Volatile var rejectOwnedRole = true
        @Volatile var ownedRoleErrorCode: String = "CONNECTION_FAILED"
        @Volatile var ownedRoleErrorStatus: Int = 400
        @Volatile var rejectHostRegistration = false
        @Volatile var ownedRoleEntered: CountDownLatch? = null
        @Volatile var ownedRoleRelease: CountDownLatch? = null
        val clientIdentity = RemoteStaticIdentityFactory.generate(RemoteDeviceId("00000000-0000-4000-8000-000000000011"))
        val ownedHostIdentity = RemoteStaticIdentityFactory.generate(RemoteDeviceId("00000000-0000-4000-8000-000000000012"))
        val clientTokens = RemoteTokenPair("client-access-token-0001", 4_000_000_000_000, "client-refresh-token-0001", 4_000_000_000_000)
        val hostTokens = RemoteTokenPair("owned-host-access-0001", 4_000_000_000_000, "owned-host-refresh-0001", 4_000_000_000_000)
        lateinit var origin: String
        lateinit var active: RemoteActiveAccount
        lateinit var runtime: RemoteAccountRuntime

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/api/v1/devices/register-owned-role" -> handleOwnedRole(request)
                    "/api/v1/devices" -> response(obj("items" to emptyList<JsonValue>(), "nextCursor" to null))
                    "/api/v1/me" -> response(obj("deviceId" to clientIdentity.deviceId.value, "name" to "测试手机", "platform" to "android",
                        "role" to "client", "membershipId" to "member", "identityKey" to RemoteBase64Url.encode(clientIdentity.publicKey)))
                    "/api/v1/auth/login" -> response(obj("token" to "l".repeat(32), "account" to "owner",
                        "expiresAt" to 4_000_000_000_000))
                    "/api/v1/devices/register" -> {
                        val device = RemoteWireJson.parse(request.body.readUtf8()).objectValue().value("device").objectValue()
                        if (device.requiredText("role") == "host" && rejectHostRegistration) {
                            response(obj("error" to obj("code" to "CONNECTION_FAILED")), 400)
                        } else response(obj("accessToken" to "registered-access-0001",
                            "accessTokenExpiresAt" to 4_000_000_000_000, "refreshToken" to "registered-refresh-0001",
                            "refreshTokenExpiresAt" to 4_000_000_000_000))
                    }
                    "/ws/v1/connect" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onMessage(socket: WebSocket, text: String) {
                            val frame = RemoteControlCodec.decode(text.encodeToByteArray())
                            if (frame.type != "hello") return
                            val role = frame.payload.requiredText("role")
                            websockets += socket
                            helloRoles += role
                            socket.send(Json.canonical(obj("v" to 1, "id" to "ack", "type" to "hello.ack", "timestamp" to 1,
                                "payload" to obj("protocol" to 1, "serverVersion" to "fixture", "connectionSessionId" to "session-$role",
                                    "heartbeatIntervalMs" to 25_000, "maxControlFrameBytes" to 65_536, "maxRelayFrameBytes" to 1_048_576,
                                    "capabilities" to listOf("transport.relay")))))
                        }
                        override fun onClosing(socket: WebSocket, code: Int, reason: String) { socket.close(code, reason) }
                    })
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            origin = server.url("/").toString().removeSuffix("/")
            store.save(RemoteIdentityScope(origin, "owner", RemoteRole.CLIENT), RemoteRoleCredential(clientIdentity, clientTokens))
            vault.installationIdentityStore { "测试手机" }.loadOrCreate(clientIdentity, ownedHostIdentity)
            active = vault.activate(origin, "owner", vault.revokeActive(), hostRegistrationError = "CONNECTION_FAILED")
            runtime = newRuntime()
        }

        private fun handleOwnedRole(request: RecordedRequest): MockResponse {
            val activeCount = inFlightOwnedRequests.incrementAndGet()
            maxInFlightOwnedRequests.accumulateAndGet(activeCount, ::maxOf)
            try {
                ownedRequests += request.body.readUtf8()
                ownedAuthorizations += request.getHeader("Authorization").orEmpty()
                ownedRoleEntered?.countDown()
                ownedRoleRelease?.let { latch ->
                    check(latch.await(5, TimeUnit.SECONDS)) { "等待测试放行 register-owned-role 超时" }
                }
                if (rejectOwnedRole) {
                    registrationFailures.incrementAndGet()
                    return response(obj("error" to obj("code" to ownedRoleErrorCode)), ownedRoleErrorStatus)
                }
                return response(obj("accessToken" to hostTokens.accessToken, "accessTokenExpiresAt" to hostTokens.accessTokenExpiresAt,
                    "refreshToken" to hostTokens.refreshToken, "refreshTokenExpiresAt" to hostTokens.refreshTokenExpiresAt))
            } finally {
                inFlightOwnedRequests.decrementAndGet()
            }
        }

        fun response(value: JsonValue, status: Int = 200): MockResponse =
            MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(Json.canonical(value))

        fun newRuntime(apiFactory: ((String) -> RemoteServerApi)? = null) = RemoteAccountRuntime(
            RuntimeEnvironment.getApplication(), runtimeScope, EmptyHostHandler(), vault, protector, apiFactory)

        suspend fun restart(restore: Boolean = true, apiFactory: ((String) -> RemoteServerApi)? = null) {
            runtimeScope.coroutineContext.job.cancelAndJoin()
            runtimeScope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))
            runtime = newRuntime(apiFactory)
            if (restore) runtime.restore()
        }

        fun hostCredential(targetOrigin: String = origin, accountId: String = "owner"): RemoteRoleCredential? =
            store.load(RemoteIdentityScope(targetOrigin, accountId, RemoteRole.HOST))

        override fun close() {
            ownedRoleRelease?.countDown()
            parent.cancel()
            websockets.forEach { it.close(1000, null) }
            server.shutdown()
        }
    }

    @Test fun 同代次前台恢复用Client票据注册ownedHost且保存前不启动Host() = runBlocking {
        Fixture().use { fixture ->
            assertEquals(fixture.active, fixture.runtime.restore())
            await { fixture.registrationFailures.get() >= 1 }
            assertEquals(RemoteHostConnectionState.FAILED, fixture.runtime.hostState.value)
            assertEquals("CONNECTION_FAILED", fixture.runtime.hostFailure.value?.code)
            assertFalse("host" in fixture.helloRoles)
            assertNull(fixture.hostCredential())

            fixture.rejectOwnedRole = false
            fixture.runtime.refreshControlConnection()

            await { fixture.hostCredential() != null && "host" in fixture.helloRoles }
            val request = RemoteWireJson.parse(fixture.ownedRequests.last()).objectValue()
            val device = request.value("device").objectValue()
            assertEquals("Bearer ${fixture.clientTokens.accessToken}", fixture.ownedAuthorizations.last())
            assertEquals(fixture.ownedHostIdentity.deviceId.value, device.requiredText("deviceId"))
            assertEquals("host", device.requiredText("role"))
            assertTrue(fixture.ownedHostIdentity.sameIdentity(fixture.hostCredential()!!.identity))
            assertEquals(fixture.hostTokens, fixture.hostCredential()!!.tokens)
            assertNull(fixture.vault.roleFailures(fixture.active).hostRegistration)
            assertEquals(1, fixture.helloRoles.count { it == "host" })
        }
    }

    @Test fun 重复唤醒只保留一个有界重试job且退避后成功只提交一次() = runBlocking {
        Fixture().use { fixture ->
            val firstEntered = CountDownLatch(1)
            val firstRelease = CountDownLatch(1)
            fixture.ownedRoleEntered = firstEntered
            fixture.ownedRoleRelease = firstRelease

            fixture.runtime.restore()
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            // 首次补注册飞行期间并发多次唤醒，不能叠加新的重试协程。
            repeat(8) { fixture.runtime.refreshControlConnection() }
            assertEquals(1, fixture.maxInFlightOwnedRequests.get())
            assertNull(fixture.hostCredential())
            assertFalse("host" in fixture.helloRoles)

            fixture.ownedRoleEntered = null
            fixture.ownedRoleRelease = null
            fixture.rejectOwnedRole = false
            firstRelease.countDown()
            fixture.runtime.refreshControlConnection()
            repeat(4) { fixture.runtime.refreshControlConnection() }

            await { fixture.hostCredential() != null && "host" in fixture.helloRoles }
            assertEquals(1, fixture.maxInFlightOwnedRequests.get())
            assertEquals(1, fixture.helloRoles.count { it == "host" })
            assertTrue("有界重试请求次数应受控，实际=${fixture.ownedRequests.size}", fixture.ownedRequests.size <= 3)
            assertEquals(fixture.hostTokens, fixture.hostCredential()!!.tokens)
            assertNull(fixture.vault.roleFailures(fixture.active).hostRegistration)
        }
    }

    @Test fun 重启后前台恢复仍以同一epoch补注册并重新启动Host() = runBlocking {
        Fixture().use { fixture ->
            fixture.runtime.restore()
            await { fixture.registrationFailures.get() >= 1 }
            assertNull(fixture.hostCredential())

            fixture.restart(restore = false)
            val beforeRestart = fixture.ownedRequests.size
            fixture.rejectOwnedRole = false
            assertEquals(fixture.active, fixture.runtime.restore())
            await { fixture.hostCredential() != null && "host" in fixture.helloRoles }
            assertEquals(beforeRestart + 1, fixture.ownedRequests.size)
            assertEquals(fixture.hostTokens, fixture.hostCredential()!!.tokens)
            assertEquals(1, fixture.helloRoles.count { it == "host" })
        }
    }

    @Test fun 注销会取消恢复并挡住网络迟到的Host角色结果() = runBlocking {
        Fixture().use { fixture ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            fixture.rejectOwnedRole = false
            fixture.ownedRoleEntered = entered
            fixture.ownedRoleRelease = release

            fixture.runtime.restore()
            assertTrue(entered.await(5, TimeUnit.SECONDS))

            fixture.runtime.logout()
            await { fixture.runtime.state.value == RemoteAccountConnectionState.SIGNED_OUT }
            release.countDown()
            delay(300)

            assertNull(fixture.hostCredential())
            assertNull(fixture.vault.activeAccount())
            assertFalse("host" in fixture.helloRoles)
        }
    }

    @Test fun 换服重登后旧服务器迟到的Host角色结果不能提交或污染新账号() = runBlocking {
        Fixture().use { fixture ->
            val otherServer = MockWebServer()
            otherServer.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/api/v1/auth/login" -> fixture.response(obj("token" to "n".repeat(32), "account" to "other-owner",
                        "expiresAt" to 4_000_000_000_000))
                    "/api/v1/devices/register" -> fixture.response(obj("accessToken" to "other-access-0001",
                        "accessTokenExpiresAt" to 4_000_000_000_000, "refreshToken" to "other-refresh-0001",
                        "refreshTokenExpiresAt" to 4_000_000_000_000))
                    "/api/v1/devices" -> fixture.response(obj("items" to emptyList<JsonValue>(), "nextCursor" to null))
                    "/ws/v1/connect" -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onMessage(socket: WebSocket, text: String) {
                            val frame = RemoteControlCodec.decode(text.encodeToByteArray())
                            if (frame.type != "hello") return
                            socket.send(Json.canonical(obj("v" to 1, "id" to "ack", "type" to "hello.ack", "timestamp" to 1,
                                "payload" to obj("protocol" to 1, "serverVersion" to "fixture", "connectionSessionId" to "other-session",
                                    "heartbeatIntervalMs" to 25_000, "maxControlFrameBytes" to 65_536, "maxRelayFrameBytes" to 1_048_576,
                                    "capabilities" to listOf("transport.relay")))))
                        }
                    })
                    else -> MockResponse().setResponseCode(404)
                }
            }
            otherServer.start()
            try {
                val otherOrigin = otherServer.url("/").toString().removeSuffix("/")
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                fixture.rejectOwnedRole = false
                fixture.ownedRoleEntered = entered
                fixture.ownedRoleRelease = release

                fixture.runtime.restore()
                assertTrue(entered.await(5, TimeUnit.SECONDS))

                val login = fixture.runtime.login(otherOrigin, "other-owner", "password")
                assertEquals(RemoteRoleRegistrationState.REGISTERED, login.host.state)
                release.countDown()
                delay(300)

                val current = fixture.vault.activeAccount()!!
                assertEquals(otherOrigin, current.origin)
                assertEquals("other-owner", current.accountId)
                assertNull(fixture.hostCredential(fixture.origin, "owner"))
                assertEquals("other-access-0001", fixture.hostCredential(otherOrigin, "other-owner")!!.tokens.accessToken)
            } finally {
                otherServer.shutdown()
            }
        }
    }

    @Test fun 撤销旧epoch撤销最终代次后网络迟到结果也不能提交() = runBlocking {
        Fixture().use { fixture ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            fixture.rejectOwnedRole = false
            fixture.ownedRoleEntered = entered
            fixture.ownedRoleRelease = release

            fixture.runtime.restore()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val stale = fixture.active

            val nextEpoch = fixture.vault.revokeActive()
            fixture.vault.activate(fixture.origin, "owner", nextEpoch)
            release.countDown()
            delay(300)
            fixture.runtime.refreshControlConnection()
            delay(200)

            assertNull(fixture.hostCredential())
            assertThrows(IllegalStateException::class.java) { fixture.vault.roleFailures(stale) }
            val next = fixture.vault.activeAccount()!!
            assertNull(fixture.vault.roleFailures(next).hostRegistration)
        }
    }

    @Test fun 后台待命没有Host时只等待前台且绝不发起ownedHost注册() = runBlocking {
        Fixture().use { fixture ->
            fixture.rejectOwnedRole = false
            assertNull(fixture.runtime.restoreForStandby())
            fixture.runtime.refreshControlConnection()
            delay(200)
            assertTrue(fixture.ownedRequests.isEmpty())
            assertNull(fixture.hostCredential())
            assertTrue(fixture.helloRoles.isEmpty())

            // 真正进入前台恢复后才允许发起 ownedHost 补注册。
            assertEquals(fixture.active, fixture.runtime.restore())
            await { fixture.hostCredential() != null && "host" in fixture.helloRoles }
            assertEquals(1, fixture.ownedRequests.size)
            assertEquals(1, fixture.helloRoles.count { it == "host" })
        }
    }

    @Test fun 扫码配对仅Client身份重启与网络唤醒绝不补注册Host() = runBlocking {
        Fixture().use { fixture ->
            fixture.rejectOwnedRole = false
            val pairedClient = RemoteStaticIdentityFactory.generate(RemoteDeviceId("00000000-0000-4000-8000-000000000021"))
            val pairedPeer = RemoteStaticIdentityFactory.generate(RemoteDeviceId("00000000-0000-4000-8000-000000000022"))
            val pairedTokens = RemoteTokenPair("paired-access-token-01", 4_000_000_000_000, "paired-refresh-token-01", 4_000_000_000_000)
            val pairingRuntime = fixture.newRuntime { RemoteServerApi(fixture.origin) }
            fixture.runtime.logout()
            val import = RemotePairingImport(
                sessionId = "pair-session",
                accountId = "dsh-device:paired",
                credential = RemoteRoleCredential(pairedClient, pairedTokens,
                    listOf(RemotePeerPin(RemoteRole.HOST, pairedPeer.deviceId, pairedPeer.publicKey))),
                epoch = fixture.vault.authorizationEpoch(),
                loginAttemptId = fixture.vault.beginLoginAttempt(),
            )
            pairingRuntime.importPairing(import)
            assertEquals(RemoteHostConnectionState.STOPPED, pairingRuntime.hostState.value)
            assertNull(pairingRuntime.hostFailure.value)

            repeat(3) { pairingRuntime.refreshControlConnection() }
            delay(200)
            assertTrue(fixture.ownedRequests.isEmpty())
            assertFalse("host" in fixture.helloRoles)

            fixture.restart(restore = false) { RemoteServerApi(fixture.origin) }
            assertNotNull(fixture.runtime.restore())
            repeat(3) { fixture.runtime.refreshControlConnection() }
            delay(200)
            assertTrue(fixture.ownedRequests.isEmpty())
            assertNull(fixture.hostCredential(RemoteServerApi.DEFAULT_ORIGIN, "dsh-device:paired"))
            assertFalse("host" in fixture.helloRoles)
        }
    }

    @Test fun 登录单侧Host暂态失败后前台自动补注册且不重复启动Host() = runBlocking {
        Fixture().use { fixture ->
            fixture.rejectHostRegistration = true
            fixture.rejectOwnedRole = false
            val login = fixture.runtime.login(fixture.origin, "owner", "password")
            assertEquals(RemoteRoleRegistrationState.REGISTERED, login.client.state)
            assertEquals(RemoteRoleRegistrationState.FAILED, login.host.state)
            assertEquals("CONNECTION_FAILED", login.host.errorCode)
            await { fixture.hostCredential() != null && fixture.runtime.hostState.value == RemoteHostConnectionState.ONLINE }
            assertEquals(1, fixture.helloRoles.count { it == "host" })
            assertEquals(1, fixture.ownedRequests.size)
            assertNull(fixture.vault.roleFailures(fixture.vault.activeAccount()!!).hostRegistration)
        }
    }

    @Test fun 旧Host记录遇到注册暂态标记时直接持久终止且后续网络不再重试() = runBlocking {
        Fixture().use { fixture ->
            val oldPinPeer = RemoteStaticIdentityFactory.generate(RemoteDeviceId("00000000-0000-4000-8000-000000000013"))
            val oldHostTokens = RemoteTokenPair("legacy-host-access-0001", 4_000_000_000_000,
                "legacy-host-refresh-0001", 4_000_000_000_000)
            val oldPin = RemotePeerPin(RemoteRole.CLIENT, oldPinPeer.deviceId, oldPinPeer.publicKey)
            fixture.store.save(RemoteIdentityScope(fixture.origin, "owner", RemoteRole.HOST),
                RemoteRoleCredential(fixture.ownedHostIdentity, oldHostTokens, listOf(oldPin)))

            assertEquals(fixture.active, fixture.runtime.restore())
            val failure = requireNotNull(fixture.runtime.hostFailure.value)
            assertEquals(RemoteHostFailureStage.REGISTRATION, failure.stage)
            assertEquals("REMOTE_HOST_REGISTRATION_CONFLICT", failure.code)
            assertFalse("host" in fixture.helloRoles)
            assertEquals("REMOTE_HOST_REGISTRATION_CONFLICT",
                fixture.vault.roleFailures(fixture.active).hostStop?.code)
            assertTrue("旧 Host 凭据与 pin 必须保留", fixture.hostCredential()!!.peers.single().matches(oldPinPeer.publicKey))

            fixture.runtime.refreshControlConnection()
            delay(250)
            assertTrue(fixture.ownedRequests.isEmpty())
            assertFalse("host" in fixture.helloRoles)

            // 重启后仍维持持久终态错误码，不降级也不误发起补注册。
            fixture.restart(restore = false)
            fixture.rejectOwnedRole = false
            assertEquals(fixture.active, fixture.runtime.restore())
            assertEquals("REMOTE_HOST_REGISTRATION_CONFLICT", fixture.runtime.hostFailure.value?.code)
            fixture.runtime.refreshControlConnection()
            delay(200)
            assertTrue(fixture.ownedRequests.isEmpty())
            assertFalse("host" in fixture.helloRoles)
        }
    }

    @Test fun registerOwnedHost返回未收录4xx码时仅持久停止Host且重启后Client继续可用() = runBlocking {
        Fixture().use { fixture ->
            fixture.ownedRoleErrorStatus = 404
            fixture.ownedRoleErrorCode = "METHOD_NOT_FOUND"

            assertEquals(fixture.active, fixture.runtime.restore())
            await { fixture.runtime.hostFailure.value?.code == "CONTROL_CONNECTION_FAILED" }
            val inProcessFailures = fixture.vault.roleFailures(fixture.active)
            assertEquals(RemoteHostFailure(RemoteHostFailureStage.REGISTRATION, "CONTROL_CONNECTION_FAILED"), inProcessFailures.hostStop)
            assertNull("Host 遇到未收录 4xx 码时绝不能连带停用 Client", inProcessFailures.clientStop)
            assertNull(fixture.runtime.clientFailure.value)
            assertTrue(fixture.runtime.listHosts().isEmpty())
            assertFalse("host" in fixture.helloRoles)

            val callsBeforeRestart = fixture.ownedRequests.size
            fixture.restart(restore = false)
            fixture.rejectOwnedRole = false
            assertEquals(fixture.active, fixture.runtime.restore())
            val afterRestartFailures = fixture.vault.roleFailures(fixture.active)
            assertEquals(RemoteHostFailure(RemoteHostFailureStage.REGISTRATION, "CONTROL_CONNECTION_FAILED"), afterRestartFailures.hostStop)
            assertNull("重启后角色围栏读取不得误判损坏并停用 Client", afterRestartFailures.clientStop)
            assertNull(fixture.runtime.clientFailure.value)
            assertEquals("CONTROL_CONNECTION_FAILED", fixture.runtime.hostFailure.value?.code)
            assertTrue(fixture.runtime.listHosts().isEmpty())

            fixture.runtime.refreshControlConnection()
            delay(200)
            assertEquals(callsBeforeRestart, fixture.ownedRequests.size)
            assertFalse("host" in fixture.helloRoles)
        }
    }

    private suspend fun await(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(10)
        }
    }
}
