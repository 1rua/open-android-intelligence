package com.openandroidintelligence.gateway.attachments

import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.GatewayRequestBody
import com.openandroidintelligence.gateway.http.RawHeader
import com.openandroidintelligence.gateway.http.SignedGatewayRequest
import com.openandroidintelligence.gateway.http.requireData
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue

/**
 * The three attachment endpoints over the signed Gateway client.
 *
 * The three steps are the contract's whole attachment lifecycle: `create`
 * reserves a staging record, `content` carries the bytes with the digest the
 * Gateway will check, and `commit` is the only step that can turn the upload
 * into a verifiable attachment. Anything the server rejects stops here and is
 * reported as a code, never as a partially successful upload.
 */
class HttpAttachmentTransport(
    private val http: GatewayHttpClient,
) : GatewayAttachmentTransport {

    override suspend fun create(request: AttachmentCreateRequest): String {
        val payload = mutableMapOf<String, Any?>(
            "clientAttachmentId" to request.clientAttachmentId,
            "filename" to request.filename,
            "mediaType" to request.mediaType,
            "sizeBytes" to request.sizeBytes,
            "sha256" to request.sha256,
        )
        request.visualContext?.let { vc ->
            payload["visualContext"] = mapOf(
                "bounds" to mapOf(
                    "left" to vc.bounds.left,
                    "top" to vc.bounds.top,
                    "right" to vc.bounds.right,
                    "bottom" to vc.bounds.bottom,
                ),
                "displayMetrics" to mapOf(
                    "widthPixels" to vc.displayMetrics.widthPixels,
                    "heightPixels" to vc.displayMetrics.heightPixels,
                    "densityDpi" to vc.displayMetrics.densityDpi,
                ),
                "uiHierarchySummary" to vc.uiHierarchySummary,
            )
        }

        val response = http.execute(
            SignedGatewayRequest(
                method = "POST",
                target = "/open-android-intelligence/v2/attachments",
                headers = JSON_HEADERS,
                body = Json.canonical(Json.of(payload)).toByteArray(Charsets.UTF_8),
            ),
        )
        val data = response.requireData("ATTACHMENT_CREATE_FAILED")
        val attachment = JsonFields.obj(JsonFields.field(data, "attachment"))
        return JsonFields.string(attachment, "attachmentId")?.takeIf { WIRE_ID.matches(it) }
            ?: throw IllegalStateException("ATTACHMENT_CREATE_FAILED:malformed")
    }

    override suspend fun getStatus(attachmentId: String): AttachmentRemoteStatusInfo {
        val response = http.execute(
            SignedGatewayRequest(
                method = "GET",
                target = "/open-android-intelligence/v2/attachments/$attachmentId",
            ),
        )
        val data = response.requireData("ATTACHMENT_STATUS_FAILED")
        val attachment = JsonFields.obj(JsonFields.field(data, "attachment"))
            ?: throw IllegalStateException("ATTACHMENT_STATUS_FAILED:malformed")
        val responseAttachmentId = JsonFields.string(attachment, "attachmentId")
        val wireStatus = JsonFields.string(attachment, "status")
        val sizeBytes = JsonFields.long(attachment, "sizeBytes")
        val sha256 = JsonFields.string(attachment, "sha256")
        if (responseAttachmentId != attachmentId || sizeBytes == null || sizeBytes < 0L ||
            sha256 == null || !LOWERCASE_SHA256.matches(sha256)) {
            throw IllegalStateException("ATTACHMENT_STATUS_FAILED:malformed")
        }
        val status = AttachmentRemoteStatus.entries.firstOrNull { it.wireValue == wireStatus }
            ?: throw IllegalStateException("ATTACHMENT_STATUS_FAILED:unknown-status")
        return AttachmentRemoteStatusInfo(status, sizeBytes, sha256)
    }

    override suspend fun uploadContent(
        attachmentId: String,
        body: GatewayRequestBody,
        headers: Map<String, String>,
    ) {
        val response = http.execute(
            SignedGatewayRequest(
                method = "PUT",
                target = "/open-android-intelligence/v2/attachments/$attachmentId/content",
                headers = headers.map { (name, value) -> RawHeader(name, value) },
                streamBody = body,
            ),
        )
        response.requireData("ATTACHMENT_UPLOAD_FAILED")
    }

    override suspend fun commit(attachmentId: String) {
        val response = http.execute(
            SignedGatewayRequest(
                method = "POST",
                target = "/open-android-intelligence/v2/attachments/$attachmentId/commit",
                headers = JSON_HEADERS,
                body = Json.canonical(Json.of(emptyMap<String, Any?>())).toByteArray(Charsets.UTF_8),
            ),
        )
        val data = response.requireData("ATTACHMENT_COMMIT_FAILED")
        val attachment = JsonFields.obj(JsonFields.field(data, "attachment"))
        if (JsonFields.string(attachment, "attachmentId") != attachmentId ||
            JsonFields.string(attachment, "status") != "uploaded") {
            throw IllegalStateException("ATTACHMENT_COMMIT_FAILED:not-uploaded")
        }
    }

    private companion object {
        val WIRE_ID = Regex("[A-Za-z0-9._~-]{1,128}")
        val LOWERCASE_SHA256 = Regex("[a-f0-9]{64}")
        val JSON_HEADERS = listOf(
            RawHeader("Content-Type", "application/json"),
            RawHeader("Accept", "application/json"),
        )
    }
}
