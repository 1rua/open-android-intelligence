package com.openandroidintelligence.gateway.negotiation

import com.openandroidintelligence.gateway.http.GatewayResponse
import com.openandroidintelligence.gateway.http.RawHeader
import com.openandroidintelligence.gateway.http.SignedGatewayRequest
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.SchemaContractHash

/** What one connection actually negotiated. */
data class NegotiatedLimits(
    val maxSingleAttachmentBytes: Long?,
    val maxMessageAttachmentBytes: Long?,
    val allowedMediaTypes: List<String>,
    val attachmentTtlSeconds: Long?,
    val eventRetentionSeconds: Long?,
)

data class NegotiationResult(
    val negotiationId: String,
    val protocolMajor: Int,
    val protocolMinor: Int,
    val deploymentId: String?,
    val tlsSpkiSha256: String?,
    val messages: String?,
    val attachments: String?,
    val events: String?,
    val deviceRequests: String?,
    val limits: NegotiatedLimits,
    /** Conversation-surface features this connection actually agreed on. */
    val conversationUi: List<String> = emptyList(),
)

/**
 * Protocol negotiation, run before authentication.
 *
 * The endpoint carries no secrets, so the executor is injectable: pre-auth the
 * caller backs it with the plain HTTPS transport, because the signed client
 * cannot exist before a session does.
 */
class NegotiationClient(
    private val execute: suspend (SignedGatewayRequest) -> GatewayResponse,
    private val installationId: String,
    private val appVersion: String,
    private val platformApi: Int,
) {

    suspend fun negotiate(negotiationId: String): NegotiationResult {
        val payload = mapOf(
            "negotiationId" to negotiationId,
            "protocol" to mapOf("major" to PROTOCOL_MAJOR, "minor" to PROTOCOL_MINOR),
            "client" to mapOf(
                "installationId" to installationId,
                "appVersion" to appVersion,
                "platform" to "android",
                "platformApi" to platformApi,
            ),
            "features" to mapOf(
                // Only what this client actually implements: requesting a
                // capability the app cannot serve would make the agreement a
                // claim rather than a fact. Contract section 4 keeps the base
                // session capabilities in `messages`/`attachments` and the
                // conversation-surface ladder in `conversationUi`.
                "auth" to AUTH_FEATURES,
                "messages" to listOf("chat-v1"),
                "attachments" to listOf("staged-sha256-v1"),
                "events" to listOf("sse-cursor-v1"),
                "deviceRequests" to listOf("risk-queue-v1"),
                "conversationUi" to CONVERSATION_UI_FEATURES,
            ),
            // The Gateway compares this against the Schema documents it ships.
            // A placeholder or a missing value is a refused negotiation.
            "schemaHashes" to mapOf("core" to SchemaContractHash.CORE),
        )
        val response = execute(
            SignedGatewayRequest(
                method = "POST",
                target = "/open-android-intelligence/v2/negotiate",
                headers = listOf(
                    RawHeader("Content-Type", "application/json"),
                    RawHeader("Accept", "application/json"),
                ),
                body = Json.canonical(Json.of(payload)).toByteArray(Charsets.UTF_8),
            ),
        )
        if (response.status == 406 || response.status == 409) {
            throw IllegalStateException("PROTOCOL_INCOMPATIBLE:${response.status}")
        }
        if (response.status !in 200..299) {
            throw IllegalStateException("NEGOTIATION_FAILED:${response.status}")
        }
        val body = JsonFields.obj(
            runCatching { Json.parse(String(response.body, Charsets.UTF_8)) }.getOrNull(),
        ) ?: throw IllegalStateException("NEGOTIATION_FAILED:malformed")

        val result = JsonFields.obj(JsonFields.field(body, "data")) ?: body
        val protocol = JsonFields.obj(JsonFields.field(result, "protocol"))
        val features = JsonFields.obj(JsonFields.field(result, "features"))
        val limits = JsonFields.obj(JsonFields.field(result, "limits"))
        val identity = JsonFields.obj(JsonFields.field(result, "gatewayIdentity"))

        return NegotiationResult(
            negotiationId = JsonFields.string(result, "negotiationId") ?: negotiationId,
            protocolMajor = JsonFields.int(protocol, "major") ?: PROTOCOL_MAJOR,
            protocolMinor = JsonFields.int(protocol, "minor") ?: PROTOCOL_MINOR,
            deploymentId = JsonFields.string(identity, "deploymentId"),
            tlsSpkiSha256 = JsonFields.string(identity, "tlsSpkiSha256"),
            messages = JsonFields.string(features, "messages"),
            attachments = JsonFields.string(features, "attachments"),
            events = JsonFields.string(features, "events"),
            deviceRequests = JsonFields.string(features, "deviceRequests"),
            limits = NegotiatedLimits(
                maxSingleAttachmentBytes = JsonFields.long(limits, "maxSingleAttachmentBytes"),
                maxMessageAttachmentBytes = JsonFields.long(limits, "maxMessageAttachmentBytes"),
                allowedMediaTypes = JsonFields.strings(limits, "allowedMediaTypes"),
                attachmentTtlSeconds = JsonFields.long(limits, "attachmentTtlSeconds"),
                eventRetentionSeconds = JsonFields.long(limits, "eventRetentionSeconds"),
            ),
            conversationUi = JsonFields.strings(features, "conversationUi"),
        )
    }

    private companion object {
        const val PROTOCOL_MAJOR = 2
        const val PROTOCOL_MINOR = 0

        /** Authentication flows this client implements today. */
        val AUTH_FEATURES = listOf("password", "refresh")

        /**
         * Conversation-surface features this client implements today:
         * the command catalog, message batches and generation cancel endpoints
         * are all served by [com.openandroidintelligence.gateway.http.GatewayHttpClient].
         */
        val CONVERSATION_UI_FEATURES = listOf(
            "agent-command-catalog-v1",
            "message-batches-v1",
            "generation-cancel-v1",
        )
    }
}
