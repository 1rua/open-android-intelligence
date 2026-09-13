package com.openandroidintelligence.gateway.attachments

import java.security.MessageDigest

data class AttachmentLimits(val maxBytes: Long)

enum class AttachmentUploadPhase { CREATE_PENDING, UPLOADING, VERIFYING }

data class SelectedAttachment(
    val filename: String,
    val mediaType: String,
    val content: ByteArray,
    /** Optional client-declared digest, accepted in bare or `sha256:` form. */
    val declaredSha256: String? = null,
    val visualContext: VisualAttachmentMetadata? = null,
)

data class AttachmentCreateRequest(
    val clientAttachmentId: String,
    val filename: String,
    val mediaType: String,
    val sizeBytes: Long,
    val sha256: String,
    val visualContext: VisualAttachmentMetadata? = null,
)

data class VisualAttachmentMetadata(
    val bounds: NormalizedCropBounds,
    val displayMetrics: DisplayDensityMetrics,
    val uiHierarchySummary: String? = null,
)

data class NormalizedCropBounds(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
)

data class DisplayDensityMetrics(
    val widthPixels: Int,
    val heightPixels: Int,
    val densityDpi: Int,
)

/**
 * The three attachment endpoints. Split out so the uploader can be proven
 * without a network stack.
 */
interface GatewayAttachmentTransport {
    suspend fun create(request: AttachmentCreateRequest): String

    suspend fun uploadContent(attachmentId: String, content: ByteArray, headers: Map<String, String>)

    suspend fun commit(attachmentId: String)
}

/**
 * Three-step attachment upload: create, content, commit.
 *
 * The byte count and SHA-256 are computed in one pass over the stream as it is
 * read, so what is declared to the server is what was actually read — not a
 * second, separately traversed copy that could disagree.
 */
class AttachmentUploader(
    private val transport: GatewayAttachmentTransport,
    private val limits: AttachmentLimits = AttachmentLimits(maxBytes = DEFAULT_MAX_BYTES),
) {

    suspend fun upload(attachment: SelectedAttachment, onPhase: ((AttachmentUploadPhase) -> Unit)? = null): String {
        if (attachment.content.size.toLong() > limits.maxBytes) {
            throw IllegalArgumentException("ATTACHMENT_TOO_LARGE:${attachment.content.size}")
        }

        val digest = MessageDigest.getInstance("SHA-256")
        var sizeBytes = 0L
        for (byte in attachment.content) {
            digest.update(byte)
            sizeBytes += 1
        }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it) }

        attachment.declaredSha256?.let { declared ->
            if (normalizeDigest(declared) != sha256) {
                throw IllegalArgumentException("DIGEST_MISMATCH:declared=$declared actual=$sha256")
            }
        }

        onPhase?.invoke(AttachmentUploadPhase.CREATE_PENDING)
        val attachmentId = transport.create(
            AttachmentCreateRequest(
                clientAttachmentId = "att_${sha256.take(32)}",
                filename = attachment.filename,
                mediaType = attachment.mediaType,
                sizeBytes = sizeBytes,
                sha256 = sha256,
                visualContext = attachment.visualContext,
            ),
        )

        onPhase?.invoke(AttachmentUploadPhase.UPLOADING)
        transport.uploadContent(
            attachmentId,
            attachment.content,
            mapOf(
                "Content-Length" to sizeBytes.toString(),
                "Digest" to "sha-256=" + base64(hexToBytes(sha256)),
            ),
        )

        onPhase?.invoke(AttachmentUploadPhase.VERIFYING)
        try {
            transport.commit(attachmentId)
        } catch (cause: kotlinx.coroutines.CancellationException) {
            throw cause
        } catch (cause: Exception) {
            throw IllegalStateException("COMMIT_FAILED:$attachmentId: ${cause.message}", cause)
        }
        return attachmentId
    }

    private fun normalizeDigest(value: String): String =
        value.removePrefix("sha256:").lowercase()

    private fun hexToBytes(hex: String): ByteArray {
        val bytes = ByteArray(hex.length / 2)
        for (index in bytes.indices) {
            bytes[index] = hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
        return bytes
    }

    private fun base64(bytes: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(bytes)

    companion object {
        const val DEFAULT_MAX_BYTES = 25L * 1024 * 1024
    }
}
