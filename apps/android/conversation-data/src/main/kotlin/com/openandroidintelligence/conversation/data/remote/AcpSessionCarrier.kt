package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.ports.PageRequest
import com.openandroidintelligence.conversation.remote.*
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcChannel
import com.openandroidintelligence.gateway.schema.JsonValue
import kotlinx.coroutines.flow.Flow

/** 各 ACP 后端直接走 agent.acp.*，后端标识不会因统一界面而丢失。 */
class AcpSessionCarrier(override val channel: RemoteRpcChannel, override val backend: String) : RemoteSessionCarrier {
    private val wire = RemoteCarrierWire(channel, "agent.acp")
    override val capabilities: Set<SessionCapability> = buildSet {
        if ("agent.acp.v1" in channel.capabilities && "agent.acp.$backend.v1" in channel.capabilities) {
            addAll(setOf(SessionCapability.LIST, SessionCapability.HISTORY, SessionCapability.CREATE,
                SessionCapability.SEND, SessionCapability.STREAM, SessionCapability.CANCEL, SessionCapability.APPROVAL,
                SessionCapability.MODEL_SELECTION, SessionCapability.EFFORT_SELECTION))
            if (backend == "opencode" && "agent.acp.opencode.archive.v1" in channel.capabilities) add(SessionCapability.ARCHIVE)
        }
    }
    private suspend fun call(method: String, params: JsonValue.JObject, transfer: Boolean = false): JsonValue =
        wire.call(obj("method" to method, "params" to params), transfer,
            if (method == "session/prompt") 10 * 60_000 else 30_000)

    override suspend fun workspaces(): List<RemoteWorkspace> =
        call("dsh/workspaceList", obj("backend" to backend)).listValue().map { row ->
            val path = row.requiredText("path")
            val key = EndpointKey(RemoteServiceOrigin.fromCanonicalOrigin(channel.scope.origin), channel.scope.accountId,
                channel.scope.hostDeviceId, BackendId(backend), path)
            RemoteWorkspace(AgentEndpoint(key, row.text("title") ?: path, EndpointAvailability.AVAILABLE), path)
        }

    override suspend fun sessions(workspace: RemoteWorkspace, page: PageRequest): RemoteSessionPage {
        if (page.cursor != null) throw RemoteSessionException("CURSOR_EXPIRED")
        val result = call("dsh/sessionList", obj("path" to workspace.path, "backend" to backend))
        return RemoteSessionPage(result.requiredArray("items").map { row ->
            RemoteSessionRow(row.requiredText("conversationId"), row.text("title") ?: "", millis(row, "createdAt"),
                millis(row, "updatedAt"), row.flag("archived") == true)
        })
    }

    override suspend fun create(workspace: RemoteWorkspace): RemoteSessionRow {
        val result = call("session/new", obj("cwd" to workspace.path, "backend" to backend, "mcpServers" to emptyList<Any>()))
        return RemoteSessionRow(result.requiredText("sessionId"), "", 0, 0)
    }

    override suspend fun history(sessionId: String, page: PageRequest): RemoteHistoryPage {
        if (page.cursor != null) throw RemoteSessionException("CURSOR_EXPIRED")
        val result = call("dsh/sessionHistory", obj("sessionId" to sessionId, "backend" to backend), transfer = true)
        return RemoteHistoryPage(result.requiredArray("events"))
    }

    override fun follow(sessionId: String): Flow<JsonValue> = wire.stream(
        { id -> obj("streamId" to id, "sessionId" to sessionId) }, acceptsSessionId = sessionId)

    override suspend fun prompt(sessionId: String, requestId: String, content: List<JsonValue>, model: RemoteModelSelection?): JsonValue =
        call("session/prompt", obj("sessionId" to sessionId, "backend" to backend, "prompt" to content),
            transfer = content.any { it.text("type") == "image" })

    override suspend fun interrupt(sessionId: String, generationId: String): JsonValue =
        call("session/cancel", obj("sessionId" to sessionId))

    override suspend fun respond(sessionId: String, handle: String, allow: Boolean): JsonValue =
        channel.invoke("agent.acp.respond", obj("requestHandle" to handle,
            "decision" to if (allow) "allow-once" else "reject-once"))

    override suspend fun models(sessionId: String): RemoteModelCatalog =
        parseModelCatalog(call("dsh/sessionModels", obj("sessionId" to sessionId, "backend" to backend)))

    override suspend fun selectModel(sessionId: String, selection: RemoteModelSelection): RemoteModelSelection {
        require(selection.key.providerId == backend) { "ACP_MODEL_PROVIDER_MISMATCH" }
        val params = obj(*(listOf("sessionId" to sessionId, "backend" to backend, "model" to selection.key.modelId) +
            listOfNotNull(selection.effortId?.let { "reasoningEffort" to it })).toTypedArray())
        val result = call("dsh/selectModel", params)
        return parseSelection(result.at("selected") ?: throw RemoteSessionException("REMOTE_MODEL_NOT_CONFIRMED"))
    }

    override suspend fun archive(sessionId: String, archived: Boolean) {
        if (!archived || SessionCapability.ARCHIVE !in capabilities) throw RemoteSessionException("ARCHIVE_UNSUPPORTED")
        val result = call("dsh/sessionArchive", obj("sessionId" to sessionId, "backend" to backend))
        if (result.text("sessionId") != sessionId || result.flag("archived") != true) throw RemoteSessionException("REMOTE_ARCHIVE_NOT_CONFIRMED")
    }
}
