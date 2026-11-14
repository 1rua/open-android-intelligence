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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
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
                    "hello" -> { assertEquals(client.deviceId.value, frame.payload.requiredText("deviceId")); webSocket.send(text(ack())) }
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
}
