package com.openandroidintelligence.conversation.ports

import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.model.NativeExecutionStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RemoteContentSanitizerCaseTest {
    @Test fun `系统提醒标签大小写混合且跨分片时仍清洗`() {
        assertEquals("回答", ConversationContentSanitizer.text("<SyStEm-ReMiNdEr>隐藏</sYsTeM-rEmInDeR>回答"))
        assertEquals("", ConversationContentSanitizer.text("<SyStEm-", streaming = true))
        assertEquals("", ConversationContentSanitizer.text("<SyStEm-ReMiNdEr>隐藏", streaming = true))
        assertEquals("回答", ConversationContentSanitizer.text("<SyStEm-ReMiNdEr>隐藏</sYsTeM-rEmInDeR>回答"))
    }

    @Test fun `系统角色即使携带执行步骤也不能成为会话正文`() {
        val system = TimelineMessage("system", "system", listOf(MessagePart.Text("内部系统上下文")), 1,
            executionSteps = listOf(NativeExecutionStep("step", "event", "tool", "{}")))
        assertNull(ConversationContentSanitizer.message(system))
        assertNull(ConversationContentSanitizer.message(system.copy(sender = "future-internal")))
    }

    @Test fun `助手执行步骤与明确删除身份继续保留`() {
        val assistant = TimelineMessage("assistant", "assistant", emptyList(), 1,
            executionSteps = listOf(NativeExecutionStep("step", "event", "tool", "{}")))
        assertEquals(assistant, ConversationContentSanitizer.message(assistant))
        val deleted = assistant.copy(sender = "system", state = "DELETED", executionSteps = emptyList())
        assertSame(deleted, ConversationContentSanitizer.message(deleted))
    }
}
