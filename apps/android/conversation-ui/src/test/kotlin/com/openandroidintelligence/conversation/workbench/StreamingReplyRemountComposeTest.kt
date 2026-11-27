package com.openandroidintelligence.conversation.workbench

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.model.StreamPresentationOrigin.LIVE
import com.openandroidintelligence.conversation.state.StreamPresentationMetadata
import com.openandroidintelligence.conversation.state.TimelineEntry
import com.openandroidintelligence.conversation.theme.LocalStreamingHapticFeedbackEnabled
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 同一列表外进度重新挂载时，不把旧范围当作新出现的文字或待补触觉。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StreamingReplyRemountComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun 同注册器的旧批次初挂载与离屏重挂载静默但下一批仍渐入触觉() {
        val registry = StreamingPresentationRegistry(emptyList())
        val entry = reply()
        val shown = mutableStateOf(false)
        val calls = AtomicInteger()
        lateinit var drawing: StreamingDecorationState
        lateinit var scroll: androidx.compose.foundation.ScrollState
        var measuredBounds = Rect.Zero
        var disposals = 0
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                val actual = LocalView.current
                val probe = remember(actual) { HapticProbeView(actual, calls) }
                scroll = rememberScrollState()
                CompositionLocalProvider(LocalView provides probe, LocalStreamingHapticFeedbackEnabled provides true) {
                    Box(Modifier.width(320.dp).height(64.dp).clipToBounds()) {
                        Column(Modifier.verticalScroll(scroll)) {
                            Spacer(Modifier.height(120.dp))
                            if (shown.value) {
                                StreamingReplyBody(entry, registry,
                                    modifier = Modifier.onGloballyPositioned { measuredBounds = it.boundsInWindow() }) { blocks, active ->
                                    drawing = requireNotNull(LocalStreamingDecoration.current)
                                    DisposableEffect(Unit) { onDispose { disposals++ } }
                                    MarkdownDocumentView(blocks, isStreaming = active)
                                }
                            }
                            Spacer(Modifier.height(120.dp))
                        }
                    }
                }
            }
        }
        pump()
        compose.runOnIdle {
            registry.accept(listOf(entry), true, 0)
            shown.value = true
        }
        awaitBatch({ drawing }, 1)
        assertEquals(0f, measuredBounds.height, 0.1f)
        assertTrue("首次挂载已有批次不重新渐入", drawing.batches.isEmpty())
        assertEquals(drawing.presentation.sourceRevealEnd, drawing.presentation.sourceRevealStart)
        assertTrue(drawing.presentation.hapticSourceRanges!!.isEmpty())
        assertEquals(0, calls.get())
        assertTrue("组件基线不能改变当前回复活跃状态", drawing.presentation.active)
        val originalDrawing = drawing
        val originalText = registry.frame!!.text

        compose.runOnIdle { shown.value = false }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            disposals > 0
        }
        compose.runOnIdle {
            scroll.dispatchRawDelta(120f)
            shown.value = true
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            drawing !== originalDrawing
        }
        awaitBatch({ drawing }, 1)
        assertNotSame("真实销毁后必须建立新的局部绘制状态", originalDrawing, drawing)
        assertTrue("重新挂载的正文已经可见", measuredBounds.height > 0f)
        assertEquals("列表外吐字进度不因重新挂载改变", originalText, registry.frame!!.text)
        assertTrue("旧批次重新挂载不补播渐入", drawing.batches.isEmpty())
        assertTrue("旧实时范围不能重新获得触觉资格", drawing.presentation.hapticSourceRanges!!.isEmpty())
        assertEquals("旧批次首次离屏且从未震动也不能在重挂载时补震", 0, calls.get())

        compose.runOnIdle { registry.advance(40_000_000) }
        awaitBatch({ drawing }, 2)
        assertTrue("真正的新批次仍有新增范围", drawing.presentation.sourceRevealEnd > drawing.presentation.sourceRevealStart)
        assertTrue(drawing.presentation.hapticSourceRanges!!.isNotEmpty())
        assertTrue("下一批保留新增文字渐入", drawing.batches.any { it.id == 2L })
        assertEquals("下一批可见正文仍正常请求一次键盘触觉", 1, calls.get())
    }

    @Test fun 首条实时回复批次零临时投影到首字批次一仍保留渐入触觉() {
        val registry = StreamingPresentationRegistry(emptyList())
        val entry = reply()
        val calls = AtomicInteger()
        lateinit var drawing: StreamingDecorationState
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                val actual = LocalView.current
                val probe = remember(actual) { HapticProbeView(actual, calls) }
                CompositionLocalProvider(LocalView provides probe, LocalStreamingHapticFeedbackEnabled provides true) {
                    StreamingReplyBody(entry, registry, modifier = Modifier.width(320.dp)) { blocks, active ->
                        drawing = requireNotNull(LocalStreamingDecoration.current)
                        MarkdownDocumentView(blocks, isStreaming = active)
                    }
                }
            }
        }
        pump()
        assertEquals(0L, drawing.presentation.batchId)
        compose.runOnIdle { registry.accept(listOf(entry), true, 0) }
        awaitBatch({ drawing }, 1)
        assertEquals("首字仍及时展示", "字", registry.frame!!.text)
        assertTrue("初始临时投影不能屏蔽真正首字新增范围", drawing.presentation.sourceRevealEnd > drawing.presentation.sourceRevealStart)
        assertTrue(drawing.presentation.hapticSourceRanges!!.isNotEmpty())
        assertTrue(drawing.batches.any { it.id == 1L })
        assertEquals(1, calls.get())
    }

    private fun awaitBatch(drawing: () -> StreamingDecorationState, batch: Long) {
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.mainClock.advanceTimeByFrame()
            compose.waitForIdle()
            try { drawing().presentation.batchId == batch } catch (_: UninitializedPropertyAccessException) { false }
        }
        pump()
    }

    private fun pump() {
        compose.mainClock.advanceTimeBy(32)
        compose.waitForIdle()
    }

    /** 只记录原生触觉请求；不触发硬件，真实可见性仍由布局裁剪判定。 */
    private class HapticProbeView(actual: View, private val calls: AtomicInteger) : View(actual.context) {
        init { isHapticFeedbackEnabled = true }
        override fun isShown() = true
        override fun hasWindowFocus() = true
        override fun performHapticFeedback(feedbackConstant: Int): Boolean {
            assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, feedbackConstant)
            calls.incrementAndGet()
            return true
        }
    }

    private fun reply() = TimelineEntry("reply", "assistant", "字".repeat(150), false, 1, false, null,
        isStreaming = true, generationId = "gen", presentation = StreamPresentationMetadata(LIVE, 1))
}
