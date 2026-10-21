package com.openandroidintelligence.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 桥接状态解析的纯逻辑回归。
 *
 * 这里刻意断言"JSON 字段到界面状态"的映射，而不是渲染结果：跨进程协议已经存在，
 * 界面换皮不得改变字段语义；同时确保空/坏数据不会被解释成"已连接"或"有消息"。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AssistantStateJsonTest {
    private fun parsed(payload: String): AssistantState =
        requireNotNull(parseAssistantState(payload)) { "payload 应当可以解析：$payload" }

    /**
     * 外观必须来自主进程下发的字段：跨进程读 SharedPreferences 会拿到旧缓存，
     * 所以这条锁定"桥接里带了就用桥接值"。
     */
    @Test fun appearanceIsTakenFromPublishedPayload() {
        val state = parsed(
            """
            {
              "connected": true,
              "consent": true,
              "appearance": {
                "theme": "DARK",
                "dynamicColor": false,
                "palette": "VIOLET",
                "reduceMotion": true
              }
            }
            """.trimIndent(),
        )
        val appearance = requireNotNull(state.appearance) { "应当解析出 appearance" }
        assertEquals(THEME_DARK, appearance.theme)
        assertFalse(appearance.dynamicColor)
        assertEquals("VIOLET", appearance.palette)
        assertTrue(appearance.reduceMotion)
    }

    /** 旧版主进程不带 appearance 时保持 null，由调用方回落到本地兜底值。 */
    @Test fun missingAppearanceStaysNull() {
        val state = parsed("""{"connected": true, "consent": true}""")
        assertNull(state.appearance)
    }

    @Test fun publishedPayloadMapsToStateWithoutLosingFields() {
        val state = parsed(
            """
            {
              "connected": true,
              "consent": true,
              "draft": "草稿",
              "title": "发布计划",
              "notice": "操作未完成，请检查当前连接或对话",
              "generation": "RUNNING",
              "threads": [{"id": "thread-1", "title": "发布计划"}, {"id": "thread-2", "title": ""}],
              "messages": [
                {"id": "m1", "sender": "user", "text": "你好", "attachments": ["screen.png"], "streaming": false},
                {"id": "m2", "sender": "assistant", "text": "在的", "attachments": [], "streaming": true}
              ]
            }
            """.trimIndent(),
        )

        assertTrue(state.bridgeAvailable)
        assertTrue(state.connected)
        assertTrue(state.consent)
        assertTrue(state.canInteract)
        assertEquals("发布计划", state.title)
        assertEquals("草稿", state.draft)
        assertEquals("操作未完成，请检查当前连接或对话", state.notice)
        assertTrue(state.isGenerating)
        assertFalse(state.isStopping)
        assertEquals(2, state.threads.size)
        assertEquals("thread-2", state.threads[1].id)
        assertEquals(2, state.messages.size)
        assertTrue(state.messages[0].fromUser)
        assertEquals(listOf("screen.png"), state.messages[0].attachments)
        assertFalse(state.messages[1].fromUser)
        assertTrue(state.messages[1].streaming)
    }

    @Test fun disconnectedPayloadNeverLooksInteractive() {
        val state = parsed("""{"connected": false, "consent": false}""")

        assertFalse(state.canInteract)
        assertTrue(state.messages.isEmpty())
        assertTrue(state.threads.isEmpty())
        assertEquals("Gateway 未连接", state.gatewayStatusLabel())
        assertEquals("请先在 App 中连接 Gateway", state.composerBlockReason())
    }

    @Test fun consentOnlyAfterConnectionIsRequiredBeforeContent() {
        val state = parsed("""{"connected": true, "consent": false}""")

        assertFalse(state.canInteract)
        assertEquals("Gateway 已连接 · 尚未绑定对话", state.gatewayStatusLabel())
        assertEquals("绑定当前对话后才会显示内容", state.composerBlockReason())
    }

    @Test fun jsonNullStringsAreFoldedIntoEmptyText() {
        val state = parsed(
            """{"connected": true, "consent": true, "title": null, "notice": null, "draft": null}""",
        )

        assertEquals("", state.title)
        assertEquals("", state.notice)
        assertEquals("", state.draft)
    }

    @Test fun unknownGenerationOutcomeBlocksSendingAndSaysSo() {
        val state = parsed(
            """{"connected": true, "consent": true, "generation": "OUTCOME_UNKNOWN"}""",
        )

        assertTrue(state.hasUnknownOutcome)
        assertFalse(state.isGenerating)
        assertEquals("上一条消息结果未知，请先在 App 中确认", state.composerBlockReason())
    }

    @Test fun malformedPayloadIsRejectedInsteadOfGuessing() {
        assertNull(parseAssistantState("不是 JSON"))
        assertNull(parseAssistantState(""))
    }

    @Test fun bridgeFailureIsALocalFactThatOverridesPublishedState() {
        val base = parsed("""{"connected": true, "consent": true}""")
        val failed = base.copy(bridgeAvailable = false)

        assertFalse(failed.canInteract)
        assertEquals("助理服务未连接", failed.gatewayStatusLabel())
        assertEquals("助理服务无法连接，请打开 App 后重试", failed.composerBlockReason())
    }
}
