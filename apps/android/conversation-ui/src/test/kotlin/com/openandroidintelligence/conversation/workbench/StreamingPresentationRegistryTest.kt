package com.openandroidintelligence.conversation.workbench

import com.openandroidintelligence.conversation.model.ClientMessageId
import com.openandroidintelligence.conversation.model.StreamPresentationOrigin
import com.openandroidintelligence.conversation.model.StreamPresentationOrigin.LIVE
import com.openandroidintelligence.conversation.model.StreamPresentationOrigin.SNAPSHOT
import com.openandroidintelligence.conversation.state.StreamPresentationMetadata
import com.openandroidintelligence.conversation.state.TimelineEntry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreamingPresentationRegistryTest {
    @Test fun 首次挂载现有LIVE和快照都以完整正文建立基线() {
        for (origin in listOf(LIVE, SNAPSHOT)) {
            val cached = reply(text = "缓存正文", origin = origin)
            val registry = StreamingPresentationRegistry(listOf(cached))
            assertTrue(registry.isBaseline(cached))
            assertNull(registry.frameFor(cached))
            registry.accept(listOf(cached), true, 0)
            assertNull(registry.frameFor(cached))
            assertFalse(registry.hasPending)
        }
    }

    @Test fun 基线仅修订或状态更新没有新文字时不出现动态光标() {
        val cached = reply(text = "缓存正文")
        val registry = StreamingPresentationRegistry(listOf(cached))
        val status = cached.copy(presentation = StreamPresentationMetadata(LIVE, 2))
        assertNull(registry.frameFor(status))
        registry.accept(listOf(status), true, 0)
        assertNull(registry.frame)
        assertTrue(registry.isBaseline(status))
    }

    @Test fun 新LIVE组合首帧不能完整闪现且接收后立即展示首字() {
        val registry = StreamingPresentationRegistry(emptyList())
        registry.accept(emptyList(), true, 0)
        val incoming = reply(text = "字".repeat(150))
        assertFalse(registry.isBaseline(incoming))
        assertEquals("", registry.frameFor(incoming)?.text)
        registry.accept(listOf(incoming), true, 1)
        assertEquals("字", registry.frameFor(incoming)?.text)
        assertTrue(registry.frameFor(incoming)?.active == true)
        assertTrue(registry.hasPending)
    }

    @Test fun 同批多条真实正文只让末尾活跃回复使用首帧投影() {
        val registry = StreamingPresentationRegistry(emptyList())
        val first = reply(key = "first", text = "已经结束的正文").copy(isStreaming = false)
        val tail = reply(key = "tail", text = "字".repeat(150))
        registry.prepare(listOf(first, tail), true)
        assertNull(registry.frameFor(first))
        assertEquals("", registry.frameFor(tail)?.text)
        registry.accept(listOf(first, tail), true, 0)
        assertNull(registry.frameFor(first))
        assertEquals("字", registry.frameFor(tail)?.text)
    }

    @Test fun 同修订追加也登记并从已有正文继续吐字() {
        val first = reply(text = "已有", revision = 0)
        val registry = StreamingPresentationRegistry(listOf(first))
        registry.accept(listOf(first), true, 0)
        val next = first.copy(text = "已有新增正文")
        assertEquals("已有", registry.frameFor(next)?.text)
        registry.accept(listOf(next), true, 1)
        assertEquals("已有新", registry.frameFor(next)?.text)
        assertTrue(registry.hasPending)
        registry.advance(40_000_000)
        assertTrue(registry.frameFor(next)!!.text.startsWith("已有新"))
    }

    @Test fun 隐藏与重新可见没有新修订时不重播光标和触觉() {
        val registry = StreamingPresentationRegistry(emptyList())
        val incoming = reply(text = "字".repeat(150))
        registry.accept(listOf(incoming), true, 0)
        val oldBatch = registry.frame!!.batchId
        registry.accept(listOf(incoming), false, 1)
        assertNull(registry.frameFor(incoming))
        assertFalse(registry.consumeHaptic(oldBatch))
        assertFalse(registry.hasPending)
        registry.accept(listOf(incoming), true, 2)
        assertTrue(registry.isBaseline(incoming))
        assertNull(registry.frameFor(incoming))
        registry.advance(100_000_000)
        assertNull(registry.frame)
    }

    @Test fun 隐藏期间继续接收重显完整基线只有后续LIVE继续动画() {
        val registry = StreamingPresentationRegistry(emptyList())
        val first = reply(text = "首段")
        registry.accept(listOf(first), true, 0)
        registry.accept(listOf(first), false, 1)
        val hidden = reply(text = "首段后台增加", revision = 2)
        registry.accept(listOf(hidden), false, 2)
        registry.accept(listOf(hidden), true, 3)
        assertNull(registry.frameFor(hidden))
        val next = hidden.copy(text = hidden.text + "新内容", presentation = StreamPresentationMetadata(LIVE, 3))
        registry.accept(listOf(next), true, 4)
        assertEquals(hidden.text + "新", registry.frameFor(next)?.text)
    }

    @Test fun 活跃快照增量静默排空但不新登记历史消息() {
        val registry = StreamingPresentationRegistry(emptyList())
        val first = reply(text = "首")
        registry.accept(listOf(first), true, 0)
        val snapshot = reply(text = "首同步内容", revision = 2, origin = SNAPSHOT)
        registry.accept(listOf(snapshot), true, 1)
        assertEquals("首", registry.frameFor(snapshot)?.text)
        for (time in 1L..20L) {
            registry.advance(time * 34_000_000)
            assertFalse(registry.frame!!.hapticEligible)
        }
        assertEquals(snapshot.text, registry.frameFor(snapshot)?.text)
        val historical = reply(key = "history", text = "另一条恢复正文", revision = 3, origin = SNAPSHOT)
        registry.accept(listOf(snapshot, historical), true, 1_000_000_000)
        assertNull(registry.frameFor(historical))
    }

    @Test fun 相同修订只改变终态仍然排空并移除光标() {
        val registry = StreamingPresentationRegistry(emptyList())
        val streaming = reply(text = "字".repeat(150))
        registry.accept(listOf(streaming), true, 0)
        val completed = streaming.copy(isStreaming = false)
        registry.accept(listOf(completed), true, 1_000_000)
        registry.advance(300_000_000)
        assertEquals(completed.text, registry.frameFor(completed)?.text)
        assertFalse(registry.frameFor(completed)!!.active)
        assertFalse(registry.hasPending)
    }

    @Test fun 占位升级真实消息保留进度并接受宿主修订域() {
        val registry = StreamingPresentationRegistry(emptyList())
        val placeholder = reply(key = "stream_client", text = "字".repeat(150), revision = 40, placeholder = true)
        registry.accept(listOf(placeholder), true, 0)
        registry.advance(34_000_000)
        val before = registry.frame!!.text
        val native = placeholder.copy(key = "native_reply", localPlaceholder = false,
            presentation = StreamPresentationMetadata(LIVE, 1))
        assertEquals(before, registry.frameFor(native)?.text)
        registry.accept(listOf(native), true, 35_000_000)
        assertEquals(before, registry.frameFor(native)?.text)
        assertTrue(registry.hasPending)
        val next = native.copy(text = native.text + "后续", presentation = StreamPresentationMetadata(LIVE, 2))
        registry.accept(listOf(next), true, 36_000_000)
        registry.advance(300_000_000)
        assertTrue(registry.frameFor(next)!!.text.length > before.length)
    }

    @Test fun 两个真实消息即使generation和正文相同也不继承进度() {
        val registry = StreamingPresentationRegistry(emptyList())
        val first = reply(key = "real_first", text = "字".repeat(150))
        registry.accept(listOf(first), true, 0)
        registry.advance(34_000_000)
        val next = first.copy(key = "real_second")
        registry.accept(listOf(first, next), true, 35_000_000)
        assertEquals("字", registry.frameFor(next)?.text)
        assertNull(registry.frameFor(first))
    }

    @Test fun 收到末ZWJ后不空转直到字符续接或终态() {
        val registry = StreamingPresentationRegistry(emptyList())
        val prefix = reply(text = "👩‍")
        registry.accept(listOf(prefix), true, 0)
        assertEquals("", registry.frameFor(prefix)?.text)
        assertFalse(registry.hasPending)
        val complete = prefix.copy(text = "👩‍💻", presentation = StreamPresentationMetadata(LIVE, 2))
        registry.accept(listOf(complete), true, 1)
        assertTrue(registry.hasPending)
        registry.advance(34_000_000)
        assertEquals("👩‍💻", registry.frameFor(complete)?.text)
        assertFalse(registry.hasPending)
    }

    @Test fun 同一可见批次重复回调不会重复触觉且旧批次不能补震() {
        val registry = StreamingPresentationRegistry(emptyList())
        val incoming = reply(text = "字".repeat(150))
        registry.accept(listOf(incoming), true, 0)
        val batch = registry.frame!!.batchId
        assertTrue(registry.consumeHaptic(batch))
        assertFalse(registry.consumeHaptic(batch))
        registry.advance(34_000_000)
        val nextBatch = registry.frame!!.batchId
        assertFalse(registry.consumeHaptic(batch))
        assertTrue(registry.consumeHaptic(nextBatch))
        assertFalse(registry.consumeHaptic(nextBatch))
        assertEquals(registry.frame!!.end - registry.frame!!.start, registry.frame!!.advancedGraphemes)
    }

    @Test fun 组件销毁清理帧并拒绝迟到触觉回调() {
        val registry = StreamingPresentationRegistry(emptyList())
        val incoming = reply(text = "字".repeat(150))
        registry.accept(listOf(incoming), true, 0)
        val batch = registry.frame!!.batchId
        registry.close()
        registry.advance(100_000_000)
        assertNull(registry.frame)
        assertFalse(registry.hasPending)
        assertFalse(registry.consumeHaptic(batch))
    }

    private fun reply(
        key: String = "reply", text: String, revision: Long = 1,
        origin: StreamPresentationOrigin = LIVE, placeholder: Boolean = false,
    ) = TimelineEntry(key, "assistant", text, false, 1, false, null,
        isStreaming = true, generationId = "gen", clientMessageId = ClientMessageId("assistant_client"),
        localPlaceholder = placeholder, presentation = StreamPresentationMetadata(origin, revision))
}
