package com.openandroidintelligence.conversation.model

import com.openandroidintelligence.conversation.ports.TimelineMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalSendAtomOrderTest {
    @Test
    fun equalTimestampsKeepNativeTurnOrderWhenMergingLocalState() {
        val messages = listOf(
            TimelineMessage("u1", "user", listOf(MessagePart.Text("第一问")), 1_000L),
            TimelineMessage("a1", "assistant", listOf(MessagePart.Text("第一答")), 1_000L),
            TimelineMessage("u2", "user", listOf(MessagePart.Text("第二问")), 1_000L),
            TimelineMessage("a2", "assistant", listOf(MessagePart.Text("第二答")), 1_000L),
        )

        assertEquals(listOf("u1", "a1", "u2", "a2"), mergeLocalSendAtoms(messages, emptyList()).map { it.id })
    }
}
