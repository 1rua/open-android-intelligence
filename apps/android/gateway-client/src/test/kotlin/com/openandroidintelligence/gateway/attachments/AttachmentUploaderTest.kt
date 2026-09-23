package com.openandroidintelligence.gateway.attachments

import com.openandroidintelligence.gateway.http.GatewayRequestBody
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The uploader declares size and SHA-256 up front, then the server commits only
 * on an exact match. Anything that would let a declared digest disagree with the
 * bytes actually sent must fail closed before content is uploaded.
 */
class AttachmentUploaderTest {

    @Test
    fun testUploadWithVisualContext() = runBlocking {
        val transport = recorder()
        val uploader = AttachmentUploader(transport)
        val vc = VisualAttachmentMetadata(
            bounds = NormalizedCropBounds(0.0, 0.0, 1.0, 1.0),
            displayMetrics = DisplayDensityMetrics(1080, 1920, 420),
            uiHierarchySummary = "RootView",
        )
        val id = uploader.upload(
            SelectedAttachment(
                filename = "screen.png",
                mediaType = "image/png",
                body = GatewayRequestBody.fromBytes("PNGDATA".toByteArray(Charsets.UTF_8)),
                visualContext = vc,
            ),
        )
        assertEquals("att-server-1", id)
        assertEquals(vc, transport.created?.visualContext)
    }

    private fun recorder() = RecordingGatewayClient()

    private fun uploader(client: RecordingGatewayClient = recorder()) = AttachmentUploader(client)

    @Test
    fun declaresSizeAndDigestFromTheStreamedBytes() = runBlocking {
        val client = recorder()
        val uploader = uploader(client)
        val content = "hello gateway".toByteArray()

        uploader.upload(SelectedAttachment("report.txt", "text/plain", GatewayRequestBody.fromBytes(content)))

        assertEquals(content.size.toLong(), client.created?.sizeBytes)
        assertEquals(sha256Hex(content), client.created?.sha256)
    }

    @Test
    fun digestMismatchFailsBeforeContentIsSent() = runBlocking {
        val client = recorder()
        val uploader = uploader(client)

        val failure = runCatching {
            uploader.upload(
                SelectedAttachment(
                    filename = "report.txt",
                    mediaType = "text/plain",
                    body = GatewayRequestBody.fromBytes("actual bytes".toByteArray()),
                    declaredSha256 = "sha256:" + "a".repeat(64),
                ),
            )
        }.exceptionOrNull()

        assertTrue("a wrong declared digest must fail closed", failure != null)
        assertTrue(failure!!.message!!.contains("DIGEST_MISMATCH"))
        assertEquals("content must not be uploaded when the digest disagrees", null, client.contentSizeBytes)
    }

    @Test
    fun attachmentsLargerThanTheOldProductLimitAreUploadedAndVerifiedByActualBytes() = runBlocking {
        val client = recorder()
        val size = 25L * 1024 * 1024 + 1
        val expectedSha256 = patternedSha256(size)
        val body = GatewayRequestBody(
            contentLength = size,
            sha256Hex = expectedSha256,
            openStream = { PatternInputStream(size) },
        )

        uploader(client).upload(SelectedAttachment("big.bin", "image/x-unlisted", body))

        assertEquals(size, client.created?.sizeBytes)
        assertEquals(expectedSha256, client.created?.sha256)
        assertEquals(size, client.contentSizeBytes)
        assertEquals(expectedSha256, client.contentSha256Hex)
        assertEquals(listOf("create", "status", "content", "commit"), client.calls)
    }

    @Test
    fun contentRequestCarriesContentLengthAndDigest() = runBlocking {
        val client = recorder()
        val uploader = uploader(client)
        val content = "payload".toByteArray()

        uploader.upload(SelectedAttachment("f.bin", "application/octet-stream", GatewayRequestBody.fromBytes(content)))

        assertEquals(content.size.toLong(), client.contentHeaders?.get("Content-Length")?.toLong())
        assertEquals("sha-256=" + base64(content.sha256()), client.contentHeaders?.get("Digest"))
    }

    @Test
    fun uploadQueriesStatusBetweenCreateAndContentThenCommits() = runBlocking {
        val client = recorder()
        val uploader = uploader(client)

        uploader.upload(SelectedAttachment("f.bin", "application/octet-stream", GatewayRequestBody.fromBytes("abc".toByteArray())))

        assertEquals(listOf("create", "status", "content", "commit"), client.calls)
    }

    @Test
    fun anUploadedRemoteAttachmentIsReturnedWithoutRepeatingItsContentOrCommit() = runBlocking {
        val client = recorder().apply { remoteState = AttachmentRemoteStatus.UPLOADED }

        val id = uploader(client).upload(
            SelectedAttachment(
                filename = "note.txt",
                mediaType = "text/plain",
                body = GatewayRequestBody.fromBytes("same attachment".toByteArray()),
                clientAttachmentId = "att_client_stable_1",
            ),
        )

        assertEquals("att-server-1", id)
        assertEquals("att_client_stable_1", client.created?.clientAttachmentId)
        assertEquals(listOf("create", "status"), client.calls)
    }

    @Test
    fun failedOrExpiredRemoteAttachmentsAreNotReuploaded() = runBlocking {
        listOf(AttachmentRemoteStatus.FAILED, AttachmentRemoteStatus.EXPIRED).forEach { status ->
            val client = recorder().apply { remoteState = status }
            val failure = runCatching {
                uploader(client).upload(
                    SelectedAttachment("f.bin", "application/octet-stream", GatewayRequestBody.fromBytes(byteArrayOf(1))),
                )
            }.exceptionOrNull()

            assertTrue("$status must be terminal", failure?.message.orEmpty().contains("ATTACHMENT_ATTEMPT_TERMINAL"))
            assertEquals(listOf("create", "status"), client.calls)
        }
    }

    @Test
    fun commitFailurePropagatesAndDoesNotReportSuccess() = runBlocking {
        val client = recorder().apply { commitShouldFail = true }
        val uploader = uploader(client)

        val failure = runCatching {
            uploader.upload(SelectedAttachment("f.bin", "application/octet-stream", GatewayRequestBody.fromBytes("abc".toByteArray())))
        }.exceptionOrNull()

        assertTrue("a rejected commit must not look like success", failure != null)
        assertTrue(failure!!.message!!.contains("COMMIT_FAILED"))
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun patternedSha256(size: Long): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        var offset = 0L
        while (offset < size) {
            val count = minOf(buffer.size.toLong(), size - offset).toInt()
            for (index in 0 until count) buffer[index] = patternByte(offset + index)
            digest.update(buffer, 0, count)
            offset += count
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun patternByte(offset: Long): Byte = ((offset * 31 + 7) and 0xff).toByte()

    private inner class PatternInputStream(private val size: Long) : InputStream() {
        private var offset = 0L
        override fun read(): Int {
            if (offset >= size) return -1
            return patternByte(offset++).toInt() and 0xff
        }
        override fun read(target: ByteArray, start: Int, length: Int): Int {
            if (offset >= size) return -1
            val count = minOf(length.toLong(), size - offset).toInt()
            for (index in 0 until count) target[start + index] = patternByte(offset + index)
            offset += count
            return count
        }
    }

    private fun base64(bytes: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(bytes)

    private fun ByteArray.sha256(): ByteArray = java.security.MessageDigest.getInstance("SHA-256").digest(this)
}
