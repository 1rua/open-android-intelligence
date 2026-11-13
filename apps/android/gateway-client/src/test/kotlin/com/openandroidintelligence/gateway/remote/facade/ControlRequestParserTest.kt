package com.openandroidintelligence.gateway.remote.facade

import com.openandroidintelligence.control.ScreenAction
import com.openandroidintelligence.control.remote.ControlOperation
import com.openandroidintelligence.control.remote.ControlRequestException
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
}
