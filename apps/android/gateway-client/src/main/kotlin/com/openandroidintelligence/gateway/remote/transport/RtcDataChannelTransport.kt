package com.openandroidintelligence.gateway.remote.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * 单条连接的 RTCH 数据载体，不负责信令、ICE 或选路，也不把 DataChannel 打开等同于 Noise 认证。
 * RTC 发送监测按上游默认关闭；只有平台确认 bufferedAmount 可靠时才由协商层显式传入时长。
 */
class RtcDataChannelTransport(
    val identity: RtcChannelIdentity,
    private val port: RtcDataChannelPort,
    parentScope: CoroutineScope,
    private val sendTimeoutMillis: Long? = null,
    clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) : AutoCloseable {
    private val lock = Any()
    private val sends = SerialSend()
    private val incoming = RtcChunkCodec(clockMillis)
    private val outgoing = RtcChunkCodec(clockMillis)
    private val opened = CompletableDeferred<Unit>()
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val messageHandlers = linkedSetOf<(ByteArray) -> Unit>()
    private val closeHandlers = linkedSetOf<(String) -> Unit>()
    private var watchdog: Job? = null
    private var closed = false
    private var currentState = RtcChannelState.CONNECTING
    private var closeCode: String? = null
    private var sentBytes = 0L
    private var receivedBytes = 0L

    private val observer = object : RtcDataChannelPort.Observer {
        override fun onStateChanged(identity: RtcChannelIdentity, state: RtcChannelState) {
            if (identity != this@RtcDataChannelTransport.identity) return
            if (state == RtcChannelState.CLOSING || state == RtcChannelState.CLOSED) closeWithCode("RTC_CLOSED")
            else synchronized(lock) {
                if (closed) return
                currentState = state
                if (state == RtcChannelState.OPEN) opened.complete(Unit)
            }
        }

        override fun onMessage(identity: RtcChannelIdentity, bytes: ByteArray) {
            if (identity != this@RtcDataChannelTransport.identity) return
            val delivery = try {
                synchronized(lock) {
                    if (closed || currentState != RtcChannelState.OPEN) return
                    receivedBytes += bytes.size
                    val message = incoming.decode(bytes) ?: return
                    message to messageHandlers.toList()
                }
            } catch (_: Exception) {
                closeWithCode("RTC_CHUNK_INVALID")
                return
            }
            // 不持有载体锁调用业务回调，避免业务关闭与 native 回调相互等待。
            for (handler in delivery.second) {
                if (isClosed()) return
                try { handler(delivery.first.copyOf()) } catch (_: Exception) {
                    closeWithCode("RTC_MESSAGE_HANDLER_FAILED")
                    return
                }
            }
        }

        override fun onBufferedAmountChanged(identity: RtcChannelIdentity) {
            // 监测按上游采用定时快照；该通知不表示消息已交付，也不伪造业务确认。
        }
    }

    init {
        if (identity != port.identity || port.label != CHANNEL_LABEL || sendTimeoutMillis != null && sendTimeoutMillis <= 0) {
            port.close()
            job.cancel()
            fail("RTC_CHANNEL_BINDING_INVALID")
        }
        job.invokeOnCompletion { closeWithCode("RTC_OWNER_CANCELLED") }
        try {
            port.observe(observer)
            observer.onStateChanged(identity, port.state)
        } catch (_: Exception) {
            closeWithCode("RTC_ADOPTION_FAILED")
            fail("RTC_ADOPTION_FAILED")
        }
    }

    suspend fun awaitOpen() { opened.await() }

    /** 原始密文入口；生产业务优先使用拥有 Noise 的 SecureRtcDataChannel。 */
    suspend fun sendCiphertext(ciphertext: ByteArray) = sends.runOwned(ciphertext) { owned ->
        sendFrames(listOf(owned))
    }

    /** 受理、加密与全部密文片发送属于同一串行任务，任何可能耗掉 nonce 的失败都销毁 cipher。 */
    internal suspend fun sendEncrypted(
        plaintext: ByteArray,
        encrypt: suspend (ByteArray) -> List<ByteArray>,
        invalidateCipher: () -> Unit,
    ) = sends.runOwned(plaintext) { owned ->
        requireOpen()
        currentCoroutineContext().ensureActive()
        try {
            val encrypted = encrypt(owned)
            currentCoroutineContext().ensureActive()
            sendFrames(encrypted)
        } catch (failure: Exception) {
            invalidateCipher()
            closeWithCode(if (failure is CancellationException) "RTC_SEND_CANCELLED" else "RTC_SEND_FAILED")
            throw failure
        }
    }

    fun onMessage(handler: (ByteArray) -> Unit): AutoCloseable {
        synchronized(lock) {
            if (closed) fail("RTC_CLOSED")
            messageHandlers += handler
        }
        return AutoCloseable { synchronized(lock) { messageHandlers -= handler } }
    }

    fun onClose(handler: (String) -> Unit): AutoCloseable {
        val code = synchronized(lock) {
            if (!closed) { closeHandlers += handler; null } else closeCode
        }
        if (code != null) handler(code)
        return AutoCloseable { synchronized(lock) { closeHandlers -= handler } }
    }

    fun stats(): RtcCarrierStats = synchronized(lock) {
        RtcCarrierStats(currentState, !closed && currentState == RtcChannelState.OPEN, sentBytes, receivedBytes)
    }

    override fun close() { closeWithCode("RTC_CLOSED") }

    private suspend fun sendFrames(ciphertexts: List<ByteArray>) {
        try {
            for (ciphertext in ciphertexts) {
                requireOpen()
                for (frame in outgoing.encode(ciphertext)) {
                    currentCoroutineContext().ensureActive()
                    requireOpen()
                    if (!port.sendBinary(frame)) fail("RTC_SEND_REJECTED")
                    currentCoroutineContext().ensureActive()
                }
                synchronized(lock) { sentBytes += ciphertext.size }
            }
            armWatchdog()
        } catch (failure: Exception) {
            closeWithCode(if (failure is CancellationException) "RTC_SEND_CANCELLED" else "RTC_SEND_FAILED")
            if (failure is CancellationException || failure is RtcTransportException) throw failure
            fail("RTC_SEND_FAILED")
        }
    }

    private fun armWatchdog() {
        val timeout = sendTimeoutMillis ?: return
        synchronized(lock) {
            if (closed) return
            watchdog?.cancel()
            val baseline = port.bufferedAmount
            if (baseline <= 0) { watchdog = null; return }
            watchdog = scope.launch {
                var previous = baseline
                while (true) {
                    delay(timeout)
                    if (isClosed() || port.state != RtcChannelState.OPEN) return@launch
                    val amount = port.bufferedAmount
                    if (amount > 0 && amount >= previous) {
                        closeWithCode("RTC_SEND_TIMEOUT")
                        return@launch
                    }
                    if (amount <= 0) return@launch
                    previous = amount
                }
            }
        }
    }

    private fun requireOpen() {
        synchronized(lock) { if (closed || currentState != RtcChannelState.OPEN) fail("RTC_NOT_OPEN") }
        if (port.state != RtcChannelState.OPEN) fail("RTC_NOT_OPEN")
    }

    private fun isClosed(): Boolean = synchronized(lock) { closed }

    private fun closeWithCode(code: String) {
        val callbacks = synchronized(lock) {
            if (closed) return
            closed = true
            closeCode = code
            currentState = RtcChannelState.CLOSED
            watchdog?.cancel()
            watchdog = null
            opened.completeExceptionally(RtcTransportException(code))
            incoming.reset()
            outgoing.reset()
            messageHandlers.clear()
            closeHandlers.toList().also { closeHandlers.clear() }
        }
        sends.close()
        // native 清理不在载体锁内进行，注销 observer 后再关闭并释放 native channel。
        try { port.observe(null) } catch (_: Exception) { }
        try { port.close() } catch (_: Exception) { }
        job.cancel()
        callbacks.forEach { callback -> try { callback(code) } catch (_: Exception) { } }
    }

    companion object { const val CHANNEL_LABEL = "dsh" }
}

data class RtcCarrierStats(val state: RtcChannelState, val connected: Boolean, val bytesSent: Long, val wireBytesReceived: Long)
