package com.openandroidintelligence.gateway.agents

import com.openandroidintelligence.gateway.approvals.ApprovalClient
import com.openandroidintelligence.gateway.approvals.ApprovalDecision
import com.openandroidintelligence.gateway.approvals.ApprovalDecisionAudit
import com.openandroidintelligence.gateway.approvals.ApprovalDecisionResult
import com.openandroidintelligence.gateway.commands.CommandCatalog
import com.openandroidintelligence.gateway.commands.CommandCatalogClient
import com.openandroidintelligence.gateway.events.SseParser
import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.RawHeader
import com.openandroidintelligence.gateway.http.RawHeaders
import com.openandroidintelligence.gateway.http.SignedGatewayRequest
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.gateway.schema.JsonValue
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.io.InputStream

data class AgentCapabilities(
    val list: Boolean = false, val history: Boolean = false,
    val create: Boolean = false, val send: Boolean = false, val steer: Boolean = false,
    val rename: Boolean = false, val archive: Boolean = false,
    val delete: Boolean = false, val cancel: Boolean = false, val stream: Boolean = false,
    val commands: Boolean = false, val approvals: Boolean = false, val attachments: Boolean = false,
)
data class CoreAgent(val id: String, val name: String, val kind: String, val online: Boolean,
    val sessionScope: String, val capabilities: AgentCapabilities)
data class NativeAgentSession(val id: String, val title: String?, val updatedAt: String?, val archived: Boolean)
data class NativeAgentAttachment(val id: String, val filename: String, val mediaType: String,
    val sizeBytes: Long? = null, val sha256: String? = null)
data class NativeAgentMessage(val id: String, val role: String, val text: String, val createdAt: String?,
    val clientMessageId: String? = null, val attachments: List<NativeAgentAttachment> = emptyList())
data class NativeGenerationResult(val outcome: String, val generationId: String?, val messageId: String? = null,
    val userMessageId: String? = null, val clientMessageId: String? = null, val text: String? = null)
data class NativeSteeringResult(val generationId: String, val outcome: String)
data class NativeCurrentGeneration(val generationId: String, val conversationId: String, val state: String)
sealed interface NativeAgentStreamEvent {
    data class Generation(val generationId: String) : NativeAgentStreamEvent
    data class ApprovalRequested(val approval: JsonValue.JObject) : NativeAgentStreamEvent
    data class ApprovalResolved(val approval: JsonValue.JObject) : NativeAgentStreamEvent
    data class MessageIdentity(val messageId: String?, val userMessageId: String?, val clientMessageId: String?) : NativeAgentStreamEvent
}
class AgentSessionException(val code: String, val status: Int = 0) : IllegalStateException(code)

/** All native session operations use the already authenticated Core connection. */
class AgentSessionClient(private val http: GatewayHttpClient) {
    suspend fun agents(): List<CoreAgent> = objects(request("GET", ROOT), "agents").map { value ->
        val caps = JsonFields.obj(JsonFields.field(value, "capabilities")) ?: error("AGENTS_INVALID")
        fun cap(name: String) = JsonFields.bool(caps, name) == true
        CoreAgent(required(value, "id"), required(value, "name"), required(value, "kind"),
            JsonFields.bool(value, "online") ?: false,
            required(value, "sessionScope").also { check(it in setOf("account", "deployment")) { "AGENTS_INVALID" } },
            AgentCapabilities(cap("list"), cap("history"), cap("create"), cap("send"), cap("steer"), cap("rename"),
                cap("archive"), cap("delete"), cap("cancel"), cap("stream"), cap("commands"), cap("approvals"), cap("attachments")))
    }

    suspend fun sessions(agent: CoreAgent): List<NativeAgentSession> {
        requireCapability(agent.capabilities.list)
        return objects(request("GET", sessionsTarget(agent)), "sessions").map(::session)
    }

    suspend fun history(agent: CoreAgent, sessionId: String): List<NativeAgentMessage> {
        requireCapability(agent.capabilities.history)
        return objects(request("GET", sessionTarget(agent, sessionId) + "/history"), "messages").map {
            NativeAgentMessage(required(it, "id"), required(it, "role"),
                JsonFields.string(it, "text") ?: error("AGENT_HISTORY_INVALID"), timestamp(it, "createdAt"),
                JsonFields.string(it, "clientMessageId"), JsonFields.objects(it, "attachments").map { attachment ->
                    NativeAgentAttachment(required(attachment, "attachmentId"), required(attachment, "filename"),
                        required(attachment, "mediaType"), JsonFields.long(attachment, "sizeBytes"), JsonFields.string(attachment, "sha256"))
                })
        }
    }

    suspend fun create(agent: CoreAgent, title: String? = null): NativeAgentSession {
        requireCapability(agent.capabilities.create && agent.online)
        val value = request("POST", sessionsTarget(agent), title?.let { mapOf("title" to it) } ?: emptyMap())
        return session(JsonFields.obj(JsonFields.field(value, "session")) ?: error("AGENT_SESSION_INVALID"))
    }

    suspend fun rename(agent: CoreAgent, sessionId: String, title: String) {
        requireCapability(agent.capabilities.rename && agent.online)
        require(title.isNotBlank())
        request("PATCH", sessionTarget(agent, sessionId), mapOf("action" to "rename", "value" to title))
    }

    suspend fun archive(agent: CoreAgent, sessionId: String) {
        requireCapability(agent.capabilities.archive && agent.online)
        request("PATCH", sessionTarget(agent, sessionId), mapOf("action" to "archive"))
    }

    suspend fun delete(agent: CoreAgent, sessionId: String) {
        requireCapability(agent.capabilities.delete && agent.online)
        request("DELETE", sessionTarget(agent, sessionId))
    }

    suspend fun commands(agent: CoreAgent, languageCode: String): CommandCatalog {
        requireCapability(agent.capabilities.commands && agent.online)
        return CommandCatalogClient.decode(request("GET", "$ROOT/${segment(agent.id)}/commands?languageCode=${segment(languageCode)}"), languageCode)
    }

    suspend fun submitApprovalDecision(agent: CoreAgent, sessionId: String, approvalId: String,
        decision: ApprovalDecision, audit: ApprovalDecisionAudit? = null): ApprovalDecisionResult {
        requireCapability(agent.capabilities.approvals && agent.online)
        return ApprovalClient(http, audit) { id -> "${sessionTarget(agent, sessionId)}/approvals/${segment(id)}/decisions" }
            .submitDecision(approvalId, decision)
    }

    suspend fun cancel(agent: CoreAgent, sessionId: String, generationId: String? = null, requestId: String? = null): NativeGenerationResult {
        requireCapability(agent.capabilities.cancel && agent.online)
        val exactGeneration = agent.kind == "hermes"
        if (exactGeneration && generationId.isNullOrBlank()) throw AgentSessionException("GENERATION_ID_REQUIRED")
        val payload = if (exactGeneration) mapOf("generationId" to generationId) else emptyMap()
        val value = request("POST", sessionTarget(agent, sessionId) + "/cancel", payload, requestId)
        val receipt = JsonFields.obj(JsonFields.field(value, "session")) ?: throw AgentSessionException("OUTCOME_UNKNOWN")
        val returnedId = JsonFields.string(receipt, "generationId")
        if (exactGeneration && returnedId != generationId) throw AgentSessionException("OUTCOME_UNKNOWN")
        val outcome = JsonFields.string(receipt, "outcome")
        return NativeGenerationResult(outcome?.takeIf { it in setOf("cancelled", "already_completed") } ?: "outcome_unknown", returnedId ?: generationId)
    }

    suspend fun currentGeneration(agent: CoreAgent, sessionId: String): NativeCurrentGeneration? {
        requireCapability(agent.capabilities.send && agent.online)
        val value = request("GET", sessionTarget(agent, sessionId) + "/generation")
        val generation = when (val field = JsonFields.field(value, "generation")) {
            null, is JsonValue.JNull -> null
            else -> JsonFields.obj(field) ?: throw AgentSessionException("AGENT_RESPONSE_INVALID")
        } ?: return null
        val generationId = JsonFields.string(generation, "generationId")
            ?.takeIf(String::isNotBlank) ?: throw AgentSessionException("AGENT_RESPONSE_INVALID")
        val conversationId = JsonFields.string(generation, "conversationId")
            ?.takeIf { it == sessionId } ?: throw AgentSessionException("AGENT_RESPONSE_INVALID")
        val state = JsonFields.string(generation, "state")
            ?.takeIf { it in setOf("queued", "running", "unknown") }
            ?: throw AgentSessionException("AGENT_RESPONSE_INVALID")
        return NativeCurrentGeneration(generationId, conversationId, state)
    }

    suspend fun steer(
        agent: CoreAgent,
        sessionId: String,
        generationId: String,
        text: String,
        clientMessageId: String,
    ): NativeSteeringResult {
        requireCapability(agent.capabilities.steer && agent.online)
        if (generationId.isBlank() || text.isBlank() || clientMessageId.isBlank())
            throw AgentSessionException("INVALID_REQUEST")
        val value = request(
            "POST",
            sessionTarget(agent, sessionId) + "/steer",
            mapOf(
                "text" to text,
                "generationId" to generationId,
                "clientMessageId" to clientMessageId,
            ),
        )
        val steering = JsonFields.obj(JsonFields.field(value, "steering"))
            ?: throw AgentSessionException("AGENT_RESPONSE_INVALID")
        val returnedGeneration = JsonFields.string(steering, "generationId")
        val outcome = JsonFields.string(steering, "outcome")
        if (returnedGeneration != generationId || outcome != "accepted")
            throw AgentSessionException("OUTCOME_UNKNOWN")
        return NativeSteeringResult(returnedGeneration, outcome)
    }

    /** An interrupted stream is an unknown outcome, never an automatic resend. */
    suspend fun send(agent: CoreAgent, sessionId: String, text: String,
        attachmentIds: List<String> = emptyList(), clientMessageId: String? = null,
        onEvent: suspend (NativeAgentStreamEvent) -> Unit = {},
        onDelta: suspend (String) -> Unit): NativeGenerationResult {
        if (!agent.online) throw AgentSessionException("AGENT_OFFLINE")
        requireCapability(agent.capabilities.send && agent.capabilities.stream)
        if (text.isBlank() && attachmentIds.isEmpty()) throw AgentSessionException("INVALID_MESSAGE")
        if (attachmentIds.any(String::isBlank) || attachmentIds.distinct().size != attachmentIds.size)
            throw AgentSessionException("INVALID_ATTACHMENT_IDS")
        if (attachmentIds.isNotEmpty() && !agent.capabilities.attachments) throw AgentSessionException("UNSUPPORTED_CAPABILITY")
        val payload = mutableMapOf<String, Any?>("text" to text)
        if (attachmentIds.isNotEmpty()) payload["attachmentIds"] = attachmentIds
        if (clientMessageId != null) payload["clientMessageId"] = clientMessageId
        val request = jsonRequest("POST", sessionTarget(agent, sessionId) + "/messages", payload)
            .copy(headers = listOf(RawHeader("Content-Type", "application/json"), RawHeader("Accept", "text/event-stream")))
        return try {
            http.consumeResponse(request) { status, headers, stream ->
                if (status !in 200..299) {
                    val failure = try { readErrorBody(stream)?.let(::responseRoot) }
                    catch (_: IOException) { null }
                    throw AgentSessionException(errorCode(failure, "AGENT_SEND_FAILED"), status)
                }
                check(RawHeaders.single(headers, "Content-Type")?.substringBefore(';')?.trim() == "text/event-stream") {
                    "AGENT_STREAM_INVALID"
                }
                val parser = SseParser()
                val buffer = ByteArray(8 * 1024)
                var done = false
                var generationId: String? = null
                var outcome = "completed"
                var finalText: String? = null
                var identity = NativeAgentStreamEvent.MessageIdentity(null, null, null)
                while (!done) {
                    currentCoroutineContext().ensureActive()
                    val read = stream.read(buffer)
                    if (read < 0) break
                    for (event in parser.feedBytes(buffer.copyOf(read))) {
                        if (event.event != "message") continue
                        val value = JsonFields.obj(Json.parse(event.data)) ?: error("AGENT_STREAM_INVALID")
                        val nextIdentity = NativeAgentStreamEvent.MessageIdentity(
                            JsonFields.string(value, "messageId") ?: identity.messageId,
                            JsonFields.string(value, "userMessageId") ?: identity.userMessageId,
                            JsonFields.string(value, "clientMessageId") ?: identity.clientMessageId)
                        if (nextIdentity != identity) {
                            identity = nextIdentity
                            if (identity.clientMessageId != null && clientMessageId != null && identity.clientMessageId != clientMessageId)
                                throw AgentSessionException("AGENT_STREAM_INVALID")
                            onEvent(identity)
                        }
                        when (JsonFields.string(value, "type")) {
                            "delta" -> onDelta(JsonFields.string(value, "text") ?: error("AGENT_STREAM_INVALID"))
                            "generation" -> {
                                val id = required(value, "generationId")
                                if (generationId != null && generationId != id) throw AgentSessionException("AGENT_STREAM_INVALID")
                                generationId = id
                                onEvent(NativeAgentStreamEvent.Generation(id))
                            }
                            "approval_requested", "approval_resolved" -> {
                                val approval = JsonFields.obj(JsonFields.field(value, "approval")) ?: error("AGENT_STREAM_INVALID")
                                onEvent(if (JsonFields.string(value, "type") == "approval_requested")
                                    NativeAgentStreamEvent.ApprovalRequested(approval) else NativeAgentStreamEvent.ApprovalResolved(approval))
                            }
                            "done" -> {
                                finalText = JsonFields.string(value, "text")
                                val returnedId = JsonFields.string(value, "generationId")
                                if (returnedId != null && generationId != null && returnedId != generationId)
                                    throw AgentSessionException("AGENT_STREAM_INVALID")
                                generationId = returnedId ?: generationId
                                outcome = JsonFields.string(value, "outcome") ?: "completed"
                                if (outcome !in setOf("completed", "cancelled")) throw AgentSessionException("OUTCOME_UNKNOWN")
                                done = true
                                break
                            }
                            "error" -> throw AgentSessionException(JsonFields.string(value, "code") ?: "AGENT_SEND_FAILED")
                            else -> error("AGENT_STREAM_INVALID")
                        }
                    }
                }
                if (!done) throw AgentSessionException("OUTCOME_UNKNOWN")
                NativeGenerationResult(outcome, generationId, identity.messageId, identity.userMessageId, identity.clientMessageId, finalText)
            }
        } catch (_: IOException) {
            // A transport failure cannot establish whether the native turn ran.
            // Preserve the draft and partial output; never resend it here.
            throw AgentSessionException("OUTCOME_UNKNOWN")
        }
    }

    private suspend fun request(method: String, target: String, body: Map<String, Any?>? = null, requestId: String? = null): JsonValue.JObject {
        val response = http.execute(jsonRequest(method, target, body).copy(requestId = requestId))
        val root = responseRoot(response.body)
        if (response.status !in 200..299) {
            throw AgentSessionException(errorCode(root, "AGENT_REQUEST_FAILED"), response.status)
        }
        return JsonFields.obj(JsonFields.field(root, "data")) ?: root ?: error("AGENT_RESPONSE_INVALID")
    }

    private fun responseRoot(body: ByteArray): JsonValue.JObject? =
        runCatching { JsonFields.obj(Json.parse(body.decodeToString())) }.getOrNull()

    private fun errorCode(root: JsonValue.JObject?, fallback: String): String =
        JsonFields.string(JsonFields.obj(JsonFields.field(root, "error")), "code") ?: fallback

    /** Error responses are small JSON envelopes; never buffer an unbounded rejected stream. */
    private fun readErrorBody(stream: InputStream): ByteArray? {
        val buffer = ByteArray(MAX_ERROR_BODY_BYTES + 1)
        var size = 0
        while (size < buffer.size) {
            val read = stream.read(buffer, size, buffer.size - size)
            if (read < 0) return buffer.copyOf(size)
            size += read
        }
        return null
    }

    private fun jsonRequest(method: String, target: String, body: Map<String, Any?>?): SignedGatewayRequest =
        SignedGatewayRequest(method, target, body?.let { Json.canonical(Json.of(it)).toByteArray() } ?: ByteArray(0),
            listOf(RawHeader("Content-Type", "application/json"), RawHeader("Accept", "application/json")))

    private fun sessionsTarget(agent: CoreAgent) = "$ROOT/${segment(agent.id)}/sessions"
    private fun sessionTarget(agent: CoreAgent, sessionId: String) = "${sessionsTarget(agent)}/${segment(sessionId)}"
    private fun session(value: JsonValue.JObject) = NativeAgentSession(required(value, "id"),
        JsonFields.string(value, "title"), timestamp(value, "updatedAt"), JsonFields.bool(value, "archived") ?: false)
    private fun timestamp(value: JsonValue.JObject, key: String): String? = when (val date = JsonFields.field(value, key)) {
        is JsonValue.JString -> date.value
        is JsonValue.JNumber -> date.raw
        else -> null
    }
    private fun objects(value: JsonValue.JObject, key: String): List<JsonValue.JObject> {
        val array = JsonFields.array(JsonFields.field(value, key)) ?: error("AGENT_RESPONSE_INVALID")
        return array.items.map { JsonFields.obj(it) ?: error("AGENT_RESPONSE_INVALID") }
    }
    private fun required(value: JsonValue.JObject, key: String) =
        JsonFields.string(value, key)?.takeIf(String::isNotBlank) ?: error("AGENT_RESPONSE_INVALID")
    private fun requireCapability(available: Boolean) {
        if (!available) throw AgentSessionException("UNSUPPORTED")
    }

    companion object {
        const val ROOT = "/open-android-intelligence/v2/agents"
        private const val MAX_ERROR_BODY_BYTES = 8 * 1024
        internal fun segment(value: String): String {
            require(value.isNotBlank() && value !in setOf(".", "..") && value.none { it == '/' || it == '\\' }) {
                "AGENT_IDENTIFIER_INVALID"
            }
            return value.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
                val number = byte.toInt() and 255
                val char = number.toChar()
                if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "-._~") char.toString()
                else "%%%02X".format(number)
            }
        }
    }
}
