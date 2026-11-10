package com.openandroidintelligence.conversation.batch

import com.openandroidintelligence.conversation.model.ClientMessageId
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.ports.OutgoingMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DebounceBatcherTest {

    private val gatewayScope = ConversationScope("profile", "gateway", "account", "install")

    /** 客户端默认策略就是「不合并」：任何一条消息都在同一次调用里立即提交。 */
    @Test fun `the default policy sends every message immediately`() = runTest {
        val flushed = mutableListOf<List<String>>()
        val batcher = DebounceBatcher(this) { _, _, messages -> flushed += messages.map { it.text } }

        batcher.offer(gatewayScope, "conv_a", message("m1", "第一条"))
        batcher.offer(gatewayScope, "conv_a", message("m2", "第二条"))
        runCurrent()

        assertEquals(listOf(listOf("第一条"), listOf("第二条")), flushed)
    }

    @Test fun `zero delay never holds a batch for the timer`() = runTest {
        val flushed = mutableListOf<List<String>>()
        val batcher = DebounceBatcher(this, DebouncePolicy(delay = 0.milliseconds)) { _, _, messages ->
            flushed += messages.map { it.text }
        }

        batcher.offer(gatewayScope, "conv_a", message("m1", "立即发送"))
        runCurrent()

        assertEquals(listOf(listOf("立即发送")), flushed)
    }

    /**
     * One gateway scope serves every thread on it, so a batch keyed by scope
     * used to merge two conversations typed into within the same debounce
     * window and deliver the aggregate to only one of them.
     */
    @Test fun `two conversations keep separate batches`() = runTest {
        val flushed = mutableListOf<Pair<String, List<String>>>()
        val batcher = DebounceBatcher(
            scope = this,
            policy = DebouncePolicy(delay = 1_500.milliseconds),
            onFlush = { _, conversationId, messages ->
                flushed += conversationId to messages.map { it.text }
            },
        )

        batcher.offer(gatewayScope, "conv_a", message("m1", "给 A 的第一条"))
        batcher.offer(gatewayScope, "conv_b", message("m2", "给 B 的第一条"))

        advanceTimeBy(1500)
        runCurrent()

        assertEquals(2, flushed.size)
        assertEquals("conv_a" to listOf("给 A 的第一条"), flushed[0])
        assertEquals("conv_b" to listOf("给 B 的第一条"), flushed[1])
    }

    @Test fun `one conversation still aggregates its own members`() = runTest {
        val flushed = mutableListOf<Pair<String, List<String>>>()
        val batcher = DebounceBatcher(
            scope = this,
            policy = DebouncePolicy(delay = 1_500.milliseconds),
            onFlush = { _, conversationId, messages ->
                flushed += conversationId to messages.map { it.text }
            },
        )

        batcher.offer(gatewayScope, "conv_a", message("m1", "第一句"))
        batcher.offer(gatewayScope, "conv_a", message("m2", "第二句"))

        advanceTimeBy(1500)
        runCurrent()

        assertEquals(1, flushed.size)
        assertEquals("conv_a" to listOf("第一句", "第二句"), flushed.single())
    }

    /** 同一批发满 20 条就必须提交，不能继续等窗口结束。 */
    @Test fun `the twentieth member flushes the batch`() = runTest {
        val flushed = mutableListOf<List<String>>()
        val batcher = DebounceBatcher(this, DebouncePolicy(delay = 10.seconds)) { _, _, messages ->
            flushed += messages.map { it.text }
        }

        (1..20).forEach { index -> batcher.offer(gatewayScope, "conv_a", message("m$index", "第 $index 条")) }
        runCurrent()

        assertEquals(20, flushed.single().size)

        // 第 21 条不能挤进已满的批次，也不能被丢弃：它开启下一批并在窗口结束时发出。
        batcher.offer(gatewayScope, "conv_a", message("m21", "第 21 条"))
        runCurrent()
        assertEquals(20, flushed.single().size)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf(listOf("第 21 条")), flushed.drop(1))
    }

    private fun message(id: String, text: String): OutgoingMessage =
        OutgoingMessage(ClientMessageId(id), text, emptyList())

    @Test fun newlineByteSplitsTwo32KiBMembersBeforeTheyExceedTheWireLimit() = runTest {
        val flushed = mutableListOf<List<String>>()
        val batcher = DebounceBatcher(this, DebouncePolicy(delay = 1_500.milliseconds)) { _, _, messages ->
            flushed += messages.map { it.text }
        }
        batcher.offer(gatewayScope,"conv_a",message("m1","a".repeat(32 * 1024)))
        batcher.offer(gatewayScope,"conv_a",message("m2","b".repeat(32 * 1024)))
        advanceTimeBy(1500); runCurrent()
        assertEquals(listOf(1,1),flushed.map { it.size })
        flushed.forEach { assertTrue(it.joinToString("\n").toByteArray().size <= 64 * 1024) }
    }

    @Test fun countsPriorSeparatorsAndUtf8BytesWhileAcceptingAnExactFit() = runTest {
        val flushed = mutableListOf<List<String>>()
        val batcher = DebounceBatcher(this,DebouncePolicy(delay = 1_500.milliseconds, maximumBytes = 5)) { _, _, messages ->
            flushed += messages.map { it.text }
        }
        listOf("a","b","c").forEachIndexed { i,text -> batcher.offer(gatewayScope,"conv_a",message("m$i",text)) }
        advanceTimeBy(1500); runCurrent()
        assertEquals(listOf(listOf("a","b","c")),flushed)
        flushed.clear()
        listOf("é","a","b").forEachIndexed { i,text -> batcher.offer(gatewayScope,"conv_a",message("n$i",text)) }
        advanceTimeBy(1500); runCurrent()
        assertEquals(listOf(listOf("é","a"),listOf("b")),flushed)
    }
}
