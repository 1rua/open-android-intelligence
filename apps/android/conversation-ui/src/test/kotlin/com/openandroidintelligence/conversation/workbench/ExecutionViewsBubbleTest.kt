package com.openandroidintelligence.conversation.workbench

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.state.TimelineEntry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 验证 R2 / F2：彻底消除“此消息没有可显示的文本”兜底空气泡。
 * 当消息仅包含工具调用、未知工具回执、或无可见正文文本时，绝不允许渲染占位空气泡。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExecutionViewsBubbleTest {

    @get:Rule
    val compose = createComposeRule()

    private fun assistantEntry(
        key: String = "msg_1",
        text: String = "",
        streaming: Boolean = false,
        reasoning: List<MessageReasoning> = emptyList(),
        attachments: List<TimelineAttachment> = emptyList(),
    ) = TimelineEntry(
        key = key,
        sender = "assistant",
        text = text,
        isUser = false,
        timestamp = 1000L,
        pendingAcceptance = false,
        batchGroupId = null,
        attachments = attachments,
        isStreaming = streaming,
        reasoning = reasoning,
    )

    private fun userEntry(
        key: String = "user_1",
        text: String = "",
        attachments: List<TimelineAttachment> = emptyList(),
    ) = TimelineEntry(
        key = key,
        sender = "user",
        text = text,
        isUser = true,
        timestamp = 900L,
        pendingAcceptance = false,
        batchGroupId = null,
        attachments = attachments,
        isStreaming = false,
    )

    // ==========================================
    // Tier 1: 功能覆盖测试 (F2 消除兜底空气泡)
    // ==========================================

    @Test
    fun t1_F2_01_Tool_Only_Message_No_Bubble() {
        // 纯工具调用消息：无正文文本，仅包含工具调用的 XML 格式
        val toolXml = """
            <tool_call name="adb_shell">
              <command>pm list packages</command>
              <result status="success">package:com.android.settings</result>
            </tool_call>
        """.trimIndent()
        val entry = assistantEntry(text = toolXml)

        compose.setContent {
            MaterialTheme {
                AssistantMessageContent(entry = entry)
            }
        }

        // 验证结构化工具卡片被渲染
        compose.onNodeWithText("[adb_shell]").assertIsDisplayed()
        // 核心断言：绝对不出现“此消息没有可显示的文本”占位气泡
        compose.onNodeWithText("此消息没有可显示的文本").assertDoesNotExist()
    }

    @Test
    fun t1_F2_02_Completed_Empty_Text_Skip() {
        // 已完成的空正文助手消息，无任何内容，不应渲染任何空气泡
        val entry = assistantEntry(text = "", streaming = false)

        compose.setContent {
            MaterialTheme {
                AssistantMessageContent(entry = entry)
            }
        }

        // 绝不允许渲染兜底占位文案
        compose.onNodeWithText("此消息没有可显示的文本").assertDoesNotExist()
    }

    @Test
    fun t1_F2_03_User_Empty_Message_No_Bubble() {
        // 用户空消息（无文本无附件），不应渲染空气泡
        val entry = userEntry(text = "")

        compose.setContent {
            MaterialTheme {
                AssistantMessageContent(entry = entry)
            }
        }

        compose.onNodeWithText("此消息没有可显示的文本").assertDoesNotExist()
    }

    @Test
    fun t1_F2_04_Tool_Execution_Card_Only() {
        // 消息仅包含围栏形式的工具调用，且无后续正文
        val fencedTool = """
            ```tool_call:search
            query=open intelligence
            ```
        """.trimIndent()
        val entry = assistantEntry(text = fencedTool)

        compose.setContent {
            MaterialTheme {
                AssistantMessageContent(entry = entry)
            }
        }

        compose.onNodeWithText("[search]").assertIsDisplayed()
        compose.onNodeWithText("此消息没有可显示的文本").assertDoesNotExist()
    }

    @Test
    fun t1_F2_05_Unparsed_Tool_Skip_Bubble() {
        // 未解析的工具回执或未知格式标记，即使正文解析为空，也不出现占位提示文本
        val unparsedXml = "<unknown_action id=\"123\"></unknown_action>"
        val entry = assistantEntry(text = unparsedXml)

        compose.setContent {
            MaterialTheme {
                AssistantMessageContent(entry = entry)
            }
        }

        compose.onNodeWithText("此消息没有可显示的文本").assertDoesNotExist()
    }

    // ==========================================
    // Tier 2: 边界与极端条件测试 (F2 Boundary & Corner Cases)
    // ==========================================

    @Test
    fun t2_F2_01_Consecutive_Empty_Messages() {
        // 连续多条空文本消息，界面上不能出现多个空气泡
        val emptyEntries = listOf(
            assistantEntry(key = "empty_1", text = ""),
            assistantEntry(key = "empty_2", text = ""),
            assistantEntry(key = "empty_3", text = ""),
        )

        compose.setContent {
            MaterialTheme {
                emptyEntries.forEach { entry ->
                    AssistantMessageContent(entry = entry)
                }
            }
        }

        compose.onNodeWithText("此消息没有可显示的文本").assertDoesNotExist()
    }

    @Test
    fun t2_F2_02_Empty_Message_With_Error_Status() {
        // 正文为空但带有失败/中断标记的消息，不渲染“此消息没有可显示的文本”
        val entry = assistantEntry(text = "", streaming = false)

        compose.setContent {
            MaterialTheme {
                AssistantMessageContent(entry = entry)
            }
        }

        compose.onNodeWithText("此消息没有可显示的文本").assertDoesNotExist()
    }

    @Test
    fun t2_F2_03_Whitespace_Only_Text() {
        // 包含空格、换行、制表符的纯空白文本，等价于空文本，绝不渲染占位文本
        val entry = assistantEntry(text = "   \n\n\t  \r\n   ")

        compose.setContent {
            MaterialTheme {
                AssistantMessageContent(entry = entry)
            }
        }

        compose.onNodeWithText("此消息没有可显示的文本").assertDoesNotExist()
    }

    @Test
    fun t2_F2_04_Transient_Empty_Stream_Frame() {
        // 流式状态下瞬时正文为空，仅可展示光标动效，绝不出现占位文本
        val entry = assistantEntry(text = "", streaming = true)

        compose.setContent {
            MaterialTheme {
                AssistantMessageContent(entry = entry)
            }
        }

        compose.onNodeWithText("此消息没有可显示的文本").assertDoesNotExist()
    }

    @Test
    fun t2_F2_05_Attachment_Only_Message() {
        // 仅包含附件、正文为空的消息，不渲染“此消息没有可显示的文本”
        val attachment = TimelineAttachment(
            draftId = "att_1",
            filename = "photo.jpg",
            mediaType = "image/jpeg",
        )
        val entry = assistantEntry(text = "", attachments = listOf(attachment))

        compose.setContent {
            MaterialTheme {
                AssistantMessageContent(entry = entry)
            }
        }

        compose.onNodeWithText("此消息没有可显示的文本").assertDoesNotExist()
    }
}
