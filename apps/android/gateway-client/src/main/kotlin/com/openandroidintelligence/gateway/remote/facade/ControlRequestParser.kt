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

/** JSON 与 CLI 只在此处解析一次；CLI 不经过 shell，字符串内的命令字符只是数据。 */
object ControlRequestParser {
    fun parse(input: String): ParsedControlRequest = try {
        val text = input.trim()
        val envelope = if (text.startsWith('{')) RemoteWireJson.parse(text).obj() else {
            val match = Regex("([a-z][a-z0-9_.]*)[ \\t]+--request-id[ \\t]+([A-Za-z0-9_-]{16,128})[ \\t]+(\\{[\\s\\S]*})").matchEntire(text)
                ?: fail("CONTROL_SYNTAX_INVALID")
            Json.of(mapOf("request_id" to match.groupValues[2], "op" to match.groupValues[1],
                "args" to RemoteWireJson.parse(match.groupValues[3]))) .obj()
        }
        val fields = envelope.fieldsMap()
        fields.exact(setOf("request_id", "op", "args"))
        val requestId = fields.text("request_id")
        val op = fields.text("op")
        val args = fields.getValue("args").obj().fieldsMap()
        val operation = operation(op, args)
        ParsedControlRequest(requestId, operation, Json.sha256(Json.of(mapOf("op" to op, "args" to JsonValue.JObject(args.toList())))))
    } catch (failure: ControlRequestException) { throw failure }
    catch (_: Exception) { fail("CONTROL_PARAMETERS_INVALID") }

    private fun operation(op: String, a: Map<String, JsonValue>): ControlOperation = when (op) {
        "ui.dump_tree" -> {
            a.allowed(setOf("operation", "maxNodes", "maxDepth", "maxBytes", "text", "resourceId", "description", "previousHash"))
            ControlOperation.DumpTree(ScreenReadQuery(operation = a.optionalText("operation") ?: "snapshot",
                maxNodes = a.optionalInt("maxNodes") ?: 128, maxDepth = a.optionalInt("maxDepth") ?: 24,
                maxBytes = a.optionalInt("maxBytes") ?: 48_000, text = a.optionalText("text"), resourceId = a.optionalText("resourceId"),
                description = a.optionalText("description"), previousHash = a.optionalText("previousHash")))
        }
        "ui.capture_som" -> { a.exact(emptySet()); ControlOperation.CaptureSom }
        "ui.tap", "ui.long_press" -> {
            a.allowed(setOf("snapshotId", "nodeId", "x", "y") + if (op == "ui.long_press") setOf("durationMs") else emptySet())
            val node = a.optionalText("nodeId")
            val x = a.optionalInt("x"); val y = a.optionalInt("y")
            if (node != null && (x != null || y != null) || node == null && (x == null || y == null)) fail("CONTROL_TARGET_CONFLICT")
            val action = if (op == "ui.tap") ScreenAction.Click(node, x, y)
                else ScreenAction.LongPress(node, x, y, a.optionalLong("durationMs") ?: 600)
            ControlOperation.Screen(a.text("snapshotId"), action, op)
        }
        "ui.swipe", "ui.drag" -> {
            a.allowed(setOf("snapshotId", "x", "y", "endX", "endY", "durationMs"))
            val action = if (op == "ui.swipe") ScreenAction.Swipe(a.int("x"), a.int("y"), a.int("endX"), a.int("endY"), a.optionalLong("durationMs") ?: 400)
                else ScreenAction.Drag(a.int("x"), a.int("y"), a.int("endX"), a.int("endY"), a.optionalLong("durationMs") ?: 800)
            ControlOperation.Screen(a.text("snapshotId"), action, op)
        }
        "ui.input_text" -> {
            a.exact(setOf("snapshotId", "nodeId", "text"))
            ControlOperation.Screen(a.text("snapshotId"), ScreenAction.SetText(a.text("nodeId"), a.text("text", allowEmpty = true)), op)
        }
        "ui.scroll" -> {
            a.exact(setOf("snapshotId", "nodeId", "direction"))
            val direction = a.text("direction")
            if (direction !in setOf("forward", "backward", "up", "down", "left", "right")) fail("CONTROL_PARAMETERS_INVALID")
            ControlOperation.Screen(a.text("snapshotId"), ScreenAction.Scroll(a.text("nodeId"), direction), op)
        }
        "ui.global_action" -> {
            a.exact(setOf("snapshotId", "globalAction"))
            val action = a.text("globalAction")
            if (action !in setOf("back", "home", "recents", "notifications", "quickSettings")) fail("CONTROL_PARAMETERS_INVALID")
            ControlOperation.Screen(a.text("snapshotId"), ScreenAction.Global(action), op)
        }
        "ui.launch_app" -> {
            a.exact(setOf("snapshotId", "packageName"))
            ControlOperation.Screen(a.text("snapshotId"), ScreenAction.LaunchApp(a.text("packageName")), op)
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
