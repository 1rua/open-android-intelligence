package com.openandroidintelligence.conversation.ports

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteContentSanitizerCaseTest {
    @Test fun `系统提醒标签大小写混合且跨分片时仍清洗`() {
        assertEquals("回答", ConversationContentSanitizer.text("<SyStEm-ReMiNdEr>隐藏</sYsTeM-rEmInDeR>回答"))
        assertEquals("", ConversationContentSanitizer.text("<SyStEm-", streaming = true))
        assertEquals("", ConversationContentSanitizer.text("<SyStEm-ReMiNdEr>隐藏", streaming = true))
        assertEquals("回答", ConversationContentSanitizer.text("<SyStEm-ReMiNdEr>隐藏</sYsTeM-rEmInDeR>回答"))
    }
}
