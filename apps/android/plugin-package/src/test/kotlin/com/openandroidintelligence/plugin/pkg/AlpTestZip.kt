package com.openandroidintelligence.plugin.pkg

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds ZIP archives for verifier tests.
 *
 * [build] emits well-formed archives the way `plugin-tooling` would. [raw]
 * emits hand-written archives so tests can express containers a normal writer
 * refuses to produce: duplicate names, and sizes that lie.
 */
internal object AlpTestZip {
    /** Well-formed, sorted by UTF-8 path bytes as the contract requires. */
    fun build(entries: List<Pair<String, ByteArray>>): ByteArray {
        val sorted = entries.sortedWith { a, b -> compareUtf8(a.first, b.first) }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in sorted) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * Hand-written STORED archive. [declaredUncompressedSize] lets a test state
     * a size that does not match the bytes actually present, which is exactly
     * the lie a ZIP bomb tells.
     */
    fun raw(vararg entries: RawEntry): ByteArray {
        val local = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()

        for (entry in entries) {
            val nameBytes = entry.name.toByteArray(StandardCharsets.UTF_8)
            val crc = CRC32().also { it.update(entry.data) }.value
            val declared = entry.declaredUncompressedSize ?: entry.data.size.toLong()

            offsets += local.size()
            val lh = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN)
            lh.putInt(0x04034b50)
            lh.putShort(20)
            lh.putShort(0)
            lh.putShort(0) // STORED
            lh.putShort(0)
            lh.putShort(0x21) // 1980-01-01
            lh.putInt(crc.toInt())
            lh.putInt(entry.data.size)
            lh.putInt(declared.toInt())
            lh.putShort(nameBytes.size.toShort())
            lh.putShort(0)
            local.write(lh.array())
            local.write(nameBytes)
            local.write(entry.data)

            val ch = ByteBuffer.allocate(46).order(ByteOrder.LITTLE_ENDIAN)
            ch.putInt(0x02014b50)
            ch.putShort(20)
            ch.putShort(20)
            ch.putShort(0)
            ch.putShort(0)
            ch.putShort(0)
            ch.putShort(0x21)
            ch.putInt(crc.toInt())
            ch.putInt(entry.data.size)
            ch.putInt(declared.toInt())
            ch.putShort(nameBytes.size.toShort())
            ch.putShort(0)
            ch.putShort(0)
            ch.putShort(0)
            ch.putShort(0)
            ch.putInt(0)
            ch.putInt(offsets.last())
            central.write(ch.array())
            central.write(nameBytes)
        }

        val centralBytes = central.toByteArray()
        val localBytes = local.toByteArray()
        val eocd = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN)
        eocd.putInt(0x06054b50)
        eocd.putShort(0)
        eocd.putShort(0)
        eocd.putShort(entries.size.toShort())
        eocd.putShort(entries.size.toShort())
        eocd.putInt(centralBytes.size)
        eocd.putInt(localBytes.size)
        eocd.putShort(0)

        return localBytes + centralBytes + eocd.array()
    }

    fun manifestJson(
        id: String = "org.example.notifications",
        version: String = "1.0.0",
        publicKey: String = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        runtime: String = """"type":"protected-wasm","abiVersion":"1.0","entrypoint":"open_android_intelligence_plugin_main","payload":"payload/plugin.wasm"""",
    ): String = """{"schemaVersion":"1.0","plugin":{"id":"$id","version":"$version","name":"n","description":"d"},""" +
        """"author":{"algorithm":"Ed25519","publicKey":"$publicKey"},"runtime":{$runtime},""" +
        """"compatibility":{"androidHost":">=2.0.0 <3.0.0"},""" +
        """"capabilities":{"provides":[],"depends":[],"kernelPrimitives":[]},""" +
        """"security":{"network":[],"background":{"requested":false,"minimumIntervalSeconds":null},""" +
        """"resources":{"maxInvocationMillis":5000,"maxMemoryBytes":16777216,"maxStorageBytes":10485760,"maxConcurrentInvocations":1,"maxDailyNetworkBytes":0}},""" +
        """"ui":{"settings":[],"cards":[]},"state":{"schemaVersion":1,"portableExport":false}}"""

    internal fun compareUtf8(a: String, b: String): Int {
        val ab = a.toByteArray(StandardCharsets.UTF_8)
        val bb = b.toByteArray(StandardCharsets.UTF_8)
        val len = minOf(ab.size, bb.size)
        for (i in 0 until len) {
            if (ab[i] != bb[i]) return ab[i].compareTo(bb[i])
        }
        return ab.size.compareTo(bb.size)
    }
}

internal data class RawEntry(
    val name: String,
    val data: ByteArray,
    val declaredUncompressedSize: Long? = null,
)
