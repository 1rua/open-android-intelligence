package com.openandroidintelligence.gateway.remote.protocol

import java.nio.ByteBuffer

/** 在 Noise 加密前分块，解密后重组；每条认证连接必须持有独立实例。 */
class SecureMessageCodec {
    private var nextMessageId = 1L
    private val assemblies = mutableMapOf<Long, Assembly>()

    @Synchronized
    fun encode(message: ByteArray): List<ByteArray> {
        if (message.size > RemoteProtocol.MAX_SECURE_MESSAGE_BYTES) invalid("SECURE_MESSAGE_TOO_LARGE")
        if (message.size <= RemoteProtocol.SECURE_FRAGMENT_CHUNK_BYTES) return listOf(message.copyOf())
        val id = nextMessageId
        nextMessageId = if (id == 0xffff_ffffL) 1 else id + 1
        val total = chunkCount(message.size)
        return List(total) { index ->
            val start = index * RemoteProtocol.SECURE_FRAGMENT_CHUNK_BYTES
            val length = minOf(RemoteProtocol.SECURE_FRAGMENT_CHUNK_BYTES, message.size - start)
            ByteBuffer.allocate(HEADER_BYTES + length).apply {
                put(MAGIC)
                put(VERSION.toByte())
                putInt(id.toInt())
                putShort(index.toShort())
                putShort(total.toShort())
                putInt(message.size)
                put(message, start, length)
            }.array()
        }
    }

    @Synchronized
    fun decode(frame: ByteArray): ByteArray? {
        if (!hasMagic(frame)) return frame.copyOf()
        if (frame.size < HEADER_BYTES || frame[4].toInt() != VERSION) invalid("SECURE_FRAGMENT_HEADER_INVALID")
        val input = ByteBuffer.wrap(frame)
        val id = input.getInt(5).toLong() and 0xffff_ffffL
        val index = input.getShort(9).toInt() and 0xffff
        val total = input.getShort(11).toInt() and 0xffff
        val totalBytes = input.getInt(13).toLong() and 0xffff_ffffL
        if (id == 0L || total < 2 || index >= total || totalBytes <= RemoteProtocol.SECURE_FRAGMENT_CHUNK_BYTES ||
            totalBytes > RemoteProtocol.MAX_SECURE_MESSAGE_BYTES || total != chunkCount(totalBytes.toInt())) {
            invalid("SECURE_FRAGMENT_METADATA_INVALID")
        }
        val expected = minOf(RemoteProtocol.SECURE_FRAGMENT_CHUNK_BYTES, totalBytes.toInt() - index * RemoteProtocol.SECURE_FRAGMENT_CHUNK_BYTES)
        if (frame.size - HEADER_BYTES != expected) invalid("SECURE_FRAGMENT_LENGTH_INVALID")
        val assembly = assemblies[id] ?: run {
            if (index != 0 || assemblies.size >= MAX_IN_FLIGHT) invalid("SECURE_FRAGMENT_SEQUENCE_INVALID")
            Assembly(total, totalBytes.toInt()).also { assemblies[id] = it }
        }
        if (assembly.total != total || assembly.totalBytes != totalBytes.toInt() || index != assembly.chunks.size) {
            invalid("SECURE_FRAGMENT_SEQUENCE_INVALID")
        }
        assembly.chunks += frame.copyOfRange(HEADER_BYTES, frame.size)
        assembly.receivedBytes += expected
        if (assembly.chunks.size < total) return null
        assemblies.remove(id)
        if (assembly.receivedBytes != assembly.totalBytes) invalid("SECURE_MESSAGE_LENGTH_INVALID")
        return ByteBuffer.allocate(assembly.totalBytes).apply { assembly.chunks.forEach(::put) }.array()
    }

    @Synchronized
    fun reset() {
        nextMessageId = 1
        assemblies.values.forEach { it.chunks.forEach { chunk -> chunk.fill(0) } }
        assemblies.clear()
    }

    private fun invalid(code: String): Nothing {
        reset()
        throw RemoteProtocolException(code)
    }

    private class Assembly(val total: Int, val totalBytes: Int) {
        var receivedBytes = 0
        val chunks = mutableListOf<ByteArray>()
    }

    companion object {
        const val HEADER_BYTES = 17
        private const val VERSION = 1
        private const val MAX_IN_FLIGHT = 8
        private val MAGIC = byteArrayOf(0x44, 0x53, 0x48, 0x46)
        private fun chunkCount(bytes: Int): Int = (bytes + RemoteProtocol.SECURE_FRAGMENT_CHUNK_BYTES - 1) / RemoteProtocol.SECURE_FRAGMENT_CHUNK_BYTES
        private fun hasMagic(frame: ByteArray): Boolean = frame.size >= MAGIC.size && MAGIC.indices.all { frame[it] == MAGIC[it] }
    }
}
