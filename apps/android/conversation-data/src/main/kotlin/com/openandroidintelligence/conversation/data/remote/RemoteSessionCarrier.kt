package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.ports.PageRequest
import com.openandroidintelligence.conversation.remote.AgentEndpoint
import com.openandroidintelligence.conversation.remote.ModelKey
import com.openandroidintelligence.conversation.remote.ReasoningEffortOption
import com.openandroidintelligence.conversation.remote.SessionCapability
import com.openandroidintelligence.conversation.remote.SessionModelOption
import com.openandroidintelligence.gateway.remote.rpc.RemoteRpcChannel
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import kotlinx.coroutines.flow.Flow

/** 原生后端保留独立的网络方法，只有进入聊天领域后才共享展示模型。 */
interface RemoteSessionCarrier {
    val channel: RemoteRpcChannel
    val backend: String
    val capabilities: Set<SessionCapability>
    suspend fun workspaces(): List<RemoteWorkspace>
    suspend fun sessions(workspace: RemoteWorkspace, page: PageRequest): RemoteSessionPage
    suspend fun create(workspace: RemoteWorkspace): RemoteSessionRow
    suspend fun history(sessionId: String, page: PageRequest): RemoteHistoryPage
    fun follow(sessionId: String): Flow<JsonValue>
    suspend fun prompt(sessionId: String, requestId: String, content: List<JsonValue>, model: RemoteModelSelection? = null): JsonValue
    suspend fun interrupt(sessionId: String, generationId: String): JsonValue
    suspend fun steer(sessionId: String, generationId: String, requestId: String, text: String): JsonValue =
        throw RemoteSessionException("STEERING_UNSUPPORTED")
    suspend fun respond(sessionId: String, handle: String, allow: Boolean): JsonValue
    suspend fun models(sessionId: String): RemoteModelCatalog
    suspend fun commands(sessionId: String?): com.openandroidintelligence.conversation.ports.AgentCommandCatalog =
        throw RemoteSessionException("COMMANDS_UNSUPPORTED")
    suspend fun selectModel(sessionId: String, selection: RemoteModelSelection): RemoteModelSelection
    suspend fun rename(sessionId: String, title: String): Unit = throw RemoteSessionException("RENAME_UNSUPPORTED")
    suspend fun archive(sessionId: String, archived: Boolean): Unit = throw RemoteSessionException("ARCHIVE_UNSUPPORTED")
}

data class RemoteWorkspace(val endpoint: AgentEndpoint, val path: String, val sessionIds: Set<String>? = null)
data class RemoteSessionRow(val id: String, val title: String, val createdAt: Long, val updatedAt: Long,
    val archived: Boolean = false, val model: RemoteModelSelection? = null)
data class RemoteSessionPage(val items: List<RemoteSessionRow>, val nextCursor: String? = null)
data class RemoteHistoryPage(val records: List<JsonValue>, val nextCursor: String? = null,
    val revision: Long? = null, val activeGenerationId: String? = null)
data class RemoteModelSelection(val key: ModelKey, val effortId: String? = null)
data class RemoteModelCatalog(val models: List<SessionModelOption>,
    val efforts: Map<ModelKey, List<ReasoningEffortOption>>, val selected: RemoteModelSelection? = null)

class RemoteSessionException(val code: String) : IllegalStateException(code)

internal fun obj(vararg pairs: Pair<String, Any?>): JsonValue.JObject = Json.of(linkedMapOf(*pairs)) as JsonValue.JObject
internal fun JsonValue.objectValue(): JsonValue.JObject = this as? JsonValue.JObject
    ?: throw RemoteSessionException("REMOTE_INVALID_RESPONSE")
internal fun JsonValue.at(key: String): JsonValue? = (this as? JsonValue.JObject)?.fields?.firstOrNull { it.first == key }?.second
internal fun JsonValue.text(key: String): String? = (at(key) as? JsonValue.JString)?.value
internal fun JsonValue.requiredText(key: String): String = text(key)?.takeIf { it.isNotEmpty() }
    ?: throw RemoteSessionException("REMOTE_INVALID_RESPONSE")
internal fun JsonValue.number(key: String): Long? = (at(key) as? JsonValue.JNumber)?.raw?.toBigDecimalOrNull()?.longValueExact()
internal fun JsonValue.flag(key: String): Boolean? = (at(key) as? JsonValue.JBool)?.value
internal fun JsonValue.array(key: String): List<JsonValue>? = (at(key) as? JsonValue.JArray)?.items
internal fun JsonValue.requiredArray(key: String): List<JsonValue> = array(key)
    ?: throw RemoteSessionException("REMOTE_INVALID_RESPONSE")
internal fun JsonValue.listValue(): List<JsonValue> = (this as? JsonValue.JArray)?.items
    ?: throw RemoteSessionException("REMOTE_INVALID_RESPONSE")
internal fun JsonValue.stringValue(): String? = (this as? JsonValue.JString)?.value
internal fun millis(value: JsonValue, key: String): Long = value.number(key)
    ?: value.text(key)?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0
internal fun secondsMillis(value: JsonValue, key: String): Long = value.number(key)?.let { Math.multiplyExact(it, 1_000) }
    ?: value.text(key)?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0

/** 业务失败不能伪装成成功的空历史或空模型目录。 */
internal fun JsonValue.unwrapResult(): JsonValue {
    return when (flag("ok")) {
        true -> at("value") ?: JsonValue.JNull
        false -> throw RemoteSessionException(at("error")?.text("code") ?: "REMOTE_REQUEST_REJECTED")
        null -> this
    }
}
