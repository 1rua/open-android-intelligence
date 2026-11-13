package com.openandroidintelligence.gateway.remote.transport

import com.openandroidintelligence.gateway.remote.protocol.RemoteConnectionBinding
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RtcDataChannelTransportTest {
    @Test
    fun 真实状态打开后多片密文一次投递且统计不伪造确认() = runTest {
        val port = FakeRtcPort()
        val carrier = RtcDataChannelTransport(port.identity, port, backgroundScope)
        val opened = async { carrier.awaitOpen() }
        runCurrent()
        assertFalse(opened.isCompleted)
        port.changeState(RtcChannelState.OPEN)
        opened.await()
        val messages = mutableListOf<ByteArray>()
        carrier.onMessage { messages += it }
        val data = ByteArray(128 * 1024) { (it % 251).toByte() }
        carrier.sendCiphertext(data)
        assertEquals(16, port.sent.size)
        port.sent.forEach { port.receive(it) }
        assertEquals(1, messages.size)
        assertArrayEquals(data, messages.single())
        assertEquals(data.size.toLong(), carrier.stats().bytesSent)
        assertTrue(carrier.stats().wireBytesReceived > data.size)
        carrier.close()
    }

    @Test
    fun 错误连接或旧代际回调不能打开接收或关闭当前载体() = runTest {
        val port = FakeRtcPort()
        val carrier = RtcDataChannelTransport(port.identity, port, backgroundScope)
        val observer = port.listener!!
        val other = port.identity.copy(generation = 1)
        observer.onStateChanged(other, RtcChannelState.OPEN)
        assertFalse(carrier.stats().connected)
        port.changeState(RtcChannelState.OPEN)
        val received = mutableListOf<ByteArray>()
        carrier.onMessage { received += it }
        observer.onMessage(other, byteArrayOf(1))
        observer.onStateChanged(other, RtcChannelState.CLOSED)
        assertTrue(carrier.stats().connected)
        assertTrue(received.isEmpty())
        carrier.close()
    }

    @Test
    fun close移除监听且旧回调不能复活或再投递() = runTest {
        val port = FakeRtcPort(RtcChannelState.OPEN)
        val carrier = RtcDataChannelTransport(port.identity, port, backgroundScope)
        val stale = port.listener!!
        var messages = 0
        var closes = 0
        carrier.onMessage { messages++ }
        carrier.onClose { closes++ }
        carrier.close(); carrier.close()
        stale.onStateChanged(port.identity, RtcChannelState.OPEN)
        stale.onMessage(port.identity, byteArrayOf(1))
        assertFalse(carrier.stats().connected)
        assertEquals(0, messages)
        assertEquals(1, closes)
        assertEquals(1, port.closeCount)
        assertNull(port.listener)
        assertTrue(runCatching { carrier.sendCiphertext(byteArrayOf(1)) }.exceptionOrNull() is RtcTransportException)
    }

    @Test
    fun 畸形RTCH立即关闭且不投递剩余片段() = runTest {
        val port = FakeRtcPort(RtcChannelState.OPEN)
        val carrier = RtcDataChannelTransport(port.identity, port, backgroundScope)
        var delivered = 0
        carrier.onMessage { delivered++ }
        val chunks = RtcChunkCodec().encode(ByteArray(16 * 1024))
        val stale = port.listener!!
        port.receive(chunks[1])
        stale.onMessage(port.identity, chunks[0])
        assertEquals(0, delivered)
        assertFalse(carrier.stats().connected)
        assertEquals(1, port.closeCount)
    }

    @Test
    fun send返回false按未知失败关闭且不发送后续密文() = runTest {
        val port = FakeRtcPort(RtcChannelState.OPEN).apply { acceptSend = false }
        val carrier = RtcDataChannelTransport(port.identity, port, backgroundScope)
        val failure = runCatching { carrier.sendCiphertext(ByteArray(50_000)) }.exceptionOrNull()
        assertEquals("RTC_SEND_REJECTED", (failure as RtcTransportException).code)
        assertFalse(carrier.stats().connected)
        assertEquals(0, port.sent.size)
    }

    @Test
    fun RTC默认不设停滞时限只有显式启用才按实际缓冲监测() = runTest {
        val port = FakeRtcPort(RtcChannelState.OPEN).apply { buffered = 100 }
        val carrier = RtcDataChannelTransport(port.identity, port, backgroundScope)
        carrier.sendCiphertext(byteArrayOf(1))
        advanceTimeBy(60_000); runCurrent()
        assertTrue(carrier.stats().connected)
        carrier.close()
        val watched = FakeRtcPort(RtcChannelState.OPEN).apply { buffered = 100 }
        val guarded = RtcDataChannelTransport(watched.identity, watched, backgroundScope, sendTimeoutMillis = 100)
        guarded.sendCiphertext(byteArrayOf(2))
        runCurrent()
        advanceTimeBy(100); runCurrent()
        assertFalse(guarded.stats().connected)
        assertEquals(1, watched.closeCount)
    }

    @Test
    fun 缓冲持续减少可继续等待并在归零后停止监测() = runTest {
        val port = FakeRtcPort(RtcChannelState.OPEN).apply { buffered = 100 }
        val carrier = RtcDataChannelTransport(port.identity, port, backgroundScope, sendTimeoutMillis = 100)
        carrier.sendCiphertext(byteArrayOf(1)); runCurrent()
        port.buffered = 50
        advanceTimeBy(100); runCurrent()
        assertTrue(carrier.stats().connected)
        port.buffered = 0
        advanceTimeBy(100); runCurrent()
        assertTrue(carrier.stats().connected)
        port.buffered = 100
        advanceTimeBy(1000); runCurrent()
        assertTrue(carrier.stats().connected)
        carrier.close()
    }

    @Test
    fun 错误binding构造拒绝且取消await不伪造OPEN() = runTest {
        val wrong = FakeRtcPort()
        assertThrows(RtcTransportException::class.java) {
            RtcDataChannelTransport(wrong.identity.copy(generation = 2), wrong, backgroundScope)
        }
        assertEquals(1, wrong.closeCount)
        val port = FakeRtcPort()
        val carrier = RtcDataChannelTransport(port.identity, port, backgroundScope)
        val waiter = launch { carrier.awaitOpen() }
        runCurrent(); waiter.cancelAndJoin()
        assertFalse(carrier.stats().connected)
        port.changeState(RtcChannelState.OPEN)
        carrier.awaitOpen()
        assertTrue(carrier.stats().connected)
        carrier.close()
    }
}

internal class FakeRtcPort(initialState: RtcChannelState = RtcChannelState.CONNECTING) : RtcDataChannelPort {
    override val identity = RtcChannelIdentity(RemoteConnectionBinding("connection", "host", "client"), 0)
    override val label = "dsh"
    override var state = initialState
    var buffered = 0L
    override val bufferedAmount: Long get() = buffered
    var listener: RtcDataChannelPort.Observer? = null
    val sent = mutableListOf<ByteArray>()
    var closeCount = 0
    var acceptSend = true
    var duringSend: (() -> Unit)? = null
    override fun observe(observer: RtcDataChannelPort.Observer?) { listener = observer }
    override fun sendBinary(bytes: ByteArray): Boolean {
        duringSend?.invoke()
        if (!acceptSend || state != RtcChannelState.OPEN) return false
        sent += bytes.copyOf()
        return true
    }
    fun changeState(value: RtcChannelState) { state = value; listener?.onStateChanged(identity, value) }
    fun receive(bytes: ByteArray) { listener?.onMessage(identity, bytes) }
    override fun close() {
        closeCount++
        state = RtcChannelState.CLOSED
        listener?.onStateChanged(identity, state)
    }
}
