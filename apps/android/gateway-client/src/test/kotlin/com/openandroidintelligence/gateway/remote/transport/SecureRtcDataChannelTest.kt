package com.openandroidintelligence.gateway.remote.transport

import com.openandroidintelligence.gateway.remote.crypto.NoiseChannelException
import com.openandroidintelligence.gateway.remote.crypto.NoiseIkChannel
import com.openandroidintelligence.gateway.remote.crypto.NoiseRole
import com.openandroidintelligence.gateway.remote.crypto.RemoteSecureChannel
import com.openandroidintelligence.gateway.remote.protocol.RemoteConnectionBinding
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch

@OptIn(ExperimentalCoroutinesApi::class)
class SecureRtcDataChannelTest {
    @Test
    fun 真实Noise双向多片正文通过DSHF和RTCH还原() = runTest {
        val pair = securePair()
        val aPort = FakeRtcPort(RtcChannelState.OPEN)
        val bPort = FakeRtcPort(RtcChannelState.OPEN)
        val aCarrier = RtcDataChannelTransport(aPort.identity, aPort, backgroundScope)
        val bCarrier = RtcDataChannelTransport(bPort.identity, bPort, backgroundScope)
        val a = SecureRtcDataChannel(pair.first, aCarrier)
        val b = SecureRtcDataChannel(pair.second, bCarrier)
        val messages = mutableListOf<ByteArray>()
        b.onMessage { messages += it }
        val body = ByteArray(200_000) { (it % 239).toByte() }
        a.sendMessage(body)
        assertTrue(aPort.sent.size > 20)
        aPort.sent.forEach(bPort::receive)
        assertArrayEquals(body, messages.single())
        a.close(); b.close()
    }

    @Test
    fun RTCH完整重放由Noise拒绝且关闭当前连接() = runTest {
        val pair = securePair()
        val aPort = FakeRtcPort(RtcChannelState.OPEN)
        val bPort = FakeRtcPort(RtcChannelState.OPEN)
        val a = SecureRtcDataChannel(pair.first, RtcDataChannelTransport(aPort.identity, aPort, backgroundScope))
        val bCarrier = RtcDataChannelTransport(bPort.identity, bPort, backgroundScope)
        val b = SecureRtcDataChannel(pair.second, bCarrier)
        var delivered = 0
        b.onMessage { delivered++ }
        a.sendMessage(ByteArray(30_000))
        val frames = aPort.sent.map { it.copyOf() }
        frames.forEach(bPort::receive)
        assertEquals(1, delivered)
        frames.forEach(bPort::receive)
        assertEquals(1, delivered)
        assertFalse(bCarrier.stats().connected)
        assertFalse(pair.second.complete)
        a.close(); b.close()
    }

    @Test
    fun cipher与carrier连接不同构造即拒绝() = runTest {
        val pair = securePair(RemoteConnectionBinding("其他连接", "host", "client"))
        val port = FakeRtcPort(RtcChannelState.OPEN)
        val carrier = RtcDataChannelTransport(port.identity, port, backgroundScope)
        assertThrows(RtcTransportException::class.java) { SecureRtcDataChannel(pair.first, carrier) }
        assertFalse(pair.first.complete)
        assertFalse(carrier.stats().connected)
        pair.second.close()
    }

    @Test
    fun carrier预先关闭时构造失败必须销毁已认证Noise() = runTest {
        val pair = securePair()
        val port = FakeRtcPort(RtcChannelState.OPEN)
        val carrier = RtcDataChannelTransport(port.identity, port, backgroundScope)
        carrier.close()
        val failure = runCatching { SecureRtcDataChannel(pair.first, carrier) }.exceptionOrNull()
        assertNotNull(failure)
        assertFalse("构造失败后不得保留可用Noise", pair.first.complete)
        assertThrows(NoiseChannelException::class.java) { pair.first.encryptMessage(byteArrayOf(1)) }
        assertEquals("SECURE_RTC_INITIALIZATION_FAILED", (failure as RtcTransportException).code)
        assertNull(failure.cause)
        pair.second.close()
    }

    @Test
    fun carrier关闭与两次订阅竞争不留下可用Noise或可接收外壳() = runTest {
        repeat(32) {
            val pair = securePair()
            val port = FakeRtcPort(RtcChannelState.OPEN)
            val carrier = RtcDataChannelTransport(port.identity, port, backgroundScope)
            val start = CountDownLatch(1)
            val closer = Thread {
                start.await()
                carrier.close()
            }
            closer.start()
            start.countDown()
            val outcome = runCatching { SecureRtcDataChannel(pair.first, carrier) }
            closer.join()
            assertFalse("关闭与订阅竞争后Noise必须失效", pair.first.complete)
            assertThrows(NoiseChannelException::class.java) { pair.first.encryptMessage(byteArrayOf(1)) }
            outcome.getOrNull()?.let { channel ->
                assertThrows(RtcTransportException::class.java) { channel.onMessage { error("关闭后不得接收") } }
                assertTrue(runCatching { channel.sendMessage(byteArrayOf(1)) }.exceptionOrNull() is RtcTransportException)
                channel.close()
            }
            pair.second.close()
        }
    }

    @Test
    fun 加密后的部分发送取消会关闭cipher而后续发送不可继续() = runTest {
        val pair = securePair()
        val port = FakeRtcPort(RtcChannelState.OPEN)
        val carrier = RtcDataChannelTransport(port.identity, port, backgroundScope)
        val channel = SecureRtcDataChannel(pair.first, carrier)
        lateinit var sender: Job
        port.duringSend = { sender.cancel() }
        sender = launch { channel.sendMessage(ByteArray(50_000)) }
        runCurrent(); sender.join()
        assertTrue(sender.isCancelled)
        assertFalse(pair.first.complete)
        assertFalse(carrier.stats().connected)
        assertTrue(runCatching { channel.sendMessage(byteArrayOf(1)) }.exceptionOrNull() is RtcTransportException)
        channel.close(); pair.second.close()
    }

    @Test
    fun 排队未开始的取消不会加密或关闭无辜cipher() = runTest {
        val pair = securePair()
        val port = FakeRtcPort(RtcChannelState.OPEN)
        val carrier = RtcDataChannelTransport(port.identity, port, backgroundScope)
        val gate = CompletableDeferred<Unit>()
        val first = launch { carrier.sendEncrypted(byteArrayOf(1), { gate.await(); listOf(ByteArray(1)) }, {}) }
        runCurrent()
        val channel = SecureRtcDataChannel(pair.first, carrier)
        val waiting = launch { channel.sendMessage(byteArrayOf(1)) }
        runCurrent()
        waiting.cancelAndJoin()
        assertTrue(pair.first.complete)
        assertTrue(carrier.stats().connected)
        gate.complete(Unit); first.join()
        channel.close(); pair.second.close()
    }

    @Test
    fun native拒发后已消耗nonce不能继续用同一cipher() = runTest {
        val pair = securePair()
        val port = FakeRtcPort(RtcChannelState.OPEN).apply { acceptSend = false }
        val carrier = RtcDataChannelTransport(port.identity, port, backgroundScope)
        val channel = SecureRtcDataChannel(pair.first, carrier)
        assertEquals("RTC_SEND_REJECTED", (runCatching { channel.sendMessage(byteArrayOf(1)) }.exceptionOrNull() as RtcTransportException).code)
        assertFalse(pair.first.complete)
        assertFalse(carrier.stats().connected)
        assertThrows(NoiseChannelException::class.java) { pair.first.encryptMessage(byteArrayOf(2)) }
        channel.close(); pair.second.close()
    }

    private fun securePair(binding: RemoteConnectionBinding = FakeRtcPort().identity.binding): Pair<RemoteSecureChannel, RemoteSecureChannel> {
        val aKey = ByteArray(32) { it.toByte() }
        val bKey = ByteArray(32) { (it + 32).toByte() }
        val a = RemoteSecureChannel(NoiseIkChannel.create(NoiseRole.CLIENT_INITIATOR, binding, aKey, NoiseIkChannel.publicKeyFor(bKey)))
        val b = RemoteSecureChannel(NoiseIkChannel.create(NoiseRole.HOST_RESPONDER, binding, bKey, NoiseIkChannel.publicKeyFor(aKey)))
        b.readHandshakeFrame(a.writeHandshakeFrame("a", 1))
        a.readHandshakeFrame(b.writeHandshakeFrame("b", 2))
        return a to b
    }
}
