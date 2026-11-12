package com.openandroidintelligence.conversation.workbench

import androidx.compose.runtime.snapshots.Snapshot
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.model.StreamPresentationOrigin.LIVE
import com.openandroidintelligence.conversation.model.StreamPresentationOrigin.SNAPSHOT
import com.openandroidintelligence.conversation.state.StreamPresentationMetadata
import com.openandroidintelligence.conversation.state.TimelineEntry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 不启动重组器的状态检查使用独立快照，避免污染后续 Compose 空闲状态。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StreamingReviewPresentationRegistryTest {
    @get:Rule val isolation = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val snapshot = Snapshot.takeMutableSnapshot()
            try { snapshot.enter { base.evaluate() } } finally { snapshot.dispose() }
        }
    } }

    @Test fun 完整执行前缀被跳过但事实全文与回答来源偏移保留() {
        val text = "引导\n\n<think>${"隐".repeat(5_000)}</think>\n\n过程\n\n<think>继续</think>\n\n回答"
        val registry = StreamingPresentationRegistry(emptyList())
        val entry = reply(text)
        registry.accept(listOf(entry), true, 0, GenerationState.RUNNING)
        val frame = requireNotNull(registry.frameFor(entry))
        assertEquals("首批只消费回答首字，隐藏执行前缀不产生待显长尾", "回", frame.text.drop(frame.sourceStart))
        assertEquals(text.indexOf("回答"), frame.sourceStart)
        assertEquals(frame.sourceStart, frame.start)
        assertEquals(listOf(frame.sourceStart until frame.end), frame.liveSourceRanges)
        assertEquals(1, frame.advancedGraphemes)
        registry.advance(100_000_000)
        assertEquals(text, registry.frameFor(entry)?.text)
        assertEquals(text, entry.text)
    }

    @Test fun 同轮次执行事实的明确终态也结束正文而旧轮次不影响当前() {
        val registry = StreamingPresentationRegistry(emptyList())
        val entry = reply("字".repeat(150))
        val old = reply("旧正文", key = "old").copy(executionGenerationState = GenerationState.COMPLETED)
        registry.accept(listOf(old, entry), true, 0)
        assertTrue(registry.frameFor(entry)!!.active)
        val terminalFact = entry.copy(key = "fact", executionFact = true, text = "", executionGenerationState = GenerationState.CANCELLED)
        registry.accept(listOf(old, entry, terminalFact), true, 1_000_000)
        registry.advance(300_000_000)
        assertEquals(entry.text, registry.frameFor(entry)?.text)
        assertFalse(registry.frameFor(entry)!!.active)
    }

    @Test fun 取消请求和未知状态不排空且旧生成终态不认领新身份() {
        val registry = StreamingPresentationRegistry(emptyList())
        val first = reply("字".repeat(150))
        registry.accept(listOf(first), true, 0, GenerationState.RUNNING)
        registry.accept(listOf(first), true, 1_000_000, GenerationState.CANCEL_REQUESTED)
        registry.advance(300_000_000)
        assertTrue(registry.frameFor(first)!!.active)
        registry.accept(listOf(first), true, 310_000_000, GenerationState.COMPLETED)
        registry.advance(600_000_000)
        assertFalse(registry.frameFor(first)!!.active)
        val next = reply("字".repeat(150), "next")
        registry.accept(listOf(first, next), true, 610_000_000, GenerationState.COMPLETED)
        registry.advance(900_000_000)
        assertTrue("保持的旧终态不能认领新回复", registry.frameFor(next)!!.active)
        registry.accept(listOf(first, next), true, 910_000_000, GenerationState.OUTCOME_UNKNOWN)
        assertTrue(registry.frameFor(next)!!.active)
    }

    @Test fun 相同消息键的旧生成归属不能结束替换后的生成() {
        val registry = StreamingPresentationRegistry(emptyList())
        val old = reply("旧")
        registry.accept(listOf(old), true, 0, GenerationState.RUNNING)
        val next = old.copy(text = "旧" + "字".repeat(150), generationId = "new-generation",
            presentation = StreamPresentationMetadata(LIVE, 2))
        registry.accept(listOf(next), true, 1_000_000, GenerationState.COMPLETED)
        registry.advance(300_000_000)
        assertTrue("相同显示键不能绕过生成身份检查", registry.frameFor(next)!!.active)
    }

    @Test fun 旧生成排空后同键新生成修订归零不继承旧进度或终态时钟() {
        val registry = StreamingPresentationRegistry(emptyList())
        val old = reply("旧".repeat(150)).copy(presentation = StreamPresentationMetadata(LIVE, 40))
        registry.accept(listOf(old), true, 0, GenerationState.RUNNING)
        registry.accept(listOf(old), true, 1_000_000, GenerationState.COMPLETED)
        registry.advance(300_000_000)
        assertEquals(old.text, registry.frameFor(old)?.text)
        assertFalse(registry.frameFor(old)!!.active)

        val next = old.copy(text = "新".repeat(150), generationId = "next-generation",
            presentation = StreamPresentationMetadata(LIVE, 0))
        val first = requireNotNull(registry.frameFor(next))
        assertEquals("新身份的组合首帧不能投射旧回复全文", "", first.text)
        assertTrue(first.active)
        registry.accept(listOf(next), true, 310_000_000, GenerationState.RUNNING)
        assertEquals("新", registry.frameFor(next)?.text)
        registry.advance(610_000_000)
        val progressed = requireNotNull(registry.frameFor(next))
        assertTrue("新生成不能继承旧排空截止时间", progressed.active)
        assertTrue(progressed.text.length in 2 until next.text.length)
        assertTrue(progressed.text.all { it == '新' })
        assertTrue(registry.hasPending)
    }

    @Test fun 同键同生成但客户端身份变化也从空投影和新修订开始() {
        val old = reply("旧正文").copy(clientMessageId = com.openandroidintelligence.conversation.model.ClientMessageId("old-client"))
        val registry = StreamingPresentationRegistry(listOf(old))
        registry.accept(listOf(old), true, 0, GenerationState.RUNNING)
        val next = old.copy(text = "新".repeat(150), clientMessageId = com.openandroidintelligence.conversation.model.ClientMessageId("new-client"),
            presentation = StreamPresentationMetadata(LIVE, 0))
        assertEquals("", registry.frameFor(next)?.text)
        registry.accept(listOf(next), true, 1_000_000, GenerationState.RUNNING)
        assertEquals("新", registry.frameFor(next)?.text)
        assertTrue(registry.frameFor(next)!!.active)
        assertTrue(registry.hasPending)
    }

    @Test fun 没有先看到运行状态的匹配生成终态仍然排空新接收正文() {
        val registry = StreamingPresentationRegistry(emptyList())
        val entry = reply("字".repeat(150)).copy(executionGenerationState = GenerationState.COMPLETED)
        registry.accept(listOf(entry), true, 0, GenerationState.COMPLETED)
        registry.advance(300_000_000)
        assertEquals(entry.text, registry.frameFor(entry)?.text)
        assertFalse(registry.frameFor(entry)!!.active)
    }

    @Test fun 执行最终回答快照恢复仍然直接建立完整基线() {
        val entry = reply("<think>过程</think>\n\n完整回答").copy(presentation = StreamPresentationMetadata(SNAPSHOT, 1))
        val registry = StreamingPresentationRegistry(listOf(entry))
        registry.accept(listOf(entry), true, 0, GenerationState.COMPLETED)
        assertNull(registry.frameFor(entry))
        assertFalse(registry.hasPending)
    }

    private fun reply(text: String, key: String = "reply") = TimelineEntry(key, "assistant", text, false, 1, false, null,
        isStreaming = true, generationId = "gen-$key", presentation = StreamPresentationMetadata(LIVE, 1))
}
