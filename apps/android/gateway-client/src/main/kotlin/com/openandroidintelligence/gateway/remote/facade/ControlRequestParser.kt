package com.openandroidintelligence.gateway.remote.facade

import com.openandroidintelligence.control.ScreenAction
import com.openandroidintelligence.control.ScreenReadQuery
import com.openandroidintelligence.control.remote.ControlOperation
import com.openandroidintelligence.control.remote.ControlRequestException
import com.openandroidintelligence.control.remote.ControlValue
import com.openandroidintelligence.control.remote.ParsedControlRequest
import com.openandroidintelligence.gateway.remote.protocol.RemoteWireJson
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonValue
import java.math.BigDecimal
import java.nio.CharBuffer
import java.security.MessageDigest

/** JSON 与 CLI 只在此处解析一次；CLI 不经过 shell，字符串内的命令字符只是数据。 */
object ControlRequestParser {
    fun parse(input: String): ParsedControlRequest = try {
        val text = input.trim()
        val envelope = if (text.startsWith('{')) RemoteWireJson.parse(text).obj() else {
            val match = Regex("([a-z][a-z0-9_.]*)[ \\t]+--request-id[ \\t]+([A-Za-z0-9_-]{16,128})[ \\t]+(\\{[\\s\\S]*})").matchEntire(text)
                ?: fail("CONTROL_SYNTAX_INVALID")
            Json.of(mapOf("request_id" to match.groupValues[2], "op" to match.groupValues[1],
                "args" to RemoteWireJson.parse(match.groupValues[3]))).obj()
        }
        validateUnicode(envelope)
        val fields = envelope.fieldsMap()
        fields.exact(setOf("request_id", "op", "args"))
        val requestId = fields.text("request_id")
        val op = fields.text("op")
        val args = fields.getValue("args").obj().fieldsMap()
        val operation = operation(op, args)
        validateAction(operation)
        ParsedControlRequest(requestId, operation, parameterDigest(Json.of(mapOf("op" to op, "args" to canonicalArgs(operation)))))
    } catch (failure: ControlRequestException) { throw failure }
    catch (_: Exception) { fail("CONTROL_PARAMETERS_INVALID") }

    private fun operation(op: String, a: Map<String, JsonValue>): ControlOperation = when (op) {
        "ui.dump_tree" -> {
            a.allowed(setOf("operation", "maxNodes", "maxDepth", "maxBytes", "text", "resourceId", "description", "previousHash"))
            val defaults = ScreenReadQuery()
            ControlOperation.DumpTree(ScreenReadQuery(operation = a.optionalText("operation") ?: defaults.operation,
                maxNodes = a.optionalInt("maxNodes") ?: defaults.maxNodes, maxDepth = a.optionalInt("maxDepth") ?: defaults.maxDepth,
                maxBytes = a.optionalInt("maxBytes") ?: defaults.maxBytes, text = a.optionalText("text"), resourceId = a.optionalText("resourceId"),
                description = a.optionalText("description"), previousHash = a.optionalText("previousHash")))
        }
        "ui.capture_som" -> { a.exact(emptySet()); ControlOperation.CaptureSom }
        "ui.tap", "ui.long_press" -> {
            a.allowed(setOf("snapshotId", "nodeId", "x", "y") + if (op == "ui.long_press") setOf("durationMs") else emptySet())
            val node = a.optionalText("nodeId")
            val x = a.optionalInt("x"); val y = a.optionalInt("y")
            if (node != null && (x != null || y != null) || node == null && (x == null || y == null)) fail("CONTROL_TARGET_CONFLICT")
            val action = if (op == "ui.tap") ScreenAction.Click(node, x, y)
                else ScreenAction.LongPress(node, x, y).let { defaults ->
                    a.optionalLong("durationMs")?.let { defaults.copy(durationMs = it) } ?: defaults
                }
            ControlOperation.Screen(a.text("snapshotId"), action)
        }
        "ui.swipe", "ui.drag" -> {
            a.allowed(setOf("snapshotId", "x", "y", "endX", "endY", "durationMs"))
            val action = if (op == "ui.swipe") ScreenAction.Swipe(a.int("x"), a.int("y"), a.int("endX"), a.int("endY")).let { defaults ->
                a.optionalLong("durationMs")?.let { defaults.copy(durationMs = it) } ?: defaults
            } else ScreenAction.Drag(a.int("x"), a.int("y"), a.int("endX"), a.int("endY")).let { defaults ->
                a.optionalLong("durationMs")?.let { defaults.copy(durationMs = it) } ?: defaults
            }
            ControlOperation.Screen(a.text("snapshotId"), action)
        }
        "ui.input_text" -> {
            a.exact(setOf("snapshotId", "nodeId", "text"))
            ControlOperation.Screen(a.text("snapshotId"), ScreenAction.SetText(a.text("nodeId"), a.text("text", allowEmpty = true)))
        }
        "ui.scroll" -> {
            a.exact(setOf("snapshotId", "nodeId", "direction"))
            val direction = a.text("direction")
            if (direction !in setOf("forward", "backward", "up", "down", "left", "right")) fail("CONTROL_PARAMETERS_INVALID")
            ControlOperation.Screen(a.text("snapshotId"), ScreenAction.Scroll(a.text("nodeId"), direction))
        }
        "ui.global_action" -> {
            a.exact(setOf("snapshotId", "globalAction"))
            val action = a.text("globalAction")
            if (action !in setOf("back", "home", "recents", "notifications", "quickSettings")) fail("CONTROL_PARAMETERS_INVALID")
            ControlOperation.Screen(a.text("snapshotId"), ScreenAction.Global(action))
        }
        "ui.launch_app" -> {
            a.exact(setOf("snapshotId", "packageName"))
            ControlOperation.Screen(a.text("snapshotId"), ScreenAction.LaunchApp(a.text("packageName")))
        }
        "plugin.invoke" -> {
            a.exact(setOf("pluginId", "capabilityId", "method", "params"))
            ControlOperation.PluginInvoke(a.text("pluginId"), a.text("capabilityId"), a.text("method"), toControl(a.getValue("params")) as? ControlValue.Object
                ?: fail("CONTROL_PARAMETERS_INVALID"))
        }
        "request.status" -> {
            a.exact(setOf("targetRequestId", "targetTaskSessionId"))
            ControlOperation.Status(requestId(a.text("targetRequestId")), a.text("targetTaskSessionId"))
        }
        "request.cancel" -> {
            a.exact(setOf("targetRequestId")); ControlOperation.Cancel(requestId(a.text("targetRequestId")))
        }
        else -> fail("CONTROL_OPERATION_UNSUPPORTED")
    }

    /** 与现有原生后端相同的静态约束；屏幕上界、节点可用性仍须在真实快照上复核。 */
    private fun validateAction(operation: ControlOperation) {
        val action = (operation as? ControlOperation.Screen)?.action ?: return
        fun point(x: Int?, y: Int?) { if (x != null && x < 0 || y != null && y < 0) fail("CONTROL_PARAMETERS_INVALID") }
        fun duration(value: Long) { if (value !in 50..3000) fail("CONTROL_PARAMETERS_INVALID") }
        when (action) {
            is ScreenAction.Click -> point(action.x, action.y)
            is ScreenAction.LongPress -> { point(action.x, action.y); duration(action.durationMs) }
            is ScreenAction.Swipe -> { point(action.x, action.y); point(action.endX, action.endY); duration(action.durationMs) }
            is ScreenAction.Drag -> { point(action.x, action.y); point(action.endX, action.endY); duration(action.durationMs) }
            is ScreenAction.SetText -> if (action.text.length > 4096) fail("CONTROL_PARAMETERS_INVALID")
            is ScreenAction.LaunchApp -> if (action.packageName.length > 256 ||
                !Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(action.packageName)) fail("CONTROL_PARAMETERS_INVALID")
            else -> Unit
        }
    }

    /** 从实际执行参数形成摘要，省略值与显式默认值不能产生两个不同的写入身份。 */
    private fun canonicalArgs(operation: ControlOperation): Any = when (operation) {
        is ControlOperation.DumpTree -> operation.query.let { mapOf("operation" to it.operation, "maxNodes" to it.maxNodes,
            "maxDepth" to it.maxDepth, "maxBytes" to it.maxBytes, "text" to it.text, "resourceId" to it.resourceId,
            "description" to it.description, "previousHash" to it.previousHash) }
        ControlOperation.CaptureSom -> emptyMap<String, Any>()
        is ControlOperation.Screen -> mapOf("snapshotId" to operation.snapshotId) + when (val action = operation.action) {
            is ScreenAction.Click -> mapOf("nodeId" to action.nodeId, "x" to action.x, "y" to action.y)
            is ScreenAction.LongPress -> mapOf("nodeId" to action.nodeId, "x" to action.x, "y" to action.y, "durationMs" to action.durationMs)
            is ScreenAction.Swipe -> mapOf("x" to action.x, "y" to action.y, "endX" to action.endX, "endY" to action.endY, "durationMs" to action.durationMs)
            is ScreenAction.Drag -> mapOf("x" to action.x, "y" to action.y, "endX" to action.endX, "endY" to action.endY, "durationMs" to action.durationMs)
            is ScreenAction.SetText -> mapOf("nodeId" to action.nodeId, "text" to action.text)
            is ScreenAction.Scroll -> mapOf("nodeId" to action.nodeId, "direction" to action.direction)
            is ScreenAction.Global -> mapOf("globalAction" to action.globalAction)
            is ScreenAction.LaunchApp -> mapOf("packageName" to action.packageName)
        }
        is ControlOperation.PluginInvoke -> mapOf("pluginId" to operation.pluginId, "capabilityId" to operation.capabilityId,
            "method" to operation.method, "params" to fromControl(operation.params))
        is ControlOperation.Status -> mapOf("targetRequestId" to operation.targetRequestId, "targetTaskSessionId" to operation.targetTaskSessionId)
        is ControlOperation.Cancel -> mapOf("targetRequestId" to operation.targetRequestId)
    }

    private fun fromControl(value: ControlValue): JsonValue = when (value) {
        ControlValue.Null -> JsonValue.JNull
        is ControlValue.Text -> JsonValue.JString(value.value)
        is ControlValue.Number -> JsonValue.JNumber(value.value)
        is ControlValue.Boolean -> JsonValue.JBool(value.value)
        is ControlValue.Object -> JsonValue.JObject(value.values.map { it.key to fromControl(it.value) })
        is ControlValue.Array -> JsonValue.JArray(value.values.map(::fromControl))
    }

    /** 仅此控制入口使用精确十进制摘要，避免旧通用 JSON 的 Double 舍入合并不同请求。 */
    private fun parameterDigest(value: JsonValue): String {
        fun canonical(item: JsonValue): String = when (item) {
            JsonValue.JNull -> "null"
            is JsonValue.JBool -> item.value.toString()
            is JsonValue.JString -> Json.canonical(item)
            is JsonValue.JNumber -> BigDecimal(item.raw).stripTrailingZeros().toString()
            is JsonValue.JArray -> item.items.joinToString(",", "[", "]", transform = ::canonical)
            is JsonValue.JObject -> item.fields.sortedBy { it.first }.joinToString(",", "{", "}") {
                Json.canonical(JsonValue.JString(it.first)) + ":" + canonical(it.second)
            }
        }
        return "sha256:" + MessageDigest.getInstance("SHA-256").digest(canonical(value).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** 拒绝孤立 UTF-16 代理项，不能在 UTF-8 编码时替换为问号而改变参数身份。 */
    private fun validateUnicode(value: JsonValue) {
        fun text(value: String) {
            try { Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value)) }
            catch (_: Exception) { fail("CONTROL_UNICODE_INVALID") }
        }
        val pending = ArrayDeque<JsonValue>()
        pending.add(value)
        while (pending.isNotEmpty()) when (val item = pending.removeLast()) {
            is JsonValue.JString -> text(item.value)
            is JsonValue.JArray -> pending.addAll(item.items)
            is JsonValue.JObject -> item.fields.forEach { text(it.first); pending.add(it.second) }
            else -> Unit
        }
    }

    internal fun toControl(value: JsonValue): ControlValue = when (value) {
        JsonValue.JNull -> ControlValue.Null
        is JsonValue.JString -> ControlValue.Text(value.value)
        is JsonValue.JNumber -> ControlValue.Number(value.raw)
        is JsonValue.JBool -> ControlValue.Boolean(value.value)
        is JsonValue.JArray -> ControlValue.Array(value.items.map(::toControl))
        is JsonValue.JObject -> ControlValue.Object(value.fields.associate { it.first to toControl(it.second) })
    }

    private fun JsonValue.obj() = this as? JsonValue.JObject ?: fail("CONTROL_PARAMETERS_INVALID")
    private fun JsonValue.JObject.fieldsMap() = fields.toMap().also { if (it.size != fields.size) fail("CONTROL_PARAMETERS_INVALID") }
    private fun Map<String, JsonValue>.allowed(keys: Set<String>) { if (this.keys.any { it !in keys }) fail("CONTROL_UNKNOWN_FIELD") }
    private fun Map<String, JsonValue>.exact(keys: Set<String>) { allowed(keys); if (this.keys != keys) fail("CONTROL_PARAMETERS_INVALID") }
    private fun Map<String, JsonValue>.text(key: String, allowEmpty: Boolean = false): String =
        (this[key] as? JsonValue.JString)?.value?.takeIf { allowEmpty || it.isNotEmpty() } ?: fail("CONTROL_PARAMETERS_INVALID")
    private fun Map<String, JsonValue>.optionalText(key: String): String? = if (key in this) text(key) else null
    private fun Map<String, JsonValue>.optionalLong(key: String): Long? = if (key in this) {
        val raw = (this[key] as? JsonValue.JNumber)?.raw ?: fail("CONTROL_PARAMETERS_INVALID")
        try { BigDecimal(raw).longValueExact() } catch (_: Exception) { fail("CONTROL_PARAMETERS_INVALID") }
    } else null
    private fun Map<String, JsonValue>.optionalInt(key: String): Int? = optionalLong(key)?.let {
        if (it !in Int.MIN_VALUE..Int.MAX_VALUE) fail("CONTROL_PARAMETERS_INVALID"); it.toInt()
    }
    private fun Map<String, JsonValue>.int(key: String): Int = optionalInt(key) ?: fail("CONTROL_PARAMETERS_INVALID")
    private fun requestId(value: String): String = value.also { if (!Regex("[A-Za-z0-9_-]{16,128}").matches(it)) fail("CONTROL_REQUEST_ID_INVALID") }
    private fun fail(code: String): Nothing = throw ControlRequestException(code)
}
