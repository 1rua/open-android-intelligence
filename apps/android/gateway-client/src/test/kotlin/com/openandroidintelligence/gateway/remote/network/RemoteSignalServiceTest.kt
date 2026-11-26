package com.openandroidintelligence.gateway.remote.network

import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.openandroidintelligence.gateway.remote.crypto.*
import com.openandroidintelligence.gateway.remote.identity.*
import com.openandroidintelligence.gateway.remote.protocol.*
import com.openandroidintelligence.gateway.remote.rpc.*
import com.openandroidintelligence.gateway.remote.runtime.*
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class RemoteSignalServiceTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun token() = RemoteTokenPair("a".repeat(16), System.currentTimeMillis()+60_000, "r".repeat(16), System.currentTimeMillis()+600_000)
    private fun control(type: String, payload: JsonValue.JObject) = RemoteControlCodec.create(type, payload, UUID.randomUUID().toString(), System.currentTimeMillis())
    private fun text(frame: RemoteControlFrame) = RemoteControlCodec.encode(frame).toString(Charsets.UTF_8)
    private fun ack() = control("hello.ack", obj("protocol" to 1, "serverVersion" to "fixture", "connectionSessionId" to "session",
        "heartbeatIntervalMs" to 25_000, "maxControlFrameBytes" to 65536, "maxRelayFrameBytes" to 1048576,
        "capabilities" to listOf("transport.relay"), "webrtcEnabled" to false))
    private fun business(id: String, type: String, payload: JsonValue.JObject) = Json.canonical(obj("v" to 1, "id" to id, "type" to type,
        "timestamp" to 1, "payload" to payload)).encodeToByteArray()

    @Test fun Client真实WebSocket控制与Noise后双向RPC且关闭不重发() = runBlocking {
        val host = RemoteStaticIdentityFactory.generate(RemoteDeviceId(UUID.randomUUID().toString()))
        val client = RemoteStaticIdentityFactory.generate(RemoteDeviceId(UUID.randomUUID().toString()))
        val binding = RemoteConnectionBinding("connection", host.deviceId.value, client.deviceId.value)
        val secure = RemoteSecureChannel(NoiseIkChannel.create(NoiseRole.HOST_RESPONDER, binding, host.privateKeyCopy(), client.publicKey))
        val requests = mutableListOf<String>(); var counter = 0L
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = RemoteControlCodec.decode(text.encodeToByteArray())
                when (frame.type) {
                    "hello" -> { assertEquals(client.deviceId.value, frame.payload.requiredText("deviceId"));
                        assertEquals(com.openandroidintelligence.gateway.client.BuildConfig.OAI_REMOTE_CLIENT_VERSION, frame.payload.requiredText("clientVersion")); webSocket.send(text(ack())) }
                    "connect.request" -> webSocket.send(text(control("connect.accepted", obj("connectionId" to binding.connectionId))))
                    "transport.selected" -> assertEquals("relay", frame.payload.requiredText("transport"))
                    "secure.handshake" -> {
                        secure.readHandshakeFrame(frame)
                        webSocket.send(text(secure.writeHandshakeFrame(UUID.randomUUID().toString(), System.currentTimeMillis())))
                    }
                    "relay" -> {
                        val bytes = secure.decryptMessage(RemoteBase64Url.decode(frame.payload.requiredText("ciphertext"))) ?: return
                        val message = RemoteWireJson.decode(bytes).objectValue(); requests += message.value("payload").objectValue().requiredText("method")
                        for (cipher in secure.encryptMessage(business("response", "rpc.response", obj("requestId" to message.requiredText("id"), "result" to "actual-result")))) {
                            webSocket.send(text(control("relay", obj("connectionId" to binding.connectionId, "targetDeviceId" to client.deviceId.value,
                                "counter" to counter++, "ciphertext" to RemoteBase64Url.encode(cipher)))))
                        }
                    }
                }
            }
        }))
        server.start()
        val api = RemoteServerApi(server.url("/").toString().removeSuffix("/"))
        val parent = CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val peer = RemoteAuthorizedPeer(RemoteHostDevice(host.deviceId.value, "电脑", "linux", "member", true), RemoteRole.HOST, host.publicKey)
        val service = RemoteSignalService(context, parent, api, RemoteIdentityScope(api.origin, "owner", RemoteRole.CLIENT), RemoteRoleCredential(client, token()), {}, { _, _ -> peer })
        try {
            val channel = service.connect(peer)
            assertEquals(JsonValue.JString("actual-result"), channel.invoke("harness.transport.describe", obj(), "request", 3000))
            channel.close(); assertEquals(listOf("harness.transport.describe"), requests)
        } finally { service.close(); parent.cancel(); server.shutdown(); secure.close() }
    }

    @Test fun 退役取消时旧隧道不能回退创建Relay() = verifyRetiredTunnel(forceClose = true)

    @Test fun 退出控制代际后旧隧道不能回退创建Relay() = verifyRetiredTunnel(forceClose = false)

    private fun verifyRetiredTunnel(forceClose: Boolean): Unit = runBlocking {
        val scheduler = TestCoroutineScheduler()
        val worker = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val client = RemoteStaticIdentityFactory.generate(RemoteDeviceId(UUID.randomUUID().toString()))
        val host = RemoteStaticIdentityFactory.generate(RemoteDeviceId(UUID.randomUUID().toString()))
        val peer = RemoteAuthorizedPeer(RemoteHostDevice(host.deviceId.value, "电脑", "linux", "member", true), RemoteRole.HOST, host.publicKey)
        val iceRequested = CompletableDeferred<Unit>()
        val releaseIce = CompletableDeferred<Unit>()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    when (RemoteControlCodec.decode(text.encodeToByteArray()).type) {
                        "hello" -> webSocket.send(text(control("hello.ack", obj("protocol" to 1, "serverVersion" to "fixture",
                            "connectionSessionId" to "session", "heartbeatIntervalMs" to 25_000, "maxControlFrameBytes" to 65536,
                            "maxRelayFrameBytes" to 1048576, "capabilities" to listOf("transport.p2p", "transport.relay")))))
                        "connect.request" -> webSocket.send(text(control("connect.accepted", obj("connectionId" to "retiring",
                            "relayEndpoint" to server.url("/relay").toString().replaceFirst("http", "ws"), "relayTicket" to "fixture"))))
                    }
                }
            }))
            val api = RemoteServerApi(server.url("/").toString().removeSuffix("/"))
            val service = RemoteSignalService(context, worker, api, RemoteIdentityScope(api.origin, "owner", RemoteRole.CLIENT),
                RemoteRoleCredential(client, token()), {}, { _, _ -> peer }, iceServers = {
                    iceRequested.complete(Unit)
                    releaseIce.await()
                    emptyList()
                })
            val connecting = worker.async { runCatching { service.connect(peer) } }
            try {
                withTimeout(5_000) {
                    while (!iceRequested.isCompleted) {
                        scheduler.runCurrent()
                        if (!iceRequested.isCompleted) yield()
                    }
                }
                // 保留旧隧道引用，退役后直接确认是否还分配了 Relay WS。
                val tunnels = RemoteSignalService::class.java.getDeclaredField("tunnels").apply { isAccessible = true }
                val tunnel = requireNotNull((tunnels.get(service) as Map<*, *>)["retiring"])
                val relay = tunnel.javaClass.getDeclaredField("relaySocket").apply { isAccessible = true }
                if (forceClose) service.close() else {
                    service.stopTransportCreation()
                    releaseIce.complete(Unit)
                }
                scheduler.runCurrent()
                assertNull("已终止的旧隧道不能创建 Relay WS", relay.get(tunnel))
                val failure = withTimeout(5_000) { connecting.await() }.exceptionOrNull() as RemoteRpcException
                assertEquals(if (forceClose) "CLIENT_CLOSED" else "CONNECTION_FAILED", failure.code)
            } finally {
                service.close()
                worker.cancel()
                scheduler.runCurrent()
            }
        }
    }

    @Test fun Host认证入站后先登记执行器再回应Noise并只使用可信连接主体() = runBlocking {
        val host = RemoteStaticIdentityFactory.generate(RemoteDeviceId(UUID.randomUUID().toString()))
        val client = RemoteStaticIdentityFactory.generate(RemoteDeviceId(UUID.randomUUID().toString()))
        val binding = RemoteConnectionBinding("connection", host.deviceId.value, client.deviceId.value)
        val secure = RemoteSecureChannel(NoiseIkChannel.create(NoiseRole.CLIENT_INITIATOR, binding, client.privateKeyCopy(), host.publicKey))
        val result = CompletableDeferred<JsonValue>(); val seen = CompletableDeferred<RemoteRpcConnectionScope>(); var counter = 0L
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = RemoteControlCodec.decode(text.encodeToByteArray())
                when (frame.type) {
                    "hello" -> {
                        assertEquals("host", frame.payload.requiredText("role")); webSocket.send(text(ack()))
                        webSocket.send(text(control("connect.incoming", obj("connectionId" to binding.connectionId, "clientDeviceId" to client.deviceId.value,
                            "clientIdentityKey" to RemoteBase64Url.encode(client.publicKey), "authorization" to "account", "preferredTransports" to listOf("relay")))))
                    }
                    "connect.accepted" -> {
                        webSocket.send(text(control("transport.selected", obj("connectionId" to binding.connectionId, "targetDeviceId" to host.deviceId.value, "transport" to "relay"))))
                        webSocket.send(text(secure.writeHandshakeFrame(UUID.randomUUID().toString(), System.currentTimeMillis())))
                    }
                    "secure.handshake" -> {
                        secure.readHandshakeFrame(frame)
                        for (cipher in secure.encryptMessage(business("incoming", "rpc.request", obj("method" to "harness.transport.describe", "params" to obj("accountId" to "spoof"))))) {
                            webSocket.send(text(control("relay", obj("connectionId" to binding.connectionId, "targetDeviceId" to host.deviceId.value,
                                "counter" to counter++, "ciphertext" to RemoteBase64Url.encode(cipher)))))
                        }
                    }
                    "relay" -> {
                        val bytes = secure.decryptMessage(RemoteBase64Url.decode(frame.payload.requiredText("ciphertext"))) ?: return
                        result.complete(RemoteWireJson.decode(bytes).objectValue().value("payload").objectValue().value("result"))
                    }
                }
            }
        }))
        server.start(); val api = RemoteServerApi(server.url("/").toString().removeSuffix("/")); val parent = CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val peer = RemoteAuthorizedPeer(RemoteHostDevice(client.deviceId.value, "客户端", "linux", "member", true), RemoteRole.CLIENT, client.publicKey)
        val handler = object : RemoteRpcHostHandler {
            override suspend fun invoke(scope: RemoteRpcConnectionScope, method: String, params: JsonValue.JObject): JsonValue { seen.complete(scope); return obj("capabilities" to listOf("harness.remote.v3")) }
            override fun events(scope: RemoteRpcConnectionScope) = emptyFlow<RemoteRpcEvent>()
        }
        val service = RemoteSignalService(context, parent, api, RemoteIdentityScope(api.origin, "real-owner", RemoteRole.HOST), RemoteRoleCredential(host, token()), {}, { _, _ -> peer }, handler)
        try {
            service.startHost(); withTimeout(5000) { result.await() }
            assertEquals("real-owner", seen.await().accountId); assertEquals(binding.clientDeviceId, seen.await().clientDeviceId)
        } finally { service.close(); parent.cancel(); server.shutdown(); secure.close() }
    }
    @Test fun Host单连接关闭回调抛错仍结束该RPC且保留另一连接并完整关闭服务() = runBlocking {
        val host = RemoteStaticIdentityFactory.generate(RemoteDeviceId(UUID.randomUUID().toString()))
        val clients = (1..2).map { RemoteStaticIdentityFactory.generate(RemoteDeviceId(UUID.randomUUID().toString())) }
        val bindings = clients.mapIndexed { index, client -> RemoteConnectionBinding("connection-$index", host.deviceId.value, client.deviceId.value) }
        val secure = clients.mapIndexed { index, client -> RemoteSecureChannel(NoiseIkChannel.create(NoiseRole.CLIENT_INITIATOR, bindings[index], client.privateKeyCopy(), host.publicKey)) }
        val result = CompletableDeferred<JsonValue>(); val handshakes = mutableSetOf<Int>(); var outgoing = 0L
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = RemoteControlCodec.decode(text.encodeToByteArray())
                fun send(type: String, payload: JsonValue.JObject) { webSocket.send(text(control(type, payload))) }
                when (frame.type) {
                    "hello" -> {
                        webSocket.send(text(ack()))
                        bindings.forEachIndexed { index, binding -> send("connect.incoming", obj("connectionId" to binding.connectionId,
                            "clientDeviceId" to binding.clientDeviceId, "clientIdentityKey" to RemoteBase64Url.encode(clients[index].publicKey),
                            "authorization" to "account", "preferredTransports" to listOf("relay"))) }
                    }
                    "connect.accepted" -> {
                        val index = bindings.indexOfFirst { it.connectionId == frame.payload.requiredText("connectionId") }
                        val binding = bindings[index]
                        send("transport.selected", obj("connectionId" to binding.connectionId, "targetDeviceId" to binding.hostDeviceId, "transport" to "relay"))
                        webSocket.send(text(secure[index].writeHandshakeFrame(UUID.randomUUID().toString(), System.currentTimeMillis())))
                    }
                    "secure.handshake" -> {
                        val index = bindings.indexOfFirst { it.connectionId == frame.payload.requiredText("connectionId") }
                        secure[index].readHandshakeFrame(frame); handshakes += index
                        if (handshakes.size == 2) {
                            val bad = secure[0].encryptMessage(business("bad", "rpc.request", obj("method" to "harness.transport.describe", "params" to obj()))).single()
                            send("relay", obj("connectionId" to bindings[0].connectionId, "targetDeviceId" to host.deviceId.value, "counter" to 1,
                                "ciphertext" to RemoteBase64Url.encode(bad)))
                            val good = secure[1].encryptMessage(business("good", "rpc.request", obj("method" to "harness.transport.describe", "params" to obj()))).single()
                            send("relay", obj("connectionId" to bindings[1].connectionId, "targetDeviceId" to host.deviceId.value, "counter" to 0,
                                "ciphertext" to RemoteBase64Url.encode(good)))
                        }
                    }
                    "relay" -> {
                        val index = bindings.indexOfFirst { it.connectionId == frame.payload.requiredText("connectionId") }
                        val value = secure[index].decryptMessage(RemoteBase64Url.decode(frame.payload.requiredText("ciphertext"))) ?: return
                        if (index == 1) result.complete(RemoteWireJson.decode(value))
                    }
                }
            }
        }))
        server.start(); val api = RemoteServerApi(server.url("/").toString().removeSuffix("/")); val parent = CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val peers = clients.associate { client -> client.deviceId.value to RemoteAuthorizedPeer(RemoteHostDevice(client.deviceId.value, "客户端", "linux", "member", true), RemoteRole.CLIENT, client.publicKey) }
        val closedHandlers = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        var serviceClosed = false
        val handler = object : RemoteRpcHostHandler {
            override fun connectionClosed(scope: RemoteRpcConnectionScope) { closedHandlers += scope.connectionId }
            override suspend fun invoke(scope: RemoteRpcConnectionScope, method: String, params: JsonValue.JObject) = obj("healthy" to true)
            override fun events(scope: RemoteRpcConnectionScope) = emptyFlow<RemoteRpcEvent>()
        }
        val service = RemoteSignalService(context, parent, api, RemoteIdentityScope(api.origin, "owner", RemoteRole.HOST), RemoteRoleCredential(host, token()), {}, { id, _ -> peers.getValue(id) }, handler,
            onPeerClosed = { _, _ -> throw java.io.IOException("模拟关闭登记失败") }, onClosed = { serviceClosed = true })
        try {
            service.startHost()
            assertEquals("good", withTimeout(3000) { result.await() }.objectValue().value("payload").objectValue().requiredText("requestId"))
            assertTrue("首个登记回调失败仍须通知真实RPC关闭", closedHandlers.contains(bindings[0].connectionId))
            val failure = runCatching { service.close() }.exceptionOrNull() as RemoteRpcException
            assertEquals("CONNECTION_CLEANUP_FAILED", failure.code)
            assertTrue(serviceClosed)
            assertEquals(bindings.map { it.connectionId }.toSet(), closedHandlers)
            withTimeout(3000) { while (parent.coroutineContext[Job]!!.children.any()) delay(1) }
        } finally { runCatching { service.close() }; parent.cancel(); server.shutdown(); secure.forEach { it.close() } }
    }

    @Test fun Host当前已认证隧道忽略重复第一握手且不重跑认证或Noise() = runBlocking {
        val rig = HostSignalRig(afterFirstHandshake = { webSocket, first -> webSocket.send(text(copyHandshake(first))) })
        try {
            rig.start()
            val result = withTimeout(3000) { rig.request(0, "after-duplicate").await() }

            assertEquals(JsonValue.JBool(true), result.objectValue().value("ok"))
            assertEquals(1, rig.authenticatedCount(0))
            assertEquals(1, rig.stepTwoReplyCount(0))
            assertFalse("重复第一握手不得关闭当前隧道", rig.closed(0).isCompleted)
            assertTrue(rig.service.connected)
        } finally { rig.shutdown() }
    }

    @Test fun Host已认证隧道忽略迟到重复transportSelected且不关闭连接() = runBlocking {
        lateinit var rig: HostSignalRig
        rig = HostSignalRig(afterFirstHandshake = { webSocket, _ ->
            webSocket.send(text(control("transport.selected", obj(
                "connectionId" to rig.bindings[0].connectionId,
                "targetDeviceId" to rig.host.deviceId.value,
                "transport" to "relay",
            ))))
        })
        try {
            rig.start()
            val result = withTimeout(3000) { rig.request(0, "after-late-transport").await() }

            assertEquals(JsonValue.JBool(true), result.objectValue().value("ok"))
            assertEquals(1, rig.authenticatedCount(0))
            assertFalse("迟到 transport.selected 不得关闭已认证隧道", rig.closed(0).isCompleted)
            assertTrue(rig.service.connected)
        } finally { rig.shutdown() }
    }

    @Test fun Host错误目标的第一握手仍关闭当前隧道() = runBlocking {
        val rig = HostSignalRig(afterFirstHandshake = { webSocket, first ->
            webSocket.send(text(copyHandshake(first, targetDeviceId = "another-host")))
        })
        try {
            rig.start()
            withTimeout(3000) { rig.closed(0).await() }

            assertEquals(1, rig.authenticatedCount(0))
            assertEquals(1, rig.stepTwoReplyCount(0))
        } finally { rig.shutdown() }
    }

    @Test fun Host乱序第二握手仍关闭当前隧道() = runBlocking {
        val rig = HostSignalRig(afterFirstHandshake = { webSocket, first ->
            webSocket.send(text(copyHandshake(first, step = 2)))
        })
        try {
            rig.start()
            withTimeout(3000) { rig.closed(0).await() }

            assertEquals(1, rig.authenticatedCount(0))
            assertEquals(1, rig.stepTwoReplyCount(0))
        } finally { rig.shutdown() }
    }

    @Test fun Host其他隧道的第一握手不复用当前认证且不替换原隧道() = runBlocking {
        val rig = HostSignalRig(clientCount = 2, afterFirstHandshake = { webSocket, first ->
            webSocket.send(text(copyHandshake(first, connectionId = "other-connection")))
        })
        try {
            rig.start()

            assertEquals(1, rig.authenticatedCount(0))
            assertEquals(0, rig.authenticatedCount(1))
            assertEquals(1, rig.stepTwoReplyCount(0))
            assertEquals(0, rig.stepTwoReplyCount(1))
            assertEquals(JsonValue.JBool(true), withTimeout(3000) { rig.request(0, "after-other-connection").await() }
                .objectValue().value("ok"))
            assertFalse("未知连接不得关闭或替换当前已认证隧道", rig.closed(0).isCompleted)
        } finally { rig.shutdown() }
    }

    private fun copyHandshake(frame: RemoteControlFrame, connectionId: String = frame.payload.requiredText("connectionId"),
        targetDeviceId: String = frame.payload.requiredText("targetDeviceId"), step: Int = frame.payload.longValue("step").toInt()): RemoteControlFrame =
        control("secure.handshake", obj("connectionId" to connectionId, "targetDeviceId" to targetDeviceId, "step" to step,
            "data" to frame.payload.requiredText("data")))

    /** 真 WebSocket + 双端 Noise，单线程调度让“重复帧失败”与后续 RPC 的先后可重复观察。 */
    private inner class HostSignalRig(
        private val clientCount: Int = 1,
        private val handshakeClients: Set<Int> = setOf(0),
        private val afterFirstHandshake: (WebSocket, RemoteControlFrame) -> Unit,
    ) {
        val host = RemoteStaticIdentityFactory.generate(RemoteDeviceId(UUID.randomUUID().toString()))
        val clients = (0 until clientCount).map { RemoteStaticIdentityFactory.generate(RemoteDeviceId(UUID.randomUUID().toString())) }
        val bindings = clients.mapIndexed { index, client -> RemoteConnectionBinding("connection-$index", host.deviceId.value, client.deviceId.value) }
        private val secure = clients.mapIndexed { index, client ->
            RemoteSecureChannel(NoiseIkChannel.create(NoiseRole.CLIENT_INITIATOR, bindings[index], client.privateKeyCopy(), host.publicKey))
        }
        private val executor = Executors.newSingleThreadExecutor()
        private val dispatcher = executor.asCoroutineDispatcher()
        private val parent = CoroutineScope(SupervisorJob() + dispatcher)
        private val authenticated = AtomicIntegerArray(clientCount)
        private val stepTwoReplies = AtomicIntegerArray(clientCount)
        private val outgoingCounters = AtomicLongArray(clientCount)
        private val firstFrames = AtomicReferenceArray<RemoteControlFrame>(clientCount)
        private val closedByConnection = bindings.map { it.connectionId to CompletableDeferred<Unit>() }.toMap()
        private val responses = ConcurrentHashMap<String, CompletableDeferred<JsonValue>>()
        private val handshaken = CompletableDeferred<Unit>()
        private lateinit var webSocket: WebSocket
        private val server = MockWebServer()
        private val api: RemoteServerApi
        val service: RemoteSignalService

        init {
            val peers = clients.associate { client -> client.deviceId.value to
                RemoteAuthorizedPeer(RemoteHostDevice(client.deviceId.value, "客户端", "linux", "member", true), RemoteRole.CLIENT, client.publicKey) }
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { this@HostSignalRig.webSocket = webSocket }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val frame = RemoteControlCodec.decode(text.encodeToByteArray())
                    when (frame.type) {
                        "hello" -> {
                            webSocket.send(text(ack()))
                            bindings.forEachIndexed { index, binding -> webSocket.send(text(control("connect.incoming", obj(
                                "connectionId" to binding.connectionId, "clientDeviceId" to binding.clientDeviceId,
                                "clientIdentityKey" to RemoteBase64Url.encode(clients[index].publicKey), "authorization" to "account",
                                "preferredTransports" to listOf("relay"))))) }
                        }
                        "connect.accepted" -> {
                            val index = indexOf(frame.payload.requiredText("connectionId"))
                            val binding = bindings[index]
                            webSocket.send(text(control("transport.selected", obj("connectionId" to binding.connectionId,
                                "targetDeviceId" to binding.hostDeviceId, "transport" to "relay"))))
                            if (index in handshakeClients) {
                                val first = secure[index].writeHandshakeFrame(UUID.randomUUID().toString(), System.currentTimeMillis())
                                firstFrames.set(index, first)
                                webSocket.send(text(first))
                            }
                        }
                        "secure.handshake" -> {
                            val index = indexOf(frame.payload.requiredText("connectionId"))
                            secure[index].readHandshakeFrame(frame)
                            stepTwoReplies.incrementAndGet(index)
                            if (index == 0) {
                                afterFirstHandshake(webSocket, firstFrames.get(index))
                                handshaken.complete(Unit)
                            }
                        }
                        "relay" -> {
                            val index = indexOf(frame.payload.requiredText("connectionId"))
                            val plain = secure[index].decryptMessage(RemoteBase64Url.decode(frame.payload.requiredText("ciphertext"))) ?: return
                            val message = RemoteWireJson.decode(plain).objectValue()
                            if (message.requiredText("type") == "rpc.response") {
                                val payload = message.value("payload").objectValue()
                                responses.remove(payload.requiredText("requestId"))?.complete(payload.value("result"))
                            }
                        }
                    }
                }
            }))
            server.start()
            api = RemoteServerApi(server.url("/").toString().removeSuffix("/"))
            service = RemoteSignalService(context, parent, api, RemoteIdentityScope(api.origin, "owner", RemoteRole.HOST),
                RemoteRoleCredential(host, token()), {}, { id, _ -> peers.getValue(id) }, object : RemoteRpcHostHandler {
                    override suspend fun invoke(scope: RemoteRpcConnectionScope, method: String, params: JsonValue.JObject) = obj("ok" to true)
                    override fun events(scope: RemoteRpcConnectionScope) = emptyFlow<RemoteRpcEvent>()
                }, onAuthenticatedPeer = { authenticated.incrementAndGet(indexOf(it.scope.connectionId)) },
                onPeerClosed = { closedByConnection.getValue(it.connectionId).complete(Unit) })
        }

        suspend fun start() {
            service.startHost()
            withTimeout(5000) { handshaken.await() }
        }

        fun request(index: Int, requestId: String): CompletableDeferred<JsonValue> {
            val result = CompletableDeferred<JsonValue>()
            check(responses.putIfAbsent(requestId, result) == null)
            val request = business(requestId, "rpc.request", obj("method" to "harness.transport.describe", "params" to obj()))
            for (cipher in secure[index].encryptMessage(request)) {
                webSocket.send(text(control("relay", obj("connectionId" to bindings[index].connectionId,
                    "targetDeviceId" to host.deviceId.value, "counter" to outgoingCounters.getAndIncrement(index),
                    "ciphertext" to RemoteBase64Url.encode(cipher)))))
            }
            return result
        }

        fun authenticatedCount(index: Int): Int = authenticated.get(index)
        fun stepTwoReplyCount(index: Int): Int = stepTwoReplies.get(index)
        fun closed(index: Int): CompletableDeferred<Unit> = closedByConnection.getValue(bindings[index].connectionId)
        private fun indexOf(connectionId: String): Int = bindings.indexOfFirst { it.connectionId == connectionId }.also {
            check(it >= 0) { "未知测试连接：$connectionId" }
        }

        suspend fun shutdown() {
            runCatching { service.close() }
            parent.cancel()
            server.shutdown()
            secure.forEach { it.close() }
            dispatcher.close()
        }
    }

}
