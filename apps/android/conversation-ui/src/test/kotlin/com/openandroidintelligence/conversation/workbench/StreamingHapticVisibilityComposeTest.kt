package com.openandroidintelligence.conversation.workbench

import android.os.SystemClock
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 使用真实裁剪和滚动坐标，确认触觉只属于首次可见判定，不等待用户把旧批次滚入视口。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StreamingHapticVisibilityComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun 首帧离屏批次在两百毫秒内滚入也不会补震而新批次可以触发() {
        val callbacks = mutableListOf<Long>()
        val progress = mutableStateOf(StreamingTextPresentation(0, 0, 0, false, true,
            onVisibleAdvance = callbacks::add))
        val document = StreamingMarkdownCache().parse("字")
        lateinit var drawing: StreamingDecorationState
        var measuredBounds = Rect.Zero
        lateinit var scroll: androidx.compose.foundation.ScrollState
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                scroll = rememberScrollState()
                Box(Modifier.width(320.dp).height(80.dp).clipToBounds()) {
                    Column(Modifier.verticalScroll(scroll)) {
                        Spacer(Modifier.height(120.dp))
                        StreamingMarkdownContainer("字", progress.value, document = document,
                            modifier = Modifier.onGloballyPositioned { measuredBounds = it.boundsInWindow() }) {
                            drawing = requireNotNull(LocalStreamingDecoration.current)
                            MarkdownDocumentView(document.blocks, isStreaming = false)
                        }
                        Spacer(Modifier.height(120.dp))
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
        assertEquals("首次实际布局完全被视口裁剪", 0f, measuredBounds.height, 0.1f)
        val started = SystemClock.uptimeMillis()
        compose.runOnIdle { progress.value = progress.value.copy(batchId = 1, sourceRevealEnd = 1) }
        awaitBatch(drawing, 1)
        assertTrue("离屏推进批次应被丢弃", callbacks.isEmpty())

        compose.runOnIdle { scroll.dispatchRawDelta(120f) }
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        assertTrue("滚动后相同正文已实际进入可见裁剪区", measuredBounds.height > 0f)
        assertTrue("回归必须在原200ms补震窗口内完成", SystemClock.uptimeMillis() - started < 200)
        assertTrue("滚动本身不能给旧批次补触觉", callbacks.isEmpty())

        compose.runOnIdle { progress.value = progress.value.copy(batchId = 2) }
        awaitBatch(drawing, 2)
        assertEquals("可见状态的新推进仍然触发一次", listOf(2L), callbacks)
        compose.runOnIdle { scroll.dispatchRawDelta(1f) }
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
        assertEquals("再次布局不重复触觉", listOf(2L), callbacks)
    }

    @Test fun 同批多个叶节点任一可见只触发一次且销毁丢弃待判定批次() {
        val callbacks = mutableListOf<Long>()
        val progress = mutableStateOf(StreamingTextPresentation(0, 0, 0, false, true,
            onVisibleAdvance = callbacks::add))
        val shown = mutableStateOf(true)
        val document = StreamingMarkdownCache().parse("首行\n\n末行")
        lateinit var drawing: StreamingDecorationState
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                if (shown.value) {
                    Box(Modifier.width(320.dp).height(32.dp).clipToBounds()) {
                        StreamingMarkdownContainer(document.sourceText, progress.value, document = document) {
                            drawing = requireNotNull(LocalStreamingDecoration.current)
                            MarkdownDocumentView(document.blocks, isStreaming = false)
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
        compose.runOnIdle { progress.value = progress.value.copy(batchId = 1, sourceRevealEnd = document.sourceText.length) }
        awaitBatch(drawing, 1)
        assertEquals("同批第二叶离屏不能阻止第一叶的可见触觉，也不能重复", listOf(1L), callbacks)

        compose.runOnIdle {
            progress.value = progress.value.copy(batchId = 2)
            shown.value = false
        }
        compose.mainClock.advanceTimeBy(48)
        compose.waitForIdle()
        assertEquals("销毁组件后不保留待触觉任务", listOf(1L), callbacks)
    }

    private fun awaitBatch(drawing: StreamingDecorationState, batchId: Long) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            drawing.presentation.batchId == batchId
        }
        // 再推进唯一收集器所等待的一帧；正文布局和裁剪坐标已经提交。
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
    }
}
