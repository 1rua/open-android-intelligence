package com.openandroidintelligence.gateway.remote.transport

import org.webrtc.DataChannel
import org.webrtc.PeerConnection
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** 原生 JNI 的内存与生命周期适配；回调返回前复制收到的 ByteBuffer。 */
class NativeDataChannelPort internal constructor(
    private val handle: NativeDataChannelHandle,
    override val identity: RtcChannelIdentity,
) : RtcDataChannelPort {
    private val nativeLock = Any()
    private val closed = AtomicBoolean(false)
    private val listener = AtomicReference<RtcDataChannelPort.Observer?>(null)
    override val label: String = handle.label

    constructor(channel: DataChannel, identity: RtcChannelIdentity) : this(OrgWebRtcChannelHandle(channel), identity)

    override val state: RtcChannelState get() = synchronized(nativeLock) {
        if (closed.get()) RtcChannelState.CLOSED else handle.state
    }

    override val bufferedAmount: Long get() = synchronized(nativeLock) {
        if (closed.get()) 0 else handle.bufferedAmount
    }

    override fun observe(observer: RtcDataChannelPort.Observer?) {
        synchronized(nativeLock) {
            if (closed.get()) {
                if (observer != null) fail("RTC_CLOSED")
                return
            }
            listener.set(observer)
            if (observer == null) handle.observe(null)
            else handle.observe(object : NativeDataChannelHandle.Observer {
                override fun onStateChanged() {
                    if (closed.get()) return
                    val current = try { handle.state } catch (_: Exception) { RtcChannelState.CLOSED }
                    listener.get()?.onStateChanged(identity, current)
                }

                override fun onMessage(data: ByteBuffer) {
                    if (closed.get()) return
                    // 原生文档明确：回调退出后 buffer 内存释放，不能把原 buffer 交给异步代码。
                    val view = data.asReadOnlyBuffer()
                    val bytes = ByteArray(view.remaining())
                    view.get(bytes)
                    if (!closed.get()) listener.get()?.onMessage(identity, bytes)
                }

                override fun onBufferedAmountChanged() {
                    if (!closed.get()) listener.get()?.onBufferedAmountChanged(identity)
                }
            })
        }
    }

    override fun sendBinary(bytes: ByteArray): Boolean = synchronized(nativeLock) {
        if (closed.get() || handle.state != RtcChannelState.OPEN) return false
        try { handle.send(ByteBuffer.wrap(bytes)) } catch (_: Exception) { false }
    }

    override fun close() {
        synchronized(nativeLock) {
            if (!closed.compareAndSet(false, true)) return
            listener.set(null)
            try { handle.observe(null) } finally {
                try { handle.close() } finally { handle.dispose() }
            }
        }
    }
}

/** 只创建/接收 dsh 通道，调用方仍负责初始化 PeerConnectionFactory、信令与 PeerConnection 清理。 */
object NativeDataChannelFactory {
    fun createOutgoing(peerConnection: PeerConnection, identity: RtcChannelIdentity): NativeDataChannelPort {
        val init = outgoingConfiguration()
        val channel = peerConnection.createDataChannel(RtcDataChannelTransport.CHANNEL_LABEL, init) ?: fail("RTC_CHANNEL_CREATE_FAILED")
        return adoptIncoming(channel, identity)
    }

    fun adoptIncoming(channel: DataChannel, identity: RtcChannelIdentity): NativeDataChannelPort {
        val port = NativeDataChannelPort(channel, identity)
        if (port.label != RtcDataChannelTransport.CHANNEL_LABEL) {
            port.close()
            fail("RTC_CHANNEL_LABEL_INVALID")
        }
        // Android DataChannel 没有 ordered/reliable getter；不能声称核对了远端 Init。
        return port
    }

    internal fun outgoingConfiguration(): DataChannel.Init = DataChannel.Init().apply {
        ordered = true
        maxRetransmits = -1
        maxRetransmitTimeMs = -1
    }
}

/** 仅隔离 JNI 以便 JVM 证明拷贝、失败和关闭语义，不代替原生加载验收。 */
internal interface NativeDataChannelHandle {
    val label: String
    val state: RtcChannelState
    val bufferedAmount: Long
    fun observe(observer: Observer?)
    fun send(data: ByteBuffer): Boolean
    fun close()
    fun dispose()

    interface Observer {
        fun onStateChanged()
        fun onMessage(data: ByteBuffer)
        fun onBufferedAmountChanged()
    }
}

private class OrgWebRtcChannelHandle(private val channel: DataChannel) : NativeDataChannelHandle {
    private var observing = false
    override val label: String get() = channel.label()
    override val state: RtcChannelState get() = when (channel.state()) {
        DataChannel.State.CONNECTING -> RtcChannelState.CONNECTING
        DataChannel.State.OPEN -> RtcChannelState.OPEN
        DataChannel.State.CLOSING -> RtcChannelState.CLOSING
        DataChannel.State.CLOSED -> RtcChannelState.CLOSED
    }
    override val bufferedAmount: Long get() = channel.bufferedAmount()
    override fun observe(observer: NativeDataChannelHandle.Observer?) {
        if (observer == null) {
            if (observing) channel.unregisterObserver()
            observing = false
        } else {
            channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) { observer.onBufferedAmountChanged() }
            override fun onStateChange() { observer.onStateChanged() }
            override fun onMessage(buffer: DataChannel.Buffer) { observer.onMessage(buffer.data) }
            })
            observing = true
        }
    }
    override fun send(data: ByteBuffer): Boolean = channel.send(DataChannel.Buffer(data, true))
    override fun close() { channel.close() }
    override fun dispose() { channel.dispose() }
}
