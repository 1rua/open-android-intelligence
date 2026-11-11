package com.openandroidintelligence.conversation.workbench

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreamingDrawingStateTest {
    @Test fun 权威改写清除旧范围和光标轨迹而最终排空保留末批渐入() {
        val state = StreamingDecorationState()
        val document = StreamingMarkdownCache().parse("已有文字新增")
        state.update(document, StreamingTextPresentation(1, 4, 6, true, false))
        state.cursorPosition = Offset(100f, 10f)
        state.previousTarget = Offset(120f, 10f)
        state.trailOrigin = Offset(80f, 10f)
        state.update(document, StreamingTextPresentation(1, 4, 6, false, false))
        assertEquals(1, state.batches.size)
        assertNull(state.trailOrigin)
        state.update(StreamingMarkdownCache().parse("更正"), StreamingTextPresentation(2, 2, 2, false, false))
        assertTrue(state.batches.isEmpty())
        assertNull(state.cursorPosition)
        assertNull(state.previousTarget)
        assertNull(state.trailOrigin)
    }

    @Test fun 高速推进的短期范围合并后仍覆盖新增内容且数量有界() {
        val state = StreamingDecorationState()
        val document = StreamingMarkdownCache().parse("字".repeat(30))
        repeat(20) { index ->
            state.update(document, StreamingTextPresentation(index + 1L, index, index + 1, true, false))
        }
        assertEquals(8, state.batches.size)
        assertEquals(0, state.batches.first().sourceStart)
        assertEquals(20, state.batches.last().sourceEnd)
        state.update(document, StreamingTextPresentation(20, 19, 20, true, true))
        assertTrue(state.batches.isEmpty())
    }
}
