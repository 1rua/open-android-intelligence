package com.openandroidintelligence.gateway.remote.network

import android.content.Context
import com.openandroidintelligence.gateway.remote.crypto.*
import com.openandroidintelligence.gateway.remote.identity.*
import com.openandroidintelligence.gateway.remote.protocol.*
import com.openandroidintelligence.gateway.remote.rpc.*
import com.openandroidintelligence.gateway.remote.runtime.*
import com.openandroidintelligence.gateway.schema.JsonValue
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Noise 验证后的主体事实来自账号目录和握手，业务参数不能覆盖它。 */
data class AuthenticatedRemotePeer(val scope: RemoteRpcConnectionScope, val localFingerprint: String,
    val peerFingerprint: String, val role: RemoteRole)

/**
 * 一个角色独立拥有 Control WS；主动连接拥有自己的实例，避免依赖电脑全局工作区投影。
 * 不声明恢复租约能力，断线关闭旧 Noise/请求，不把未知写入重放到新连接。
 */
internal class RemoteSignalService(
    private val context: Context,
    private val parentScope: CoroutineScope,
    private val api: RemoteServerApi,
    private val identityScope: RemoteIdentityScope,
    private val credential: RemoteRoleCredential,
    private val requireCurrent: () -> Unit,
    private val authorizePeer: suspend (String, RemoteRole) -> RemoteAuthorizedPeer,
    private val hostHandler: RemoteRpcHostHandler? = null,
    private val onAuthenticatedPeer: (AuthenticatedRemotePeer) -> Unit = {},
    private val onPeerClosed: (RemoteRpcConnectionScope) -> Unit = {},
    private val onClosed: (String) -> Unit = {},
) {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val worker = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val tunnels = ConcurrentHashMap<String, Tunnel>()
    private val ready = CompletableDeferred<Unit>()
    private var target: RemoteAuthorizedPeer? = null
    private var clientResult = CompletableDeferred<RemoteRpcChannel>()
    @Volatile private var socket: RemoteSocket? = null
    @Volatile private var closed = false
    @Volatile private var limits = RemoteFrameLimits()
    @Volatile private var negotiated = emptySet<String>()
    @Volatile private var lastReceived = System.currentTimeMillis()
    private val offered = setOf("transport.lan", "transport.p2p", "transport.turn", "transport.relay", "transport.relay.attach")

    suspend fun startHost() { check(identityScope.role == RemoteRole.HOST); start(); withTimeout(15_000) { ready.await() } }
    suspend fun connect(peer: RemoteAuthorizedPeer): RemoteRpcChannel {
        check(identityScope.role == RemoteRole.CLIENT && peer.role == RemoteRole.HOST)
        target = peer
        start()
        return try { withTimeout(60_000) { clientResult.await() } } catch (failure: Exception) { close(); throw failure }
    }
    private fun start() {
        requireCurrent()
        if (socket != null || closed) throw RemoteRpcException("CLIENT_CLOSED")
        val control = RemoteSocket(api.http, websocketUrl(api.origin), onFailure = ::fail)
        socket = control
        worker.launch {
            try {
                control.awaitOpen(); requireCurrent()
                sendControl("hello", obj("role" to identityScope.role.wireValue, "deviceId" to credential.identity.deviceId.value,
                    "accessToken" to credential.tokens.accessToken, "protocols" to listOf(1), "capabilities" to offered.toList(), "clientVersion" to "oai-android"))
                for (frame in control.frames) { lastReceived = System.currentTimeMillis(); requireCurrent(); handle(frame) }
            } catch (_: CancellationException) { fail("CLIENT_CLOSED") }
            catch (failure: RemoteRpcException) { fail(failure.code) }
            catch (_: Exception) { fail("TRANSPORT_CLOSED") }
        }
    }
    private suspend fun handle(frame: RemoteControlFrame) {
        val payload = frame.payload
        when (frame.type) {
            "hello.ack" -> {
                if (ready.isCompleted) throw RemoteRpcException("INVALID_MESSAGE")
                negotiated = (payload.fields.firstOrNull { it.first == "capabilities" }?.second as? JsonValue.JArray)?.items
                    ?.map { (it as? JsonValue.JString)?.value ?: throw RemoteRpcException("INVALID_MESSAGE") }?.toSet() ?: setOf("transport.relay")
                if (!offered.containsAll(negotiated) || "transport.relay" !in negotiated) throw RemoteRpcException("UNSUPPORTED_VERSION")
                limits = RemoteFrameLimits(payload.longValue("maxControlFrameBytes").toInt(), payload.longValue("maxRelayFrameBytes").toInt())
                socket?.limits = limits; ready.complete(Unit)
                worker.launch {
                    while (isActive && !closed) {
                        delay(25_000)
                        if (System.currentTimeMillis() - lastReceived >= 75_000) { fail("HEARTBEAT_TIMEOUT"); break }
                        sendControl("ping", obj("nonce" to UUID.randomUUID().toString()))
                    }
                }
                if (identityScope.role == RemoteRole.CLIENT) sendControl("connect.request", obj("hostDeviceId" to target!!.device.deviceId,
                    "preferredTransports" to listOf("lan", "p2p", "turn", "relay")))
            }
            "connect.accepted" -> {
                if (identityScope.role != RemoteRole.CLIENT || !ready.isCompleted) throw RemoteRpcException("INVALID_MESSAGE")
                val tunnel = newTunnel(payload.requiredText("connectionId"), target!!, payload)
                worker.launch {
                    try {
                        tunnel.selectClientTransport()
                        tunnel.sendHandshake()
                    } catch (_: Exception) { tunnel.fail("CONNECTION_FAILED") }
                }
            }
            "connect.incoming" -> {
                if (identityScope.role != RemoteRole.HOST || hostHandler == null) throw RemoteRpcException("METHOD_NOT_ALLOWED")
                val connectionId = payload.requiredText("connectionId")
                try {
                    val peer = authorizePeer(payload.requiredText("clientDeviceId"), RemoteRole.CLIENT)
                    if (payload.requiredText("authorization") != "account" || !peer.publicKey.contentEquals(RemoteBase64Url.decode(payload.requiredText("clientIdentityKey")))) {
                        throw RemoteRpcException("PEER_IDENTITY_MISMATCH")
                    }
                    tunnels.values.filter { it.peer.device.deviceId == peer.device.deviceId }.forEach { it.fail("CONNECTION_REPLACED") }
                    newTunnel(connectionId, peer, payload)
                    sendControl("connect.accepted", obj("connectionId" to connectionId))
                } catch (_: Exception) { sendControl("connect.rejected", obj("connectionId" to connectionId, "code" to "MEMBERSHIP_REQUIRED")) }
            }
            "secure.handshake" -> {
                val tunnel = requireTunnel(payload)
                worker.launch { try { tunnel.receiveHandshake(frame) } catch (_: Exception) { tunnel.fail("SECURE_CHANNEL_FAILED") } }
            }
            "transport.selected" -> {
                val tunnel = requireTunnel(payload)
                if (identityScope.role == RemoteRole.HOST) worker.launch {
                    try { tunnel.selectHostTransport(payload.requiredText("transport")) } catch (_: Exception) { tunnel.fail("CONNECTION_FAILED") }
                }
            }
            "signal.offer", "signal.answer", "signal.ice" -> {
                val tunnel = requireTunnel(payload)
                worker.launch { try { tunnel.signal(frame.type, payload) } catch (_: Exception) { tunnel.fail("RTC_NEGOTIATION_FAILED") } }
            }
            "relay" -> requireTunnel(payload).receiveRelay(payload)
            "ping" -> sendControl("pong", payload)
            "pong" -> Unit
            "connect.rejected", "error" -> {
                val code = payload.optionalText("code") ?: "CONNECTION_FAILED"
                val connectionId = payload.optionalText("connectionId")
                if (connectionId != null && identityScope.role == RemoteRole.HOST) tunnels[connectionId]?.fail(code)
                else throw RemoteRpcException(code)
            }
            else -> throw RemoteRpcException("INVALID_MESSAGE")
        }
    }
    private fun requireTunnel(payload: JsonValue.JObject): Tunnel {
        if (payload.requiredText("targetDeviceId") != credential.identity.deviceId.value) throw RemoteRpcException("CONNECTION_BINDING_MISMATCH")
        return tunnels[payload.requiredText("connectionId")] ?: throw RemoteRpcException("CONNECTION_BINDING_MISMATCH")
    }
    private fun newTunnel(id: String, peer: RemoteAuthorizedPeer, assignment: JsonValue.JObject): Tunnel {
        requireCurrent()
        val tunnel = Tunnel(id, peer, assignment)
        if (tunnels.putIfAbsent(id, tunnel) != null) { tunnel.fail("CONNECTION_REPLACED"); throw RemoteRpcException("CONNECTION_REPLACED") }
        return tunnel
    }
    private fun sendControl(type: String, payload: JsonValue.JObject) { requireCurrent(); socket?.send(frame(type, payload)) ?: throw RemoteRpcException("TRANSPORT_CLOSED") }
    private fun frame(type: String, payload: JsonValue.JObject) = RemoteControlCodec.create(type, payload, UUID.randomUUID().toString(), System.currentTimeMillis())
    fun close() = fail("CLIENT_CLOSED")
    private fun fail(code: String) {
        if (closed) return
        closed = true; ready.completeExceptionally(RemoteRpcException(code)); clientResult.completeExceptionally(RemoteRpcException(code))
        tunnels.values.toList().forEach { it.fail(code) }; tunnels.clear(); socket?.close(); socket = null; lifetime.cancel(); onClosed(code)
    }

    private inner class Tunnel(val id: String, val peer: RemoteAuthorizedPeer, val assignment: JsonValue.JObject) : RemoteMessageTransport {
        val binding = RemoteConnectionBinding(id, if (identityScope.role == RemoteRole.HOST) credential.identity.deviceId.value else peer.device.deviceId,
            if (identityScope.role == RemoteRole.CLIENT) credential.identity.deviceId.value else peer.device.deviceId)
        val connectionScope = RemoteRpcConnectionScope(api.origin, identityScope.accountId, binding.hostDeviceId, binding.clientDeviceId, id, generations.incrementAndGet())
        private val localPrivate = credential.identity.privateKeyCopy()
        private val secure = try { RemoteSecureChannel(NoiseIkChannel.create(if (identityScope.role == RemoteRole.CLIENT) NoiseRole.CLIENT_INITIATOR else NoiseRole.HOST_RESPONDER,
            binding, localPrivate, peer.publicKey)) } finally { localPrivate.fill(0) }
        private val sent = Mutex(); private val handshake = Mutex(); private val rtcSignals = Mutex(); private val callbacksLock = Any()
        private val messages = linkedSetOf<(ByteArray) -> Unit>(); private val closures = linkedSetOf<(String) -> Unit>()
        private val carrierReady = CompletableDeferred<Unit>()
        private val rtcAvailable = CompletableDeferred<RemoteRtcPeer>()
        private var rtcPeer: RemoteRtcPeer? = null
        private var rtc: com.openandroidintelligence.gateway.remote.transport.RtcDataChannelTransport? = null
        private var relaySocket: RemoteSocket? = null
        private var outgoingCounter = 0L; private var incomingCounter = 0L
        @Volatile private var terminal: String? = null
        @Volatile private var selected: String? = null
        private var rpc: RemoteRpcSession? = null
        suspend fun selectClientTransport() {
            val webRtcEnabled = negotiated.any { it in setOf("transport.lan", "transport.p2p", "transport.turn") }
            if (webRtcEnabled) {
                try {
                    val peerRtc = createRtc(turnOnly = false); rtcPeer = peerRtc; rtcAvailable.complete(peerRtc); rtc = peerRtc.initiate(); selected = peerRtc.selectedTransport()
                } catch (_: Exception) { rtcPeer?.close(); rtcPeer = null; rtc = null }
            }
            if (rtc == null) { selected = "relay"; attachRelay() }
            rtc?.onMessage(::receiveCiphertext); rtc?.onClose { fail(it) }
            carrierReady.complete(Unit)
            sendControl("transport.selected", obj("connectionId" to id, "targetDeviceId" to peer.device.deviceId, "transport" to selected))
        }
        suspend fun selectHostTransport(transport: String) {
            if (selected != null) throw RemoteRpcException("CONNECTION_REPLACED")
            selected = transport
            if (transport == "relay") { rtcPeer?.close(); rtcPeer = null; rtc = null; attachRelay() }
            else {
                val peerRtc = withTimeout(15_000) { rtcAvailable.await() }
                rtc = peerRtc.awaitOpen(); rtc!!.onMessage(::receiveCiphertext); rtc!!.onClose { fail(it) }
            }
            carrierReady.complete(Unit)
        }
        private suspend fun createRtc(turnOnly: Boolean): RemoteRtcPeer = RemoteRtcPeer(context, binding, connectionScope.generation, worker,
            api.iceServers(credential.tokens.accessToken, id), turnOnly) { type, fields ->
            sendControl(type, obj("connectionId" to id, "targetDeviceId" to peer.device.deviceId, *fields.fields.map { it.first to it.second }.toTypedArray()))
        }
        suspend fun signal(type: String, payload: JsonValue.JObject) = rtcSignals.withLock {
            if (terminal != null || selected == "relay") return@withLock
            when (type) {
                "signal.offer" -> { val current = rtcPeer ?: createRtc(false).also { rtcPeer = it; rtcAvailable.complete(it) }; current.acceptOffer(payload.requiredText("sdp")) }
                "signal.answer" -> rtcPeer?.acceptAnswer(payload.requiredText("sdp")) ?: throw RemoteRpcException("RTC_UNAVAILABLE")
                "signal.ice" -> {
                    // Offer 和 ICE 各自异步到达；同一隧道保留尚未设置 remote SDP 的候选。
                    val current = rtcPeer ?: createRtc(false).also { rtcPeer = it; rtcAvailable.complete(it) }
                    current.acceptIce(payload.value("candidate").objectValue())
                }
            }
        }
        private suspend fun attachRelay() {
            val endpoint = assignment.optionalText("relayEndpoint") ?: return
            val ticket = assignment.requiredText("relayTicket")
            val uri = URI(endpoint)
            if (uri.scheme !in setOf("wss", "ws") || uri.host == null || uri.userInfo != null || uri.fragment != null || uri.query != null ||
                api.origin.startsWith("https:") && uri.scheme != "wss") throw RemoteRpcException("RELAY_ENDPOINT_INVALID")
            val data = RemoteSocket(api.http, endpoint, relayData = true, onFailure = ::fail)
            data.limits = limits; relaySocket = data
            data.awaitOpen()
            // relay.attach/ready 是独立 Relay 线格式，不把控制帧 parser 限定的类型伪装成 control。
            data.send(relayFrame("relay.attach", obj("connectionId" to id, "deviceId" to credential.identity.deviceId.value,
                "peerDeviceId" to peer.device.deviceId, "ticket" to ticket)))
            withTimeout(15_000) {
                val readyFrame = data.frames.receive()
                if (readyFrame.type != "relay.ready" || readyFrame.payload.requiredText("connectionId") != id ||
                    assignment.fields.firstOrNull { it.first == "relayEpoch" }?.second?.let { expected -> readyFrame.payload.value("epoch") != expected } == true) {
                    throw RemoteRpcException("RELAY_BINDING_MISMATCH")
                }
            }
            worker.launch {
                try { for (incoming in data.frames) when (incoming.type) {
                    "relay" -> receiveRelay(incoming.payload)
                    "secure.handshake" -> receiveHandshake(incoming)
                    "ping" -> data.send(relayFrame("pong", incoming.payload))
                    "relay.detach" -> fail("TRANSPORT_CLOSED")
                    else -> throw RemoteRpcException("INVALID_MESSAGE")
                } } catch (_: Exception) { fail("TRANSPORT_CLOSED") }
            }
        }
        private fun relayFrame(type: String, payload: JsonValue.JObject): RemoteControlFrame = RemoteControlCodec.decodeRelayData(
            com.openandroidintelligence.gateway.schema.Json.canonical(obj("v" to 1, "id" to UUID.randomUUID().toString(), "type" to type,
                "timestamp" to System.currentTimeMillis(), "payload" to payload)).encodeToByteArray(), limits)
        fun sendHandshake() { sendControlFrame(secure.writeHandshakeFrame(UUID.randomUUID().toString(), System.currentTimeMillis())) }
        private fun sendControlFrame(frame: RemoteControlFrame) { requireCurrent(); socket?.send(frame) ?: throw RemoteRpcException("TRANSPORT_CLOSED") }
        suspend fun receiveHandshake(frame: RemoteControlFrame) = handshake.withLock {
            withTimeout(20_000) { carrierReady.await() }; requireCurrent()
            if (terminal != null || secure.complete) throw RemoteRpcException("SECURE_CHANNEL_FAILED")
            secure.readHandshakeFrame(frame)
            if (identityScope.role == RemoteRole.HOST) {
                val reply = secure.writeHandshakeFrame(UUID.randomUUID().toString(), System.currentTimeMillis())
                publishRpc(); sendControlFrame(reply)
            } else publishRpc()
        }
        private fun publishRpc() {
            onAuthenticatedPeer(AuthenticatedRemotePeer(connectionScope, fingerprint(credential.identity.publicKey), fingerprint(peer.publicKey), identityScope.role))
            val session = RemoteRpcSession(connectionScope, emptySet(), this, worker, hostHandler)
            rpc = session
            if (identityScope.role == RemoteRole.CLIENT) clientResult.complete(session)
        }
        fun receiveRelay(payload: JsonValue.JObject) {
            if (payload.requiredText("connectionId") != id || payload.requiredText("targetDeviceId") != credential.identity.deviceId.value || selected != "relay") {
                throw RemoteRpcException("RELAY_BINDING_MISMATCH")
            }
            if (payload.longValue("counter") != incomingCounter++) throw RemoteRpcException("RELAY_COUNTER_INVALID")
            receiveCiphertext(RemoteBase64Url.decode(payload.requiredText("ciphertext")))
        }
        private fun receiveCiphertext(bytes: ByteArray) {
            requireCurrent(); if (terminal != null || !secure.complete) { fail("SECURE_CHANNEL_FAILED"); return }
            val plain = try { secure.decryptMessage(bytes) } catch (_: Exception) { fail("SECURE_CHANNEL_FAILED"); return } ?: return
            val targets = synchronized(callbacksLock) { messages.toList() }
            targets.forEach { if (terminal == null) it(plain.copyOf()) }
        }
        override suspend fun send(bytes: ByteArray) = sent.withLock {
            requireCurrent(); terminal?.let { throw RemoteRpcException(it) }
            try { for (cipher in secure.encryptMessage(bytes)) {
                if (rtc != null) rtc!!.sendCiphertext(cipher)
                else {
                    val payload = obj("connectionId" to id, "targetDeviceId" to peer.device.deviceId,
                        "counter" to outgoingCounter++, "ciphertext" to RemoteBase64Url.encode(cipher))
                    if (relaySocket != null) relaySocket!!.send(relayFrame("relay", payload)) else sendControl("relay", payload)
                }
            } } catch (failure: Exception) { fail("TRANSPORT_CLOSED"); throw failure }
        }
        override fun onMessage(handler: (ByteArray) -> Unit): AutoCloseable {
            synchronized(callbacksLock) { terminal?.let { throw RemoteRpcException(it) }; messages += handler }
            return AutoCloseable { synchronized(callbacksLock) { messages -= handler } }
        }
        override fun onClose(handler: (String) -> Unit): AutoCloseable {
            synchronized(callbacksLock) { terminal?.let { handler(it); return AutoCloseable {} }; closures += handler }
            return AutoCloseable { synchronized(callbacksLock) { closures -= handler } }
        }
        override suspend fun close() { fail("CLIENT_CLOSED") }
        fun fail(code: String) {
            val callbacks = synchronized(callbacksLock) {
                if (terminal != null) return
                terminal = code; messages.clear(); closures.toList().also { closures.clear() }
            }
            tunnels.remove(id, this); onPeerClosed(connectionScope); rtcAvailable.completeExceptionally(RemoteRpcException(code)); carrierReady.completeExceptionally(RemoteRpcException(code)); secure.close(); rtcPeer?.close(); relaySocket?.close()
            callbacks.forEach { it(code) }
            if (identityScope.role == RemoteRole.CLIENT) clientResult.completeExceptionally(RemoteRpcException(code))
        }
    }
    companion object {
        private val generations = AtomicLong(0)
        private fun websocketUrl(origin: String) = origin.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws/v1/connect"
        private fun fingerprint(key: ByteArray) = "sha256:" + java.security.MessageDigest.getInstance("SHA-256").digest(key).joinToString("") { "%02x".format(it) }
    }
}
