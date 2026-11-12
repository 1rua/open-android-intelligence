package com.openandroidintelligence.conversation.workbench

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.model.StreamPresentationOrigin.LIVE
import com.openandroidintelligence.conversation.model.StreamPresentationOrigin.SNAPSHOT
import com.openandroidintelligence.conversation.state.StreamPresentationMetadata
import com.openandroidintelligence.conversation.state.TimelineEntry
import kotlin.math.ceil
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 从共享呈现入口覆盖完整执行源边界和仅生成状态变化的真实组合更新。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StreamingReviewPresentationComposeTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var contentView: View

    @Test fun 单块大段思考后的回答首帧不泄漏执行标记且不等待隐藏前缀() {
        assertAnswerStartsImmediately("<think>${"隐".repeat(5_000)}</think>\n\n回答")
    }

    @Test fun 前置过程叙述与多个执行块均不进入最终回答的逐字前缀() {
        assertAnswerStartsImmediately("过程引导\n\n<think>第一步</think>\n\n过程叙述\n\n<think>第二步</think>\n\n回答")
    }

    @Test fun 仅生成完成状态变化也排空保留流式标记的正文并移除光标() {
        assertGenerationTerminalDrains(GenerationState.COMPLETED)
    }

    @Test fun 仅生成失败状态变化也排空保留流式标记的正文并移除光标() {
        assertGenerationTerminalDrains(GenerationState.FAILED)
    }

    @Test fun 仅取消成功状态变化也排空保留流式标记的正文并移除光标() {
        assertGenerationTerminalDrains(GenerationState.CANCELLED)
    }

    @Test fun 旧轮次完成枚举保持时新轮次实时正文仍可继续生成() {
        val old = reply("旧回答", "old").copy(presentation = StreamPresentationMetadata(SNAPSHOT, 1))
        val entries = mutableStateOf(listOf(old))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = CursorColor)) {
                contentView = LocalView.current
                MessageTimeline(entries.value, generation = GenerationState.COMPLETED, modifier = Modifier.width(320.dp))
            }
        }
        pump()
        val next = reply("字".repeat(150), "new")
        compose.runOnIdle { entries.value = listOf(old, next) }
        compose.waitUntil(5_000) {
            pump()
            cursorPixelCount("new") > 3
        }
        assertTrue("旧生成终态不能把新轮次一次性排空", visibleReplyText().length < next.text.length)
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        assertTrue("新轮次仍在生成时末端光标继续保留", cursorPixelCount("new") > 3)
    }

    private fun assertAnswerStartsImmediately(received: String) {
        val registry = StreamingPresentationRegistry(emptyList())
        val entry = reply(received)
        val observed = mutableListOf<String>()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                StreamingReplyBody(entry, registry, finalOnly = true) { blocks, active ->
                    val text = blocks.filterIsInstance<TimelineBlock.Paragraph>().joinToString("") { it.text }
                    SideEffect { observed += text }
                    MarkdownDocumentView(blocks, isStreaming = active)
                }
            }
        }
        pump()
        compose.runOnIdle { registry.accept(listOf(entry), true, 0) }
        pump()
        assertTrue("任何最终回答呈现帧都只能是回答前缀：$observed", observed.all { "回答".startsWith(it) })
        assertEquals("首个接收批次应直接呈现首个回答字素，隐藏执行内容不能消耗吐字时间", "回", observed.last())
        assertEquals("动画不能修改已接收事实", received, entry.text)
    }

    private fun assertGenerationTerminalDrains(terminal: GenerationState) {
        val entries = mutableStateOf(emptyList<TimelineEntry>())
        val generation = mutableStateOf(GenerationState.RUNNING)
        val received = "字".repeat(150)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = CursorColor)) {
                contentView = LocalView.current
                MessageTimeline(entries.value, generation = generation.value, modifier = Modifier.width(320.dp))
            }
        }
        pump()
        val entry = reply(received)
        compose.runOnIdle { entries.value = listOf(entry) }
        compose.waitUntil(5_000) { pump(); visibleReplyText().isNotEmpty() }
        assertTrue(visibleReplyText().length < received.length)
        compose.runOnIdle { generation.value = terminal }
        compose.mainClock.advanceTimeBy(350)
        compose.waitForIdle()
        assertEquals("生成明确终态后必须在排空窗口结束全文", received, visibleReplyText())
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        assertEquals("正文仍标作流式也不能保留生成终态光标", 0, cursorPixelCount("reply"))
        assertTrue("展示层不能回写事实的旧流式标记", entry.isStreaming)
    }

    private fun pump() {
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
    }

    private fun visibleReplyText(): String = compose.onAllNodes(hasText("字", substring = true), useUnmergedTree = true)
        .fetchSemanticsNodes().flatMap { it.config[SemanticsProperties.Text] }.map { it.text }
        .filter { it.isNotEmpty() && it.all { char -> char == '字' } }.maxByOrNull { it.length }.orEmpty()

    private fun cursorPixelCount(key: String): Int {
        val bounds = compose.onNodeWithTag("message-bubble:$key", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val image = compose.runOnIdle {
            val bitmap = Bitmap.createBitmap(ceil(bounds.width).toInt(), ceil(bounds.height).toInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.translate(-bounds.left, -bounds.top)
            contentView.draw(canvas)
            bitmap.asImageBitmap()
        }.toPixelMap()
        var count = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            val color = image[x, y]
            if (color.red > 0.6f && color.blue > 0.6f && color.green < 0.6f && color.red - color.green > 0.3f &&
                color.blue - color.green > 0.3f && color.alpha > 0.9f) count++
        }
        return count
    }

    private fun reply(text: String, key: String = "reply") = TimelineEntry(key, "assistant", text, false, 1, false, null,
        isStreaming = true, generationId = "gen-$key", presentation = StreamPresentationMetadata(LIVE, 1))

    private companion object { val CursorColor = Color(0xFFFF00FF) }
}
