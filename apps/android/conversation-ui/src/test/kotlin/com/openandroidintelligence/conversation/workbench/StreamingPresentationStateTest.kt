package com.openandroidintelligence.conversation.workbench

import com.openandroidintelligence.conversation.model.StreamPresentationOrigin.LIVE
import com.openandroidintelligence.conversation.model.StreamPresentationOrigin.SNAPSHOT
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreamingPresentationStateTest {
    @Test fun 首批立即显示一个字素且突发全文不会瞬间弹出() {
        val state = StreamingPresentationState()
        val advance = state.update("字".repeat(150), 1, LIVE, true, 0)
        assertEquals("字", state.displayedText)
        assertTrue(advance.hapticEligible)
        assertEquals(149, state.pendingBuffer.length)
        assertFalse(state.advance(16_000_000).changed)
        val next = state.advance(34_000_000)
        assertTrue(next.changed)
        assertTrue(state.displayedText.length in 2..20)
        assertEquals("字".repeat(150), state.receivedText)
    }

    @Test fun 终态先到时在短窗口内排空并保留全文() {
        val state = StreamingPresentationState()
        val text = "正文".repeat(2000)
        state.update(text, 1, LIVE, true, 0)
        state.update(text, 2, LIVE, false, 1_000_000)
        for (time in 35L..280L step 35) state.advance(time * 1_000_000)
        assertEquals(text, state.displayedText)
        assertEquals("", state.pendingBuffer)
        assertFalse(state.isPresenting)
    }

    @Test fun 取消成功和终态同样排空且没有新的网络正文也能完成() {
        val state = StreamingPresentationState()
        state.update("保留已收内容".repeat(40), 1, LIVE, true, 0)
        state.update(state.receivedText, 1, LIVE, false, 1_000_000)
        state.advance(300_000_000)
        assertEquals(state.receivedText, state.displayedText)
        assertFalse(state.isPresenting)
    }

    @Test fun 首屏快照直接显示且不登记旧动画() {
        val state = StreamingPresentationState()
        val change = state.update("恢复的旧正文", 4, SNAPSHOT, true, 0)
        assertEquals("恢复的旧正文", state.displayedText)
        assertFalse(change.hapticEligible)
        assertFalse(state.isPresenting)
        assertFalse(state.advance(1_000_000_000).changed)
        val live = state.update("恢复的旧正文新", 5, LIVE, true, 1_010_000_000)
        assertEquals("恢复的旧正文新", state.displayedText)
        assertTrue(live.hapticEligible)
    }

    @Test fun 活跃回复追加同步快照仅静默增加缓冲() {
        val state = StreamingPresentationState()
        state.update("实", 1, LIVE, true, 0)
        state.update("实时同步的额外内容", 2, SNAPSHOT, true, 10_000_000)
        assertEquals("实", state.displayedText)
        val changes = (1L..50L).map { state.advance(it * 34_000_000) }
        assertTrue(changes.any { it.changed })
        assertTrue(changes.none { it.hapticEligible })
        assertEquals(state.receivedText, state.displayedText)
    }

    @Test fun 重复与旧修订不回放不覆盖新全文() {
        val state = StreamingPresentationState()
        state.update("实时正文", 5, LIVE, true, 0)
        val offset = state.revealOffset
        assertFalse(state.update("实时正文", 5, LIVE, true, 1).changed)
        assertFalse(state.update("旧内容", 4, SNAPSHOT, false, 2).changed)
        assertFalse(state.update("相同修订的冲突正文", 5, SNAPSHOT, false, 3).changed)
        assertEquals("实时正文", state.receivedText)
        assertEquals(offset, state.revealOffset)
    }

    @Test fun 权威非前缀改写直接校正且不产生新字触觉() {
        val state = StreamingPresentationState()
        state.update("旧正文还在输出", 1, LIVE, true, 0)
        val correction = state.update("更正后的内容", 2, SNAPSHOT, false, 1)
        assertTrue(correction.corrected)
        assertFalse(correction.hapticEligible)
        assertEquals("更正后的内容", state.displayedText)
        assertFalse(state.isPresenting)
    }

    @Test fun 全部UTF16分割都不会输出半个代理对且最终全文相等() {
        val text = "中e\u0301👩🏽‍💻🇨🇳文\n终"
        for (split in 0..text.length) {
            val state = StreamingPresentationState()
            state.update(text.substring(0, split), 1, LIVE, true, 0)
            assertValidSurrogates(state.displayedText)
            state.update(text, 2, LIVE, false, 1_000_000)
            for (time in 1L..10L) {
                state.advance(time * 34_000_000)
                assertValidSurrogates(state.displayedText)
            }
            assertEquals("分割位置 $split", text, state.displayedText)
        }
    }

    @Test fun 后到组合符与ZWJ只续接末字素不重复触觉() {
        val accents = StreamingPresentationState()
        accents.update("e", 1, LIVE, true, 0)
        val accent = accents.update("e\u0301", 2, LIVE, true, 1)
        assertEquals("e\u0301", accents.displayedText)
        assertFalse(accent.hapticEligible)
        val emoji = StreamingPresentationState()
        emoji.update("👩", 1, LIVE, true, 0)
        emoji.update("👩‍", 2, LIVE, true, 1)
        assertEquals("👩", emoji.displayedText)
        val joined = emoji.update("👩‍💻", 3, LIVE, true, 2)
        assertEquals("👩‍💻", emoji.displayedText)
        assertFalse(joined.hapticEligible)
    }

    @Test fun 停顿不会产生重复推进且恢复后可以继续() {
        val state = StreamingPresentationState()
        state.update("字", 1, LIVE, true, 0)
        repeat(20) { assertFalse(state.advance((it + 1L) * 34_000_000).changed) }
        state.update("字新内容", 2, LIVE, true, 700_000_000)
        state.advance(740_000_000)
        assertTrue(state.revealOffset > 1)
        assertTrue(state.isPresenting)
    }

    @Test fun 高频推进只采样不排队补震且关闭不产生触觉() {
        val throttle = StreamingHapticThrottle()
        val triggers = (0L..1000L step 10).filter { throttle.shouldTrigger(it * 1_000_000, 20) }
        assertTrue(triggers.size in 6..9)
        assertTrue(triggers.zipWithNext().all { (a, b) -> b - a >= 100 })
        assertFalse(throttle.shouldTrigger(2_000_000_000, 0))
        assertFalse(throttle.shouldTrigger(2_000_000_000, 20, eligible = false))
        assertTrue(throttle.shouldTrigger(2_000_000_000, 1))
        assertFalse(throttle.shouldTrigger(2_000_000_001, 1))
        throttle.reset()
        assertTrue(throttle.shouldTrigger(2_000_000_002, 1))
    }

    private fun assertValidSurrogates(text: String) {
        text.forEachIndexed { index, char ->
            if (char.isHighSurrogate()) assertTrue(index + 1 < text.length && text[index + 1].isLowSurrogate())
            if (char.isLowSurrogate()) assertTrue(index > 0 && text[index - 1].isHighSurrogate())
        }
    }
}
