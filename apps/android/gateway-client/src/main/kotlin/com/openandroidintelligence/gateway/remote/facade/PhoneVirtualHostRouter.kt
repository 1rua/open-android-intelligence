package com.openandroidintelligence.gateway.remote.facade

import com.openandroidintelligence.control.remote.ControlAccessBinding
import com.openandroidintelligence.control.remote.ControlRequestException
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import java.util.Base64
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach

/** 只公开手机工作区。网络层负责 Noise 身份，门面从不接受业务正文中的主体字段。 */
class PhoneVirtualHostRouter(private val bus: PhoneControlBus) {
    fun outputEpoch(access: ControlAccessBinding): Long = bus.outputEpoch(access)
    fun requireOutput(access: ControlAccessBinding, epoch: Long, metadataOnly: Boolean = false) = bus.requireOutput(access, epoch, metadataOnly)

    suspend fun route(access: ControlAccessBinding, method: String, input: JsonValue): JsonValue {
        val epoch = outputEpoch(access)
        val args = carrierArgs(input)
        val result = when (method) {
            "harness.transport.describe" -> Json.of(mapOf("capabilities" to listOf("harness.remote.v3")))
            "workspace/list" -> workspace(access)
            "session/list" -> Json.of(mapOf("items" to bus.sessions(access)))
            "session/create" -> {
                val request = request(args)
                request.only(setOf("workspaceId"))
                if (request.text("workspaceId") != WORKSPACE) fail("CONTROL_WORKSPACE_NOT_FOUND")
                Json.of(mapOf("sessionId" to bus.create(access)))
            }
            "session/prompt" -> {
                val request = request(args)
                request.only(setOf("sessionId", "requestId", "mode", "content"))
                if (request.text("mode") != "queue") fail("CONTROL_PROMPT_MODE_UNSUPPORTED")
                val content = request["content"] as? JsonValue.JArray ?: fail("CONTROL_PROMPT_INVALID")
                if (content.items.size != 1) fail("CONTROL_PROMPT_INVALID")
                val block = fields(content.items.single())
                block.only(setOf("type", "text"))
                if (block.text("type") != "text") fail("CONTROL_PROMPT_INVALID")
                val parsed = ControlRequestParser.parse(block.text("text"))
                bus.submit(access, request.text("sessionId"), parsed)
                // 上游 requestId 是传输相关标识；业务幂等只使用信封 request_id。
                Json.of(mapOf("accepted" to true, "requestId" to request.text("requestId"), "request_id" to parsed.requestId))
            }
            "session/cancel" -> request(args).let { it.only(setOf("sessionId")); bus.cancelTask(access, it.text("sessionId")) }
            "session/page", "session/history" -> history(access, request(args))
            "session/projections" -> {
                val page = fields(history(access, request(args)))
                page.getValue("projections")
            }
            "workspaceFiles/stat", "workspaceFiles/readBytes" -> file(access, method, request(args))
            "phone/capabilities" -> bus.capabilities(access)
            else -> fail("METHOD_NOT_FOUND")
        }
        val metadataOnly = when (method) {
            "session/page", "session/history", "session/projections" -> sessionId(request(args)) == "control-bus"
            "workspaceFiles/stat", "workspaceFiles/readBytes", "phone/capabilities" -> false
            else -> true
        }
        // 文件拷贝和 JSON 组装也可能跨越撤销；返回前复核，不能只在读取入口核对一次。
        requireOutput(access, epoch, metadataOnly)
        return result
    }

    /** 每个订阅自行记录 cursor，取消订阅只停止观察，不取消手机请求。 */
    fun metadataStream(method: String, input: JsonValue): Boolean = method in setOf("workspace/follow", "session/control") ||
        method == "session/follow" && sessionId(request(carrierArgs(input))) == "control-bus"

    fun stream(access: ControlAccessBinding, method: String, input: JsonValue): Flow<JsonValue> {
        val epoch = outputEpoch(access)
        val metadataOnly = metadataStream(method, input)
        return flow {
        val args = carrierArgs(input)
        when (method) {
            "workspace/follow" -> bus.changes.collect { emit(Json.of(mapOf("type" to "baseline", "value" to workspace(access)))) }
            "session/control" -> bus.changes.collect {
                emit(Json.of(mapOf("type" to "baseline", "value" to mapOf("projections" to bus.sessions(access)))))
            }
            "session/follow" -> {
                val request = request(args)
                val sessionId = sessionId(request)
                var lastCursor: Long? = null
                var revision = 0L
                bus.changes.collect {
                    val page = history(access, request)
                    val fields = fields(page)
                    val cursor = (fields.getValue("cursor") as JsonValue.JNumber).raw.toLong()
                    if (lastCursor == null) {
                        revision = fields["assistantStream"]?.let(::fields)?.number("revision") ?: 0
                        emit(page)
                    } else {
                        bus.eventsSince(access, sessionId, checkNotNull(lastCursor)).forEach { row ->
                            val event = fields(fields(row).getValue("event"))
                            if (event.text("type") == "assistant/message") {
                                val seq = event.number("seq")!!
                                val data = fields(event.getValue("data"))
                                val attempt = "phone:$sessionId:$seq"
                                emit(Json.of(mapOf("type" to "assistant-stream", "frame" to mapOf("type" to "start", "attemptId" to attempt,
                                    "revision" to ++revision, "startedAfterSeq" to seq - 1, "turn" to (data.number("turn") ?: 0), "step" to 1))))
                                val chunks = (data["stream"] as? JsonValue.JArray)?.items.orEmpty()
                                chunks.forEachIndexed { index, chunk -> emit(Json.of(mapOf("type" to "assistant-stream", "frame" to mapOf(
                                    "type" to "chunk", "attemptId" to attempt, "revision" to ++revision, "index" to index,
                                    "time" to event.getValue("time"), "chunk" to (fields(chunk)["chunk"] ?: chunk))))) }
                                emit(row)
                                emit(Json.of(mapOf("type" to "assistant-stream", "frame" to mapOf("type" to "end", "attemptId" to attempt,
                                    "revision" to ++revision, "index" to chunks.size,
                                    "outcome" to mapOf("kind" to "committed", "eventType" to "assistant/message", "seq" to seq)))))
                            } else emit(row)
                        }
                        val projected = fields["projections"]?.let(::fields)?.get("values")?.let(::fields)
                        projected?.get("inbox")?.let { value -> emit(Json.of(mapOf("type" to "projection", "key" to "inbox", "value" to value, "seq" to cursor))) }
                    }
                    lastCursor = cursor
                }
            }
            else -> fail("METHOD_NOT_FOUND")
        }
        }.onEach { requireOutput(access, epoch, metadataOnly) }
    }

    private fun workspace(access: ControlAccessBinding): JsonValue = Json.of(mapOf("items" to listOf(mapOf("workspaceId" to WORKSPACE,
        "path" to WORKSPACE, "title" to "手机能力", "sessionIds" to bus.sessions(access).map { fields(it).text("sessionId") })),
        "archivedSessionIds" to emptyList<String>(), "pinnedSessionIds" to emptyList<String>()))

    private fun history(access: ControlAccessBinding, request: Map<String, JsonValue>): JsonValue =
        bus.history(access, sessionId(request), request.number("beforeSeq"), request.number("throughSeq"), request.number("maxMessages")?.toInt() ?: 50)

    private fun file(access: ControlAccessBinding, method: String, request: Map<String, JsonValue>): JsonValue {
        val session = request["scope"]?.let(::fields)?.let { sessionId(it) }
            ?: request["workspaceFileScopeId"]?.let { (it as? JsonValue.JString)?.value } ?: sessionId(request)
        val path = request.text("path")
        if (!Regex("artifacts/[A-Za-z0-9_-]+").matches(path)) fail("CONTROL_ARTIFACT_PATH_INVALID")
        val artifact = bus.artifact(access, session, path.substringAfter('/'))
        val absolutePath = "/phone-capabilities/$session/$path"
        if (method == "workspaceFiles/stat") return Json.of(mapOf("path" to path, "absolutePath" to absolutePath, "kind" to "file", "size" to artifact.bytes.size,
            "bytes" to artifact.bytes.size, "version" to artifact.version, "mimeType" to artifact.mimeType))
        request["version"]?.let { if ((it as? JsonValue.JString)?.value != artifact.version) fail("CONTROL_ARTIFACT_VERSION_CHANGED") }
        val range = request["range"]?.let(::fields) ?: request
        val offset = range.number("offset") ?: 0
        val length = range.number("length") ?: range.number("maxBytes") ?: fail("CONTROL_ARTIFACT_RANGE_INVALID")
        if (offset !in 0..artifact.bytes.size.toLong() || length !in 1..Int.MAX_VALUE.toLong()) fail("CONTROL_ARTIFACT_RANGE_INVALID")
        val end = minOf(artifact.bytes.size.toLong(), offset + length).toInt()
        return Json.of(mapOf("path" to path, "absolutePath" to absolutePath, "bytes" to artifact.bytes.size, "version" to artifact.version, "offset" to offset,
            "data" to Base64.getEncoder().encodeToString(artifact.bytes.copyOfRange(offset.toInt(), end)), "eof" to (end == artifact.bytes.size), "nextOffset" to end))
    }

    private fun carrierArgs(input: JsonValue): Map<String, JsonValue> {
        val root = fields(input)
        return if (root.keys == setOf("args")) fields(root.getValue("args")) else root
    }
    private fun request(args: Map<String, JsonValue>) = args["request"]?.let(::fields) ?: args
    private fun sessionId(request: Map<String, JsonValue>): String = request["sessionId"]?.let { (it as? JsonValue.JString)?.value }
        ?: request["address"]?.let(::fields)?.let { address ->
            if (address.text("kind") != "session") fail("CONTROL_TASK_INVALID")
            address.text("sessionId")
        } ?: fail("CONTROL_TASK_INVALID")
    private fun fields(value: JsonValue): Map<String, JsonValue> = (value as? JsonValue.JObject)?.fields?.toMap() ?: fail("CONTROL_PARAMETERS_INVALID")
    private fun Map<String, JsonValue>.text(name: String): String = (get(name) as? JsonValue.JString)?.value ?: fail("CONTROL_PARAMETERS_INVALID")
    private fun Map<String, JsonValue>.number(name: String): Long? = get(name)?.let { (it as? JsonValue.JNumber)?.raw?.toLongOrNull() ?: fail("CONTROL_PARAMETERS_INVALID") }
    private fun Map<String, JsonValue>.only(allowed: Set<String>) { if (!keys.all { it in allowed }) fail("CONTROL_PARAMETERS_INVALID") }
    private fun fail(code: String): Nothing = throw ControlRequestException(code)
    companion object { const val WORKSPACE = "phone-capabilities" }
}
