package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.ports.PageRequest
import com.openandroidintelligence.conversation.remote.*
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcChannel
import com.openandroidintelligence.gateway.schema.JsonValue
import kotlinx.coroutines.flow.Flow

/** Codex App Server 原生 Thread/Turn 通路，不向 Host 请求第二份 Harness。 */
class CodexSessionCarrier(override val channel: RemoteRpcChannel) : RemoteSessionCarrier {
    override val backend = "codex"
    private val wire = RemoteCarrierWire(channel, "codex.app")
    override val capabilities: Set<SessionCapability> = if ("codex.appserver.v1" in channel.capabilities)
        setOf(SessionCapability.LIST, SessionCapability.HISTORY, SessionCapability.CREATE, SessionCapability.SEND,
            SessionCapability.STREAM, SessionCapability.CANCEL, SessionCapability.STEER, SessionCapability.APPROVAL,
            SessionCapability.MODEL_SELECTION, SessionCapability.EFFORT_SELECTION, SessionCapability.RENAME,
            SessionCapability.ARCHIVE, SessionCapability.UNARCHIVE) else emptySet()
    private suspend fun call(method: String, params: JsonValue.JObject, transfer: Boolean = false): JsonValue =
        wire.call(obj("method" to method, "params" to params), transfer)

    private suspend fun all(method: String, extra: List<Pair<String, Any?>> = emptyList()): List<JsonValue> {
        val rows = mutableListOf<JsonValue>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val args = listOf("limit" to 100) + extra + listOfNotNull(cursor?.let { "cursor" to it })
            val page = call(method, obj(*args.toTypedArray()))
            rows += page.requiredArray("data")
            cursor = page.text("nextCursor")?.takeIf { it.isNotEmpty() }
            if (cursor != null && !seen.add(cursor)) throw RemoteSessionException("REMOTE_CURSOR_CYCLE")
        } while (cursor != null)
        return rows
    }

    override suspend fun workspaces(): List<RemoteWorkspace> {
        val projects = try { all("project/list") } catch (failure: com.openandroidintelligence.gateway.remote.rpc.RemoteRpcException) {
            if (failure.code !in setOf("METHOD_NOT_ALLOWED", "METHOD_NOT_FOUND", "method-not-found")) throw failure
            emptyList()
        }
        val discovered = projects.map { project ->
            val path = project.requiredArray("roots").firstOrNull()?.requiredText("path")
                ?: throw RemoteSessionException("REMOTE_INVALID_WORKSPACE")
            workspace(project.requiredText("id"), project.text("name") ?: path, path)
        }
        if (discovered.isNotEmpty()) return discovered
        return all("thread/list", listOf("archived" to false)).map { it.requiredText("cwd") }.distinct()
            .map { path -> workspace(path, path.substringAfterLast('/').ifEmpty { path }, path) }
    }

    private fun workspace(id: String, name: String, path: String) = RemoteWorkspace(AgentEndpoint(EndpointKey(
        RemoteServiceOrigin.fromCanonicalOrigin(channel.scope.origin), channel.scope.accountId,
        channel.scope.hostDeviceId, BackendId(backend), id), name, EndpointAvailability.AVAILABLE), path)

    override suspend fun sessions(workspace: RemoteWorkspace, page: PageRequest): RemoteSessionPage {
        val params = listOf("limit" to page.limit.coerceAtMost(100), "cwd" to workspace.path,
            "sortKey" to "updated_at", "sortDirection" to "desc") + listOfNotNull(page.cursor?.let { "cursor" to it })
        val result = call("thread/list", obj(*params.toTypedArray()))
        return RemoteSessionPage(result.requiredArray("data").map(::row), result.text("nextCursor"))
    }

    private fun row(value: JsonValue) = RemoteSessionRow(value.requiredText("id"), value.text("name") ?: value.text("preview") ?: "",
        secondsMillis(value, "createdAt"), secondsMillis(value, "updatedAt"), value.flag("archived") == true,
        value.text("model")?.let { RemoteModelSelection(ModelKey(it, "codex"), value.text("reasoningEffort")) })

    override suspend fun create(workspace: RemoteWorkspace): RemoteSessionRow {
        val result = call("thread/start", obj("cwd" to workspace.path))
        return row(result.at("thread") ?: throw RemoteSessionException("REMOTE_INVALID_SESSION"))
    }

    override suspend fun history(sessionId: String, page: PageRequest): RemoteHistoryPage {
        val params = mutableListOf<Pair<String, Any?>>("threadId" to sessionId, "maxMessages" to page.limit)
        page.cursor?.let { cursor ->
            val parsed = com.openandroidintelligence.gateway.remote.protocol.RemoteWireJson.parse(cursor)
            params += "throughSeq" to (parsed.number("throughSeq") ?: throw RemoteSessionException("CURSOR_EXPIRED"))
            params += "beforeSeq" to (parsed.number("beforeSeq") ?: throw RemoteSessionException("CURSOR_EXPIRED"))
        }
        return sessionHistoryPage(call("dsh/sessionHistory", obj(*params.toTypedArray()), transfer = true))
    }

    override fun follow(sessionId: String): Flow<JsonValue> =
        wire.stream({ id -> obj("streamId" to id, "threadId" to sessionId) })

    override suspend fun prompt(sessionId: String, requestId: String, content: List<JsonValue>, model: RemoteModelSelection?): JsonValue {
        call("thread/resume", obj("threadId" to sessionId))
        val params = listOf("threadId" to sessionId, "input" to content) + listOfNotNull(
            model?.key?.modelId?.let { "model" to it }, model?.effortId?.let { "effort" to it })
        return call("turn/start", obj(*params.toTypedArray()), transfer = content.any { it.text("type") == "image" })
    }

    override suspend fun steer(sessionId: String, generationId: String, requestId: String, text: String): JsonValue =
        call("turn/steer", obj("threadId" to sessionId, "expectedTurnId" to generationId,
            "input" to listOf(obj("type" to "text", "text" to text))))
    override suspend fun interrupt(sessionId: String, generationId: String): JsonValue =
        call("turn/interrupt", obj("threadId" to sessionId, "turnId" to generationId))
    override suspend fun respond(sessionId: String, handle: String, allow: Boolean): JsonValue =
        channel.invoke("codex.app.respond", obj("requestHandle" to handle, "decision" to if (allow) "accept" else "decline"))

    override suspend fun models(sessionId: String): RemoteModelCatalog {
        val efforts = linkedMapOf<ModelKey, List<ReasoningEffortOption>>()
        val models = all("model/list", listOf("includeHidden" to false)).filter { it.flag("hidden") != true }.map { value ->
            val key = ModelKey(value.text("model") ?: value.requiredText("id"), "codex")
            efforts[key] = value.requiredArray("supportedReasoningEfforts").map { effort ->
                val id = effort.requiredText("reasoningEffort")
                ReasoningEffortOption(id, effort.text("description") ?: id)
            }
            SessionModelOption(key, value.text("displayName") ?: key.modelId)
        }
        val thread = call("thread/read", obj("threadId" to sessionId, "includeTurns" to false)).at("thread")
            ?: throw RemoteSessionException("REMOTE_INVALID_SESSION")
        val selected = thread.text("model")?.let { RemoteModelSelection(ModelKey(it, "codex"), thread.text("reasoningEffort")) }
        return RemoteModelCatalog(models, efforts, selected)
    }

    override suspend fun selectModel(sessionId: String, selection: RemoteModelSelection): RemoteModelSelection {
        val directory = models(sessionId)
        if (directory.models.none { it.key == selection.key } || selection.effortId?.let { effort ->
                directory.efforts[selection.key].orEmpty().none { it.id == effort } } == true) throw RemoteSessionException("REMOTE_MODEL_UNAVAILABLE")
        // App Server 的 effort 属于下一 turn/start；这里只确认模型，effort 在发消息时随请求提交。
        val result = call("thread/resume", obj("threadId" to sessionId, "model" to selection.key.modelId))
        val confirmed = result.text("model") ?: result.at("thread")?.text("model")
        if (confirmed != selection.key.modelId) throw RemoteSessionException("REMOTE_MODEL_NOT_CONFIRMED")
        return selection
    }

    override suspend fun rename(sessionId: String, title: String) { call("thread/name/set", obj("threadId" to sessionId, "name" to title)) }
    override suspend fun archive(sessionId: String, archived: Boolean) {
        call(if (archived) "thread/archive" else "thread/unarchive", obj("threadId" to sessionId))
    }
}
