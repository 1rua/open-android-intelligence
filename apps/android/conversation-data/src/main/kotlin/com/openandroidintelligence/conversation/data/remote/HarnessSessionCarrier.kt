package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.ports.PageRequest
import com.openandroidintelligence.conversation.remote.*
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcChannel
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.filterNot

/** Harness 只走官方 Session V3；不经过 Codex 或 ACP 的虚拟 Harness。 */
class HarnessSessionCarrier(override val channel: RemoteRpcChannel) : RemoteSessionCarrier {
    override val backend = "harness"
    private val wire = RemoteCarrierWire(channel, "harness.remote")
    override val capabilities: Set<SessionCapability> = if ("harness.remote.v3" in channel.capabilities) setOf(SessionCapability.LIST, SessionCapability.HISTORY,
        SessionCapability.CREATE, SessionCapability.SEND, SessionCapability.STREAM, SessionCapability.CANCEL,
        SessionCapability.STEER, SessionCapability.APPROVAL, SessionCapability.MODEL_SELECTION,
        SessionCapability.EFFORT_SELECTION, SessionCapability.RENAME) else emptySet()

    private suspend fun invoke(endpoint: String, args: JsonValue.JObject = obj()): JsonValue =
        wire.call(obj("endpoint" to endpoint, "payload" to obj("args" to args))).unwrapResult().unwrapResult()

    private fun stream(endpoint: String, args: JsonValue.JObject): Flow<JsonValue> =
        wire.stream({ id -> obj("streamId" to id, "endpoint" to endpoint, "payload" to obj("args" to args),
            "transferFrames" to ("harness.remote.stream.transfer.v1" in channel.capabilities)) }, "value")

    override suspend fun workspaces(): List<RemoteWorkspace> {
        val baseline = stream("workspace/follow", obj()).first { it.text("type") == "baseline" }
        return (baseline.at("value") ?: throw RemoteSessionException("REMOTE_INVALID_WORKSPACE_BASELINE"))
            .requiredArray("items").map { row ->
                val id = row.requiredText("workspaceId")
                RemoteWorkspace(endpoint(id, row.text("title") ?: id), row.requiredText("path"),
                    row.requiredArray("sessionIds").map { it.stringValue() ?: throw RemoteSessionException("REMOTE_INVALID_WORKSPACE") }.toSet())
            }
    }

    private fun endpoint(id: String, name: String): AgentEndpoint = AgentEndpoint(EndpointKey(
        RemoteServiceOrigin.fromCanonicalOrigin(channel.scope.origin), channel.scope.accountId,
        channel.scope.hostDeviceId, BackendId(backend), id), name, EndpointAvailability.AVAILABLE)

    override suspend fun sessions(workspace: RemoteWorkspace, page: PageRequest): RemoteSessionPage {
        if (page.cursor != null) throw RemoteSessionException("CURSOR_EXPIRED")
        val allowed = workspaces().firstOrNull { it.endpoint.key == workspace.endpoint.key }?.sessionIds
            ?: throw RemoteSessionException("REMOTE_WORKSPACE_NOT_FOUND")
        val rows = invoke("session/list", obj("_request" to obj())).requiredArray("items")
            .filter { it.text("sessionId") in allowed && it.text("parentSessionId") == null && it.text("origin") != "subagent" }
            .map { row -> RemoteSessionRow(row.requiredText("sessionId"), row.text("title") ?: "",
                millis(row, "createdAt"), millis(row, "updatedAt"), row.flag("archived") == true) }
        return RemoteSessionPage(rows)
    }

    override suspend fun create(workspace: RemoteWorkspace): RemoteSessionRow {
        val result = invoke("session/create", obj("request" to obj("workspaceId" to workspace.endpoint.key.workspaceId)))
        return RemoteSessionRow(result.requiredText("sessionId"), "", 0, 0)
    }

    override suspend fun history(sessionId: String, page: PageRequest): RemoteHistoryPage {
        val request = mutableListOf<Pair<String, Any?>>("address" to obj("kind" to "session", "sessionId" to sessionId),
            "maxMessages" to page.limit)
        page.cursor?.let { cursor ->
            val parsed = com.openandroidintelligence.gateway.remote.protocol.RemoteWireJson.parse(cursor)
            request += "throughSeq" to (parsed.number("throughSeq") ?: throw RemoteSessionException("CURSOR_EXPIRED"))
            request += "beforeSeq" to (parsed.number("beforeSeq") ?: throw RemoteSessionException("CURSOR_EXPIRED"))
        }
        val result = invoke("session/page", obj("request" to obj(*request.toTypedArray())))
        return sessionHistoryPage(result)
    }

    override fun follow(sessionId: String): Flow<JsonValue> = merge(
        stream("session/follow", obj("request" to obj("address" to obj("kind" to "session", "sessionId" to sessionId),
            "assistantStream" to true))), stream("\$events", obj()).filterNot { it.text("type") == "remote-stream-ready" })

    override suspend fun prompt(sessionId: String, requestId: String, content: List<JsonValue>, model: RemoteModelSelection?): JsonValue {
        if (model != null) selectModel(sessionId, model)
        return invoke("session/prompt", obj("request" to obj("sessionId" to sessionId, "requestId" to requestId,
            "mode" to "queue", "content" to content)))
    }

    override suspend fun commands(sessionId: String?): com.openandroidintelligence.conversation.ports.AgentCommandCatalog {
        val result = invoke("commands/list", obj("request" to obj(*listOfNotNull(sessionId?.let { "sessionId" to it }).toTypedArray())))
        val items = result.array("items") ?: result.array("commands") ?: throw RemoteSessionException("REMOTE_INVALID_COMMAND_CATALOG")
        return com.openandroidintelligence.conversation.ports.AgentCommandCatalog(com.openandroidintelligence.conversation.model.CatalogVersion("remote"),
            items.map { command -> com.openandroidintelligence.conversation.ports.AgentCommand(command.text("command") ?: command.requiredText("name"), command.text("description") ?: "") })
    }

    override suspend fun steer(sessionId: String, generationId: String, requestId: String, text: String): JsonValue =
        invoke("session/prompt", obj("request" to obj("sessionId" to sessionId, "requestId" to requestId,
            "mode" to "steer", "content" to listOf(obj("type" to "text", "text" to text)))))

    override suspend fun interrupt(sessionId: String, generationId: String): JsonValue =
        invoke("session/cancel", obj("request" to obj("sessionId" to sessionId)))

    override suspend fun respond(sessionId: String, handle: String, allow: Boolean): JsonValue =
        invoke("\$events/answer", obj("eventId" to handle, "outcome" to obj("kind" to "result",
            "value" to if (allow) "allowed-once" else "denied")))

    override suspend fun models(sessionId: String): RemoteModelCatalog =
        parseModelCatalog(invoke("session/models", obj("request" to obj("sessionId" to sessionId))))

    override suspend fun selectModel(sessionId: String, selection: RemoteModelSelection): RemoteModelSelection {
        val result = invoke("session/selectModel", obj("request" to modelArgs(sessionId, selection)))
        return parseSelection(result.at("selected") ?: throw RemoteSessionException("REMOTE_MODEL_NOT_CONFIRMED"))
    }

    override suspend fun rename(sessionId: String, title: String) {
        invoke("session/rename", obj("request" to obj("sessionId" to sessionId, "title" to title)))
    }
}

internal fun sessionHistoryPage(result: JsonValue): RemoteHistoryPage {
    val records = result.requiredArray("records")
    val cursor = result.number("cursor") ?: throw RemoteSessionException("REMOTE_INVALID_HISTORY")
    val before = records.mapNotNull { it.at("event")?.number("seq") }.minOrNull()
    val next = if (result.flag("hasMore") == true) {
        if (before == null || before <= 0) throw RemoteSessionException("REMOTE_INVALID_HISTORY_CURSOR")
        Json.canonical(obj("throughSeq" to cursor, "beforeSeq" to before))
    } else null
    return RemoteHistoryPage(records, next, cursor, result.text("activeTurnId"))
}

internal fun parseSelection(value: JsonValue): RemoteModelSelection = RemoteModelSelection(
    ModelKey(value.requiredText("model"), value.text("provider")), value.text("reasoningEffort"))

internal fun modelArgs(sessionId: String, selection: RemoteModelSelection): JsonValue.JObject = obj(
    *(listOf("sessionId" to sessionId, "model" to selection.key.modelId) +
        listOfNotNull(selection.key.providerId?.let { "provider" to it }, selection.effortId?.let { "reasoningEffort" to it })).toTypedArray())

internal fun parseModelCatalog(value: JsonValue): RemoteModelCatalog {
    val efforts = linkedMapOf<ModelKey, List<ReasoningEffortOption>>()
    val models = value.requiredArray("groups").flatMap { group ->
        group.requiredArray("models").map { model ->
            val key = ModelKey(model.requiredText("id"), group.requiredText("id"))
            efforts[key] = model.at("reasoning")?.array("efforts")?.map { effort ->
                ReasoningEffortOption(effort.requiredText("id"), effort.text("name") ?: effort.requiredText("id"))
            }.orEmpty()
            SessionModelOption(key, model.text("name") ?: key.modelId)
        }
    }
    return RemoteModelCatalog(models, efforts, (value.at("current") ?: value.at("default"))?.let(::parseSelection))
}
