package com.openandroidintelligence.gateway.attachments

import com.openandroidintelligence.gateway.http.GatewayRequestBody
import java.security.MessageDigest

/** Records the attachment calls so the uploader can be proven without a network. */
class RecordingGatewayClient : GatewayAttachmentTransport {

    val calls = mutableListOf<String>()

    var created: AttachmentCreateRequest? = null
        private set
    var contentUploaded: ByteArray? = null
        private set
    var contentSizeBytes: Long? = null
        private set
    var contentSha256Hex: String? = null
        private set
    var contentHeaders: Map<String, String>? = null
        private set

    var commitShouldFail = false
    var remoteState: AttachmentRemoteStatus = AttachmentRemoteStatus.STAGED

    override suspend fun create(request: AttachmentCreateRequest): String {
        calls += "create"
        created = request
        return "att-server-1"
    }

    override suspend fun getStatus(attachmentId: String): AttachmentRemoteStatusInfo {
        calls += "status"
        val request = requireNotNull(created)
        return AttachmentRemoteStatusInfo(remoteState, request.sizeBytes, request.sha256)
    }

    override suspend fun uploadContent(
        attachmentId: String,
        body: GatewayRequestBody,
        headers: Map<String, String>,
    ) {
        calls += "content"
        val digest = MessageDigest.getInstance("SHA-256")
        val small = if (body.contentLength <= 1024 * 1024) java.io.ByteArrayOutputStream() else null
        var length = 0L
        body.openStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
                small?.write(buffer, 0, read)
                length += read
            }
        }
        contentSizeBytes = length
        contentSha256Hex = digest.digest().joinToString("") { "%02x".format(it) }
        contentUploaded = small?.toByteArray()
        contentHeaders = headers
        remoteState = AttachmentRemoteStatus.STAGED
    }

    override suspend fun commit(attachmentId: String) {
        calls += "commit"
        if (commitShouldFail) throw IllegalStateException("server rejected the commit")
        remoteState = AttachmentRemoteStatus.UPLOADED
    }
}
