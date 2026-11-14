package com.openandroidintelligence.gateway.remote.network

import android.content.Context
import com.openandroidintelligence.gateway.remote.protocol.RemoteConnectionBinding
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcException
import com.openandroidintelligence.gateway.remote.runtime.*
import com.openandroidintelligence.gateway.remote.transport.*
import com.openandroidintelligence.gateway.schema.JsonValue
import java.util.concurrent.atomic.AtomicBoolean
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
    private val closed = AtomicBoolean(false)
    private val factory: PeerConnectionFactory
    private val peer: PeerConnection
    private var transport: RtcDataChannelTransport? = null
    private var port: NativeDataChannelPort? = null
    init {
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
            override fun onIceCandidate(candidate: IceCandidate) { if (!closed.get()) signal("signal.ice", obj("candidate" to
                obj("candidate" to candidate.sdp, "sdpMid" to candidate.sdpMid, "sdpMLineIndex" to candidate.sdpMLineIndex))) }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) = Unit
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onDataChannel(channel: DataChannel) { adopt(channel) }
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
        }) ?: throw RemoteRpcException("RTC_UNAVAILABLE")
    }
    suspend fun initiate(): RtcDataChannelTransport {
        adopt(peer.createDataChannel(RtcDataChannelTransport.CHANNEL_LABEL, DataChannel.Init()))
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
        synchronized(pendingIce) { if (!remoteReady.isCompleted) pendingIce += candidate else peer.addIceCandidate(candidate) }
    }
    suspend fun awaitOpen() = withTimeout(15_000) { opened.await() }
    private fun flushIce() { synchronized(pendingIce) { remoteReady.complete(Unit); pendingIce.forEach(peer::addIceCandidate); pendingIce.clear() } }
    private fun adopt(channel: DataChannel) {
        if (closed.get() || transport != null || channel.label() != RtcDataChannelTransport.CHANNEL_LABEL) { channel.close(); channel.dispose(); return }
        val native = NativeDataChannelPort(channel, RtcChannelIdentity(binding, generation))
        port = native
        val carrier = RtcDataChannelTransport(native.identity, native, scope)
        transport = carrier
        scope.launch { try { carrier.awaitOpen(); opened.complete(carrier) } catch (_: Exception) { opened.completeExceptionally(RemoteRpcException("RTC_UNAVAILABLE")) } }
    }
    private suspend fun createSdp(offer: Boolean): SessionDescription = suspendCancellableCoroutine { continuation ->
        val observer = object : SdpObserver {
            override fun onCreateSuccess(value: SessionDescription) { if (continuation.isActive) continuation.resume(value) }
            override fun onCreateFailure(error: String) { if (continuation.isActive) continuation.resumeWithException(RemoteRpcException("RTC_SDP_FAILED")) }
            override fun onSetSuccess() = Unit
            override fun onSetFailure(error: String) = Unit
        }
        if (offer) peer.createOffer(observer, MediaConstraints()) else peer.createAnswer(observer, MediaConstraints())
    }
    private suspend fun setSdp(value: SessionDescription, local: Boolean): Unit = suspendCancellableCoroutine { continuation ->
        val observer = object : SdpObserver {
            override fun onSetSuccess() { if (continuation.isActive) continuation.resume(Unit) }
            override fun onSetFailure(error: String) { if (continuation.isActive) continuation.resumeWithException(RemoteRpcException("RTC_SDP_FAILED")) }
            override fun onCreateSuccess(value: SessionDescription) = Unit
            override fun onCreateFailure(error: String) = Unit
        }
        if (local) peer.setLocalDescription(observer, value) else peer.setRemoteDescription(observer, value)
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        opened.completeExceptionally(RemoteRpcException("RTC_CLOSED")); transport?.close(); port?.close(); peer.close(); peer.dispose(); factory.dispose()
    }
    companion object { private val factoryLock = Any(); private var initialized = false }
}
