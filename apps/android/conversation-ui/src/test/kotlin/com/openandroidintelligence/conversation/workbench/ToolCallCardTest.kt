package com.openandroidintelligence.conversation.workbench

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.model.NativeExecutionStatus
import com.openandroidintelligence.conversation.model.NativeExecutionStep
import com.openandroidintelligence.conversation.model.StreamPresentationOrigin
import com.openandroidintelligence.conversation.state.StreamPresentationMetadata
import com.openandroidintelligence.conversation.state.TimelineEntry
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.MotionPolicy
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
    fun aClosedFenceFollowedByAnUnclosedFenceKeepsTheSecondRunning() {
        val markdown = """
            ```tool_call:adb_shell
            pm list packages
            ```
            我先确认一下。
            ```tool_call:adb_shell
            dumpsys battery
        """.trimIndent()

        val blocks = MarkdownParser.parse(markdown)
        assertEquals(3, blocks.size)
        assertFalse((blocks[0] as TimelineBlock.ToolCallBlock).isRunning)
        assertEquals("我先确认一下。", (blocks[1] as TimelineBlock.Paragraph).text)
        val second = blocks[2] as TimelineBlock.ToolCallBlock
        assertEquals("dumpsys battery", second.command)
        assertTrue(second.isRunning, "闭合围栏之后的未闭合围栏也必须标记为执行中")
    }

    @Test
    fun ordinaryProseStartingWithReadingIsNotAToolCard() {
        val blocks = MarkdownParser.parse("Reading your question, I found the answer.\nRunning the check next.")

        assertEquals(1, blocks.size)
        val paragraph = blocks.single() as TimelineBlock.Paragraph
        assertTrue(paragraph.text.contains("Reading your question"))
        assertTrue(paragraph.text.contains("Running the check next"))
    }

    @Test
    fun singleActivityLineIsNotEnoughToBeMistakenForAToolProgressMessage() {
        val blocks = MarkdownParser.parse("Reading your question, I found the answer.")

        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is TimelineBlock.Paragraph)
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
    fun hermesInvocationCardShowsCalledStatusWithoutFakingSuccess() {
        val block = toolCall(toolName = "execute_code", command = "").copy(invocationOnly = true)

        compose.setContent {
            MaterialTheme {
                MarkdownToolCallView(block = block)
            }
        }

        compose.onNodeWithText("已调用").assertIsDisplayed()
        compose.onNodeWithText("执行成功").assertDoesNotExist()

        compose.onNodeWithText("已调用").performClick()
        // 规格与需求 R1: 彻底移除硬编码文案 "Hermes 在消息中未提供命令参数"
        compose.onNodeWithText("Hermes 在消息中未提供命令参数").assertDoesNotExist()
        // 无参时自动隐藏参数行
        compose.onNodeWithText("命令").assertDoesNotExist()
        compose.onNodeWithText("此调用未附带返回值").assertIsDisplayed()
    }

    // ==========================================
    // 3. Tier 1 功能覆盖测试 (F1 通用工具解析与适配)
    // ==========================================

    @Test
    fun t1_F1_01_ToolName_Extraction_Various_Agents() {
        // 验证来自不同 Agent 的工具名称正确显示在卡片徽标中
        val agentsTools = listOf("read_file", "web_search", "bash", "execute_code", "browser_action")
        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    agentsTools.forEach { name ->
                        MarkdownToolCallView(block = toolCall(toolName = name, command = "test_arg"))
                    }
                }
            }
        }
        agentsTools.forEach { name ->
            compose.onNodeWithText("[$name]").assertIsDisplayed()
        }
    }

    @Test
    fun t1_F1_02_Input_Args_Object_Formatted_String() {
        // 验证格式化 JSON 对象入参在展开后正确呈现
        val jsonArgs = """{"query": "open android intelligence", "limit": 10}"""
        val block = toolCall(toolName = "web_search", command = jsonArgs)

        compose.setContent {
            MaterialTheme {
                MarkdownToolCallView(block = block)
            }
        }

        compose.onNodeWithText("执行成功").performClick()
        compose.onNodeWithText("open android intelligence", substring = true).assertIsDisplayed()
        compose.onNodeWithText("limit", substring = true).assertIsDisplayed()
    }

    @Test
    fun t1_F1_03_Output_Result_Extraction() {
        // 验证输出结果在展开后正确呈现为输出内容
        val block = toolCall(output = "Device connected: Pixel 9 Pro", summary = "查询成功")

        compose.setContent {
            MaterialTheme {
                MarkdownToolCallView(block = block)
            }
        }

        compose.onNodeWithText("查询成功").performClick()
        compose.onNodeWithText("输出").assertIsDisplayed()
        compose.onNodeWithText("Device connected: Pixel 9 Pro").assertIsDisplayed()
    }

    @Test
    fun t1_F1_04_No_Params_Hide_Label() {
        // 验证无参数时自动隐藏参数/命令行，不报错
        val block = toolCall(toolName = "get_network_info", command = "", output = "WiFi Connected")

        compose.setContent {
            MaterialTheme {
                MarkdownToolCallView(block = block)
            }
        }

        compose.onNodeWithText("执行成功").performClick()
        // 无参时命令/参数标签与代码块都不存在
        compose.onNodeWithText("命令").assertDoesNotExist()
        compose.onNodeWithText("Hermes 在消息中未提供命令参数").assertDoesNotExist()
        compose.onNodeWithText("输出").assertIsDisplayed()
        compose.onNodeWithText("WiFi Connected").assertIsDisplayed()
    }

    @Test
    fun t1_F1_05_Non_Shell_Tool_Label() {
        // 验证非 shell 工具（如 web_search）在有参数时标签或内容正常展示，且不显示 shell 专属硬编码
        val block = toolCall(toolName = "web_search", command = "https://android.google.com")

        compose.setContent {
            MaterialTheme {
                MarkdownToolCallView(block = block)
            }
        }

        compose.onNodeWithText("执行成功").performClick()
        compose.onNodeWithText("https://android.google.com").assertIsDisplayed()
        compose.onNodeWithText("Hermes 在消息中未提供命令参数").assertDoesNotExist()
    }

    // ==========================================
    // 4. Tier 2 边界与极端条件测试 (F1 Boundary & Corner Cases)
    // ==========================================

    @Test
    fun t2_F1_01_Empty_Command_Never_Shows_Hermes_Copy() {
        // 边界测试：即使 command 为纯空白字符，也绝不漏出 Hermes 提示词
        val block = toolCall(toolName = "unknown_agent_tool", command = "   \n\t  ")

        compose.setContent {
            MaterialTheme {
                MarkdownToolCallView(block = block)
            }
        }

        compose.onNodeWithText("执行成功").performClick()
        compose.onNodeWithText("Hermes 在消息中未提供命令参数").assertDoesNotExist()
        compose.onNodeWithText("参数来自 Hermes 预览，可能已被截断").assertDoesNotExist()
    }

    @Test
    fun t2_F1_02_Malformed_Json_Arguments() {
        // 边界测试：非合法 JSON 的原始字符串入参能够安全展示
        val malformedJson = "{ unclosed_key: 'value' "
        val block = toolCall(toolName = "legacy_tool", command = malformedJson)

        compose.setContent {
            MaterialTheme {
                MarkdownToolCallView(block = block)
            }
        }

        compose.onNodeWithText("执行成功").performClick()
        compose.onNodeWithText(malformedJson, substring = true).assertIsDisplayed()
    }

    @Test
    fun t2_F1_03_Deeply_Nested_Arguments() {
        // 边界测试：多层极深嵌套 JSON 入参安全展示
        val deeplyNested = """{"a":{"b":{"c":{"d":{"e":"deep_value"}}}}}"""
        val block = toolCall(toolName = "config_tool", command = deeplyNested)

        compose.setContent {
            MaterialTheme {
                MarkdownToolCallView(block = block)
            }
        }

        compose.onNodeWithText("执行成功").performClick()
        compose.onNodeWithText("deep_value", substring = true).assertIsDisplayed()
    }

    @Test
    fun t2_F1_04_Huge_Output_Text() {
        // 边界测试：巨量文本输出（上万字）正常展开不崩溃
        val hugeOutput = "LOG_LINE_OUTPUT: " + "A".repeat(10_000)
        val block = toolCall(toolName = "logcat", output = hugeOutput)

        compose.setContent {
            MaterialTheme {
                MarkdownToolCallView(block = block)
            }
        }

        compose.onNodeWithText("执行成功").performClick()
        compose.onNodeWithText("输出").assertIsDisplayed()
        compose.onNodeWithText("LOG_LINE_OUTPUT", substring = true).assertIsDisplayed()
    }

    @Test
    fun t2_F1_05_Escaped_Special_Chars() {
        // 边界测试：入参及输出包含特殊转义字符、换行符及 Unicode Emoji
        val specialCommand = "echo \"Hello\tWorld\nSpecial: \uD83D\uDE80 & < > ' \""
        val specialOutput = "Output: \uD83D\uDE80 Line1\nLine2\r\nLine3"
        val block = toolCall(toolName = "echo_tool", command = specialCommand, output = specialOutput)

        compose.setContent {
            MaterialTheme {
                MarkdownToolCallView(block = block)
            }
        }

        compose.onNodeWithText("执行成功").performClick()
        compose.onNodeWithText(specialCommand, substring = true).assertIsDisplayed()
        compose.onNodeWithText(specialOutput, substring = true).assertIsDisplayed()
    }

    @Test
    fun collapsedSummaryBarShowsTheRealResultBeforeExpanding() {
        val block = toolCall(summary = "设置应用已成功打开")

        compose.setContent {
            MaterialTheme {
                MarkdownToolCallView(block = block)
            }
        }

        // 规格 §4.2：状态徽标栏显示「执行成功（或 summary）」；summary 不再被藏到展开态。
        compose.onNodeWithText("设置应用已成功打开").assertIsDisplayed()
        compose.onNodeWithText("pm list packages -3").assertDoesNotExist()

        compose.onNodeWithText("设置应用已成功打开").performClick()
        compose.onNodeWithText("命令").assertIsDisplayed()
        compose.onNodeWithText("pm list packages -3").assertIsDisplayed()
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
    fun runningToolCallDefaultsToCollapsedWithLiveStatusAndExpandsOnClick() {
        val block = toolCall(command = "dumpsys battery", isRunning = true)

        compose.setContent {
            MaterialTheme {
                MarkdownToolCallView(block = block, isStreaming = true)
            }
        }

        compose.onNodeWithText("执行中").assertIsDisplayed()
        compose.onNodeWithContentDescription("执行中").assertIsDisplayed()
        // 需求 3：工具卡片即使在运行中也默认折叠，不自动展开命令详情。
        compose.onNodeWithText("命令").assertDoesNotExist()
        compose.onNodeWithText("dumpsys battery").assertDoesNotExist()

        compose.onNodeWithText("执行中").performClick()
        compose.onNodeWithText("命令").assertIsDisplayed()
        compose.onNodeWithText("dumpsys battery").assertIsDisplayed()
        // 运行中还没有输出，不显示「无输出」误导用户。
        compose.onNodeWithText("无输出").assertDoesNotExist()
    }

    @Test
    fun unknownNativeStatusExplicitlyShowsNoResultLabelInSummaryAndDetails() {
        val block = TimelineBlock.ToolCallBlock(
            toolName = "bash",
            command = "echo hello",
            output = null,
            isSuccess = true,
            isRunning = false,
            executionStatus = NativeExecutionStatus.UNKNOWN,
        )

        compose.setContent {
            MaterialTheme {
                MarkdownToolCallView(block = block)
            }
        }

        compose.onNodeWithText("未返回结果").assertIsDisplayed()
        compose.onNodeWithContentDescription("未返回结果").assertIsDisplayed()
        compose.onNodeWithText("结果未知").assertDoesNotExist()

        compose.onNodeWithText("未返回结果").performClick()
        compose.onAllNodesWithText("未返回结果").assertCountEquals(2)
        compose.onNodeWithText("未收到工具结果").assertDoesNotExist()
    }

    @Test
    fun toolCardRevealTrackerAnimatesFreshCardOnceAndSkipsHistoryRecycleAndUpdates() {
        val step1 = NativeExecutionStep("s-hist", "ev-1", "read_file", "{}", "ok1", NativeExecutionStatus.COMPLETED, 1L)
        val step2Running = NativeExecutionStep("s-live", "ev-2", "bash", "ls", null, NativeExecutionStatus.RUNNING, 1L)
        val step2Done = step2Running.copy(output = "ok2", status = NativeExecutionStatus.COMPLETED, revision = 2L)

        val historyEntries = listOf(
            TimelineEntry(
                key = "e-hist",
                sender = "assistant",
                text = "",
                isUser = false,
                timestamp = 1L,
                pendingAcceptance = false,
                batchGroupId = null,
                generationId = "g-hist",
                executionSteps = listOf(step1),
            ),
        )
        val liveEntries = historyEntries + TimelineEntry(
            key = "e-live",
            sender = "assistant",
            text = "",
            isUser = false,
            timestamp = 2L,
            pendingAcceptance = false,
            batchGroupId = null,
            generationId = "g-live",
            executionGenerationState = GenerationState.RUNNING,
            executionSteps = listOf(step2Running),
        )
        val settledLiveEntries = historyEntries + TimelineEntry(
            key = "e-live",
            sender = "assistant",
            text = "",
            isUser = false,
            timestamp = 2L,
            pendingAcceptance = false,
            batchGroupId = null,
            generationId = "g-live",
            executionGenerationState = GenerationState.COMPLETED,
            executionSteps = listOf(step2Done),
        )

        val model = TimelineDisplayModel()
        val tracker = ToolCardRevealTracker()

        // 未就绪时不建立基线
        tracker.sync(emptyList(), ready = false)
        // 首次 Ready 建立历史基线：已有工具卡不播入场显现
        tracker.sync(model.build(historyEntries, GenerationState.COMPLETED), ready = true)
        assertFalse(tracker.consumeEntranceAnimation("execution:g-hist:s-hist"))

        // 实时流新增 s-live：首次消费返回 true，随后刷新或滚动回收均返回 false
        tracker.sync(model.build(liveEntries, GenerationState.RUNNING), ready = true)
        assertTrue(tracker.consumeEntranceAnimation("execution:g-live:s-live"))
        assertFalse(tracker.consumeEntranceAnimation("execution:g-live:s-live"))

        tracker.sync(model.build(settledLiveEntries, GenerationState.COMPLETED), ready = true)
        assertFalse(tracker.consumeEntranceAnimation("execution:g-live:s-live"))
    }

    @Test
    fun toolCardRevealTrackerSkipsLateSnapshotEntriesAndProvisionalGenerationUpgrade() {
        val snapshotStep = NativeExecutionStep("s-snap", "ev-snap", "read_file", "{}", "snap-out", NativeExecutionStatus.COMPLETED, 1L)
        val provisionalStep = NativeExecutionStep("s-upgrade", "ev-up-1", "bash", "pwd", null, NativeExecutionStatus.RUNNING, 1L)
        val upgradedStep = provisionalStep.copy(output = "/tmp", status = NativeExecutionStatus.COMPLETED, revision = 2L)

        val model = TimelineDisplayModel()
        val tracker = ToolCardRevealTracker()

        // 即使会话首次 Ready 时为空，后续补拉的 SNAPSHOT 历史条目也不得触发入场动画
        tracker.sync(emptyList(), ready = true)
        val snapshotEntries = listOf(
            TimelineEntry(
                key = "e-snap",
                sender = "assistant",
                text = "",
                isUser = false,
                timestamp = 1L,
                pendingAcceptance = false,
                batchGroupId = null,
                generationId = "g-snap",
                executionSteps = listOf(snapshotStep),
                presentation = StreamPresentationMetadata(StreamPresentationOrigin.SNAPSHOT, 1L),
            ),
        )
        tracker.sync(model.build(snapshotEntries, GenerationState.COMPLETED), ready = true)
        assertFalse(tracker.consumeEntranceAnimation("execution:g-snap:s-snap"))

        // 临时 generationId（request:*）下首次出现的实时工具卡播一次入场动画
        val provisionalEntries = snapshotEntries + TimelineEntry(
            key = "e-prov",
            sender = "assistant",
            text = "",
            isUser = false,
            timestamp = 2L,
            pendingAcceptance = false,
            batchGroupId = null,
            generationId = "request:c-1",
            executionGenerationState = GenerationState.RUNNING,
            executionSteps = listOf(provisionalStep),
        )
        tracker.sync(model.build(provisionalEntries, GenerationState.RUNNING), ready = true)
        assertTrue(tracker.consumeEntranceAnimation("execution:request:c-1:s-upgrade"))

        // 同一工具调用随后升级为服务端权威 generationId 时，不得重播入场动画
        val upgradedEntries = snapshotEntries + TimelineEntry(
            key = "e-auth",
            sender = "assistant",
            text = "",
            isUser = false,
            timestamp = 2L,
            pendingAcceptance = false,
            batchGroupId = null,
            generationId = "g-auth-1",
            executionGenerationState = GenerationState.COMPLETED,
            executionSteps = listOf(upgradedStep),
        )
        tracker.sync(model.build(upgradedEntries, GenerationState.COMPLETED), ready = true)
        assertFalse(tracker.consumeEntranceAnimation("execution:g-auth-1:s-upgrade"))
    }

    @Test
    fun freshToolCardRevealsLeftToRightAndDegradesToNoDisplacementFadeInReduceMotion() {
        val backdrop = Color(0xFFFF0000)
        lateinit var rootView: View
        val block = toolCall(toolName = "read_file", command = "a.kt", output = "ok")

        // 1. 标准模式：animateEntrance=true 早段左露右裁
        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = false)) {
                MaterialTheme {
                    rootView = LocalView.current
                    Box(Modifier.width(240.dp).heightIn(min = 56.dp).background(backdrop).testTag("tool-reveal-host")) {
                        MarkdownToolCallView(
                            block = block,
                            revealKey = "fresh-tool-1",
                            animateEntrance = true,
                        )
                    }
                }
            }
        }
        repeat(4) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        val bounds = compose.onNodeWithTag("tool-reveal-host").fetchSemanticsNode().boundsInRoot
        val earlyMap = compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(bounds.width.toInt().coerceAtLeast(1), bounds.height.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.translate(-bounds.left, -bounds.top)
            rootView.draw(canvas)
            bitmap.asImageBitmap()
        }.toPixelMap()
        val leftPixel = earlyMap[(earlyMap.width * 0.1f).toInt(), earlyMap.height / 2]
        val rightPixel = earlyMap[(earlyMap.width * 0.9f).toInt(), earlyMap.height / 2]
        assertTrue(leftPixel.red < 0.95f || leftPixel.green > 0.05f || leftPixel.blue > 0.05f, "标准模式早段左侧应已显露出卡片而非纯红背景: left=$leftPixel")
        assertTrue(rightPixel.red > 0.95f && rightPixel.blue < 0.05f, "标准模式早段右侧应仍被水平裁切保留红色底板: right=$rightPixel")

        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        // 卡片默认折叠，点击后仍可正常展开
        compose.onNodeWithText("a.kt").assertDoesNotExist()
        compose.onNodeWithText("[read_file]").performClick()
        compose.onNodeWithText("a.kt").assertIsDisplayed()
    }

    @Test
    fun freshToolCardDegradesToNoDisplacementLinearFadeInReduceMotion() {
        val backdrop = Color(0xFFFF0000)
        lateinit var rootView: View
        val block = toolCall(toolName = "read_file", command = "a.kt", output = "ok")

        compose.mainClock.autoAdvance = false
        compose.setContent {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = true)) {
                MaterialTheme {
                    rootView = LocalView.current
                    Box(Modifier.size(width = 240.dp, height = 56.dp).background(backdrop).testTag("tool-reveal-reduced-host")) {
                        MarkdownToolCallView(
                            block = block,
                            revealKey = "fresh-tool-reduced",
                            animateEntrance = true,
                        )
                    }
                }
            }
        }
        repeat(5) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
        }
        val bounds = compose.onNodeWithTag("tool-reveal-reduced-host").fetchSemanticsNode().boundsInRoot
        val midMap = compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(bounds.width.toInt().coerceAtLeast(1), bounds.height.toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.translate(-bounds.left, -bounds.top)
            rootView.draw(canvas)
            bitmap.asImageBitmap()
        }.toPixelMap()
        // 采样上下卡片底色区（避开文字区域）验证左右两端同步半透明淡入、无水平裁切差值
        val leftBg = midMap[(midMap.width * 0.08f).toInt(), (midMap.height * 0.15f).toInt()]
        val rightBg = midMap[(midMap.width * 0.92f).toInt(), (midMap.height * 0.15f).toInt()]
        assertTrue(leftBg.red < 0.98f || leftBg.green > 0.05f || leftBg.blue > 0.05f, "减少动态中段左侧应处于半透明淡入而非纯红: left=$leftBg")
        assertTrue(rightBg.red < 0.98f || rightBg.green > 0.05f || rightBg.blue > 0.05f, "减少动态中段右侧应同样处于半透明淡入而非被水平裁切: right=$rightBg")
        assertEquals(leftBg.red, rightBg.red, 0.05f)
        assertEquals(leftBg.blue, rightBg.blue, 0.05f)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
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

        // 失败摘要优先出现在徽标栏（规格 §4.2：状态或 summary）。
        compose.onNodeWithText("Activity not found").assertIsDisplayed()
        compose.onNodeWithContentDescription("执行失败").assertIsDisplayed()
        compose.onNodeWithText("ActivityNotFoundException", substring = true).assertDoesNotExist()

        compose.onNodeWithText("Activity not found").performClick()

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
