package com.openandroidintelligence.conversation.workbench

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolCallCardTest {

    @get:Rule
    val compose = createComposeRule()

    // ==========================================
    // 1. MarkdownParser AST 解析测试
    // ==========================================

    @Test
    fun xmlToolCallParsesWithSuccessResult() {
        val markdown = """
            <tool_call name="adb_shell">
              <command>am start -a android.settings.DISPLAY_SETTINGS</command>
              <result status="success">设置应用已成功打开</result>
            </tool_call>
        """.trimIndent()

        val blocks = MarkdownParser.parse(markdown)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is TimelineBlock.ToolCallBlock)

        val toolCall = blocks[0] as TimelineBlock.ToolCallBlock
        assertEquals("adb_shell", toolCall.toolName)
        assertEquals("am start -a android.settings.DISPLAY_SETTINGS", toolCall.command)
        assertTrue(toolCall.isSuccess)
        assertEquals("设置应用已成功打开", toolCall.summary)
        assertNull(toolCall.output)
    }

    @Test
    fun xmlToolCallParsesWithFailedResult() {
        val markdown = """
            <tool_call name="adb_shell">
              <command>am start -a unknown.component</command>
              <result status="failed">ActivityNotFoundException: Unable to find explicit activity class</result>
            </tool_call>
        """.trimIndent()

        val blocks = MarkdownParser.parse(markdown)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is TimelineBlock.ToolCallBlock)

        val toolCall = blocks[0] as TimelineBlock.ToolCallBlock
        assertEquals("adb_shell", toolCall.toolName)
        assertEquals("am start -a unknown.component", toolCall.command)
        assertFalse(toolCall.isSuccess)
        assertEquals("ActivityNotFoundException: Unable to find explicit activity class", toolCall.summary)
    }

    @Test
    fun xmlToolCallParsesWithSummaryAndDetailedOutput() {
        val markdown = """
            <tool_call name="platform_setting">
              <command>get_display_info</command>
              <summary>已成功获取屏幕参数</summary>
              <output>Display 0: 1080x2400 420dpi 120Hz</output>
            </tool_call>
        """.trimIndent()

        val blocks = MarkdownParser.parse(markdown)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is TimelineBlock.ToolCallBlock)

        val toolCall = blocks[0] as TimelineBlock.ToolCallBlock
        assertEquals("platform_setting", toolCall.toolName)
        assertEquals("get_display_info", toolCall.command)
        assertTrue(toolCall.isSuccess)
        assertEquals("已成功获取屏幕参数", toolCall.summary)
        assertEquals("Display 0: 1080x2400 420dpi 120Hz", toolCall.output)
    }

    @Test
    fun fencedToolCallParsesWithLanguageName() {
        val markdown = """
            ```tool_call:adb_shell
            am start -a android.settings.DISPLAY_SETTINGS
            ```
        """.trimIndent()

        val blocks = MarkdownParser.parse(markdown)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is TimelineBlock.ToolCallBlock)

        val toolCall = blocks[0] as TimelineBlock.ToolCallBlock
        assertEquals("adb_shell", toolCall.toolName)
        assertEquals("am start -a android.settings.DISPLAY_SETTINGS", toolCall.command)
        assertTrue(toolCall.isSuccess)
    }

    @Test
    fun fencedToolCallDefaultsToExecutionCommandWhenNoName() {
        val markdown = """
            ```tool_call
            uname -a
            ```
        """.trimIndent()

        val blocks = MarkdownParser.parse(markdown)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is TimelineBlock.ToolCallBlock)

        val toolCall = blocks[0] as TimelineBlock.ToolCallBlock
        assertEquals("执行命令", toolCall.toolName)
        assertEquals("uname -a", toolCall.command)
    }

    @Test
    fun codeBlockStartingWithAdbShellConvertsToToolCallBlock() {
        val markdown = """
            ```bash
            $ adb shell input tap 500 800
            执行成功
            ```
        """.trimIndent()

        val blocks = MarkdownParser.parse(markdown)
        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is TimelineBlock.ToolCallBlock)

        val toolCall = blocks[0] as TimelineBlock.ToolCallBlock
        assertEquals("adb_shell", toolCall.toolName)
        assertEquals("adb shell input tap 500 800", toolCall.command)
        assertTrue(toolCall.isSuccess)
        assertEquals("执行成功", toolCall.summary)
    }

    // ==========================================
    // 2. Compose UI 呈现与交互测试
    // ==========================================

    @Test
    fun toolCallRendersHeaderBadgeAndCommand() {
        val block = TimelineBlock.ToolCallBlock(
            toolName = "adb_shell",
            command = "input keyevent KEYCODE_HOME",
            output = null,
            isSuccess = true,
            summary = null,
        )

        compose.setContent {
            MaterialTheme {
                MarkdownToolCallView(block = block)
            }
        }

        // 验证 Badge 等宽工具名称
        compose.onNodeWithText("[adb_shell]").assertIsDisplayed()
        // 验证命令正文
        compose.onNodeWithText("input keyevent KEYCODE_HOME").assertIsDisplayed()
        // 验证复制按钮
        compose.onNodeWithText("复制").assertIsDisplayed()
    }

    @Test
    fun copyButtonCopiesCommandAndShowsFeedback() {
        val block = TimelineBlock.ToolCallBlock(
            toolName = "adb_shell",
            command = "pm list packages -3",
            output = null,
            isSuccess = true,
            summary = null,
        )

        var clipboardText: String? = null

        compose.setContent {
            val clipboard = LocalClipboardManager.current
            MaterialTheme {
                MarkdownToolCallView(block = block)
            }
            clipboardText = clipboard.getText()?.text
        }

        // 点击复制
        compose.onNodeWithText("复制").performClick()

        // 验证提示切换为“已复制”
        compose.onNodeWithText("已复制").assertIsDisplayed()
    }

    @Test
    fun toolCallRendersSuccessResultStrip() {
        val block = TimelineBlock.ToolCallBlock(
            toolName = "adb_shell",
            command = "am start -a android.settings.SETTINGS",
            output = null,
            isSuccess = true,
            summary = "设置应用已成功打开",
        )

        compose.setContent {
            MaterialTheme {
                MarkdownToolCallView(block = block)
            }
        }

        // 验证成功摘要
        compose.onNodeWithText("设置应用已成功打开").assertIsDisplayed()
        compose.onNodeWithContentDescription("执行成功").assertIsDisplayed()
    }

    @Test
    fun toolCallRendersFailureResultStrip() {
        val block = TimelineBlock.ToolCallBlock(
            toolName = "adb_shell",
            command = "am start -a non.existent",
            output = null,
            isSuccess = false,
            summary = "Activity not found",
        )

        compose.setContent {
            MaterialTheme {
                MarkdownToolCallView(block = block)
            }
        }

        // 验证失败提示
        compose.onNodeWithText("Activity not found").assertIsDisplayed()
        compose.onNodeWithContentDescription("执行失败").assertIsDisplayed()
    }

    @Test
    fun toolCallWithDetailedOutputExpandsAndCollapses() {
        val detailedLog = "Starting: Intent { act=android.settings.SETTINGS }\nStatus: complete"
        val block = TimelineBlock.ToolCallBlock(
            toolName = "adb_shell",
            command = "am start -a android.settings.SETTINGS",
            output = detailedLog,
            isSuccess = true,
            summary = "执行成功",
        )

        compose.setContent {
            MaterialTheme {
                MarkdownToolCallView(block = block)
            }
        }

        // 初始状态：显示“查看输出”，未展示详细日志
        compose.onNodeWithText("查看输出").assertIsDisplayed()
        compose.onNodeWithText(detailedLog).assertDoesNotExist()

        // 点击“查看输出”展开
        compose.onNodeWithText("查看输出").performClick()

        // 展开后状态：详细日志可见，按钮变为“收起输出”
        compose.onNodeWithText("收起输出").assertIsDisplayed()
        compose.onNodeWithText(detailedLog, substring = true).assertIsDisplayed()

        // 再次点击“收起输出”
        compose.onNodeWithText("收起输出").performClick()

        // 收起后状态
        compose.onNodeWithText("查看输出").assertIsDisplayed()
        compose.onNodeWithText(detailedLog).assertDoesNotExist()
    }
}

