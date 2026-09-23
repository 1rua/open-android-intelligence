package com.openandroidintelligence.gateway.attachments

import com.openandroidintelligence.gateway.http.GatewayRequestBody

enum class AttachmentUploadPhase { CREATE_PENDING, UPLOADING, VERIFYING }

enum class AttachmentRemoteStatus(val wireValue: String) {
    STAGED("staged"),
    UPLOADED("uploaded"),
    FAILED("failed"),
    EXPIRED("expired"),
}

data class AttachmentRemoteStatusInfo(
    val status: AttachmentRemoteStatus,
    val sizeBytes: Long,
    val sha256: String,
)

data class SelectedAttachment(
    val filename: String,
    val mediaType: String,
    val body: GatewayRequestBody,
    /** Stable across retries of this staged selection. */
    val clientAttachmentId: String? = null,
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
 * The attachment lifecycle endpoints. Split out so the uploader can be proven
 * without a network stack.
 */
interface GatewayAttachmentTransport {
    suspend fun create(request: AttachmentCreateRequest): String

    suspend fun getStatus(attachmentId: String): AttachmentRemoteStatusInfo

    suspend fun uploadContent(attachmentId: String, body: GatewayRequestBody, headers: Map<String, String>)

    suspend fun commit(attachmentId: String)
}

/**
 * Upload flow: create idempotently, query the public status, then upload and commit if still staged.
 *
 * The encrypted staging pass records the exact byte count and SHA-256. Each
 * HTTP replay is then streamed and checked again against those recorded values.
 */
class AttachmentUploader(
    private val transport: GatewayAttachmentTransport,
) {

    suspend fun upload(attachment: SelectedAttachment, onPhase: ((AttachmentUploadPhase) -> Unit)? = null): String {
        val sizeBytes = attachment.body.contentLength
        val sha256 = attachment.body.sha256Hex

        attachment.declaredSha256?.let { declared ->
            if (normalizeDigest(declared) != sha256) {
                throw IllegalArgumentException("DIGEST_MISMATCH:declared=$declared actual=$sha256")
            }
        }

        onPhase?.invoke(AttachmentUploadPhase.CREATE_PENDING)
        val attachmentId = transport.create(
            AttachmentCreateRequest(
                clientAttachmentId = attachment.clientAttachmentId ?: "att_${sha256.take(32)}",
                filename = attachment.filename,
                mediaType = attachment.mediaType,
                sizeBytes = sizeBytes,
                sha256 = sha256,
                visualContext = attachment.visualContext,
            ),
        )

        val remoteState = try {
            transport.getStatus(attachmentId)
        } catch (cause: kotlinx.coroutines.CancellationException) {
            throw cause
        } catch (cause: Exception) {
            throw IllegalStateException("ATTACHMENT_STATUS_UNKNOWN:$attachmentId", cause)
        }
        if (remoteState.sizeBytes != sizeBytes || remoteState.sha256 != sha256) {
            throw IllegalStateException("ATTACHMENT_STATUS_MISMATCH:$attachmentId")
        }
        when (remoteState.status) {
            AttachmentRemoteStatus.UPLOADED -> return attachmentId
            AttachmentRemoteStatus.STAGED -> {
                onPhase?.invoke(AttachmentUploadPhase.UPLOADING)
                transport.uploadContent(
                    attachmentId,
                    attachment.body,
                    mapOf(
                        "Content-Length" to sizeBytes.toString(),
                        "Digest" to "sha-256=" + base64(hexToBytes(sha256)),
                    ),
                )
            }
            AttachmentRemoteStatus.FAILED, AttachmentRemoteStatus.EXPIRED ->
                throw IllegalStateException("ATTACHMENT_ATTEMPT_TERMINAL:${remoteState.status.wireValue}")
        }

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

    private fun hexToBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { index ->
        hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private fun base64(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)
}
