package com.openandroidintelligence.gateway.agents

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
    val create: Boolean = false, val send: Boolean = false,
    val rename: Boolean = false, val archive: Boolean = false,
    val delete: Boolean = false, val cancel: Boolean = false, val stream: Boolean = false,
)
data class CoreAgent(val id: String, val name: String, val kind: String, val online: Boolean,
    val sessionScope: String, val capabilities: AgentCapabilities)
data class NativeAgentSession(val id: String, val title: String?, val updatedAt: String?, val archived: Boolean)
data class NativeAgentMessage(val id: String, val role: String, val text: String, val createdAt: String?)
class AgentSessionException(val code: String, val status: Int = 0) : IllegalStateException(code)

/** All native session operations use the already authenticated Core connection. */
class AgentSessionClient(private val http: GatewayHttpClient) {
    suspend fun agents(): List<CoreAgent> = objects(request("GET", ROOT), "agents").map { value ->
        val caps = JsonFields.obj(JsonFields.field(value, "capabilities")) ?: error("AGENTS_INVALID")
        fun cap(name: String) = JsonFields.bool(caps, name) == true
        CoreAgent(required(value, "id"), required(value, "name"), required(value, "kind"),
            JsonFields.bool(value, "online") ?: false,
            required(value, "sessionScope").also { check(it in setOf("account", "deployment")) { "AGENTS_INVALID" } },
            AgentCapabilities(cap("list"), cap("history"), cap("create"), cap("send"), cap("rename"),
                cap("archive"), cap("delete"), cap("cancel"), cap("stream")))
    }

    suspend fun sessions(agent: CoreAgent): List<NativeAgentSession> {
        requireCapability(agent.capabilities.list)
        return objects(request("GET", sessionsTarget(agent)), "sessions").map(::session)
    }

    suspend fun history(agent: CoreAgent, sessionId: String): List<NativeAgentMessage> {
        requireCapability(agent.capabilities.history)
        return objects(request("GET", sessionTarget(agent, sessionId) + "/history"), "messages").map {
            NativeAgentMessage(required(it, "id"), required(it, "role"),
                JsonFields.string(it, "text") ?: error("AGENT_HISTORY_INVALID"), JsonFields.string(it, "createdAt"))
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

    suspend fun cancel(agent: CoreAgent, sessionId: String) {
        requireCapability(agent.capabilities.cancel && agent.online)
        request("POST", sessionTarget(agent, sessionId) + "/cancel", emptyMap())
    }

    /** An interrupted stream is an unknown outcome, never an automatic resend. */
    suspend fun send(agent: CoreAgent, sessionId: String, text: String, onDelta: suspend (String) -> Unit) {
        requireCapability(agent.capabilities.send && agent.capabilities.stream && agent.online)
        require(text.isNotBlank())
        val request = jsonRequest("POST", sessionTarget(agent, sessionId) + "/messages", mapOf("text" to text))
            .copy(headers = listOf(RawHeader("Content-Type", "application/json"), RawHeader("Accept", "text/event-stream")))
        try {
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
                while (!done) {
                    currentCoroutineContext().ensureActive()
                    val read = stream.read(buffer)
                    if (read < 0) break
                    for (event in parser.feedBytes(buffer.copyOf(read))) {
                        if (event.event != "message") continue
                        val value = JsonFields.obj(Json.parse(event.data)) ?: error("AGENT_STREAM_INVALID")
                        when (JsonFields.string(value, "type")) {
                            "delta" -> onDelta(JsonFields.string(value, "text") ?: error("AGENT_STREAM_INVALID"))
                            "done" -> { done = true; break }
                            "error" -> throw AgentSessionException(JsonFields.string(value, "code") ?: "AGENT_SEND_FAILED")
                            else -> error("AGENT_STREAM_INVALID")
                        }
                    }
                }
                if (!done) throw AgentSessionException("OUTCOME_UNKNOWN")
            }
        } catch (_: IOException) {
            // A transport failure cannot establish whether the native turn ran.
            // Preserve the draft and partial output; never resend it here.
            throw AgentSessionException("OUTCOME_UNKNOWN")
        }
    }

    private suspend fun request(method: String, target: String, body: Map<String, Any?>? = null): JsonValue.JObject {
        val response = http.execute(jsonRequest(method, target, body))
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
        JsonFields.string(value, "title"), JsonFields.string(value, "updatedAt"), JsonFields.bool(value, "archived") ?: false)
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
