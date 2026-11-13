package com.openandroidintelligence.gateway.remote.transport

import org.webrtc.DataChannel
import org.webrtc.PeerConnection
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** 原生回调内复制并串行交付，不再积累一份无界接收队列；native 销毁交给独立清理执行器。 */
class NativeDataChannelPort internal constructor(
    private val handle: NativeDataChannelHandle,
    override val identity: RtcChannelIdentity,
    private val callbacks: Executor = newCallbackExecutor(),
) : RtcDataChannelPort {
    private val nativeLock = Any()
    private val callbackLock = Any()
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
        listener.set(observer)
        // 取消订阅只切断本地交付；native observer 在 close 的异步清理中注销。
        if (observer == null) return
        synchronized(nativeLock) {
            if (closed.get()) {
                listener.set(null)
                fail("RTC_CLOSED")
            }
            handle.observe(object : NativeDataChannelHandle.Observer {
                override fun onStateChanged() {
                    if (closed.get()) return
                    val current = try { handle.state } catch (_: Exception) { RtcChannelState.CLOSED }
                    dispatch { it.onStateChanged(identity, current) }
                }

                override fun onMessage(data: ByteBuffer, binary: Boolean) {
                    if (closed.get()) return
                    // 原生文档明确：回调退出后 buffer 内存释放，不能把原 buffer 交给异步代码。
                    val view = data.asReadOnlyBuffer()
                    if (view.remaining() > RtcChunkCodec.MAX_MESSAGE_BYTES) { close(); return }
                    // 上游接受 ArrayBuffer 或 JS string；原生 text 先验证 UTF-8，合法编码即其 TextEncoder 结果。
                    if (!binary) try {
                        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(view.asReadOnlyBuffer())
                    } catch (_: Exception) { close(); return }
                    val bytes = ByteArray(view.remaining())
                    view.get(bytes)
                    dispatch { it.onMessage(identity, bytes) }
                }

                override fun onBufferedAmountChanged() {
                    dispatch { it.onBufferedAmountChanged(identity) }
                }
            })
        }
    }

    override fun sendBinary(bytes: ByteArray): Boolean = synchronized(nativeLock) {
        if (closed.get() || handle.state != RtcChannelState.OPEN) return false
        try { handle.send(ByteBuffer.wrap(bytes)) } catch (_: Exception) { false }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        listener.set(null)
        callbacks.execute {
            synchronized(nativeLock) {
                try { handle.observe(null) } finally {
                    try { handle.close() } finally {
                        try { handle.dispose() } finally { (callbacks as? ExecutorService)?.shutdown() }
                    }
                }
            }
        }
    }

    private fun dispatch(action: (RtcDataChannelPort.Observer) -> Unit) {
        if (closed.get()) return
        try {
            synchronized(callbackLock) {
                if (!closed.get()) listener.get()?.let(action)
            }
        } catch (_: Exception) {
            close()
        }
    }

    companion object {
        private fun newCallbackExecutor(): ExecutorService = Executors.newSingleThreadExecutor { task ->
            Thread(task, "oai-remote-datachannel").apply { isDaemon = true }
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
        fun onMessage(data: ByteBuffer, binary: Boolean)
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
            override fun onMessage(buffer: DataChannel.Buffer) { observer.onMessage(buffer.data, buffer.binary) }
            })
            observing = true
        }
    }
    override fun send(data: ByteBuffer): Boolean = channel.send(DataChannel.Buffer(data, true))
    override fun close() { channel.close() }
    override fun dispose() { channel.dispose() }
}
