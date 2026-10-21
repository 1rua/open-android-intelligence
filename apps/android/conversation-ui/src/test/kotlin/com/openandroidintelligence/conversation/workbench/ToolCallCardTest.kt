package com.openandroidintelligence.conversation.workbench

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.runtime.CompositionLocalProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolCallCardTest {

    @get:Rule
    val compose = createComposeRule()

    private class FakeClock(var now: Long = 0L)

    private fun toolCall(
        toolName: String = "adb_shell",
        command: String = "pm list packages -3",
        output: String? = null,
        isSuccess: Boolean = true,
        summary: String? = null,
        isRunning: Boolean = false,
    ) = TimelineBlock.ToolCallBlock(
        toolName = toolName,
        command = command,
        output = output,
        isSuccess = isSuccess,
        summary = summary,
        isRunning = isRunning,
    )

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
        assertEquals(null, toolCall.output)
        // 闭合标签是已执行完成的调用，不再是运行中。
        assertFalse(toolCall.isRunning)
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
        assertFalse(toolCall.isRunning)
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
    fun unclosedXmlToolCallParsesAsRunning() {
        val markdown = """
            <tool_call name="adb_shell">
              <command>pm list packages -3</command>
        """.trimIndent()

        val blocks = MarkdownParser.parse(markdown)
        assertEquals(1, blocks.size)
        val toolCall = blocks[0] as TimelineBlock.ToolCallBlock
        assertEquals("adb_shell", toolCall.toolName)
        assertEquals("pm list packages -3", toolCall.command)
        assertTrue(toolCall.isRunning, "未闭合的 XML 工具调用应标记为执行中")
    }

    @Test
    fun aClosedToolCallFollowedByAnUnclosedOneKeepsTheSecondRunning() {
        val markdown = """
            <tool_call name="adb_shell">
              <command>pm list packages</command>
              <result status="success">ok</result>
            </tool_call>
            我先确认一下。
            <tool_call name="adb_shell">
              <command>dumpsys battery</command>
        """.trimIndent()

        val blocks = MarkdownParser.parse(markdown)
        assertEquals(3, blocks.size)
        assertFalse((blocks[0] as TimelineBlock.ToolCallBlock).isRunning)
        assertEquals("我先确认一下。", (blocks[1] as TimelineBlock.Paragraph).text)
        val second = blocks[2] as TimelineBlock.ToolCallBlock
        assertEquals("dumpsys battery", second.command)
        assertTrue(second.isRunning, "闭合工具调用之后的未闭合工具调用也必须标记为执行中")
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
        assertFalse(toolCall.isRunning)
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
    fun unclosedFencedToolCallParsesAsRunning() {
        val markdown = """
            ```tool_call:adb_shell
            ${'$'} pm list packages
        """.trimIndent()

        val blocks = MarkdownParser.parse(markdown)
        assertEquals(1, blocks.size)
        val toolCall = blocks[0] as TimelineBlock.ToolCallBlock
        assertEquals("adb_shell", toolCall.toolName)
        assertEquals("pm list packages", toolCall.command)
        assertTrue(toolCall.isRunning, "未闭合的 fenced 工具调用应标记为执行中")
    }

    @Test
    fun codeBlockStartingWithAdbShellConvertsToToolCallBlock() {
        val markdown = """
            ```bash
            ${'$'} adb shell input tap 500 800
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
        assertFalse(toolCall.isRunning)
    }

    // ==========================================
    // 2. Compose UI 呈现与交互测试
    // ==========================================

    @Test
    fun collapsedSuccessCardShowsBadgeAndStatusWithoutCommandOrOutput() {
        val block = toolCall(output = "package:com.demo", summary = "执行成功")

        compose.setContent {
            MaterialTheme {
                MarkdownToolCallView(block = block)
            }
        }

        compose.onNodeWithText("[adb_shell]").assertIsDisplayed()
        compose.onNodeWithText("执行成功").assertIsDisplayed()
        compose.onNodeWithContentDescription("执行成功").assertIsDisplayed()
        // 完成态默认收起：命令与输出都不参与组合，也没有第二层「查看输出」开关。
        compose.onNodeWithText("pm list packages -3").assertDoesNotExist()
        compose.onNodeWithText("package:com.demo").assertDoesNotExist()
        compose.onNodeWithText("查看输出").assertDoesNotExist()
    }

    @Test
    fun expandingSuccessCardShowsCommandOutputAndCopyAction() {
        val detailedOutput = "package:com.demo.alpha\npackage:com.demo.beta"
        val block = toolCall(output = detailedOutput)

        compose.setContent {
            MaterialTheme {
                MarkdownToolCallView(block = block)
            }
        }

        compose.onNodeWithText("执行成功").performClick()

        compose.onNodeWithText("命令").assertIsDisplayed()
        compose.onNodeWithText("pm list packages -3").assertIsDisplayed()
        compose.onNodeWithText("输出").assertIsDisplayed()
        compose.onNodeWithText("package:com.demo.alpha", substring = true).assertIsDisplayed()
        compose.onNodeWithText("复制").assertIsDisplayed()
        // 只有一层展开：旧版「查看输出/收起输出」开关已移除。
        compose.onNodeWithText("查看输出").assertDoesNotExist()
        compose.onNodeWithText("收起输出").assertDoesNotExist()

        // 再次点击摘要栏收起详情。
        compose.onNodeWithText("执行成功").performClick()
        compose.onNodeWithText("命令").assertDoesNotExist()
        compose.onNodeWithText("pm list packages -3").assertDoesNotExist()
    }

    @Test
    fun copyCommandShowsCopiedFeedback() {
        val block = toolCall(command = "pm list packages -3")

        compose.setContent {
            MaterialTheme {
                MarkdownToolCallView(block = block)
            }
        }

        compose.onNodeWithText("执行成功").performClick()
        compose.onNodeWithText("复制").performClick()

        compose.onNodeWithText("已复制").assertIsDisplayed()
    }

    @Test
    fun runningToolCallDefaultsToExpandedWithLiveStatus() {
        val block = toolCall(command = "dumpsys battery", isRunning = true)

        compose.setContent {
            MaterialTheme {
                MarkdownToolCallView(block = block, isStreaming = true)
            }
        }

        compose.onNodeWithText("执行中").assertIsDisplayed()
        compose.onNodeWithContentDescription("执行中").assertIsDisplayed()
        compose.onNodeWithText("命令").assertIsDisplayed()
        compose.onNodeWithText("dumpsys battery").assertIsDisplayed()
        // 运行中还没有输出，不显示「无输出」误导用户。
        compose.onNodeWithText("无输出").assertDoesNotExist()
    }

    @Test
    fun interruptedToolCallCollapsesWithWarningAndRevealsCommandOnExpand() {
        val block = toolCall(command = "sleep 999", isRunning = true)

        compose.setContent {
            MaterialTheme {
                MarkdownToolCallView(block = block, isStreaming = false)
            }
        }

        compose.onNodeWithText("执行已中断 · 结果未知", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("执行已中断").assertIsDisplayed()
        compose.onNodeWithText("sleep 999").assertDoesNotExist()

        compose.onNodeWithText("执行已中断", substring = true).performClick()

        compose.onNodeWithText("命令").assertIsDisplayed()
        compose.onNodeWithText("sleep 999").assertIsDisplayed()
    }

    @Test
    fun failedToolCallCollapsedShowsErrorAndExpandsToErrorOutput() {
        val block = toolCall(
            command = "am start -a non.existent",
            output = "ActivityNotFoundException: 无法找到 Activity",
            isSuccess = false,
            summary = "Activity not found",
        )

        compose.setContent {
            MaterialTheme {
                MarkdownToolCallView(block = block)
            }
        }

        compose.onNodeWithText("执行失败").assertIsDisplayed()
        compose.onNodeWithContentDescription("执行失败").assertIsDisplayed()
        compose.onNodeWithText("Activity not found").assertDoesNotExist()

        compose.onNodeWithText("执行失败").performClick()

        compose.onNodeWithText("Activity not found").assertIsDisplayed()
        compose.onNodeWithText("错误输出").assertIsDisplayed()
        compose.onNodeWithText("ActivityNotFoundException", substring = true).assertIsDisplayed()
    }

    @Test
    fun successWithoutOutputShowsNoOutputAfterExpand() {
        val block = toolCall(output = null, summary = null)

        compose.setContent {
            MaterialTheme {
                MarkdownToolCallView(block = block)
            }
        }

        compose.onNodeWithText("执行成功").performClick()

        compose.onNodeWithText("无输出").assertIsDisplayed()
        compose.onNodeWithText("命令").assertIsDisplayed()
    }

    @Test
    fun toolCallShowsObservedDurationInSummaryBar() {
        val clock = FakeClock()
        val store = ExecutionTimingStore { clock.now }
        store.observe("a1#0", running = true)
        clock.now = 2_400L
        store.observe("a1#0", running = false)

        val block = toolCall()

        compose.setContent {
            CompositionLocalProvider(LocalExecutionTiming provides store) {
                MaterialTheme {
                    MarkdownToolCallView(block = block, timingKey = "a1#0")
                }
            }
        }

        compose.onNodeWithText("执行成功", substring = true).assertIsDisplayed()
        compose.onNodeWithText("2.4 秒", substring = true).assertIsDisplayed()
    }
}
