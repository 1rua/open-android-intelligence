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

/** 一条隧道拥有一个 PeerConnection；只传输 Noise 密文，不将 SDP 就绪当作认证。 */
internal class RemoteRtcPeer(
    context: Context,
    private val binding: RemoteConnectionBinding,
    private val generation: Long,
    private val scope: CoroutineScope,
    iceServers: List<RemoteIceServer>,
    turnOnly: Boolean,
    private val signal: (String, JsonValue.JObject) -> Unit,
) : AutoCloseable {
    private val opened = CompletableDeferred<RtcDataChannelTransport>()
    private val remoteReady = CompletableDeferred<Unit>()
    private val pendingIce = mutableListOf<IceCandidate>()
    private val nativeReady = java.util.concurrent.CountDownLatch(1)
    private val boundary = RemoteRtcCallbackBoundary(
        cleanup = { nativeReady.await(); try { port?.close() } finally {
            try { if (::peer.isInitialized) peer.close() } finally {
                try { if (::peer.isInitialized) peer.dispose() } finally { if (::factory.isInitialized) factory.dispose() }
            }
        } },
        onClosing = { opened.completeExceptionally(RemoteRpcException("RTC_CLOSED")); transport?.close() },
    )
    private lateinit var factory: PeerConnectionFactory
    private lateinit var peer: PeerConnection
    private var transport: RtcDataChannelTransport? = null
    private var port: NativeDataChannelPort? = null
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
                if (!boundary.callback { adopt(channel) }) disposeChannel(channel)
            }
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
        }) ?: throw RemoteRpcException("RTC_UNAVAILABLE")
        } catch (failure: Exception) { boundary.close(); throw failure }
        finally { nativeReady.countDown() }
    }
    suspend fun initiate(): RtcDataChannelTransport {
        boundary.native {
            adopt(peer.createDataChannel(RtcDataChannelTransport.CHANNEL_LABEL, DataChannel.Init())
                ?: throw RemoteRpcException("RTC_CHANNEL_CREATE_FAILED"))
        }
        val offer = createSdp(offer = true); setSdp(offer, local = true)
        signal("signal.offer", obj("sdp" to offer.description))
        return withTimeout(15_000) { opened.await() }
    }
    suspend fun acceptOffer(sdp: String) {
        setSdp(SessionDescription(SessionDescription.Type.OFFER, sdp), local = false)
        flushIce(); val answer = createSdp(offer = false); setSdp(answer, local = true)
        signal("signal.answer", obj("sdp" to answer.description))
    }
    suspend fun acceptAnswer(sdp: String) { setSdp(SessionDescription(SessionDescription.Type.ANSWER, sdp), local = false); flushIce() }
    fun acceptIce(value: JsonValue.JObject) {
        val candidate = IceCandidate(value.optionalText("sdpMid"), (value.fields.firstOrNull { it.first == "sdpMLineIndex" }?.second as? JsonValue.JNumber)?.raw?.toIntOrNull() ?: 0,
            value.optionalText("candidate") ?: "")
        boundary.native { synchronized(pendingIce) { if (!remoteReady.isCompleted) pendingIce += candidate else peer.addIceCandidate(candidate) } }
    }
    suspend fun selectedTransport(): String = withTimeout(5_000) { suspendCancellableCoroutine { continuation ->
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
    suspend fun awaitOpen() = withTimeout(15_000) { opened.await() }
    private fun flushIce() { boundary.native { synchronized(pendingIce) { remoteReady.complete(Unit); pendingIce.forEach(peer::addIceCandidate); pendingIce.clear() } } }
    private fun adopt(channel: DataChannel) {
        if (boundary.isClosed || transport != null || channel.label() != RtcDataChannelTransport.CHANNEL_LABEL) {
            // 拒绝的 DataChannel 同样不能在原生 observer 栈中同步 dispose。
            disposeChannel(channel)
            return
        }
        val native = NativeDataChannelPort(channel, RtcChannelIdentity(binding, generation))
        port = native
        val carrier = RtcDataChannelTransport(native.identity, native, scope)
        transport = carrier
        scope.launch { try { carrier.awaitOpen(); opened.complete(carrier) } catch (_: Exception) { opened.completeExceptionally(RemoteRpcException("RTC_UNAVAILABLE")) } }
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
    companion object { private val factoryLock = Any(); private var initialized = false }
}
