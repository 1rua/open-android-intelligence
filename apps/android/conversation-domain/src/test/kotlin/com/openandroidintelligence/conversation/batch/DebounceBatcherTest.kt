package com.openandroidintelligence.conversation.batch

import com.openandroidintelligence.conversation.model.ClientMessageId
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.ports.OutgoingMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DebounceBatcherTest {

    private val gatewayScope = ConversationScope("profile", "gateway", "account", "install")

    /**
     * One gateway scope serves every thread on it, so a batch keyed by scope
     * used to merge two conversations typed into within the same debounce
     * window and deliver the aggregate to only one of them.
     */
    @Test fun `two conversations keep separate batches`() = runTest {
        val flushed = mutableListOf<Pair<String, List<String>>>()
        val batcher = DebounceBatcher(
            scope = this,
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

    private fun message(id: String, text: String): OutgoingMessage =
        OutgoingMessage(ClientMessageId(id), text, emptyList())

    @Test fun newlineByteSplitsTwo32KiBMembersBeforeTheyExceedTheWireLimit() = runTest {
        val flushed = mutableListOf<List<String>>()
        val batcher = DebounceBatcher(this) { _, _, messages -> flushed += messages.map { it.text } }
        batcher.offer(gatewayScope,"conv_a",message("m1","a".repeat(32 * 1024)))
        batcher.offer(gatewayScope,"conv_a",message("m2","b".repeat(32 * 1024)))
        advanceTimeBy(1500); runCurrent()
        assertEquals(listOf(1,1),flushed.map { it.size })
        flushed.forEach { org.junit.Assert.assertTrue(it.joinToString("\n").toByteArray().size <= 64 * 1024) }
    }

    @Test fun countsPriorSeparatorsAndUtf8BytesWhileAcceptingAnExactFit() = runTest {
        val flushed = mutableListOf<List<String>>()
        val batcher = DebounceBatcher(this,DebouncePolicy(maximumBytes=5)) { _, _, messages -> flushed += messages.map { it.text } }
        listOf("a","b","c").forEachIndexed { i,text -> batcher.offer(gatewayScope,"conv_a",message("m$i",text)) }
        advanceTimeBy(1500); runCurrent()
        assertEquals(listOf(listOf("a","b","c")),flushed)
        flushed.clear()
        listOf("é","a","b").forEachIndexed { i,text -> batcher.offer(gatewayScope,"conv_a",message("n$i",text)) }
        advanceTimeBy(1500); runCurrent()
        assertEquals(listOf(listOf("é","a"),listOf("b")),flushed)
    }
}
