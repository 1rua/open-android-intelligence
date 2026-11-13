package com.openandroidintelligence.gateway.remote.transport

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class NativeDataChannelPortTest {
    @Test
    fun native接收回调内复制且不改变原Buffer位置() {
        val handle = Handle()
        val port = NativeDataChannelPort(handle, FakeRtcPort().identity)
        var received: ByteArray? = null
        port.observe(object : RtcDataChannelPort.Observer {
            override fun onStateChanged(identity: RtcChannelIdentity, state: RtcChannelState) { }
            override fun onMessage(identity: RtcChannelIdentity, bytes: ByteArray) { received = bytes }
            override fun onBufferedAmountChanged(identity: RtcChannelIdentity) { }
        })
        val source = byteArrayOf(99, 1, 2, 3, 98)
        val buffer = ByteBuffer.wrap(source).apply { position(1); limit(4) }
        handle.listener!!.onMessage(buffer)
        source.fill(0)
        assertEquals(1, buffer.position())
        assertArrayEquals(byteArrayOf(1, 2, 3), received)
        port.close()
    }

    @Test
    fun close先注销再关闭释放且旧native回调被拒绝() {
        val handle = Handle()
        val port = NativeDataChannelPort(handle, FakeRtcPort().identity)
        var messages = 0
        port.observe(object : RtcDataChannelPort.Observer {
            override fun onStateChanged(identity: RtcChannelIdentity, state: RtcChannelState) { }
            override fun onMessage(identity: RtcChannelIdentity, bytes: ByteArray) { messages++ }
            override fun onBufferedAmountChanged(identity: RtcChannelIdentity) { }
        })
        val old = handle.listener!!
        port.close(); port.close()
        old.onMessage(ByteBuffer.wrap(byteArrayOf(1)))
        assertEquals(0, messages)
        assertEquals(listOf("observe", "unregister", "close", "dispose"), handle.steps)
        assertFalse(port.sendBinary(byteArrayOf(1)))
        assertEquals(RtcChannelState.CLOSED, port.state)
    }

    @Test
    fun send返回native结果且只发送binary字节() {
        val handle = Handle()
        val port = NativeDataChannelPort(handle, FakeRtcPort().identity)
        assertTrue(port.sendBinary(byteArrayOf(1, 2)))
        assertArrayEquals(byteArrayOf(1, 2), handle.sent)
        handle.accept = false
        assertFalse(port.sendBinary(byteArrayOf(3)))
        port.close()
    }

    @Test
    fun 出站Init固定ordered可靠且不限制重传次数或时限() {
        val init = NativeDataChannelFactory.outgoingConfiguration()
        assertTrue(init.ordered)
        assertEquals(-1, init.maxRetransmits)
        assertEquals(-1, init.maxRetransmitTimeMs)
        assertFalse(init.negotiated)
        assertEquals(-1, init.id)
    }

    private class Handle : NativeDataChannelHandle {
        override val label = "dsh"
        override val state = RtcChannelState.OPEN
        override val bufferedAmount = 0L
        var listener: NativeDataChannelHandle.Observer? = null
        var accept = true
        var sent: ByteArray? = null
        val steps = mutableListOf<String>()
        override fun observe(observer: NativeDataChannelHandle.Observer?) {
            steps += if (observer == null) "unregister" else "observe"
            listener = observer
        }
        override fun send(data: ByteBuffer): Boolean {
            sent = ByteArray(data.remaining()).also { data.get(it) }
            return accept
        }
        override fun close() { steps += "close" }
        override fun dispose() { steps += "dispose" }
    }
}
