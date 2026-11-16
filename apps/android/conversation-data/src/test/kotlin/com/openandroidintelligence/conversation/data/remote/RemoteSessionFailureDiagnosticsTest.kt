package com.openandroidintelligence.conversation.data.remote

import com.openandroidintelligence.gateway.diagnostics.GatewayLog
import org.junit.Assert.*
import org.junit.Test

class RemoteSessionFailureDiagnosticsTest {
    @Test fun `失败诊断保留源码定位并排除正文凭据和绝对路径`() {
        val captured = mutableListOf<String>()
        val previous = GatewayLog.sink
        GatewayLog.sink = { tag, message -> if (tag == "OaiRemoteSession") captured += message }
        try {
            val failure = IllegalStateException("正文和token=不应出现")
            failure.stackTrace = arrayOf(StackTraceElement("RemoteTimelineProjection", "assistantStream", "/private/用户/RemoteTimelineProjection.kt", 239))
            logRemoteSessionFailure("harness", "stream", "REMOTE_ASSISTANT_STREAM_GAP", failure)
            logRemoteSessionFailure("harness", "terminal", "/private/用户/内部内容")
            assertEquals(2, captured.size)
            assertTrue(captured.first().contains("REMOTE_ASSISTANT_STREAM_GAP"))
            assertTrue(captured.first().contains("RemoteTimelineProjection.kt:239"))
            assertTrue(captured.last().contains("REMOTE_FAILURE"))
            assertFalse(captured.joinToString().contains("token"))
            assertFalse(captured.joinToString().contains("private"))
            assertFalse(captured.joinToString().contains("内部内容"))
            assertFalse(captured.joinToString().contains("正文"))
        } finally { GatewayLog.sink = previous }
    }
}
