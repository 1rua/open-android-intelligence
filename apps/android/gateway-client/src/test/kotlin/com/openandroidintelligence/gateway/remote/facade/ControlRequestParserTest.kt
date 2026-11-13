package com.openandroidintelligence.gateway.remote.facade

import com.openandroidintelligence.control.ScreenAction
import com.openandroidintelligence.control.remote.ControlOperation
import com.openandroidintelligence.control.remote.ControlRequestException
import com.openandroidintelligence.gateway.schema.Json
import org.junit.Assert.*
import org.junit.Test

class ControlRequestParserTest {
    @Test
    fun JSON与CLI同义且数据字符不进入Shell() {
        val json = ControlRequestParser.parse("""{"request_id":"request-00000001","op":"ui.input_text","args":{"snapshotId":"s","nodeId":"12","text":"$(echo text);|"}}""")
        val cli = ControlRequestParser.parse("""ui.input_text --request-id request-00000001 {"text":"$(echo text);|","nodeId":"12","snapshotId":"s"}""")
        assertEquals(json.requestId, cli.requestId)
        assertEquals(json.parameterDigest, cli.parameterDigest)
        assertEquals(json.operation, cli.operation)
        assertEquals("$(echo text);|", ((json.operation as ControlOperation.Screen).action as ScreenAction.SetText).text)
    }

    @Test
    fun 未知字段自报身份重复键自然语言和Shell后缀均拒绝() {
        listOf(
            """{"request_id":"request-00000001","op":"ui.capture_som","args":{},"principal":"other"}""",
            """{"request_id":"request-00000001","op":"ui.capture_som","args":{"account":"other"}}""",
            """{"request_id":"request-00000001","op":"ui.capture_som","op":"ui.tap","args":{}}""",
            "请点击登录按钮",
            """ui.capture_som --request-id request-00000001 {} ; echo x""",
            """{"request_id":"short","op":"ui.capture_som","args":{}}""",
        ).forEach { assertThrows(ControlRequestException::class.java) { ControlRequestParser.parse(it) } }
    }

    @Test
    fun 点击定位互斥并保留现有长按拖动滚动导航能力() {
        val conflict = """{"request_id":"request-00000001","op":"ui.tap","args":{"snapshotId":"s","nodeId":"12","x":1,"y":2}}"""
        assertThrows(ControlRequestException::class.java) { ControlRequestParser.parse(conflict) }
        val examples = mapOf(
            "ui.long_press" to """{"snapshotId":"s","nodeId":"12","durationMs":700}""",
            "ui.drag" to """{"snapshotId":"s","x":1,"y":2,"endX":3,"endY":4}""",
            "ui.scroll" to """{"snapshotId":"s","nodeId":"12","direction":"left"}""",
            "ui.global_action" to """{"snapshotId":"s","globalAction":"quickSettings"}""",
            "ui.launch_app" to """{"snapshotId":"s","packageName":"example.app"}""",
        )
        examples.forEach { (op, args) -> assertEquals(op, ControlRequestParser.parse("$op --request-id request-00000001 $args").operation.wireName) }
    }

    @Test
    fun 管理操作仅接受明确原请求任务且插件参数保留中立参数树() {
        val status = ControlRequestParser.parse("""request.status --request-id status-query-0001 {"targetRequestId":"request-00000001","targetTaskSessionId":"task-a"}""")
        assertEquals(ControlOperation.Status("request-00000001", "task-a"), status.operation)
        val plugin = ControlRequestParser.parse("""plugin.invoke --request-id request-00000001 {"pluginId":"sms","capabilityId":"sms.query:v1","method":"query","params":{"content":false,"filter":[1,null]}}""")
        assertEquals(setOf("content", "filter"), (plugin.operation as ControlOperation.PluginInvoke).params.values.keys)
    }

    @Test
    fun 每个操作必要参数缺失或类型错误均在执行前拒绝() {
        val required = mapOf(
            "ui.tap" to mapOf("snapshotId" to "s", "nodeId" to "12"),
            "ui.long_press" to mapOf("snapshotId" to "s", "nodeId" to "12"),
            "ui.swipe" to mapOf("snapshotId" to "s", "x" to 1, "y" to 2, "endX" to 3, "endY" to 4),
            "ui.drag" to mapOf("snapshotId" to "s", "x" to 1, "y" to 2, "endX" to 3, "endY" to 4),
            "ui.input_text" to mapOf("snapshotId" to "s", "nodeId" to "12", "text" to ""),
            "ui.scroll" to mapOf("snapshotId" to "s", "nodeId" to "12", "direction" to "forward"),
            "ui.global_action" to mapOf("snapshotId" to "s", "globalAction" to "back"),
            "ui.launch_app" to mapOf("snapshotId" to "s", "packageName" to "example.app"),
            "plugin.invoke" to mapOf("pluginId" to "sms", "capabilityId" to "sms.query:v1", "method" to "query", "params" to emptyMap<String, Any>()),
            "request.status" to mapOf("targetRequestId" to "request-00000001", "targetTaskSessionId" to "task-a"),
            "request.cancel" to mapOf("targetRequestId" to "request-00000001"),
        )
        required.forEach { (op, args) ->
            fun parse(values: Map<String, Any>) = ControlRequestParser.parse(Json.canonical(Json.of(
                mapOf("request_id" to "request-00000001", "op" to op, "args" to values))))
            assertEquals(op, parse(args).operation.wireName)
            args.keys.forEach { field ->
                assertThrows("$op 缺 $field", ControlRequestException::class.java) { parse(args - field) }
                assertThrows("$op 错类型 $field", ControlRequestException::class.java) { parse(args + (field to true)) }
            }
        }
        assertEquals("ui.dump_tree", ControlRequestParser.parse("ui.dump_tree --request-id request-00000001 {}").operation.wireName)
        assertEquals("ui.capture_som", ControlRequestParser.parse("ui.capture_som --request-id request-00000001 {}").operation.wireName)
        assertThrows(ControlRequestException::class.java) {
            ControlRequestParser.parse("ui.swipe --request-id request-00000001 {\"snapshotId\":\"s\",\"x\":1.5,\"y\":2,\"endX\":3,\"endY\":4}")
        }
    }

    @Test
    fun 执行同义默认值和数字编码产生同一摘要但操作类型仍隔离() {
        fun parse(op: String, args: String) = ControlRequestParser.parse("$op --request-id request-00000001 $args")
        val defaults = com.openandroidintelligence.control.ScreenReadQuery()
        assertEquals(parse("ui.dump_tree", "{}").parameterDigest, parse("ui.dump_tree", Json.canonical(Json.of(mapOf(
            "operation" to defaults.operation, "maxNodes" to defaults.maxNodes, "maxDepth" to defaults.maxDepth, "maxBytes" to defaults.maxBytes,
        )))).parameterDigest)
        val duration = ScreenAction.LongPress(nodeId = "12").durationMs
        assertEquals(parse("ui.long_press", """{"snapshotId":"s","nodeId":"12"}""").parameterDigest,
            parse("ui.long_press", """{"snapshotId":"s","nodeId":"12","durationMs":$duration}""").parameterDigest)
        assertEquals(parse("ui.tap", """{"snapshotId":"s","x":1,"y":2}""").parameterDigest,
            parse("ui.tap", """{"snapshotId":"s","x":1.0,"y":2e0}""").parameterDigest)
        assertNotEquals(parse("ui.tap", """{"snapshotId":"s","nodeId":"12"}""").parameterDigest,
            parse("ui.long_press", """{"snapshotId":"s","nodeId":"12"}""").parameterDigest)
    }

    @Test
    fun 现有原生静态参数约束在解析时拒绝且不伪造屏幕上界() {
        val invalid = listOf(
            """ui.tap --request-id request-00000001 {"snapshotId":"s","x":-1,"y":2}""",
            """ui.long_press --request-id request-00000001 {"snapshotId":"s","nodeId":"12","durationMs":0}""",
            """ui.drag --request-id request-00000001 {"snapshotId":"s","x":1,"y":2,"endX":3,"endY":4,"durationMs":3001}""",
            """ui.dump_tree --request-id request-00000001 {"operation":"other"}""",
            """ui.dump_tree --request-id request-00000001 {"maxNodes":-1}""",
            """ui.launch_app --request-id request-00000001 {"snapshotId":"s","packageName":"../app"}""",
        )
        invalid.forEach { assertThrows(ControlRequestException::class.java) { ControlRequestParser.parse(it) } }
        val input = Json.canonical(Json.of(mapOf("request_id" to "request-00000001", "op" to "ui.input_text",
            "args" to mapOf("snapshotId" to "s", "nodeId" to "12", "text" to "a".repeat(4097)))))
        assertThrows(ControlRequestException::class.java) { ControlRequestParser.parse(input) }
        assertEquals(Int.MAX_VALUE, ((ControlRequestParser.parse(
            """ui.tap --request-id request-00000001 {"snapshotId":"s","x":2147483647,"y":2}""").operation as ControlOperation.Screen).action as ScreenAction.Click).x)
    }

    @Test
    fun 精确插件数字与Unicode参数不能因摘要编码合并() {
        fun plugin(value: String) = ControlRequestParser.parse("""plugin.invoke --request-id request-00000001 {"pluginId":"p","capabilityId":"p:v1","method":"m","params":{"value":$value}}""")
        val numbers = listOf("9223372036854775807", "9223372036854775808", "9223372036854775809", "100000000000000000000")
        assertEquals(4, numbers.map { plugin(it).parameterDigest }.distinct().size)
        assertEquals(numbers, numbers.map { ((plugin(it).operation as ControlOperation.PluginInvoke).params.values.getValue("value")
            as com.openandroidintelligence.control.remote.ControlValue.Number).value })
        assertEquals(plugin("1000").parameterDigest, plugin("1e3").parameterDigest)
        assertEquals(plugin("1e-999999").parameterDigest, plugin("10e-1000000").parameterDigest)
        assertThrows(ControlRequestException::class.java) { plugin("\"\\ud800\"") }
        assertThrows(ControlRequestException::class.java) { plugin("{\"\\ud800\":1}") }
        assertNotNull(plugin("\"?\"").parameterDigest)
        assertNotNull(plugin("\"\\ud83d\\ude00\"").parameterDigest)
    }
}
