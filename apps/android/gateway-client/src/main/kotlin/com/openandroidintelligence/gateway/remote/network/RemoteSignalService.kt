package com.openandroidintelligence.gateway.remote.network

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.openandroidintelligence.gateway.remote.auth.safeRoleErrorCode
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
    private val onPeerClosed: (RemoteRpcConnectionScope, String) -> Unit = { _, _ -> },
    private val onClosed: (String) -> Unit = {},
    private val onHeartbeat: () -> Unit = {},
    private val iceServers: suspend (String) -> List<RemoteIceServer> = { id -> api.iceServers(credential.tokens.accessToken, id) },
    private val rtcPeerFactory: ((RemoteConnectionBinding, Long, List<RemoteIceServer>, Boolean, (String, JsonValue.JObject) -> Unit) -> RemoteRtcPeerSession)? = null,
) {
    private val lifetime = SupervisorJob(parentScope.coroutineContext[Job])
    private val worker = CoroutineScope(parentScope.coroutineContext + lifetime)
    private val tunnels = ConcurrentHashMap<String, Tunnel>()
    private val ready = CompletableDeferred<Unit>()
    @Volatile private var target: RemoteAuthorizedPeer? = null
    @Volatile private var clientResult = CompletableDeferred<RemoteRpcChannel>()
    private val clientSelection = Mutex()
    private val clientPublication = Any()
    private val transportCreation = Any()
    @Volatile private var transportCreationStopped = false
    @Volatile private var socket: RemoteSocket? = null
    @Volatile var closeCode: String? = null
        private set
    private val closed: Boolean get() = closeCode != null
    private val cleanupDone = CompletableDeferred<Unit>()
    @Volatile private var limits = RemoteFrameLimits()
    @Volatile private var negotiated = emptySet<String>()
    @Volatile private var lastReceived = SystemClock.elapsedRealtime()
    private var heartbeatObserved = false
    private val offered = setOf("transport.lan", "transport.p2p", "transport.turn", "transport.relay", "transport.relay.attach")

    suspend fun startHost() { check(identityScope.role == RemoteRole.HOST); startControl() }
    suspend fun startClient() { check(identityScope.role == RemoteRole.CLIENT); startControl() }
    private suspend fun startControl() {
        start()
        if (withTimeoutOrNull(15_000) { ready.await() } == null) throw RemoteRpcException("HELLO_TIMEOUT")
    }
    val connected: Boolean get() = ready.isCompleted && !ready.isCancelled && !closed
    fun cancelPendingSelection() {
        if (target != null && !clientResult.isCompleted) close()
    }
    suspend fun connect(peer: RemoteAuthorizedPeer): RemoteRpcChannel = clientSelection.withLock {
        check(identityScope.role == RemoteRole.CLIENT && peer.role == RemoteRole.HOST)
        if (socket == null && !closed) start()
        withTimeout(15_000) { ready.await() }
        requireCurrent()
        tunnels.values.toList().forEach { it.fail("CONNECTION_REPLACED") }
        val result = synchronized(clientPublication) {
            if (closed) throw RemoteRpcException("CLIENT_CLOSED")
            target = peer
            CompletableDeferred<RemoteRpcChannel>().also { clientResult = it }
        }
        sendControl("connect.request", obj("hostDeviceId" to peer.device.deviceId,
            "preferredTransports" to listOf("lan", "p2p", "turn", "relay").filter { "transport.$it" in negotiated }))
        try { withTimeout(60_000) { result.await() } }
        catch (failure: Exception) {
            // accepted 不含 Host 身份；取消未完成选择后必须换控制连接，拒绝迟到回包借用新目标。
            if (!result.isCompleted) close()
            throw failure
        }
    }
    private fun start() {
        requireCurrent()
        val control = synchronized(clientPublication) {
            if (socket != null || closed) throw RemoteRpcException("CLIENT_CLOSED")
            createTransport { RemoteSocket(api.http, websocketUrl(api.origin), onFailure = ::fail).also { socket = it } }
        }
        worker.launch {
            try {
                control.awaitOpen(); requireCurrent()
                sendControl("hello", obj("role" to identityScope.role.wireValue, "deviceId" to credential.identity.deviceId.value,
                    "accessToken" to credential.tokens.accessToken, "protocols" to listOf(1), "capabilities" to offered.toList(),
                    "clientVersion" to RemoteProtocol.REMOTE_COMPATIBILITY_VERSION,
                    *if (identityScope.role == RemoteRole.HOST) arrayOf("harnessVersion" to RemoteProtocol.HARNESS_COMPATIBILITY_VERSION) else emptyArray()))
                for (frame in control.frames) {
                    lastReceived = SystemClock.elapsedRealtime(); requireCurrent()
                    try { handle(frame) }
                    catch (failure: Exception) {
                        val connectionId = frame.payload.optionalText("connectionId")
                        val tunnel = connectionId?.let(tunnels::get)
                        if (identityScope.role == RemoteRole.HOST && connectionId != null) {
                            // 已认证Client的坏隧道数据不能终止Host上其他Client；未知旧连接同样只丢弃该归属。
                            try { tunnel?.fail((failure as? RemoteRpcException)?.code ?: "INVALID_MESSAGE") }
                            catch (_: Exception) {
                                // 已完成全部资源清理；记录固定错误码，不泄露账本或业务异常正文，也不停掉其他Client。
                                android.util.Log.e("RemoteSignalService", "CONNECTION_CLEANUP_FAILED")
                            }
                        } else throw failure
                    }
                }
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
                val heartbeatInterval = payload.longValue("heartbeatIntervalMs")
                worker.launch {
                    try {
                        while (isActive && !closed) {
                            delay(heartbeatInterval)
                            if (SystemClock.elapsedRealtime() - lastReceived >= maxOf(15_000L, heartbeatInterval * 3)) {
                                fail("HEARTBEAT_TIMEOUT")
                                break
                            }
                            sendControl("ping", obj("nonce" to UUID.randomUUID().toString()))
                            onHeartbeat()
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: RemoteRpcException) { fail(failure.code) }
                    catch (_: Exception) { fail("TRANSPORT_CLOSED") }
                }
            }
            "connect.accepted" -> {
                if (identityScope.role != RemoteRole.CLIENT || !ready.isCompleted) throw RemoteRpcException("INVALID_MESSAGE")
                val tunnel = newTunnel(payload.requiredText("connectionId"), target ?: throw RemoteRpcException("CONNECTION_BINDING_MISMATCH"), payload)
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
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    val code = (failure as? RemoteRpcException)?.let { safeRoleErrorCode(it.code, "PEER_AUTHORIZATION_FAILED") }
                        ?: "PEER_AUTHORIZATION_FAILED"
                    Log.w(RemoteHostControl.TAG, "PEER_AUTHORIZATION $code")
                    // 对端只接收可采取行动的固定分类，手机的凭据错误不离开本机。
                    val rejection = when (code) {
                        "PEER_IDENTITY_MISMATCH", "MEMBERSHIP_REQUIRED" -> code
                        "DEVICE_NOT_FOUND", "PERMISSION_DENIED", "DEVICE_OWNERSHIP_REQUIRED" -> "MEMBERSHIP_REQUIRED"
                        else -> "CONNECTION_FAILED"
                    }
                    sendControl("connect.rejected", obj("connectionId" to connectionId, "code" to rejection))
                }
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
            "ping" -> { sendControl("pong", payload); observeHeartbeat() }
            "pong" -> observeHeartbeat()
            "connect.rejected", "error" -> {
                val code = payload.optionalText("code") ?: "CONNECTION_FAILED"
                val connectionId = payload.optionalText("connectionId")
                if (connectionId != null) tunnels[connectionId]?.fail(code)
                    ?: if (frame.type == "connect.rejected" && identityScope.role == RemoteRole.CLIENT && !clientResult.isCompleted) {
                        clientResult.completeExceptionally(RemoteRpcException(code))
                    } else Unit
                else if (identityScope.role == RemoteRole.CLIENT && !clientResult.isCompleted && code in setOf("HOST_OFFLINE", "MEMBERSHIP_REQUIRED", "CAPABILITY_NOT_SUPPORTED")) {
                    clientResult.completeExceptionally(RemoteRpcException(code))
                } else throw RemoteRpcException(code)
            }
            else -> throw RemoteRpcException("INVALID_MESSAGE")
        }
    }
    private fun observeHeartbeat() {
        if (identityScope.role == RemoteRole.HOST && !heartbeatObserved && connected) {
            heartbeatObserved = true
            android.util.Log.d(RemoteHostControl.TAG, "HEARTBEAT_OBSERVED")
        }
    }
    private fun requireTunnel(payload: JsonValue.JObject): Tunnel {
        if (payload.requiredText("targetDeviceId") != credential.identity.deviceId.value) throw RemoteRpcException("CONNECTION_BINDING_MISMATCH")
        return tunnels[payload.requiredText("connectionId")] ?: throw RemoteRpcException("CONNECTION_BINDING_MISMATCH")
    }
    private fun newTunnel(id: String, peer: RemoteAuthorizedPeer, assignment: JsonValue.JObject): Tunnel {
        requireCurrent()
        return synchronized(clientPublication) {
            closeCode?.let { throw RemoteRpcException(it) }
            if (id in tunnels.keys) throw RemoteRpcException("CONNECTION_REPLACED")
            val selectionResult = if (identityScope.role == RemoteRole.CLIENT) clientResult else null
            createTransport { Tunnel(id, peer, assignment, selectionResult).also { tunnels[id] = it } }
        }
    }
    private fun sendControl(type: String, payload: JsonValue.JObject) { requireCurrent(); socket?.send(frame(type, payload)) ?: throw RemoteRpcException("TRANSPORT_CLOSED") }
    private fun frame(type: String, payload: JsonValue.JObject) = RemoteControlCodec.create(type, payload, UUID.randomUUID().toString(), System.currentTimeMillis())
    fun close() = fail("CLIENT_CLOSED")
    /** 换代时先封住传输创建入口；关闭原因仍沿用 WebSocket 最先确认的终态。 */
    fun stopTransportCreation() {
        synchronized(transportCreation) { transportCreationStopped = true }
    }
    private inline fun <T> createTransport(create: () -> T): T = synchronized(transportCreation) {
        if (transportCreationStopped) throw RemoteRpcException("CLIENT_CLOSED")
        create()
    }
    suspend fun closeAndAwaitCleanup() {
        // 如果关闭已由收包线程发起，仍须等它完成旧 WS 和隧道的清理。
        try { close() } finally { cleanupDone.await() }
    }
    fun credentialRejected(code: String) = fail(code)
    private fun fail(reason: String) {
        val code = synchronized(clientPublication) {
            if (closed) return
            val firstReason = socket?.claimCloseReason(reason) ?: reason
            closeCode = firstReason
            ready.completeExceptionally(RemoteRpcException(firstReason))
            clientResult.completeExceptionally(RemoteRpcException(firstReason))
            firstReason
        }
        try {
            val closing = tunnels.values.toList()
            tunnels.clear()
            val control = socket.also { socket = null }
            closeRemoteConnectionResources(
                *closing.map { tunnel -> { tunnel.fail(code) } }.toTypedArray(),
                { control?.close(); Unit }, { lifetime.cancel() }, { onClosed(code) },
            )
        } finally {
            cleanupDone.complete(Unit)
        }
    }

    private inner class Tunnel(val id: String, val peer: RemoteAuthorizedPeer, val assignment: JsonValue.JObject,
        private val selectionResult: CompletableDeferred<RemoteRpcChannel>?,
    ) : RemoteMessageTransport {
        val binding = RemoteConnectionBinding(id, if (identityScope.role == RemoteRole.HOST) credential.identity.deviceId.value else peer.device.deviceId,
            if (identityScope.role == RemoteRole.CLIENT) credential.identity.deviceId.value else peer.device.deviceId)
        val connectionScope = RemoteRpcConnectionScope(api.origin, identityScope.accountId, binding.hostDeviceId, binding.clientDeviceId, id, generations.incrementAndGet())
        private val localPrivate = credential.identity.privateKeyCopy()
        private val secure = try { RemoteSecureChannel(NoiseIkChannel.create(if (identityScope.role == RemoteRole.CLIENT) NoiseRole.CLIENT_INITIATOR else NoiseRole.HOST_RESPONDER,
            binding, localPrivate, peer.publicKey)) } finally { localPrivate.fill(0) }
        private val sent = Mutex(); private val handshake = Mutex(); private val rtcSignals = Mutex(); private val transportSelection = Mutex(); private val callbacksLock = Any()
        private val rtcStateLock = Any()
        private val messages = linkedSetOf<(ByteArray) -> Unit>(); private val closures = linkedSetOf<(String) -> Unit>()
        private val carrierReady = CompletableDeferred<Unit>()
        private val rtcAvailable = CompletableDeferred<RemoteRtcPeerSession>()
        @Volatile private var rtcDisabled = false
        private var rtcGeneration = 0L
        private var rtcPeer: RemoteRtcPeerSession? = null
        private var rtcInitTask: Deferred<RemoteRtcPeerSession?>? = null
        private var rtc: com.openandroidintelligence.gateway.remote.transport.RtcDataChannelTransport? = null
        @Volatile private var relaySocket: RemoteSocket? = null
        private var outgoingCounter = 0L; private var incomingCounter = 0L
        @Volatile private var terminal: String? = null
        @Volatile private var selected: String? = null
        private var rpc: RemoteRpcSession? = null
        private fun isRtcGenerationActive(generation: Long): Boolean = synchronized(rtcStateLock) {
            terminal == null && selected != "relay" && !rtcDisabled && rtcGeneration == generation
        }
        private fun invalidateRtc(reason: String? = null): Pair<RemoteRtcPeerSession?, Deferred<RemoteRtcPeerSession?>?> {
            val closingPeer: RemoteRtcPeerSession?
            val cancellingInit: Deferred<RemoteRtcPeerSession?>?
            synchronized(rtcStateLock) {
                rtcDisabled = true
                rtcGeneration++
                rtc = null
                closingPeer = rtcPeer.also { rtcPeer = null }
                cancellingInit = rtcInitTask.also { rtcInitTask = null }
            }
            reason?.let { rtcAvailable.completeExceptionally(RemoteRpcException(it)) }
            return closingPeer to cancellingInit
        }
        private fun cleanupRtcAsync(closingPeer: RemoteRtcPeerSession?, cancellingInit: Deferred<RemoteRtcPeerSession?>?) {
            if (closingPeer == null && cancellingInit == null) return
            val dispatched = worker.isActive && runCatching {
                worker.launch(NonCancellable) {
                    cancellingInit?.cancel()
                    runCatching { closingPeer?.close() }
                }
            }.isSuccess
            if (!dispatched) {
                cancellingInit?.cancel()
                runCatching { closingPeer?.close() }
            }
        }
        suspend fun selectClientTransport() {
            val webRtcEnabled = negotiated.any { it in setOf("transport.lan", "transport.p2p", "transport.turn") }
            if (webRtcEnabled) {
                try {
                    val (generation, peerRtc) = ensureRtcPeer() ?: throw RemoteRpcException("RTC_UNAVAILABLE")
                    val carrier = peerRtc.initiate()
                    val transport = peerRtc.selectedTransport()
                    synchronized(rtcStateLock) {
                        requireCurrentSelection()
                        if (!isRtcGenerationActive(generation) || rtcPeer !== peerRtc) throw RemoteRpcException("RTC_CLOSED")
                        rtc = carrier
                        selected = transport
                    }
                } catch (failure: Exception) {
                    // 局部协商超时可以回退，外部取消不能再创建 Relay。
                    currentCoroutineContext().ensureActive()
                    if (failure is CancellationException && failure !is TimeoutCancellationException) throw failure
                }
            }
            val activeRtc = synchronized(rtcStateLock) { rtc }
            if (activeRtc == null) selectRelay()
            activeRtc?.let { carrier -> carrier.onMessage(::receiveCiphertext); carrier.onClose { fail(it) } }
            carrierReady.complete(Unit)
            sendControl("transport.selected", obj("connectionId" to id, "targetDeviceId" to peer.device.deviceId, "transport" to selected))
        }
        private suspend fun selectRelay() {
            synchronized(rtcStateLock) {
                requireCurrentSelection()
                selected = "relay"
            }
            // 先让旧协商失效。即使 TURN 或原生构造仍未结束，中继也可以独立就绪。
            val (closingRtc, cancellingInit) = invalidateRtc("RTC_UNAVAILABLE")
            try {
                attachRelay()
                carrierReady.complete(Unit)
            } finally {
                cleanupRtcAsync(closingRtc, cancellingInit)
            }
        }
        suspend fun selectHostTransport(transport: String) = transportSelection.withLock {
            if (selected != null) {
                // 固定 v0.6.5 Host 在已认证隧道上忽略迟到重复 transport.selected，不切换数据面也不撕毁连接。
                if (secure.complete) return@withLock
                throw RemoteRpcException("CONNECTION_REPLACED")
            }
            val preferred = (assignment.fields.firstOrNull { it.first == "preferredTransports" }?.second as? JsonValue.JArray)
                ?.items?.mapNotNull { (it as? JsonValue.JString)?.value } ?: listOf("lan", "p2p", "turn", "relay")
            if (transport !in preferred || "transport.$transport" !in negotiated) throw RemoteRpcException("TRANSPORT_NOT_NEGOTIATED")
            if (transport == "relay") {
                selectRelay()
                return@withLock
            }
            synchronized(rtcStateLock) {
                requireCurrentSelection()
                selected = transport
            }
            val peerRtc = withTimeout(15_000) { rtcAvailable.await() }
            val opened = peerRtc.awaitOpen()
            synchronized(rtcStateLock) {
                requireCurrentSelection()
                if (rtcDisabled || rtcPeer !== peerRtc) throw RemoteRpcException("RTC_CLOSED")
                rtc = opened
            }
            opened.onMessage(::receiveCiphertext); opened.onClose { fail(it) }
            carrierReady.complete(Unit)
        }
        private suspend fun createRtc(turnOnly: Boolean, expectedGeneration: Long): RemoteRtcPeerSession? {
            if (!isRtcGenerationActive(expectedGeneration)) return null
            val servers = try { iceServers(id) }
            catch (cancelled: CancellationException) {
                if (!isRtcGenerationActive(expectedGeneration)) return null
                throw cancelled
            } catch (_: Exception) { emptyList() }
            // TURN 凭据返回后再次核对代次，已切到 Relay 或失效时不再构造原生 PeerConnection。
            if (!isRtcGenerationActive(expectedGeneration)) return null
            currentCoroutineContext().ensureActive()
            requireCurrentSelection()
            val signalCallback: (String, JsonValue.JObject) -> Unit = { type, fields ->
                if (isRtcGenerationActive(expectedGeneration)) {
                    sendControl(type, obj("connectionId" to id, "targetDeviceId" to peer.device.deviceId,
                        *fields.fields.map { it.first to it.second }.toTypedArray()))
                }
            }
            fun buildPeer(): RemoteRtcPeerSession =
                rtcPeerFactory?.invoke(binding, connectionScope.generation, servers, turnOnly, signalCallback)
                    ?: RemoteRtcPeer(context, binding, connectionScope.generation, worker, servers, turnOnly, signalCallback)
            return try {
                if (identityScope.role == RemoteRole.CLIENT) createTransport(::buildPeer) else buildPeer()
            } catch (_: LinkageError) {
                // 原生库缺失或ABI不可加载属于传输不可用，必须走已协商Relay而不是留下悬挂握手。
                throw RemoteRpcException("RTC_UNAVAILABLE")
            }
        }
        private suspend fun ensureRtcPeer(): Pair<Long, RemoteRtcPeerSession>? {
            val (epoch, task) = synchronized(rtcStateLock) {
                val epoch = rtcGeneration
                if (!isRtcGenerationActive(epoch)) return null
                rtcPeer?.let { return epoch to it }
                val task = rtcInitTask ?: worker.async(start = CoroutineStart.LAZY) {
                    val created = createRtc(turnOnly = false, expectedGeneration = epoch) ?: return@async null
                    val adopted = synchronized(rtcStateLock) {
                        if (!isRtcGenerationActive(epoch)) false else {
                            rtcPeer = created
                            true
                        }
                    }
                    if (adopted) {
                        rtcAvailable.complete(created)
                        created
                    } else {
                        // 原生连接构造迟到时只回收它自身，不能挂到已接管的 Relay 上。
                        runCatching { created.close() }
                        null
                    }
                }.also { rtcInitTask = it }
                epoch to task
            }
            val resolved = try {
                task.await()
            } catch (cancelled: CancellationException) {
                if (!isRtcGenerationActive(epoch)) return null
                throw cancelled
            } ?: return null
            return if (isRtcGenerationActive(epoch)) epoch to resolved else null
        }
        suspend fun signal(type: String, payload: JsonValue.JObject) {
            val initialGeneration = synchronized(rtcStateLock) {
                if (terminal != null || selected == "relay" || rtcDisabled) return
                rtcGeneration
            }
            try {
                val (epoch, current) = when (type) {
                    // Offer 和 ICE 各自异步到达；共享单次 RTC 初始化且不持锁等待 TURN 凭据网络请求。
                    "signal.offer", "signal.ice" -> ensureRtcPeer() ?: return
                    "signal.answer" -> synchronized(rtcStateLock) {
                        if (terminal != null || selected == "relay" || rtcDisabled) return
                        val peerRtc = rtcPeer ?: throw RemoteRpcException("RTC_UNAVAILABLE")
                        rtcGeneration to peerRtc
                    }
                    else -> return
                }
                rtcSignals.withLock {
                    if (!isRtcGenerationActive(epoch)) return@withLock
                    when (type) {
                        "signal.offer" -> current.acceptOffer(payload.requiredText("sdp"))
                        "signal.answer" -> current.acceptAnswer(payload.requiredText("sdp"))
                        "signal.ice" -> current.acceptIce(payload.value("candidate").objectValue())
                    }
                }
            } catch (cancelled: CancellationException) {
                if (!isRtcGenerationActive(initialGeneration)) return
                throw cancelled
            } catch (failure: RemoteRpcException) {
                if (!isRtcGenerationActive(initialGeneration)) return
                if (identityScope.role != RemoteRole.HOST || selected != null || failure.code !in setOf("RTC_UNAVAILABLE", "RTC_SDP_FAILED", "RTC_CLOSED")) throw failure
                // Host本地RTC不可用只结束该候选；等待Client选择合法Relay，不伪造上游未定义的signal.error。
                val (closingRtc, cancellingInit) = invalidateRtc("RTC_UNAVAILABLE")
                cleanupRtcAsync(closingRtc, cancellingInit)
            } catch (failure: Exception) {
                if (!isRtcGenerationActive(initialGeneration)) return
                throw failure
            }
        }
        private suspend fun attachRelay() {
            val endpoint = assignment.optionalText("relayEndpoint") ?: return
            val ticket = assignment.requiredText("relayTicket")
            val uri = URI(endpoint)
            if (uri.scheme !in setOf("wss", "ws") || uri.host == null || uri.userInfo != null || uri.fragment != null || uri.query != null ||
                api.origin.startsWith("https:") && uri.scheme != "wss") throw RemoteRpcException("RELAY_ENDPOINT_INVALID")
            currentCoroutineContext().ensureActive()
            val data = synchronized(callbacksLock) {
                requireCurrentSelection()
                createTransport {
                    RemoteSocket(api.http, endpoint, relayData = true, onFailure = ::fail).also {
                        it.limits = limits
                        relaySocket = it
                    }
                }
            }
            data.awaitOpen()
            requireCurrentSelection()
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
            if (terminal != null) throw RemoteRpcException("SECURE_CHANNEL_FAILED")
            if (secure.complete) {
                // 固定 v0.6.5 Host 只忽略同隧道的重复首帧，不重跑 Noise 或认证。
                if (identityScope.role == RemoteRole.HOST &&
                    frame.payload.requiredText("connectionId") == id &&
                    frame.payload.requiredText("targetDeviceId") == credential.identity.deviceId.value &&
                    frame.payload.longValue("step") == 1L) return@withLock
                throw RemoteRpcException("SECURE_CHANNEL_FAILED")
            }
            secure.readHandshakeFrame(frame)
            if (identityScope.role == RemoteRole.HOST) {
                val reply = secure.writeHandshakeFrame(UUID.randomUUID().toString(), System.currentTimeMillis())
                publishRpc(); sendControlFrame(reply)
            } else publishRpc()
        }
        private fun publishRpc() {
            try {
                synchronized(clientPublication) { requireCurrentSelection() }
                onAuthenticatedPeer(AuthenticatedRemotePeer(connectionScope, fingerprint(credential.identity.publicKey), fingerprint(peer.publicKey), identityScope.role))
                if (identityScope.role == RemoteRole.HOST) Log.d(RemoteHostControl.TAG, "PEER_AUTHENTICATED")
                val session = RemoteRpcSession(connectionScope, emptySet(), this, worker, hostHandler, ::fail)
                // 选择替换与发布共用锁；对外认证回调留在锁外，回调返回后再次核对归属。
                synchronized(clientPublication) {
                    synchronized(callbacksLock) {
                        requireCurrentSelection()
                        rpc = session
                        selectionResult?.complete(session)
                    }
                }
            } catch (failure: Exception) {
                onPeerClosed(connectionScope, (failure as? RemoteRpcException)?.code ?: "CONNECTION_FAILED")
                throw failure
            }
        }
        private fun requireCurrentSelection() {
            terminal?.let { throw RemoteRpcException(it) }
            if (transportCreationStopped) throw RemoteRpcException("CLIENT_CLOSED")
            if (closed || selectionResult != null && selectionResult !== clientResult)
                throw RemoteRpcException("CONNECTION_REPLACED")
            // 强刷先撤下当前控制代际，旧服务即使尚未关闭也不能新建传输。
            requireCurrent()
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
                val carrier = synchronized(rtcStateLock) { rtc }
                if (carrier != null) carrier.sendCiphertext(cipher)
                else {
                    val payload = obj("connectionId" to id, "targetDeviceId" to peer.device.deviceId,
                        "counter" to outgoingCounter++, "ciphertext" to RemoteBase64Url.encode(cipher))
                    val data = relaySocket
                    if (data != null) data.send(relayFrame("relay", payload)) else sendControl("relay", payload)
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
            val closingRelay: RemoteSocket?
            val callbacks = synchronized(callbacksLock) {
                if (terminal != null) return
                terminal = code
                closingRelay = relaySocket
                messages.clear()
                closures.toList().also { closures.clear() }
            }
            val (closingRtc, cancellingInit) = invalidateRtc(code)
            tunnels.remove(id, this)
            if (identityScope.role == RemoteRole.HOST && code != "CLIENT_CLOSED") {
                Log.d(RemoteHostControl.TAG, "PEER_DISCONNECTED ${safeRoleErrorCode(code, "PEER_CONNECTION_CLOSED")}")
            }
            closeRemoteConnectionResources(
                { onPeerClosed(connectionScope, code) },
                { cancellingInit?.cancel(); Unit },
                { carrierReady.completeExceptionally(RemoteRpcException(code)); Unit },
                { secure.close() }, { closingRtc?.close(); Unit }, { closingRelay?.close(); Unit },
                *callbacks.map { callback -> { callback(code) } }.toTypedArray(),
                // 清理只完成创建本隧道时绑定的结果，不能读取已经换代的外层选择。
                { selectionResult?.completeExceptionally(RemoteRpcException(code)); Unit },
            )
        }
    }
    companion object {
        private val generations = AtomicLong(0)
        private fun websocketUrl(origin: String) = origin.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/ws/v1/connect"
        private fun fingerprint(key: ByteArray) = "sha256:" + java.security.MessageDigest.getInstance("SHA-256").digest(key).joinToString("") { "%02x".format(it) }
    }
}
