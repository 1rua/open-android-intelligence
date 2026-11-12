package com.openandroidintelligence.conversation.workbench

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.width
import com.openandroidintelligence.conversation.model.StreamPresentationOrigin.LIVE
import com.openandroidintelligence.conversation.model.StreamPresentationOrigin.SNAPSHOT
import com.openandroidintelligence.conversation.state.StreamPresentationMetadata
import com.openandroidintelligence.conversation.state.TimelineEntry
import kotlin.math.ceil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 从真实消息入口验证组合订阅，避免纯注册器测试通过而气泡永远停在空投影。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StreamingReplyComposeTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var contentView: View

    @Test fun 空时间线收到实时大块后正文持续订阅推进并在终态排空光标() {
        val received = "字".repeat(150)
        val entries = mutableStateOf(emptyList<TimelineEntry>())
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = CursorColor)) {
                contentView = LocalView.current
                MessageTimeline(entries.value, modifier = Modifier.width(320.dp))
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()

        val live = reply(received)
        compose.runOnIdle { entries.value = listOf(live) }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val firstFrame = visibleReplyText()
        assertTrue("首帧不能直接弹出整段接收内容", firstFrame.length < received.length)
        assertTrue("所有可见内容都是完整接收正文的前缀", received.startsWith(firstFrame))

        val progressing = advanceUntilText { it.length > firstFrame.length && it.length < received.length }
        assertTrue("没有再次修改 entries，气泡也必须订阅并展示后续吐字帧", progressing.isNotEmpty())
        compose.waitUntil(5_000) {
            compose.mainClock.advanceTimeByFrame()
            cursorPixelCount() > 3
        }

        // 保留同一份全文和修订，只改变终态；此时仍有缓冲，验证终态不依赖新的正文帧。
        compose.runOnIdle { entries.value = listOf(live.copy(isStreaming = false)) }
        compose.mainClock.advanceTimeBy(320)
        advanceUntilText { it == received }
        compose.mainClock.advanceTimeBy(200)
        compose.waitForIdle()
        assertEquals(received, visibleReplyText())
        assertEquals("缓冲排空后气泡内不应留下光标或主题色拖尾", 0, cursorPixelCount())

        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(received, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> action(layouts) }
        assertTrue("大块正文应经过真实排版并跨行显示", layouts.single().lineCount > 1)
    }

    @Test fun 首次挂载完整快照直接展示且不产生动态光标() {
        val received = "字".repeat(150)
        val cached = reply(received).copy(presentation = StreamPresentationMetadata(SNAPSHOT, 1))
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = CursorColor)) {
                contentView = LocalView.current
                MessageTimeline(listOf(cached), modifier = Modifier.width(320.dp))
            }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        assertEquals("恢复快照首次上屏就是完整正文", received, visibleReplyText())
        assertEquals("缓存即使仍带流式状态也不重播动态光标", 0, cursorPixelCount())
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        assertEquals(received, visibleReplyText())
        assertEquals(0, cursorPixelCount())
    }

    private fun advanceUntilText(predicate: (String) -> Boolean): String {
        var actual = ""
        compose.waitUntil(timeoutMillis = 5_000) {
            // Markdown 尾段在后台解析；推进帧并给解析结果提交机会，不借助额外事实更新重组气泡。
            compose.mainClock.advanceTimeByFrame()
            actual = visibleReplyText()
            predicate(actual)
        }
        return actual
    }

    private fun visibleReplyText(): String = compose.onAllNodes(hasText("字", substring = true), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .flatMap { it.config[SemanticsProperties.Text] }
        .map { it.text }
        .filter { text -> text.isNotEmpty() && text.all { it == '字' } }
        .maxByOrNull { it.length }.orEmpty()

    private fun cursorPixelCount(): Int {
        val bounds = compose.onNodeWithTag("message-bubble:reply", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        // 与既有 MD3 绘制回归一样，Robolectric 直接绘制 View 树；复制按钮在气泡外，不会计入。
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
            // 1.5dp 竖线在 mdpi 下会抗锯齿混色；检查洋红色相，不要求整像素完全不透明。
            if (color.red > 0.6f && color.blue > 0.6f && color.green < 0.6f &&
                color.red - color.green > 0.3f && color.blue - color.green > 0.3f && color.alpha > 0.9f) count++
        }
        return count
    }

    private fun reply(text: String) = TimelineEntry("reply", "assistant", text, false, 1, false, null,
        isStreaming = true, generationId = "gen", presentation = StreamPresentationMetadata(LIVE, 1))

    private companion object { val CursorColor = Color(0xFFFF00FF) }
}
