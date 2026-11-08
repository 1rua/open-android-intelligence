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
 * 桥接状态解析的纯逻辑回归（协议 v2）。
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
              "version": 2,
              "connected": true,
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

    /**
     * 首帧之前（还没有任何桥接状态）外观是 null，界面据此回落到本地偏好；
     * 一旦收到 v2 帧，外观就以主进程下发的值为准。
     */
    @Test fun appearanceIsOnlyNullBeforeTheFirstFrame() {
        assertNull(AssistantState().appearance)
        // v2 服务端每帧都带 appearance；帧里缺失时取协议默认值，而不是 null。
        val state = parsed("""{"version": 2, "connected": true}""")
        assertEquals(THEME_SYSTEM, requireNotNull(state.appearance).theme)
    }

    @Test fun publishedPayloadMapsToStateWithoutLosingFields() {
        val state = parsed(
            """
            {
              "version": 2,
              "revision": 7,
              "connected": true,
              "account": {
                "profileId": "profile-1",
                "accountId": "account-1",
                "agentName": "助手"
              },
              "sessionId": "session-1",
              "draft": "草稿",
              "activeThread": "thread-1",
              "generation": "RUNNING",
              "timelineTruncated": true,
              "notice": {"code": "AGENT_OFFLINE", "message": "操作未完成，请检查当前连接或对话"},
              "timeline": [
                {
                  "id": "m1",
                  "sender": "user",
                  "text": "你好",
                  "attachments": [
                    {"draftId": "draft-1", "filename": "screen.png", "mediaType": "image/png", "isImage": true, "localKey": "draft-1", "localState": "PENDING"}
                  ]
                },
                {"id": "m2", "sender": "assistant", "text": "在的", "streaming": true}
              ]
            }
            """.trimIndent(),
        )

        assertTrue(state.bridgeAvailable)
        assertTrue(state.connected)
        assertTrue(state.canInteract)
        assertEquals(7L, state.revision)
        assertEquals("session-1", state.sessionId)
        assertEquals("thread-1", state.activeThread)
        assertEquals("account-1", requireNotNull(state.account).accountId)
        assertEquals("profile-1", state.account?.profileId)
        assertEquals("助手", state.account?.agentName)
        assertEquals("草稿", state.draft)
        assertEquals("操作未完成，请检查当前连接或对话", state.notice)
        assertTrue(state.isGenerating)
        assertFalse(state.isStopping)
        assertTrue(state.timelineTruncated)
        assertEquals(2, state.timeline.size)
        assertTrue(state.timeline[0].isUser)
        assertEquals("draft-1", state.timeline[0].attachments.single().draftId)
        assertFalse(state.timeline[1].isUser)
        assertTrue(state.timeline[1].isStreaming)
    }

    @Test fun disconnectedPayloadNeverLooksInteractive() {
        val state = parsed("""{"version": 2, "connected": false}""")

        assertFalse(state.canInteract)
        assertTrue(state.timeline.isEmpty())
        assertNull(state.account)
        assertNull(state.sessionId)
        assertFalse(state.timelineTruncated)
        assertEquals("Gateway 未连接", state.gatewayStatusLabel())
        assertEquals("请先在 App 中连接 Gateway", state.composerBlockReason())
    }

    @Test fun connectedWithoutAccountStillAllowsInteractionAndPublishesNoAccount() {
        val state = parsed("""{"version": 2, "connected": true, "account": null}""")

        assertTrue(state.canInteract)
        assertNull(state.account)
        assertEquals("Gateway 已连接", state.gatewayStatusLabel())
        assertNull(state.composerBlockReason())
    }

    @Test fun jsonNullStringsAreFoldedIntoEmptyText() {
        val state = parsed(
            """{"version": 2, "connected": true, "draft": null, "activeThread": null, "sessionId": null}""",
        )

        assertEquals("", state.draft)
        assertNull(state.activeThread)
        assertNull(state.sessionId)
        assertEquals("", state.notice)
    }

    @Test fun unknownGenerationOutcomeBlocksSendingAndSaysSo() {
        val state = parsed(
            """{"version": 2, "connected": true, "generation": "OUTCOME_UNKNOWN"}""",
        )

        assertTrue(state.hasUnknownOutcome)
        assertFalse(state.isGenerating)
        assertEquals("上一条消息结果未知，请先在 App 中确认", state.composerBlockReason())
    }

    @Test fun malformedOrWrongVersionPayloadIsRejectedInsteadOfGuessing() {
        assertNull(parseAssistantState("不是 JSON"))
        assertNull(parseAssistantState(""))
        // v1 帧与新格式不兼容：整帧丢弃，保留上一帧而不是误读旧字段。
        assertNull(parseAssistantState("""{"version": 1, "connected": true}"""))
        assertNull(parseAssistantState("""{"connected": true}"""))
    }

    @Test fun bridgeFailureIsALocalFactThatOverridesPublishedState() {
        val base = parsed("""{"version": 2, "connected": true}""")
        val failed = base.copy(bridgeAvailable = false)

        assertFalse(failed.canInteract)
        assertEquals("助理服务未连接", failed.gatewayStatusLabel())
        assertEquals("助理服务无法连接，请打开 App 后重试", failed.composerBlockReason())
    }
}
