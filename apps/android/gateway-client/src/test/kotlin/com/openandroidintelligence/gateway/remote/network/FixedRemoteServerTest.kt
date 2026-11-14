package com.openandroidintelligence.gateway.remote.network

import com.openandroidintelligence.gateway.remote.auth.RemoteIdentityProtector
import com.openandroidintelligence.gateway.remote.authproduction.RemoteCredentialVault
import com.openandroidintelligence.gateway.remote.identity.*
import com.openandroidintelligence.gateway.remote.protocol.*
import com.openandroidintelligence.gateway.remote.crypto.*
import com.openandroidintelligence.gateway.remote.rpc.*
import com.openandroidintelligence.gateway.remote.runtime.*
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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
class FixedRemoteServerTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private class Server : AutoCloseable {
        private val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }.first { File(it, "tools/remote-server/fixed-server.mjs").isFile }
        private val node = System.getenv("OAI_NODE") ?: "node"
        private val process = ProcessBuilder(node, "--experimental-vm-modules", "tools/remote-server/fixed-server.mjs").directory(root).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        private val output = process.inputStream.bufferedReader().readLine().let { RemoteWireJson.parse(it).objectValue() }
        val api = RemoteServerApi(output.requiredText("origin"))
        suspend fun login() = api.login(output.requiredText("account"), output.requiredText("password"))
        override fun close() { process.outputStream.close(); if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroy() }
    }
    private fun handler() = object : RemoteRpcHostHandler {
        override suspend fun invoke(scope: RemoteRpcConnectionScope, method: String, params: JsonValue.JObject) = obj("capabilities" to listOf("harness.remote.v3"))
        override fun events(scope: RemoteRpcConnectionScope) = emptyFlow<RemoteRpcEvent>()
    }
    private class PlainProtector : RemoteIdentityProtector {
        override fun hasKey(scopeKey: String) = true
        override fun seal(scopeKey: String, plaintext: ByteArray, associatedData: ByteArray) = plaintext.copyOf()
        override fun open(scopeKey: String, ciphertext: ByteArray, associatedData: ByteArray) = ciphertext.copyOf()
    }
    @Test fun 固定真实服务端转发OAIHost的Relay选择并保留两个不同Client() = runBlocking {
        Server().use { server ->
            val login = server.login(); val parent = CoroutineScope(SupervisorJob()+Dispatchers.Default)
            val host = RemoteStaticIdentityFactory.generate(RemoteDeviceId(UUID.randomUUID().toString()))
            val hostScope = RemoteIdentityScope(server.api.origin, login.accountId, RemoteRole.HOST)
            val hostCredential = server.api.registerAccountRole(hostScope, host, login.token)
            val device = RemoteHostDevice(host.deviceId.value, "手机", "android", "member", true)
            val hostPeer = RemoteAuthorizedPeer(device, RemoteRole.HOST, host.publicKey)
            val hostService = RemoteSignalService(context, parent, server.api, hostScope, hostCredential, {},
                { id, _ -> server.api.peer(hostCredential.tokens.accessToken, id) }, handler())
            val clients = mutableListOf<RemoteSignalService>()
            try {
                hostService.startHost()
                val channels = (1..2).map {
                    val identity = RemoteStaticIdentityFactory.generate(RemoteDeviceId(UUID.randomUUID().toString()))
                    val scope = RemoteIdentityScope(server.api.origin, login.accountId, RemoteRole.CLIENT)
                    val credential = server.api.registerAccountRole(scope, identity, login.token)
                    RemoteSignalService(context, parent, server.api, scope, credential, {}, { _, _ -> hostPeer }).also(clients::add).connect(hostPeer)
                }
                for (channel in channels) assertTrue(channel.invoke("harness.transport.describe", obj(), timeoutMs = 3000) is JsonValue.JObject)
            } finally { clients.forEach { it.close() }; hostService.close(); parent.cancel() }
        }
    }

    private inner class RawClient(private val api: RemoteServerApi, private val identity: RemoteStaticIdentity,
        private val credential: RemoteRoleCredential, private val host: RemoteStaticIdentity) : AutoCloseable {
        private val frames = Channel<RemoteControlFrame>(64)
        private val socket = api.http.newWebSocket(Request.Builder().url(api.origin.replace("http:", "ws:")+"/ws/v1/connect").build(), object : WebSocketListener() {
            override fun onOpen(openedSocket: WebSocket, response: Response) { openedSocket.send(RemoteControlCodec.encode(RemoteControlCodec.create("hello", obj("role" to "client", "deviceId" to identity.deviceId.value,
                "accessToken" to credential.tokens.accessToken, "protocols" to listOf(1), "capabilities" to listOf("transport.relay"),
                "clientVersion" to com.openandroidintelligence.gateway.client.BuildConfig.OAI_REMOTE_CLIENT_VERSION), UUID.randomUUID().toString(), System.currentTimeMillis())).toString(Charsets.UTF_8)) }
            override fun onMessage(socket: WebSocket, text: String) { frames.trySend(RemoteControlCodec.decode(text.encodeToByteArray())) }
            override fun onFailure(socket: WebSocket, t: Throwable, response: Response?) { frames.close(t) }
        })
        private lateinit var secure: RemoteSecureChannel
        private lateinit var connectionId: String
        private var outgoing = 0L
        private suspend fun next(type: String): RemoteControlFrame = withTimeout(5000) {
            while (true) { val frame = frames.receive(); if (frame.type == type) return@withTimeout frame }
            error("不可达")
        }
        private fun send(type: String, payload: JsonValue.JObject) { socket.send(RemoteControlCodec.encode(RemoteControlCodec.create(type, payload,
            UUID.randomUUID().toString(), System.currentTimeMillis())).toString(Charsets.UTF_8)) }
        suspend fun open() {
            next("hello.ack"); send("connect.request", obj("hostDeviceId" to host.deviceId.value, "preferredTransports" to listOf("relay")))
            connectionId = next("connect.accepted").payload.requiredText("connectionId")
            send("transport.selected", obj("connectionId" to connectionId, "targetDeviceId" to host.deviceId.value, "transport" to "relay"))
            secure = RemoteSecureChannel(NoiseIkChannel.create(NoiseRole.CLIENT_INITIATOR, RemoteConnectionBinding(connectionId, host.deviceId.value, identity.deviceId.value),
                identity.privateKeyCopy(), host.publicKey))
            socket.send(RemoteControlCodec.encode(secure.writeHandshakeFrame(UUID.randomUUID().toString(), System.currentTimeMillis())).toString(Charsets.UTF_8))
            secure.readHandshakeFrame(next("secure.handshake"))
        }
        suspend fun call(id: String, counter: Long = outgoing++): JsonValue {
            val request = Json.canonical(obj("v" to 1, "id" to id, "type" to "rpc.request", "timestamp" to System.currentTimeMillis(),
                "payload" to obj("method" to "harness.transport.describe", "params" to obj()))).encodeToByteArray()
            for (cipher in secure.encryptMessage(request)) send("relay", obj("connectionId" to connectionId, "targetDeviceId" to host.deviceId.value,
                "counter" to counter, "ciphertext" to RemoteBase64Url.encode(cipher)))
            val incoming = next("relay"); val bytes = secure.decryptMessage(RemoteBase64Url.decode(incoming.payload.requiredText("ciphertext")))!!
            return RemoteWireJson.decode(bytes).objectValue().value("payload").objectValue().value("result")
        }
        suspend fun sendBadCounter() {
            val request = Json.canonical(obj("v" to 1, "id" to "bad", "type" to "rpc.request", "timestamp" to System.currentTimeMillis(),
                "payload" to obj("method" to "harness.transport.describe", "params" to obj()))).encodeToByteArray()
            for (cipher in secure.encryptMessage(request)) send("relay", obj("connectionId" to connectionId, "targetDeviceId" to host.deviceId.value,
                "counter" to 1, "ciphertext" to RemoteBase64Url.encode(cipher)))
        }
        override fun close() { socket.cancel(); if (::secure.isInitialized) secure.close() }
    }
    @Test fun 固定Server转发的跳号Relay只终止坏Client且健康Client继续RPC() = runBlocking {
        Server().use { server ->
            val login = server.login(); val parent = CoroutineScope(SupervisorJob()+Dispatchers.Default)
            val host = RemoteStaticIdentityFactory.generate(RemoteDeviceId(UUID.randomUUID().toString()))
            val hostScope = RemoteIdentityScope(server.api.origin, login.accountId, RemoteRole.HOST)
            val hostCredential = server.api.registerAccountRole(hostScope, host, login.token)
            val hostService = RemoteSignalService(context, parent, server.api, hostScope, hostCredential, {},
                { id, _ -> server.api.peer(hostCredential.tokens.accessToken, id) }, handler())
            val clients = mutableListOf<RawClient>()
            try {
                hostService.startHost()
                for (number in 1..2) {
                    val identity = RemoteStaticIdentityFactory.generate(RemoteDeviceId(UUID.randomUUID().toString()))
                    val credential = server.api.registerAccountRole(RemoteIdentityScope(server.api.origin, login.accountId, RemoteRole.CLIENT), identity, login.token)
                    clients += RawClient(server.api, identity, credential, host).also { it.open() }
                }
                clients[0].sendBadCounter()
                assertTrue(withTimeout(3000) { clients[1].call("healthy") } is JsonValue.JObject)
            } finally { clients.forEach { it.close() }; hostService.close(); parent.cancel() }
        }
    }


    @Test fun 切换后旧授权结果迟到不能再打开同设备ControlWebSocket() = runBlocking {
        val firstPeerStarted = CompletableDeferred<Unit>(); val releaseFirst = CompletableDeferred<Unit>()
        val client = RemoteStaticIdentityFactory.generate(RemoteDeviceId(UUID.randomUUID().toString()))
        val first = RemoteStaticIdentityFactory.generate(RemoteDeviceId(UUID.randomUUID().toString()))
        val second = RemoteStaticIdentityFactory.generate(RemoteDeviceId(UUID.randomUUID().toString()))
        val helloTargets = Channel<String>(Channel.UNLIMITED)
        val server = MockWebServer()
        val tokens = obj("accessToken" to "a".repeat(16), "accessTokenExpiresAt" to System.currentTimeMillis()+600_000,
            "refreshToken" to "r".repeat(16), "refreshTokenExpiresAt" to System.currentTimeMillis()+1200000)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/v1/auth/login") return MockResponse().setBody(Json.canonical(obj("token" to "l".repeat(16), "account" to "owner", "expiresAt" to System.currentTimeMillis()+600_000)))
                if (request.path == "/api/v1/devices/register") return MockResponse().setBody(Json.canonical(tokens))
                if (request.path?.startsWith("/api/v1/devices/") == true) {
                    val identity = if (request.path!!.endsWith(first.deviceId.value)) first else second
                    if (identity === first) { firstPeerStarted.complete(Unit); runBlocking { releaseFirst.await() } }
                    return MockResponse().setBody(Json.canonical(obj("deviceId" to identity.deviceId.value, "name" to "电脑", "platform" to "linux", "role" to "host",
                        "membershipId" to "member", "identityKey" to RemoteBase64Url.encode(identity.publicKey), "online" to true)))
                }
                if (request.path == "/ws/v1/connect") return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onMessage(socket: WebSocket, text: String) {
                        val frame = RemoteControlCodec.decode(text.encodeToByteArray())
                        if (frame.type == "hello") socket.send(Json.canonical(obj("v" to 1, "id" to "ack", "type" to "hello.ack", "timestamp" to 1,
                            "payload" to obj("protocol" to 1, "serverVersion" to "fixture", "connectionSessionId" to UUID.randomUUID().toString(),
                                "heartbeatIntervalMs" to 25000, "maxControlFrameBytes" to 65536, "maxRelayFrameBytes" to 1048576,
                                "capabilities" to listOf("transport.relay")))))
                        if (frame.type == "connect.request") helloTargets.trySend(frame.payload.requiredText("hostDeviceId"))
                    }
                })
                return MockResponse().setResponseCode(404)
            }
        }
        server.start(); val parent = CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val directory = java.nio.file.Files.createTempDirectory("remote-switch").toFile(); val protector = PlainProtector()
        val runtime = RemoteAccountRuntime(context, parent, vault = RemoteCredentialVault(directory, protector), protector = protector)
        try {
            runtime.login(server.url("/").toString().removeSuffix("/"), "owner", "password")
            val old = async { runCatching { runtime.connect(first.deviceId.value) } }
            withTimeout(3000) { firstPeerStarted.await() }
            val next = async { runCatching { runtime.connect(second.deviceId.value) } }
            assertEquals(second.deviceId.value, withTimeout(3000) { helloTargets.receive() })
            releaseFirst.complete(Unit)
            val late = withTimeoutOrNull(1000) { helloTargets.receive() }
            assertNull("旧选择授权返回后不得再发送connect.request", late)
            old.cancelAndJoin(); next.cancelAndJoin()
        } finally { releaseFirst.complete(Unit); runtime.logout(); parent.cancel(); server.shutdown() }
    }
}
