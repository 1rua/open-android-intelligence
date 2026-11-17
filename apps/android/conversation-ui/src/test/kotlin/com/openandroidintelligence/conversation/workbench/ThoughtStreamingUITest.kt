package com.openandroidintelligence.conversation.workbench

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.state.StreamPresentationMetadata
import com.openandroidintelligence.conversation.state.TimelineEntry
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.MotionPolicy
import kotlin.math.ceil
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 验证 R5 / F5：思维链流式刷字接入与真实渐显/光标动效（含手动时钟 `autoAdvance = false` 与像素验证）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThoughtStreamingUITest {

    @get:Rule
    val compose = createComposeRule()

    private fun thoughtBlock(
        thought: String = "分析用户输入",
        isComplete: Boolean = true,
    ) = TimelineBlock.ThoughtBlock(
        thought = thought,
        isComplete = isComplete,
    )

    // ==========================================
    // Tier 1: 功能覆盖测试 (F5 思维链流式输出接入)
    // ==========================================

    @Test
    fun t1_F5_01_Reasoning_Delta_Appended() {
        val thoughtState = mutableStateOf("首先")

        compose.setContent {
            MaterialTheme {
                MarkdownThoughtBlockView(
                    block = TimelineBlock.ThoughtBlock(thought = thoughtState.value, isComplete = false),
                    isStreaming = true,
                )
            }
        }

        compose.onNodeWithText("正在思考", substring = true).assertIsDisplayed()
        compose.onNodeWithText("首先").assertIsDisplayed()

        thoughtState.value = "首先分析设备状态，接下来检查网络"
        compose.waitForIdle()

        compose.onNodeWithText("首先分析设备状态，接下来检查网络").assertIsDisplayed()
    }

    @Test
    fun t1_F5_02_Streaming_Container_Active() {
        val block = thoughtBlock(thought = "正在推导解答步骤...", isComplete = false)

        compose.setContent {
            MaterialTheme {
                MarkdownThoughtBlockView(block = block, isStreaming = true)
            }
        }

        compose.onNodeWithText("正在思考", substring = true).assertIsDisplayed()
        compose.onNodeWithText("正在推导解答步骤...", substring = true).assertIsDisplayed()
    }

    @Test
    fun t1_F5_03_Thought_Streaming_Cursor() {
        val block = thoughtBlock(thought = "规划执行策略：步骤1、步骤2", isComplete = false)

        compose.setContent {
            MaterialTheme {
                MarkdownThoughtBlockView(block = block, isStreaming = true)
            }
        }

        compose.onNodeWithText("正在思考").assertIsDisplayed()
    }

    @Test
    fun t1_F5_04_Thought_Header_Live_Status() {
        val isCompleteState = mutableStateOf(false)
        val thoughtText = "已完成所有参数检验"

        compose.setContent {
            MaterialTheme {
                MarkdownThoughtBlockView(
                    block = TimelineBlock.ThoughtBlock(thought = thoughtText, isComplete = isCompleteState.value),
                    isStreaming = !isCompleteState.value,
                )
            }
        }

        compose.onNodeWithText("正在思考").assertIsDisplayed()
        compose.onNodeWithText("已思考", substring = true).assertDoesNotExist()

        isCompleteState.value = true
        compose.waitForIdle()

        compose.onNodeWithText("已思考", substring = true).assertIsDisplayed()
        compose.onNodeWithText("${thoughtText.length} 字", substring = true).assertIsDisplayed()
        compose.onNodeWithText("正在思考").assertDoesNotExist()
    }

    @Test
    fun t1_F5_05_Streaming_Frame_Not_Blocked() {
        val entry = TimelineEntry(
            key = "stream_entry",
            sender = "assistant",
            text = "正在思考后续动作...",
            isUser = false,
            timestamp = 1000L,
            pendingAcceptance = false,
            batchGroupId = null,
            isStreaming = true,
            presentation = StreamPresentationMetadata(
                origin = StreamPresentationOrigin.LIVE,
                revision = 1L,
            ),
            reasoning = listOf(MessageReasoning("r1", "未完成思考", 0, false)),
        )

        val registry = StreamingPresentationRegistry(emptyList())
        val initialFrame = registry.frameFor(entry)

        assertNotNull(initialFrame, "未完成思考块绝不得使 frameFor 返回 null")
        assertTrue(initialFrame.active, "首帧流式投影必须处于 active 状态")

        registry.accept(listOf(entry), pageVisible = true, now = 1L)
        val acceptedFrame = registry.frameFor(entry)
        assertNotNull(acceptedFrame, "accept 调度后流式帧必须持续有效")
        assertTrue(acceptedFrame.active, "accept 调度后流式帧必须保持 active")
        assertTrue(registry.hasPending, "流式推进引擎必须标记 hasPending")
    }

    // ==========================================
    // Tier 2: 边界与极端条件测试 (F5 Boundary & Corner Cases)
    // ==========================================

    @Test
    fun t2_F5_01_Unclosed_Thought_Chunk() {
        val partialChunk = "<think>正在解析未闭合的分片数据..."
        val blocks = MarkdownParser.parse(partialChunk)

        assertEquals(1, blocks.size)
        assertTrue(blocks[0] is TimelineBlock.ThoughtBlock)
        val thought = blocks[0] as TimelineBlock.ThoughtBlock
        assertFalse(thought.isComplete, "未闭合标签必须解析为进行中")
        assertEquals("正在解析未闭合的分片数据...", thought.thought)
    }

    @Test
    fun t2_F5_02_Huge_Thought_Text() {
        val hugeThought = "深度推导: " + "Step... ".repeat(2000)
        val block = thoughtBlock(thought = hugeThought, isComplete = true)

        compose.setContent {
            MaterialTheme {
                MarkdownThoughtBlockView(block = block, isStreaming = false)
            }
        }

        compose.onNodeWithText("已思考", substring = true).assertIsDisplayed()
        compose.onNodeWithText("已思考", substring = true).performClick()
        compose.onNodeWithText("深度推导", substring = true).assertIsDisplayed()
    }

    @Test
    fun t2_F5_03_Thought_Stream_Interruption() {
        val block = thoughtBlock(thought = "正在推导但是被用户打断", isComplete = false)

        compose.setContent {
            MaterialTheme {
                MarkdownThoughtBlockView(block = block, isStreaming = false)
            }
        }

        compose.onNodeWithText("思考已中断", substring = true).assertIsDisplayed()
    }

    @Test
    fun t2_F5_04_Thought_With_Markdown_Code_Blocks() {
        val markdownThought = """
            首先分析以下代码结构：
            ```kotlin
            fun test() = println("thought inside code")
            ```
            以上为关键逻辑。
        """.trimIndent()
        val block = thoughtBlock(thought = markdownThought, isComplete = false)

        compose.setContent {
            MaterialTheme {
                MarkdownThoughtBlockView(block = block, isStreaming = true)
            }
        }

        compose.onNodeWithText("fun test()", substring = true).assertIsDisplayed()
        compose.onNodeWithText("thought inside code", substring = true).assertIsDisplayed()
    }

    @Test
    fun t2_F5_05_High_Frequency_Thought_Deltas() {
        val textState = mutableStateOf("T0")

        compose.setContent {
            MaterialTheme {
                MarkdownThoughtBlockView(
                    block = TimelineBlock.ThoughtBlock(thought = textState.value, isComplete = false),
                    isStreaming = true,
                )
            }
        }

        for (i in 1..10) {
            textState.value += " -> T$i"
        }
        compose.waitForIdle()

        compose.onNodeWithText("T10", substring = true).assertIsDisplayed()
    }

    // ==========================================
    // Tier 3: 真实流式渐显批次、光标坐标与像素绘制验证 (autoAdvance = false)
    // ==========================================

    @Test
    fun t3_R5_StreamingThoughtBody_Keeps_NonEmpty_Batches_And_CursorPosition() {
        compose.mainClock.autoAdvance = false
        var decoration: StreamingDecorationState? = null
        val frameState = mutableStateOf(
            StreamingPresentationFrame(
                text = "",
                active = true,
                batchId = 0L,
                start = 0,
                end = 0,
            ),
        )
        val liveState = mutableStateOf(true)
        val thoughtState = mutableStateOf("首先")

        compose.setContent {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = false)) {
                MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFFFF00FF))) {
                    StreamingThoughtBody(
                        thoughtText = thoughtState.value,
                        frame = frameState.value,
                        live = liveState.value,
                        reduceMotion = false,
                        identityKey = "compose-thought-test",
                        modifier = Modifier.width(280.dp),
                    ) { blocks, active ->
                        decoration = LocalStreamingDecoration.current
                        MarkdownDocumentView(blocks = blocks, isStreaming = active)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()

        // 注入第 1 批增量 "首先"（0..2）
        compose.runOnIdle {
            frameState.value = StreamingPresentationFrame(
                text = "首先",
                active = true,
                batchId = 1L,
                start = 0,
                end = 2,
            )
        }
        compose.waitUntil(1_000) {
            compose.mainClock.advanceTimeByFrame()
            decoration?.cursorPosition != null
        }

        val stateAfterFirst = requireNotNull(decoration)
        assertTrue(stateAfterFirst.batches.isNotEmpty(), "渐显批次绝不可被清空")
        assertTrue(
            stateAfterFirst.presentation.sourceRevealEnd > stateAfterFirst.presentation.sourceRevealStart,
            "sourceRevealEnd 必须大于 sourceRevealStart",
        )
        assertNotNull(stateAfterFirst.cursorPosition, "排版注册后必须存在有效光标坐标")
        val firstCursorX = stateAfterFirst.cursorPosition!!.x

        // 紧接着在 120ms 渐显窗内注入第 2 批增量，光标应右移且激活拖尾
        compose.runOnIdle {
            thoughtState.value = "首先检查网络接口并输出诊断"
            frameState.value = StreamingPresentationFrame(
                text = "首先检查网络接口并输出诊断",
                active = true,
                batchId = 2L,
                start = 2,
                end = 13,
            )
        }
        compose.mainClock.advanceTimeBy(33)
        compose.waitForIdle()

        assertTrue(stateAfterFirst.batches.size >= 2, "连续增量应保有 >= 2 个渐显批次")
        assertNotNull(stateAfterFirst.cursorPosition)
        assertTrue(
            stateAfterFirst.cursorPosition!!.x > firstCursorX,
            "增量推进后光标必须右移: ${stateAfterFirst.cursorPosition!!.x} > $firstCursorX",
        )
        assertNotNull(stateAfterFirst.trailOrigin, "光标推进必须激活 trailOrigin 拖尾")

        // 终态排空后，active 与 batches 归零
        compose.runOnIdle {
            liveState.value = false
            frameState.value = StreamingPresentationFrame(
                text = "首先检查网络接口并输出诊断",
                active = false,
                batchId = 2L,
                start = 13,
                end = 13,
            )
        }
        compose.mainClock.advanceTimeBy(350)
        compose.waitForIdle()

        assertFalse(stateAfterFirst.presentation.active)
        assertTrue(stateAfterFirst.batches.isEmpty(), "终态排空窗口过后渐显批次应全部过期清空")
    }

    @Test
    fun t3_R5_MarkdownThoughtBlockView_Pixel_Cursor_And_Prefix_Reveal() {
        compose.mainClock.autoAdvance = false
        val fullThought = "推".repeat(80)
        val isStreamingState = mutableStateOf(true)
        val isCompleteState = mutableStateOf(false)
        lateinit var contentView: View

        compose.setContent {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = false)) {
                MaterialTheme(colorScheme = lightColorScheme(primary = CursorColor)) {
                    contentView = LocalView.current
                    Box(
                        modifier = Modifier
                            .width(300.dp)
                            .testTag("thought-card-host"),
                    ) {
                        MarkdownThoughtBlockView(
                            block = TimelineBlock.ThoughtBlock(
                                thought = fullThought,
                                isComplete = isCompleteState.value,
                            ),
                            isStreaming = isStreamingState.value,
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        val firstText = visibleCharText('推')
        assertTrue(
            firstText.length < fullThought.length,
            "思考卡首帧不能一次性弹出全部 80 字（实际: ${firstText.length}）",
        )
        assertTrue(fullThought.startsWith(firstText), "首帧可见文本必须是完整思考的严格前缀")

        compose.waitUntil(5_000) {
            compose.mainClock.advanceTimeByFrame()
            cursorPixelCount(contentView, "thought-card-host") > 3
        }

        // 推进至全部排空并结束流式
        compose.runOnIdle {
            isStreamingState.value = false
            isCompleteState.value = true
        }
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertEquals(0, cursorPixelCount(contentView, "thought-card-host"))
    }

    @Test
    fun t3_R5_MessageTimeline_Host_Streams_Thought_Then_Reply() {
        compose.mainClock.autoAdvance = false
        val thoughtText = "推".repeat(80)
        val replyText = "回".repeat(50)
        val entries = mutableStateOf(emptyList<TimelineEntry>())
        lateinit var contentView: View

        compose.setContent {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = false)) {
                MaterialTheme(colorScheme = lightColorScheme(primary = CursorColor)) {
                    contentView = LocalView.current
                    Box(
                        modifier = Modifier
                            .width(320.dp)
                            .testTag("timeline-thought-host"),
                    ) {
                        MessageTimeline(entries = entries.value)
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        // 推送仅含流式思考的条目
        val streamingThoughtEntry = TimelineEntry(
            key = "turn_r5",
            sender = "assistant",
            text = "",
            isUser = false,
            timestamp = 1L,
            pendingAcceptance = false,
            batchGroupId = null,
            isStreaming = true,
            generationId = "gen_r5",
            reasoning = listOf(MessageReasoning("r1", thoughtText, 0, false)),
            presentation = StreamPresentationMetadata(StreamPresentationOrigin.LIVE, 1L),
        )
        compose.runOnIdle { entries.value = listOf(streamingThoughtEntry) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        val firstThoughtVisible = visibleCharText('推')
        assertTrue(
            firstThoughtVisible.length < thoughtText.length,
            "MessageTimeline 首帧思考必须是真前缀（实际长度: ${firstThoughtVisible.length}）",
        )
        assertTrue(thoughtText.startsWith(firstThoughtVisible))

        compose.waitUntil(5_000) {
            compose.mainClock.advanceTimeByFrame()
            cursorPixelCount(contentView, "timeline-thought-host") > 3
        }

        // 思考完成、正文开始流式到达，验证互不阻塞
        compose.runOnIdle {
            entries.value = listOf(
                streamingThoughtEntry.copy(
                    text = replyText,
                    reasoning = listOf(MessageReasoning("r1", thoughtText, 0, true)),
                    presentation = StreamPresentationMetadata(StreamPresentationOrigin.LIVE, 2L),
                ),
            )
        }
        compose.waitUntil(1_000) {
            compose.mainClock.advanceTimeByFrame()
            visibleCharText('回').isNotEmpty()
        }

        val firstReplyVisible = visibleCharText('回')
        assertTrue(
            firstReplyVisible.isNotEmpty() && replyText.startsWith(firstReplyVisible),
            "正文开始流式呈现且受流式引擎控制: ${firstReplyVisible.length}",
        )

        compose.runOnIdle {
            entries.value = listOf(
                streamingThoughtEntry.copy(
                    text = replyText,
                    isStreaming = false,
                    reasoning = listOf(MessageReasoning("r1", thoughtText, 0, true)),
                    presentation = StreamPresentationMetadata(StreamPresentationOrigin.LIVE, 3L),
                ),
            )
        }
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()

        assertEquals(replyText, visibleCharText('回'))
        assertEquals(0, cursorPixelCount(contentView, "timeline-thought-host"))
    }

    @Test
    fun t3_R5_Early_Completion_Drains_Smoothly_Without_HardCut() {
        compose.mainClock.autoAdvance = false
        val fullThought = "思".repeat(90)
        val isStreamingState = mutableStateOf(true)
        val isCompleteState = mutableStateOf(false)
        lateinit var contentView: View

        compose.setContent {
            CompositionLocalProvider(LocalMotionPolicy provides MotionPolicy(reduceMotion = false)) {
                MaterialTheme(colorScheme = lightColorScheme(primary = CursorColor)) {
                    contentView = LocalView.current
                    Box(
                        modifier = Modifier
                            .width(300.dp)
                            .testTag("thought-drain-host"),
                    ) {
                        MarkdownThoughtBlockView(
                            block = TimelineBlock.ThoughtBlock(
                                thought = fullThought,
                                isComplete = isCompleteState.value,
                            ),
                            isStreaming = isStreamingState.value,
                        )
                    }
                }
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        // 锁定手动展开状态，确保结束流式后仍可观测正文排空收尾。
        compose.onNodeWithText("正在思考", substring = true).performClick()
        compose.onNodeWithText("正在思考", substring = true).performClick()
        compose.waitForIdle()

        // 等待出现流式部分前缀与光标，但尚未显示完 90 字。
        compose.waitUntil(2_000) {
            compose.mainClock.advanceTimeByFrame()
            val currentLen = visibleCharText('思').length
            currentLen in 1 until fullThought.length && cursorPixelCount(contentView, "thought-drain-host") > 3
        }

        // 在未排空前提前结束流式。
        compose.runOnIdle {
            isStreamingState.value = false
            isCompleteState.value = true
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        val midDrainLen = visibleCharText('思').length
        assertTrue(
            midDrainLen < fullThought.length,
            "提前结束流式后必须继续平滑排空，不能瞬间弹出全量文本（当前: $midDrainLen）",
        )
        assertTrue(
            cursorPixelCount(contentView, "thought-drain-host") > 3,
            "排空进行中光标必须保持显示",
        )

        // 推进到刚排空至全量 90 字并再经历 120ms 淡入收尾窗口。
        compose.waitUntil(2_000) {
            compose.mainClock.advanceTimeByFrame()
            visibleCharText('思').length == fullThought.length
        }
        compose.mainClock.advanceTimeBy(180)
        compose.waitForIdle()

        assertEquals(fullThought, visibleCharText('思'))
        assertEquals(0, cursorPixelCount(contentView, "thought-drain-host"))
    }

    private fun visibleCharText(targetChar: Char): String =
        compose.onAllNodes(hasText(targetChar.toString(), substring = true), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .flatMap { it.config[SemanticsProperties.Text] }
            .map { it.text }
            .filter { text -> text.isNotEmpty() && text.all { it == targetChar } }
            .maxByOrNull { it.length }
            .orEmpty()

    private fun cursorPixelCount(contentView: View, tag: String): Int {
        val bounds = compose.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        if (bounds.width <= 0f || bounds.height <= 0f) return 0
        val image = compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(
                ceil(bounds.width).toInt(),
                ceil(bounds.height).toInt(),
                Bitmap.Config.ARGB_8888,
            )
            val canvas = Canvas(bitmap)
            canvas.translate(-bounds.left, -bounds.top)
            contentView.draw(canvas)
            bitmap.asImageBitmap()
        }.toPixelMap()
        var count = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val color = image[x, y]
                if (color.red > 0.6f && color.blue > 0.6f && color.green < 0.6f &&
                    color.red - color.green > 0.3f && color.blue - color.green > 0.3f && color.alpha > 0.9f
                ) {
                    count++
                }
            }
        }
        return count
    }

    private companion object {
        val CursorColor = Color(0xFFFF00FF)
    }
}
