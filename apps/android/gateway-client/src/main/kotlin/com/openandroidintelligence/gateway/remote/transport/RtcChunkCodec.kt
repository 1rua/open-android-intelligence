package com.openandroidintelligence.gateway.remote.transport

import java.nio.ByteBuffer

/** 与固定上游 rtc-chunking.ts 一致，只在 DataChannel 承载层拆分密文。 */
class RtcChunkCodec(private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private var nextMessageId = 1L
    private val assemblies = mutableMapOf<Long, Assembly>()

    @Synchronized
    fun encode(data: ByteArray): List<ByteArray> {
        if (data.size <= PAYLOAD_BYTES) return listOf(data.copyOf())
        if (data.size > MAX_MESSAGE_BYTES) fail("RTC_MESSAGE_TOO_LARGE")
        val id = nextMessageId
        nextMessageId = if (id == 0xffff_ffffL) 1 else id + 1
        val total = (data.size + PAYLOAD_BYTES - 1) / PAYLOAD_BYTES
        return List(total) { index ->
            val start = index * PAYLOAD_BYTES
            val length = minOf(PAYLOAD_BYTES, data.size - start)
            ByteBuffer.allocate(HEADER_BYTES + length).apply {
                put(MAGIC)
                putInt(id.toInt())
                putShort(index.toShort())
                putShort(total.toShort())
                put(data, start, length)
            }.array()
        }
    }

    @Synchronized
    fun decode(frame: ByteArray): ByteArray? {
        if (!hasMagic(frame)) {
            if (frame.size > MAX_MESSAGE_BYTES) fail("RTC_MESSAGE_TOO_LARGE")
            return frame.copyOf()
        }
        if (frame.size < HEADER_BYTES) fail("RTC_CHUNK_HEADER_INVALID")
        val input = ByteBuffer.wrap(frame)
        val id = input.getInt(4).toLong() and 0xffff_ffffL
        val index = input.getShort(8).toInt() and 0xffff
        val total = input.getShort(10).toInt() and 0xffff
        if (id == 0L || total < 2 || total > MAX_TOTAL || index >= total) fail("RTC_CHUNK_METADATA_INVALID")
        val length = frame.size - HEADER_BYTES
        if (length > PAYLOAD_BYTES || index < total - 1 && length != PAYLOAD_BYTES || index == total - 1 && length == 0) {
            fail("RTC_CHUNK_LENGTH_INVALID")
        }
        val now = clockMillis()
        val stale = assemblies.filterValues { now - it.updatedAt > MAX_ASSEMBLY_AGE_MS }.keys
        stale.forEach { assemblies.remove(it)?.erase() }
        val assembly = assemblies[id] ?: run {
            if (index != 0 || assemblies.size >= MAX_IN_FLIGHT) fail("RTC_CHUNK_SEQUENCE_INVALID")
            Assembly(total, now).also { assemblies[id] = it }
        }
        if (assembly.total != total || index != assembly.chunks.size) {
            assemblies.remove(id)?.erase()
            fail("RTC_CHUNK_SEQUENCE_INVALID")
        }
        if (assembly.receivedBytes + length > MAX_MESSAGE_BYTES) {
            assemblies.remove(id)?.erase()
            fail("RTC_MESSAGE_TOO_LARGE")
        }
        assembly.chunks += frame.copyOfRange(HEADER_BYTES, frame.size)
        assembly.receivedBytes += length
        assembly.updatedAt = now
        if (assembly.chunks.size < total) return null
        assemblies.remove(id)
        return ByteBuffer.allocate(assembly.receivedBytes).apply { assembly.chunks.forEach { put(it) } }.array()
            .also { assembly.erase() }
    }

    @Synchronized
    fun reset() {
        nextMessageId = 1
        assemblies.values.forEach { it.erase() }
        assemblies.clear()
    }

    private class Assembly(val total: Int, var updatedAt: Long) {
        var receivedBytes = 0
        val chunks = mutableListOf<ByteArray>()
        fun erase() { chunks.forEach { it.fill(0) }; chunks.clear() }
    }

    companion object {
        const val HEADER_BYTES = 12
        const val PAYLOAD_BYTES = 8 * 1024
        const val MAX_MESSAGE_BYTES = 4 * 1024 * 1024
        const val MAX_IN_FLIGHT = 8
        const val MAX_ASSEMBLY_AGE_MS = 30_000L
        private const val MAX_TOTAL = MAX_MESSAGE_BYTES / PAYLOAD_BYTES
        private val MAGIC = byteArrayOf(0x52, 0x54, 0x43, 0x48)
        private fun hasMagic(frame: ByteArray): Boolean = frame.size >= MAGIC.size && MAGIC.indices.all { frame[it] == MAGIC[it] }
    }
}

/** 固定错误代码不附带底层异常或载荷。 */
class RtcTransportException(val code: String) : IllegalStateException(code)

internal fun fail(code: String): Nothing = throw RtcTransportException(code)
