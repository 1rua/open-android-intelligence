package com.openandroidintelligence.mobile

import com.openandroidintelligence.conversation.ports.AttachmentContentSource
import com.openandroidintelligence.conversation.ports.LocalAttachmentSelection
import com.openandroidintelligence.encrypted.store.EncryptedAttachmentStagingStore
import com.openandroidintelligence.gateway.http.GatewayProfile
import com.openandroidintelligence.gateway.http.GatewayRequestBody
import com.openandroidintelligence.gateway.http.GatewayTransport
import com.openandroidintelligence.gateway.http.RawHeader
import com.openandroidintelligence.gateway.http.WireRequest
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachmentStreamEndToEndTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun pickerStyleStreamStagesEncryptedAndUploadsExactBytesAtOneTwentyFiveAndFiftyMib() = runBlocking {
        val store = EncryptedAttachmentStagingStore(
            directory = temporaryFolder.root,
            key = SecretKeySpec(ByteArray(32) { (it + 11).toByte() }, "AES"),
            scopeId = "https://gateway.example/account-1/install-1",
        )
        val lengths = listOf(1L * MIB + 17, 25L * MIB + 1, 50L * MIB + 33)

        lengths.forEachIndexed { index, size ->
            val expectedDigest = patternedDigest(size)
            val staged = store.stage(
                LocalAttachmentSelection(
                    filename = "synthetic-$index.png",
                    mediaType = "image/png",
                    contentSource = AttachmentContentSource { PatternInputStream(size) },
                ),
            )
            assertEquals(size, staged.sizeBytes)
            assertEquals(expectedDigest, staged.sha256Hex)

            val capture = LoopbackStreamingGatewayStub()
            capture.use { stub ->
                val body = GatewayRequestBody(
                    contentLength = staged.sizeBytes,
                    sha256Hex = staged.sha256Hex,
                    openStream = { store.openStream(staged.id) },
                )
                val digestHeader = "sha-256=" + Base64.getEncoder().encodeToString(staged.sha256Hex.hexBytes())
                val response = GatewayTransport(
                    GatewayProfile("account-1", "device-1", "session-1", stub.baseUrl),
                ).execute(
                    WireRequest(
                        method = "PUT",
                        target = "/open-android-intelligence/v2/attachments/att_$index/content",
                        headers = listOf(
                            RawHeader("Content-Length", size.toString()),
                            RawHeader("Digest", digestHeader),
                        ),
                        streamBody = body,
                    ),
                )
                assertEquals(200, response.status)
                val observed = stub.awaitRequest()
                assertEquals(size, observed.contentLength)
                assertEquals(size, observed.bodyLength)
                assertEquals(expectedDigest, observed.bodySha256Hex)
                assertEquals(1, observed.headers.count { it.startsWith("Content-Length:", ignoreCase = true) })
                assertEquals(size.toString(), observed.headers.single { it.startsWith("Content-Length:", ignoreCase = true) }.substringAfter(':').trim())
                assertEquals(
                    "sha-256=" + Base64.getEncoder().encodeToString(expectedDigest.hexBytes()),
                    observed.headers.single { it.startsWith("Digest:", ignoreCase = true) }.substringAfter(':').trim(),
                )
            }
            store.delete(staged.id)
        }
    }

    private fun patternedDigest(size: Long): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var offset = 0L
        while (offset < size) {
            val count = minOf(buffer.size.toLong(), size - offset).toInt()
            for (index in 0 until count) buffer[index] = patternByte(offset + index)
            digest.update(buffer, 0, count)
            offset += count
        }
        return digest.digest().toHex()
    }

    private fun patternByte(offset: Long): Byte = ((offset * 31 + 7) and 0xff).toByte()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private inner class PatternInputStream(private val size: Long) : InputStream() {
        private var offset = 0L
        override fun read(): Int {
            if (offset >= size) return -1
            return ((offset++ * 31 + 7) and 0xff).toInt()
        }

        override fun read(buffer: ByteArray, start: Int, length: Int): Int {
            if (offset >= size) return -1
            val count = minOf(length.toLong(), size - offset).toInt()
            for (index in 0 until count) buffer[start + index] = patternByte(offset + index)
            offset += count
            return count
        }
    }

    private fun String.hexBytes(): ByteArray = ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private companion object { const val MIB = 1024L * 1024 }
}
