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
    val generationRecovery: Boolean = false,
    val eventSync: Boolean = false,
    val operationRecovery: Boolean = false,
)
data class CoreAgent(val id: String, val name: String, val kind: String, val online: Boolean,
    val sessionScope: String, val capabilities: AgentCapabilities)
data class NativeAgentSession(val id: String, val title: String?, val updatedAt: String?, val archived: Boolean)
data class NativeOperationResult(val state: String, val session: NativeAgentSession? = null)
data class NativeAgentAttachment(val id: String, val filename: String, val mediaType: String,
    val sizeBytes: Long? = null, val sha256: String? = null)
data class NativeAgentMessage(val id: String, val role: String, val text: String, val createdAt: String?,
    val clientMessageId: String? = null, val attachments: List<NativeAgentAttachment> = emptyList(),
    val generationId: String? = null, val contentRevision: Long? = null, val state: String? = null,
    val executionSteps: List<NativeAgentExecutionStep> = emptyList())
data class NativeAgentExecutionStep(val stepId: String, val eventId: String, val toolName: String,
    val arguments: String, val output: String?, val status: String, val revision: Long)
data class NativeEventApproval(val type: String, val approval: JsonValue.JObject)
data class NativeEventPage(val events: List<JsonValue.JObject>, val cursor: String, val hasMore: Boolean,
    val approvals: List<NativeEventApproval>? = null)
data class NativeGenerationResult(val outcome: String, val generationId: String?, val messageId: String? = null,
    val userMessageId: String? = null, val clientMessageId: String? = null, val text: String? = null,
    val revision: Long? = null)
data class NativeSteeringResult(val generationId: String, val outcome: String)
data class NativeCurrentGeneration(val generationId: String, val conversationId: String, val state: String)
data class NativeGenerationReceipt(val generationId: String, val conversationId: String,
    val clientMessageId: String, val userMessageId: String, val state: String, val revision: Long)
sealed interface NativeAgentStreamEvent {
    data class Generation(val generationId: String,
        val executionSteps: List<NativeAgentExecutionStep>? = null,
        val executionRevision: Long? = null, val eventId: String? = null,
        val clientMessageId: String? = null, val userMessageId: String? = null,
        val state: String? = null, val revision: Long? = null) : NativeAgentStreamEvent
    data class ApprovalRequested(val approval: JsonValue.JObject) : NativeAgentStreamEvent
    data class ApprovalResolved(val approval: JsonValue.JObject) : NativeAgentStreamEvent
    data class MessageIdentity(val messageId: String?, val userMessageId: String?, val clientMessageId: String?,
        val contentRevision: Long? = null, val eventId: String? = null) : NativeAgentStreamEvent
}
class AgentSessionException(val code: String, val status: Int = 0) : IllegalStateException(code)

/** All native session operations use the already authenticated Core connection. */
class AgentSessionClient(private val http: GatewayHttpClient, private val traceEnabled: Boolean = false) {
    suspend fun agents(): List<CoreAgent> = objects(request("GET", ROOT), "agents").map { value ->
        val caps = JsonFields.obj(JsonFields.field(value, "capabilities")) ?: error("AGENTS_INVALID")
        fun cap(name: String) = JsonFields.bool(caps, name) == true
        CoreAgent(required(value, "id"), required(value, "name"), required(value, "kind"),
            JsonFields.bool(value, "online") ?: false,
            required(value, "sessionScope").also { check(it in setOf("account", "deployment")) { "AGENTS_INVALID" } },
            AgentCapabilities(cap("list"), cap("history"), cap("create"), cap("send"), cap("steer"), cap("rename"),
                cap("archive"), cap("delete"), cap("cancel"), cap("stream"), cap("commands"), cap("approvals"), cap("attachments"), cap("generationRecovery"), cap("eventSync"), cap("operationRecovery")))
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
                }, JsonFields.string(it, "generationId"), JsonFields.long(it, "contentRevision"),
                JsonFields.string(it, "state"), JsonFields.objects(it, "executionSteps").map { step ->
                    NativeAgentExecutionStep(required(step, "stepId"), required(step, "eventId"),
                        required(step, "toolName"), JsonFields.string(step, "arguments").orEmpty(),
                        JsonFields.string(step, "output"), JsonFields.string(step, "status") ?: "unknown",
                        JsonFields.long(step, "revision")?.takeIf { revision -> revision >= 0 } ?: 0)
                })
        }
    }

    suspend fun create(agent: CoreAgent, title: String? = null, requestId: String? = null): NativeAgentSession {
        requireCapability(agent.capabilities.create && agent.online)
        val value = request("POST", sessionsTarget(agent), title?.let { mapOf("title" to it) } ?: emptyMap(), requestId, requestId?.let { "create:${agent.id}:$it" })
        return session(JsonFields.obj(JsonFields.field(value, "session")) ?: error("AGENT_SESSION_INVALID"))
    }

    /** Core 的持久操作回执；unknown/404 不构成再次创建的授权。 */
    suspend fun operation(agent: CoreAgent, requestId: String): NativeOperationResult {
        requireCapability(agent.capabilities.operationRecovery)
        require(requestId.isNotBlank())
        val value = request("GET", "$ROOT/${segment(agent.id)}/operations/${segment(requestId)}",
            traceKey = "create:${agent.id}:$requestId")
        val state = required(value, "state")
        if (state !in setOf("pending", "resolved", "unknown")) throw AgentSessionException("AGENT_RESPONSE_INVALID")
        val receipt = JsonFields.obj(JsonFields.field(value, "receipt"))
        if (state == "resolved") JsonFields.obj(JsonFields.field(receipt, "error"))?.let { error ->
            throw AgentSessionException(required(error, "code"))
        }
        val created = JsonFields.obj(JsonFields.field(receipt, "session"))?.let(::session)
        if (state == "resolved" && created == null) throw AgentSessionException("AGENT_RESPONSE_INVALID")
        return NativeOperationResult(state, created)
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
        if (generationId != null && (generationId.isBlank() || generationId.startsWith("local_")))
            throw AgentSessionException("GENERATION_ID_REQUIRED")
        val exactGeneration = generationId != null
        val payload = generationId?.let { mapOf("generationId" to it) } ?: emptyMap()
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

    /** Reads the original accepted identity even while the host is offline. Never resends. */
    suspend fun generation(agent: CoreAgent, sessionId: String, clientMessageId: String,
        generationId: String? = null, afterRevision: Long? = null, waitMs: Int = 0): NativeGenerationReceipt {
        requireCapability(agent.capabilities.generationRecovery)
        require(clientMessageId.isNotBlank() && (afterRevision == null || afterRevision >= 0) &&
            waitMs in 0..25000 && (waitMs == 0 || afterRevision != null))
        val query = buildList {
            add("clientMessageId=${segment(clientMessageId)}")
            generationId?.let { add("generationId=${queryValue(it)}") }
            afterRevision?.let { add("afterRevision=$it") }
            if (waitMs > 0) add("waitMs=$waitMs")
        }.sorted().joinToString("&")
        val value = request("GET", sessionTarget(agent, sessionId) + "/generation?" + query,
            traceKey = "send:${agent.id}:$sessionId:$clientMessageId")
        val receipt = JsonFields.obj(JsonFields.field(value, "generation"))
            ?: throw AgentSessionException("OUTCOME_UNKNOWN")
        val returnedGeneration = required(receipt, "generationId")
        val returnedClient = required(receipt, "clientMessageId")
        val returnedSession = required(receipt, "conversationId")
        val state = required(receipt, "state")
        val revision = JsonFields.long(receipt, "revision") ?: throw AgentSessionException("AGENT_RESPONSE_INVALID")
        if (returnedClient != clientMessageId || returnedSession != sessionId ||
            generationId != null && returnedGeneration != generationId || revision < 0 ||
            state !in setOf("queued", "running", "unknown", "completed", "failed", "cancelled"))
            throw AgentSessionException("AGENT_RESPONSE_INVALID")
        return NativeGenerationReceipt(returnedGeneration, returnedSession, returnedClient,
            required(receipt, "userMessageId"), state, revision)
    }

    /** 可选的原生持久事件面；宿主离线时仍可读取，不使用账号通用事件游标。 */
    suspend fun events(agent: CoreAgent, sessionId: String, cursor: String? = null,
        limit: Int = 128, waitMs: Int = 0, snapshot: Boolean = false): NativeEventPage {
        requireCapability(agent.capabilities.eventSync)
        require(limit in 1..128 && waitMs in 0..25000 && (!snapshot || cursor == null && waitMs == 0))
        cursor?.let(::requireEventCursor)
        val query = buildList {
            cursor?.let { add("cursor=$it") }
            add("limit=$limit")
            if (snapshot) add("snapshot=true")
            if (waitMs > 0) add("waitMs=$waitMs")
        }.sorted().joinToString("&")
        val page = request("GET", sessionTarget(agent, sessionId) + "/events?" + query)
        val returnedCursor = required(page, "cursor").also(::requireEventCursor)
        val hasMore = JsonFields.bool(page, "hasMore") ?: throw AgentSessionException("AGENT_RESPONSE_INVALID")
        val events = objects(page, "events").onEach { event ->
            required(event, "eventId")
            required(event, "eventType")
            if (JsonFields.obj(JsonFields.field(event, "payload")) == null)
                throw AgentSessionException("AGENT_RESPONSE_INVALID")
        }
        val approvals = if (snapshot) objects(page, "approvals").map { item ->
            val type = required(item, "type")
            if (type !in setOf("approval_requested", "approval_resolved")) throw AgentSessionException("AGENT_RESPONSE_INVALID")
            NativeEventApproval(type, JsonFields.obj(JsonFields.field(item, "approval"))
                ?: throw AgentSessionException("AGENT_RESPONSE_INVALID"))
        } else null
        return NativeEventPage(events, returnedCursor, hasMore, approvals)
    }

    /** 只确认调用方已经可靠应用的页；旧 cursor 返回的服务端位置可能更靠后。 */
    suspend fun ackEvents(agent: CoreAgent, sessionId: String, cursor: String): String {
        requireCapability(agent.capabilities.eventSync)
        requireEventCursor(cursor)
        return required(request("POST", sessionTarget(agent, sessionId) + "/events/ack", mapOf("cursor" to cursor)), "cursor")
            .also(::requireEventCursor)
    }

    private fun requireEventCursor(cursor: String) {
        require(cursor.length in 1..8192 && cursor.all { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' }) {
            "INVALID_EVENT_CURSOR"
        }
    }

    suspend fun steer(
        agent: CoreAgent,
        sessionId: String,
        generationId: String?,
        text: String,
        clientMessageId: String,
    ): NativeSteeringResult {
        if (generationId != null && generationId.isBlank() || text.isBlank() || clientMessageId.isBlank())
            throw AgentSessionException("INVALID_REQUEST")
        val value = try {
            request(
                "POST",
                sessionTarget(agent, sessionId) + "/steer",
                buildMap {
                    put("text", text)
                    if (generationId != null) put("generationId", generationId)
                    put("clientMessageId", clientMessageId)
                },
                requestId = clientMessageId,
                traceKey = "send:${agent.id}:$sessionId:$clientMessageId",
            )
        } catch (_: IOException) {
            throw AgentSessionException("OUTCOME_UNKNOWN")
        }
        val steering = JsonFields.obj(JsonFields.field(value, "steering"))
            ?: throw AgentSessionException("AGENT_RESPONSE_INVALID")
        val returnedGeneration = JsonFields.string(steering, "generationId")
        val outcome = JsonFields.string(steering, "outcome")
        if (returnedGeneration.isNullOrBlank() || generationId != null && returnedGeneration != generationId || outcome != "accepted")
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
            .copy(headers = listOf(RawHeader("Content-Type", "application/json"), RawHeader("Accept", "text/event-stream")) +
                traceHeaders(clientMessageId?.let { "send:${agent.id}:$sessionId:$it" }))
        com.openandroidintelligence.gateway.diagnostics.GatewayLog.protocolEvidence("T1.request_started", mapOf(
            "agentId" to agent.id, "conversationId" to sessionId, "clientMessageId" to clientMessageId,
            "monotonicNs" to System.nanoTime()))
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
                var finalRevision: Long? = null
                var identity = NativeAgentStreamEvent.MessageIdentity(null, null, null)
                val contentRevisions = mutableMapOf<String, Long>()
                val contentEventIds = LinkedHashSet<String>()
                val versionlessEventIds = LinkedHashSet<String>()
                var executionRevision = -1L
                var generationRevision = -1L
                var firstDeltaObserved = false
                while (!done) {
                    currentCoroutineContext().ensureActive()
                    val read = stream.read(buffer)
                    if (read < 0) break
                    for (event in parser.feedBytes(buffer.copyOf(read))) {
                        if (event.event != "message") continue
                        val value = JsonFields.obj(Json.parse(event.data)) ?: error("AGENT_STREAM_INVALID")
                        val type = JsonFields.string(value, "type") ?: throw AgentSessionException("AGENT_STREAM_INVALID")
                        val frameClient = JsonFields.string(value, "clientMessageId")
                        if (frameClient != null && clientMessageId != null && frameClient != clientMessageId)
                            throw AgentSessionException("AGENT_STREAM_INVALID")
                        val frameGeneration = JsonFields.string(value, "generationId")
                        if (frameGeneration != null && generationId != null && frameGeneration != generationId)
                            throw AgentSessionException("AGENT_STREAM_INVALID")
                        val frameMessage = JsonFields.string(value, "messageId") ?: identity.messageId
                        val frameRevision = JsonFields.long(value, "contentRevision")
                        if (frameRevision != null && frameRevision < 0) throw AgentSessionException("AGENT_STREAM_INVALID")
                        val frameEventId = JsonFields.string(value, "eventId")
                        // Only delta payloads are duplicate append operations. A done frame may
                        // legally reuse the final message event ID and must still settle the turn.
                        if (type == "delta") {
                            if (frameEventId != null && (frameEventId in contentEventIds || frameEventId in versionlessEventIds)) continue
                            if (frameMessage != null && frameRevision != null &&
                                frameRevision <= (contentRevisions[frameMessage] ?: -1L)) continue
                        }
                        val staleDoneContent = type == "done" && frameMessage != null && frameRevision != null &&
                            frameRevision < (contentRevisions[frameMessage] ?: -1L)
                        val contentFrame = (type == "delta" || type == "done") && !staleDoneContent
                        val nextIdentity = NativeAgentStreamEvent.MessageIdentity(
                            if (contentFrame) frameMessage else identity.messageId,
                            JsonFields.string(value, "userMessageId") ?: identity.userMessageId,
                            frameClient ?: identity.clientMessageId,
                            if (contentFrame) frameRevision else identity.contentRevision,
                            if (contentFrame) frameEventId else identity.eventId)
                        if (nextIdentity != identity) {
                            identity = nextIdentity
                            onEvent(identity)
                        }
                        if (type != "delta" || !firstDeltaObserved) {
                            com.openandroidintelligence.gateway.diagnostics.GatewayLog.protocolEvidence("T6.sse_read", mapOf(
                                "clientMessageId" to clientMessageId, "generationId" to frameGeneration,
                                "eventId" to frameEventId, "eventType" to type, "contentRevision" to frameRevision))
                        }
                        if (type == "delta") {
                            firstDeltaObserved = true
                            if (frameMessage != null && frameRevision != null) contentRevisions[frameMessage] = frameRevision
                            if (frameEventId != null) {
                                if (frameRevision == null) {
                                    // Without versions, evicting an ID could append a replay twice.
                                    // Stop observation and reconcile instead of silently weakening identity.
                                    if (versionlessEventIds.size >= MAX_REMEMBERED_CONTENT_EVENTS)
                                        throw AgentSessionException("OUTCOME_UNKNOWN")
                                    versionlessEventIds += frameEventId
                                } else {
                                    contentEventIds += frameEventId
                                    // Revision barriers keep older versions rejected after event-ID eviction.
                                    if (contentEventIds.size > MAX_REMEMBERED_CONTENT_EVENTS) contentEventIds.remove(contentEventIds.first())
                                }
                            }
                        }
                        when (JsonFields.string(value, "type")) {
                            "delta" -> onDelta(JsonFields.string(value, "text") ?: error("AGENT_STREAM_INVALID"))
                            "generation" -> {
                                val id = required(value, "generationId")
                                if (generationId != null && generationId != id) throw AgentSessionException("AGENT_STREAM_INVALID")
                                generationId = id
                                val stateField = JsonFields.field(value, "state")
                                val revisionField = JsonFields.field(value, "revision")
                                var lifecycleState: String? = null
                                var lifecycleRevision: Long? = null
                                if (stateField != null || revisionField != null) {
                                    val declaredState = JsonFields.string(value, "state")
                                    val declaredRevision = JsonFields.long(value, "revision")
                                    if (declaredState !in setOf("queued", "running", "unknown") || declaredRevision == null || declaredRevision < 0)
                                        throw AgentSessionException("AGENT_STREAM_INVALID")
                                    if (declaredRevision > generationRevision) {
                                        generationRevision = declaredRevision
                                        lifecycleState = declaredState
                                        lifecycleRevision = declaredRevision
                                    }
                                }
                                val stepField = JsonFields.field(value, "executionSteps")
                                val steps = if (stepField == null) null else {
                                    if (stepField !is JsonValue.JArray) throw AgentSessionException("AGENT_STREAM_INVALID")
                                    stepField.items.map { item ->
                                        decodeExecutionStep(JsonFields.obj(item) ?: throw AgentSessionException("AGENT_STREAM_INVALID"))
                                    }
                                }
                                val nextRevision = JsonFields.long(value, "executionRevision")
                                if (steps != null) {
                                    if (nextRevision == null || nextRevision < 0 || frameEventId.isNullOrBlank())
                                        throw AgentSessionException("AGENT_STREAM_INVALID")
                                    if (nextRevision <= executionRevision) continue
                                    executionRevision = nextRevision
                                }
                                if ((stateField != null || revisionField != null) && lifecycleState == null && steps == null) continue
                                onEvent(NativeAgentStreamEvent.Generation(id, steps, nextRevision, frameEventId,
                                    frameClient ?: identity.clientMessageId, identity.userMessageId, lifecycleState, lifecycleRevision))
                            }
                            "approval_requested", "approval_resolved" -> {
                                val approval = JsonFields.obj(JsonFields.field(value, "approval")) ?: error("AGENT_STREAM_INVALID")
                                onEvent(if (JsonFields.string(value, "type") == "approval_requested")
                                    NativeAgentStreamEvent.ApprovalRequested(approval) else NativeAgentStreamEvent.ApprovalResolved(approval))
                            }
                            "done" -> {
                                val revisionField = JsonFields.field(value, "revision")
                                val returnedRevision = JsonFields.long(value, "revision")
                                if (revisionField != null && (returnedRevision == null || returnedRevision < 0 || returnedRevision < generationRevision))
                                    throw AgentSessionException("AGENT_STREAM_INVALID")
                                finalRevision = returnedRevision
                                finalText = JsonFields.string(value, "text").takeUnless { staleDoneContent }
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
                NativeGenerationResult(outcome, generationId, identity.messageId, identity.userMessageId, identity.clientMessageId, finalText, finalRevision)
            }
        } catch (_: IOException) {
            // A transport failure cannot establish whether the native turn ran.
            // Preserve the draft and partial output; never resend it here.
            throw AgentSessionException("OUTCOME_UNKNOWN")
        }
    }

    private fun decodeExecutionStep(step: JsonValue.JObject): NativeAgentExecutionStep {
        val revision = JsonFields.long(step, "revision") ?: throw AgentSessionException("AGENT_STREAM_INVALID")
        if (revision < 0) throw AgentSessionException("AGENT_STREAM_INVALID")
        return NativeAgentExecutionStep(required(step, "stepId"), required(step, "eventId"), required(step, "toolName"),
            JsonFields.string(step, "arguments").orEmpty(), JsonFields.string(step, "output"),
            JsonFields.string(step, "status")?.takeIf { it in setOf("running", "completed", "failed", "unknown") } ?: "unknown",
            revision)
    }

    private fun traceHeaders(key: String?): List<RawHeader> =
        if (traceEnabled && key != null) listOf(RawHeader("traceparent", http.operationTraceParent(key))) else emptyList()

    private suspend fun request(method: String, target: String, body: Map<String, Any?>? = null, requestId: String? = null,
        traceKey: String? = null): JsonValue.JObject {
        val request = jsonRequest(method, target, body)
        val response = http.execute(request.copy(requestId = requestId, headers = request.headers + traceHeaders(traceKey)))
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
        private const val MAX_REMEMBERED_CONTENT_EVENTS = 4096
        internal fun segment(value: String): String {
            require(value.isNotBlank() && value !in setOf(".", "..") && value.none { it == '/' || it == '\\' }) {
                "AGENT_IDENTIFIER_INVALID"
            }
            return queryValue(value)
        }
        private fun queryValue(value: String): String {
            require(value.isNotBlank()) { "AGENT_IDENTIFIER_INVALID" }
            return value.toByteArray(Charsets.UTF_8).joinToString("") { byte ->
                val number = byte.toInt() and 255
                val char = number.toChar()
                if (char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "-._~") char.toString()
                else "%%%02X".format(number)
            }
        }
    }
}
