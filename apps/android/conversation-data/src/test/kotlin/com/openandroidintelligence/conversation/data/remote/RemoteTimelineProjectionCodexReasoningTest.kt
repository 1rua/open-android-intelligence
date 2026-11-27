package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.remote.RemoteLocalIds
import com.openandroidintelligence.gateway.schema.JsonValue
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** 覆盖固定上游完整 reasoning 条目的四类合法内容形态。 */
@RunWith(Parameterized::class)
class RemoteTimelineProjectionCodexReasoningTest(
    private val shape: String,
    private val field: String,
    private val value: Any,
    private val expected: String,
) {
    private val native = "session:完整思考"

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = listOf(
            arrayOf("摘要字符串数组", "summary", listOf("摘要一", "摘要二"), "摘要一\n摘要二"),
            arrayOf("正文字符串", "content", "完整思考", "完整思考"),
            arrayOf("正文字符串数组", "content", listOf("思考一", "思考二"), "思考一\n思考二"),
            arrayOf("正文对象数组", "content", listOf(obj("text" to "思考一"), obj("content" to "思考二")), "思考一\n思考二"),
        )
    }

    private fun frame(method: String, vararg params: Pair<String, Any?>): JsonValue =
        obj("method" to method, "params" to obj("threadId" to native, *params))

    private fun completed(withDelta: Boolean): RemoteTimelineProjection {
        val projection = RemoteTimelineProjection(RemoteLocalIds.encode(native))
        projection.accept(frame("turn/started", "turn" to obj("id" to "T")))
        projection.accept(frame("item/started", "item" to obj("id" to "r", "type" to "reasoning")))
        if (withDelta) projection.accept(frame("item/reasoning/textDelta", "itemId" to "r", "delta" to expected))
        projection.accept(frame("item/completed", "item" to obj("id" to "r", "type" to "reasoning", field to value)))
        return projection
    }

    private fun assertCompleted(projection: RemoteTimelineProjection) {
        val row = projection.rows.getValue("r")
        assertEquals(shape, listOf(expected), row.reasoning.map { it.text })
        assertTrue(shape, row.reasoning.single().complete)
        assertEquals("T", row.generationId)
        assertEquals("CONFIRMED", row.state)
        assertTrue(row.parts.filterIsInstance<MessagePart.Text>().isEmpty())
    }

    @Test fun `空started后完整条目补齐思考`() = assertCompleted(completed(withDelta = false))

    @Test fun `完整条目替换已有增量且不重复思考`() = assertCompleted(completed(withDelta = true))
}
