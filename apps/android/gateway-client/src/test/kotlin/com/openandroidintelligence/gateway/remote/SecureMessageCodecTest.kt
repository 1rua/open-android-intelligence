package com.openandroidintelligence.gateway.remote

import com.openandroidintelligence.gateway.remote.protocol.RemoteProtocol
import com.openandroidintelligence.gateway.remote.protocol.RemoteProtocolException
import com.openandroidintelligence.gateway.remote.protocol.SecureMessageCodec
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer

class SecureMessageCodecTest {
    @Test
    fun 固定大端DSHF头与上游逐字段一致() {
        val input = ByteArray(49_153) { (it % 251).toByte() }
        val frames = SecureMessageCodec().encode(input)
        assertEquals(2, frames.size)
        assertArrayEquals(byteArrayOf(0x44, 0x53, 0x48, 0x46, 1, 0, 0, 0, 1, 0, 0, 0, 2, 0, 0, 0xc0.toByte(), 1), frames[0].copyOf(17))
        assertEquals(17 + 49_152, frames[0].size)
        assertEquals(18, frames[1].size)
        val decoder = SecureMessageCodec()
        assertNull(decoder.decode(frames[0]))
        assertArrayEquals(input, decoder.decode(frames[1]))
    }

    @Test
    fun 小消息保持原字节且不暴露调用方数组() {
        val input = byteArrayOf(1, 2, 3)
        val codec = SecureMessageCodec()
        val encoded = codec.encode(input).single()
        input.fill(0)
        assertArrayEquals(byteArrayOf(1, 2, 3), encoded)
        val decoded = codec.decode(encoded)!!
        encoded.fill(0)
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded)
    }

    @Test
    fun 协议最大消息可完整重组且超限拒绝() {
        val codec = SecureMessageCodec()
        val message = ByteArray(RemoteProtocol.MAX_SECURE_MESSAGE_BYTES) { (it % 253).toByte() }
        val chunks = codec.encode(message)
        var decoded: ByteArray? = null
        chunks.forEach { decoded = codec.decode(it) }
        assertArrayEquals(message, decoded)
        assertThrows(RemoteProtocolException::class.java) { codec.encode(ByteArray(message.size + 1)) }
    }

    @Test
    fun 乱序重复更改总长与截断均拒绝() {
        val frames = SecureMessageCodec().encode(ByteArray(100_000))
        assertThrows(RemoteProtocolException::class.java) { SecureMessageCodec().decode(frames[1]) }
        val duplicate = SecureMessageCodec()
        duplicate.decode(frames[0])
        assertThrows(RemoteProtocolException::class.java) { duplicate.decode(frames[0]) }
        val changed = SecureMessageCodec()
        changed.decode(frames[0])
        val wrongTotal = frames[1].copyOf().also { ByteBuffer.wrap(it).putInt(13, 100_001) }
        assertThrows(RemoteProtocolException::class.java) { changed.decode(wrongTotal) }
        assertThrows(RemoteProtocolException::class.java) { SecureMessageCodec().decode(frames[0].copyOf(frames[0].size - 1)) }
    }

    @Test
    fun 无效版本与零消息标识不能进入重组() {
        val first = SecureMessageCodec().encode(ByteArray(50_000)).first()
        val wrongVersion = first.copyOf().also { it[4] = 2 }
        val zeroId = first.copyOf().also { ByteBuffer.wrap(it).putInt(5, 0) }
        listOf(first.copyOf(4), wrongVersion, zeroId).forEach {
            assertThrows(RemoteProtocolException::class.java) { SecureMessageCodec().decode(it) }
        }
    }

    @Test
    fun 并行重组最多八条且reset移除旧片段() {
        val encoder = SecureMessageCodec()
        val messages = List(9) { encoder.encode(ByteArray(50_000) { i -> (i % 233).toByte() }) }
        val decoder = SecureMessageCodec()
        messages.take(8).forEach { assertNull(decoder.decode(it.first())) }
        assertThrows(RemoteProtocolException::class.java) { decoder.decode(messages[8].first()) }
        assertThrows(RemoteProtocolException::class.java) { decoder.decode(messages[0][1]) }
        decoder.reset()
        assertNull(decoder.decode(messages[8].first()))
        assertEquals(50_000, decoder.decode(messages[8][1])!!.size)
    }
}
