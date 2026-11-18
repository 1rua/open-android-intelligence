package com.openandroidintelligence.gateway.remote.transport

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class RtcChunkCodecTest {
    @Test
    fun 固定RTCH大端头阈值与多片数据逐字节一致() {
        val input = bytes(8193)
        val chunks = RtcChunkCodec().encode(input)
        assertEquals(2, chunks.size)
        assertArrayEquals(byteArrayOf(0x52, 0x54, 0x43, 0x48, 0, 0, 0, 1, 0, 0, 0, 2), chunks[0].copyOf(12))
        assertEquals(8204, chunks[0].size)
        assertEquals(13, chunks[1].size)
        val decoder = RtcChunkCodec()
        assertNull(decoder.decode(chunks[0]))
        assertArrayEquals(input, decoder.decode(chunks[1]))
        assertEquals(1, RtcChunkCodec().encode(bytes(8192)).size)
    }

    @Test
    fun 小消息不加头且返回独立数组() {
        val data = bytes(128)
        val codec = RtcChunkCodec()
        val encoded = codec.encode(data).single()
        val decoded = codec.decode(encoded)
        assertArrayEquals(data, decoded)
        assertNotSame(data, encoded)
        assertNotSame(encoded, decoded)
    }

    @Test
    fun 双向与连续百条大消息均保持边界() {
        val a = RtcChunkCodec()
        val b = RtcChunkCodec()
        repeat(100) { i ->
            val outgoing = bytes(48 * 1024 + i)
            val incoming = bytes(128 * 1024 - i)
            assertArrayEquals(outgoing, a.encode(outgoing).mapNotNull(b::decode).single())
            assertArrayEquals(incoming, b.encode(incoming).mapNotNull(a::decode).single())
        }
    }

    @Test
    fun 最大消息可还原并拒绝出入站超限() {
        val codec = RtcChunkCodec()
        val data = bytes(RtcChunkCodec.MAX_MESSAGE_BYTES)
        assertArrayEquals(data, codec.encode(data).mapNotNull(codec::decode).single())
        assertThrows(RtcTransportException::class.java) { codec.encode(ByteArray(data.size + 1)) }
        assertThrows(RtcTransportException::class.java) { codec.decode(ByteArray(data.size + 1)) }
        assertThrows(RtcTransportException::class.java) { codec.decode(chunk(0, 513)) }
    }

    @Test
    fun 乱序重复更改total与截断均拒绝且清除该重组() {
        val decoder = RtcChunkCodec()
        assertThrows(RtcTransportException::class.java) { decoder.decode(chunk(1, 2, 1)) }
        assertNull(decoder.decode(chunk(0, 2)))
        assertThrows(RtcTransportException::class.java) { decoder.decode(chunk(0, 2)) }
        assertNull(decoder.decode(chunk(0, 2)))
        assertEquals(8193, decoder.decode(chunk(1, 2, 1))!!.size)
        assertNull(decoder.decode(chunk(0, 3)))
        assertThrows(RtcTransportException::class.java) { decoder.decode(chunk(1, 2, 1)) }
        assertThrows(RtcTransportException::class.java) { decoder.decode(chunk(0, 2).copyOf(11)) }
    }

    @Test
    fun 元数据与每片长度严格执行固定上游要求() {
        listOf(chunk(0, 2, id = 0), chunk(0, 1), chunk(2, 2), chunk(0, 2, 8191), chunk(1, 2, 0), chunk(1, 2, 8193)).forEach {
            assertThrows(RtcTransportException::class.java) { RtcChunkCodec().decode(it) }
        }
    }

    @Test
    fun 八条未完成限制可在完成或超过三十秒后释放() {
        var now = 0L
        val codec = RtcChunkCodec { now }
        (1..8).forEach { assertNull(codec.decode(chunk(0, 2, id = it))) }
        assertThrows(RtcTransportException::class.java) { codec.decode(chunk(0, 2, id = 9)) }
        assertEquals(8193, codec.decode(chunk(1, 2, 1, id = 1))!!.size)
        assertNull(codec.decode(chunk(0, 2, id = 9)))
        now = 30_000
        assertThrows(RtcTransportException::class.java) { codec.decode(chunk(0, 2, id = 10)) }
        now = 30_001
        assertNull(codec.decode(chunk(0, 2, id = 10)))
    }

    @Test
    fun reset清除旧片段并重置出站标识() {
        val codec = RtcChunkCodec()
        codec.decode(chunk(0, 2))
        codec.encode(bytes(9000))
        codec.reset()
        assertThrows(RtcTransportException::class.java) { codec.decode(chunk(1, 2, 1)) }
        assertEquals(1, ByteBuffer.wrap(codec.encode(bytes(9000)).first()).getInt(4))
    }

    private fun bytes(size: Int): ByteArray = ByteArray(size) { (it * 31 + 7).toByte() }
    private fun chunk(index: Int, total: Int, size: Int = 8192, id: Int = 1): ByteArray = ByteBuffer.allocate(12 + size).apply {
        put(byteArrayOf(0x52, 0x54, 0x43, 0x48)); putInt(id); putShort(index.toShort()); putShort(total.toShort())
    }.array()
}
