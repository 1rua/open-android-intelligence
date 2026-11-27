package com.openandroidintelligence.gateway.remote.network

import android.content.Context
import com.openandroidintelligence.gateway.remote.protocol.RemoteConnectionBinding
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcException
import com.openandroidintelligence.gateway.remote.runtime.*
import com.openandroidintelligence.gateway.remote.transport.*
import com.openandroidintelligence.gateway.schema.JsonValue
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import org.webrtc.*

/** 隧道持有的单条 WebRTC 协商会话契约；生产实现由 [RemoteRtcPeer] 提供。 */
internal interface RemoteRtcPeerSession : AutoCloseable {
    suspend fun initiate(): RtcDataChannelTransport
    suspend fun acceptOffer(sdp: String)
    suspend fun acceptAnswer(sdp: String)
    fun acceptIce(value: JsonValue.JObject)
    suspend fun selectedTransport(): String
    suspend fun awaitOpen(): RtcDataChannelTransport
}

/** 一条隧道拥有一个 PeerConnection；只传输 Noise 密文，不将 SDP 就绪当作认证。 */
internal class RemoteRtcPeer(
    context: Context,
    private val binding: RemoteConnectionBinding,
    private val generation: Long,
    private val scope: CoroutineScope,
    iceServers: List<RemoteIceServer>,
    turnOnly: Boolean,
    private val signal: (String, JsonValue.JObject) -> Unit,
) : RemoteRtcPeerSession {
    private val opened = CompletableDeferred<RtcDataChannelTransport>()
    private val pendingIce = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false
    private val channelLock = Any()
    private var channelClaimed = false
    private var transport: RtcDataChannelTransport? = null
    private val nativeReady = java.util.concurrent.CountDownLatch(1)
    private val boundary = RemoteRtcCallbackBoundary(
        cleanup = {
            nativeReady.await()
            try { if (::peer.isInitialized) peer.close() } finally {
                try { if (::peer.isInitialized) peer.dispose() } finally { if (::factory.isInitialized) factory.dispose() }
            }
        },
        onClosing = {
            opened.completeExceptionally(RemoteRpcException("RTC_CLOSED"))
            val owned = synchronized(channelLock) { transport }
            owned?.close()
        },
    )
    private lateinit var factory: PeerConnectionFactory
    private lateinit var peer: PeerConnection
    init {
        try {
        synchronized(factoryLock) {
            if (!initialized) {
                PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions())
                initialized = true
            }
        }
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
        val config = PeerConnection.RTCConfiguration(iceServers.map { row ->
            PeerConnection.IceServer.builder(row.urls).apply { row.username?.let(::setUsername); row.credential?.let(::setPassword) }.createIceServer()
        }).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            iceTransportsType = if (turnOnly) PeerConnection.IceTransportsType.RELAY else PeerConnection.IceTransportsType.ALL
        }
        peer = factory.createPeerConnection(config, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                if (state in setOf(PeerConnection.IceConnectionState.FAILED, PeerConnection.IceConnectionState.CLOSED)) close()
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidate(candidate: IceCandidate) { boundary.callback {
                signal("signal.ice", obj("candidate" to obj("candidate" to candidate.sdp,
                    "sdpMid" to candidate.sdpMid, "sdpMLineIndex" to candidate.sdpMLineIndex)))
            } }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(channel: DataChannel) {
                // 回调只移交句柄，在应用线程注册观察者以避免重入 SDK。
                if (!boundary.dispatchCallback(incomingChannelExecutor) { adopt(channel) }) disposeChannel(channel)
            }
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
        }) ?: throw RemoteRpcException("RTC_UNAVAILABLE")
        } catch (failure: Exception) { boundary.close(); throw failure }
        finally { nativeReady.countDown() }
    }
    override suspend fun initiate(): RtcDataChannelTransport {
        boundary.native {
            adopt(peer.createDataChannel(RtcDataChannelTransport.CHANNEL_LABEL, DataChannel.Init())
                ?: throw RemoteRpcException("RTC_CHANNEL_CREATE_FAILED"))
        }
        val offer = createSdp(offer = true); setSdp(offer, local = true)
        signal("signal.offer", obj("sdp" to offer.description))
        return awaitOpen()
    }
    override suspend fun acceptOffer(sdp: String) {
        setSdp(SessionDescription(SessionDescription.Type.OFFER, sdp), local = false)
        flushIce(); val answer = createSdp(offer = false); setSdp(answer, local = true)
        signal("signal.answer", obj("sdp" to answer.description))
    }
    override suspend fun acceptAnswer(sdp: String) { setSdp(SessionDescription(SessionDescription.Type.ANSWER, sdp), local = false); flushIce() }
    override fun acceptIce(value: JsonValue.JObject) {
        val candidate = IceCandidate(value.optionalText("sdpMid"), (value.fields.firstOrNull { it.first == "sdpMLineIndex" }?.second as? JsonValue.JNumber)?.raw?.toIntOrNull() ?: 0,
            value.optionalText("candidate") ?: "")
        synchronized(pendingIce) {
            if (!remoteDescriptionSet) {
                pendingIce += candidate
                return
            }
        }
        boundary.native { peer.addIceCandidate(candidate) }
    }
    override suspend fun selectedTransport(): String = withTimeout(5_000) { suspendCancellableCoroutine { continuation ->
        boundary.native { peer.getStats { report ->
            if (boundary.isClosed) {
                if (continuation.isActive) continuation.resumeWithException(RemoteRpcException("RTC_CLOSED"))
                return@getStats
            }
            val stats = report.statsMap.values
            val selectedId = stats.firstOrNull { it.type == "transport" }?.members?.get("selectedCandidatePairId") as? String
            val pair = selectedId?.let(report.statsMap::get) ?: stats.firstOrNull {
                it.type == "candidate-pair" && it.members["state"] == "succeeded" && it.members["nominated"] == true
            }
            if (pair == null) {
                if (continuation.isActive) continuation.resumeWithException(RemoteRpcException("RTC_SELECTED_PAIR_UNAVAILABLE"))
                return@getStats
            }
            val local = (pair.members?.get("localCandidateId") as? String)?.let(report.statsMap::get)
            val remote = (pair.members?.get("remoteCandidateId") as? String)?.let(report.statsMap::get)
            val selected = if (local?.members?.get("candidateType") == "relay" || remote?.members?.get("candidateType") == "relay") "turn"
                else if (local?.members?.get("candidateType") == "host" && remote?.members?.get("candidateType") == "host") "lan" else "p2p"
            if (continuation.isActive) continuation.resume(selected)
        } }
    } }
    override suspend fun awaitOpen(): RtcDataChannelTransport = withTimeout(15_000) {
        val carrier = opened.await()
        if (boundary.isClosed) throw RemoteRpcException("RTC_CLOSED")
        carrier
    }

    private fun flushIce() {
        val buffered = synchronized(pendingIce) {
            remoteDescriptionSet = true
            pendingIce.toList().also { pendingIce.clear() }
        }
        boundary.native { buffered.forEach(peer::addIceCandidate) }
    }

    private fun adopt(channel: DataChannel) {
        if (boundary.isClosed) {
            disposeChannel(channel)
            return
        }
        val native = try { NativeDataChannelPort(channel, RtcChannelIdentity(binding, generation)) }
        catch (failure: Exception) { disposeChannel(channel); throw failure }
        val claimed = synchronized(channelLock) {
            if (boundary.isClosed || channelClaimed || native.label != RtcDataChannelTransport.CHANNEL_LABEL) {
                false
            } else {
                channelClaimed = true
                true
            }
        }
        if (!claimed) {
            native.close()
            return
        }
        val carrier = try { RtcDataChannelTransport(native.identity, native, scope) }
        catch (failure: Exception) { native.close(); throw failure }
        val published = synchronized(channelLock) {
            if (boundary.isClosed) false else {
                transport = carrier
                true
            }
        }
        if (!published) {
            carrier.close()
            return
        }
        scope.launch {
            try {
                carrier.awaitOpen()
                if (!boundary.isClosed) opened.complete(carrier)
            } catch (cancelled: CancellationException) {
                close()
                throw cancelled
            } catch (_: Exception) {
                opened.completeExceptionally(RemoteRpcException("RTC_UNAVAILABLE"))
            }
        }
    }

    private suspend fun createSdp(offer: Boolean): SessionDescription = suspendCancellableCoroutine { continuation ->
        val observer = object : SdpObserver {
            override fun onCreateSuccess(value: SessionDescription) {
                if (continuation.isActive) {
                    if (boundary.isClosed) continuation.resumeWithException(RemoteRpcException("RTC_CLOSED")) else continuation.resume(value)
                }
            }
            override fun onCreateFailure(error: String) { if (continuation.isActive) continuation.resumeWithException(RemoteRpcException("RTC_SDP_FAILED")) }
            override fun onSetSuccess() = Unit
            override fun onSetFailure(error: String) = Unit
        }
        boundary.native { if (offer) peer.createOffer(observer, MediaConstraints()) else peer.createAnswer(observer, MediaConstraints()) }
    }
    private suspend fun setSdp(value: SessionDescription, local: Boolean): Unit = suspendCancellableCoroutine { continuation ->
        val observer = object : SdpObserver {
            override fun onSetSuccess() {
                if (continuation.isActive) {
                    if (boundary.isClosed) continuation.resumeWithException(RemoteRpcException("RTC_CLOSED")) else continuation.resume(Unit)
                }
            }
            override fun onSetFailure(error: String) { if (continuation.isActive) continuation.resumeWithException(RemoteRpcException("RTC_SDP_FAILED")) }
            override fun onCreateSuccess(value: SessionDescription) = Unit
            override fun onCreateFailure(error: String) = Unit
        }
        boundary.native { if (local) peer.setLocalDescription(observer, value) else peer.setRemoteDescription(observer, value) }
    }
    override fun close() {
        boundary.close()
    }
    private fun disposeChannel(channel: DataChannel) {
        RemoteRtcCallbackBoundary(cleanup = { try { channel.close() } finally { channel.dispose() } }, onClosing = {}).close()
    }
    companion object {
        private val factoryLock = Any()
        private var initialized = false
        private val incomingChannelExecutor = Dispatchers.IO.asExecutor()
    }
}
