package com.openandroidintelligence.mobile

import com.openandroidintelligence.conversation.ports.AttachmentContentSource
import com.openandroidintelligence.conversation.ports.LocalAttachmentSelection
import com.openandroidintelligence.encrypted.store.EncryptedAttachmentStagingStore
import com.openandroidintelligence.gateway.http.GatewayProfile
import com.openandroidintelligence.gateway.http.GatewayRequestBody
import com.openandroidintelligence.gateway.http.GatewayTransport
import com.openandroidintelligence.gateway.http.RawHeader
import com.openandroidintelligence.gateway.http.WireRequest
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CompletableFuture
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
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

            val capture = CompletableFuture<CapturedRequest>()
            ServerSocket(0).use { server ->
                thread(isDaemon = true) { serveOne(server, capture) }
                val body = GatewayRequestBody(
                    contentLength = staged.sizeBytes,
                    sha256Hex = staged.sha256Hex,
                    openStream = { store.openStream(staged.id) },
                )
                val digestHeader = "sha-256=" + Base64.getEncoder().encodeToString(staged.sha256Hex.hexBytes())
                val response = GatewayTransport(
                    GatewayProfile("account-1", "device-1", "session-1", "http://127.0.0.1:${server.localPort}"),
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
            }

            val observed = capture.get()
            assertEquals(size, observed.contentLength)
            assertEquals(size, observed.bodyLength)
            assertEquals(expectedDigest, observed.bodySha256Hex)
            assertEquals(1, observed.headers.count { it.startsWith("Content-Length:", ignoreCase = true) })
            assertEquals(size.toString(), observed.headers.single { it.startsWith("Content-Length:", ignoreCase = true) }.substringAfter(':').trim())
            assertEquals(
                "sha-256=" + Base64.getEncoder().encodeToString(expectedDigest.hexBytes()),
                observed.headers.single { it.startsWith("Digest:", ignoreCase = true) }.substringAfter(':').trim(),
            )
            store.delete(staged.id)
        }
    }

    private data class CapturedRequest(
        val headers: List<String>,
        val contentLength: Long,
        val bodyLength: Long,
        val bodySha256Hex: String,
    )

    private fun serveOne(server: ServerSocket, result: CompletableFuture<CapturedRequest>) {
        try {
            server.accept().use { socket ->
                val input = socket.getInputStream()
                readAsciiLine(input) // request line
                val headers = generateSequence { readAsciiLine(input) }.takeWhile { it.isNotEmpty() }.toList()
                val contentLengthHeaders = headers.filter { it.startsWith("Content-Length:", ignoreCase = true) }
                val contentLength = contentLengthHeaders.single().substringAfter(':').trim().toLong()
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (total < contentLength) {
                    val want = minOf(buffer.size.toLong(), contentLength - total).toInt()
                    val read = input.read(buffer, 0, want)
                    if (read < 0) throw java.io.EOFException("short request body")
                    digest.update(buffer, 0, read)
                    total += read
                }
                result.complete(
                    CapturedRequest(
                        headers = headers,
                        contentLength = contentLength,
                        bodyLength = total,
                        bodySha256Hex = digest.digest().toHex(),
                    ),
                )
                val response = "{}".toByteArray()
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\nConnection: close\r\n\r\n".toByteArray())
                    write(response)
                    flush()
                }
            }
        } catch (cause: Throwable) {
            result.completeExceptionally(cause)
        }
    }

    private fun readAsciiLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value == -1) return if (line.size() == 0) null else line.toString(Charsets.US_ASCII)
            if (value == '\n'.code) {
                val bytes = line.toByteArray()
                val length = bytes.size - if (bytes.lastOrNull() == '\r'.code.toByte()) 1 else 0
                return String(bytes, 0, length, Charsets.US_ASCII)
            }
            line.write(value)
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

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object { const val MIB = 1024L * 1024 }
}
