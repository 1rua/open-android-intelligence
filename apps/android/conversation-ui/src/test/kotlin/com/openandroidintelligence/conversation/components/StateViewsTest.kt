package com.openandroidintelligence.conversation.components

import com.openandroidintelligence.conversation.model.AttachmentState
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.workbench.attachmentStateLabel
import com.openandroidintelligence.conversation.workbench.formatAttachmentSize
import com.openandroidintelligence.conversation.workbench.generationLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StateViewsTest {

    @Test
    fun readableFailureMapsAllProtocolErrorCodesToHumanActionableGuidance() {
        val cursorExpired = readableFailure("CURSOR_EXPIRED:evt_123")
        assertTrue("CURSOR_EXPIRED 必须提示刷新重新同步", cursorExpired.contains("重新同步") || cursorExpired.contains("过期"))

        val outcomeUnknown = readableFailure("OUTCOME_UNKNOWN")
        assertTrue("OUTCOME_UNKNOWN 必须提醒避免重复提交", outcomeUnknown.contains("核实") || outcomeUnknown.contains("重复"))

        val conflict = readableFailure("IDEMPOTENCY_CONFLICT:409")
        assertTrue("409冲突必须提示核实结果", conflict.contains("冲突") || conflict.contains("核实"))

        val unauth = readableFailure("UNAUTHORIZED_401")
        assertTrue("未授权必须引导重新登录", unauth.contains("重新登录") || unauth.contains("凭据"))

        val forbidden = readableFailure("FORBIDDEN:REVOKED")
        assertTrue("禁止访问必须提示权限", forbidden.contains("权限"))

        val ssl = readableFailure("SSLHandshakeException: TLS pin mismatch")
        assertTrue("证书错误必须提示安全连接与证书", ssl.contains("证书") || ssl.contains("安全连接"))

        val tooLarge = readableFailure("ATTACHMENT_TOO_LARGE:413")
        assertTrue("过大文件必须提示较小文件", tooLarge.contains("较小") || tooLarge.contains("限制"))

        val rateLimit = readableFailure("RATE_LIMIT_EXCEEDED:429")
        assertTrue("限频必须提示稍后重试", rateLimit.contains("频繁") || rateLimit.contains("稍后"))

        val timeout = readableFailure("SocketTimeoutException: timed out")
        assertTrue("超时必须提示检查网络", timeout.contains("超时") || timeout.contains("网络"))

        val fallback = readableFailure("SOME_UNKNOWN_GATEWAY_INTERNAL_ERROR")
        assertTrue("兜底文案必须友好", fallback.contains("暂时无法") || fallback.contains("重试"))

        val emptyCode = readableFailure("")
        assertTrue("空错误码必须命中友好兜底", emptyCode.contains("暂时无法") || emptyCode.contains("重试"))
    }

    @Test
    fun allNineAttachmentStatesHaveExplicitDistinctLabels() {
        val states = AttachmentState.values()
        assertEquals(9, states.size)
        val labels = states.map { attachmentStateLabel(it) }
        states.forEach { state ->
            val label = attachmentStateLabel(state)
            assertTrue("附件状态 $state 必须有具体中文说明", label.isNotBlank())
        }
        assertEquals("所有状态标签必须明确独立", states.size, labels.toSet().size)
    }

    @Test
    fun generationStatesHaveClearExplanationsAndNoFabricatedTerminalState() {
        assertNull(generationLabel(GenerationState.IDLE))
        assertNull(generationLabel(GenerationState.COMPLETED))
        assertEquals("消息已接收，等待回复", generationLabel(GenerationState.QUEUED))
        assertEquals("正在接收回复", generationLabel(GenerationState.RUNNING))
        assertEquals("正在请求停止，等待 Gateway 确认", generationLabel(GenerationState.CANCEL_REQUESTED))
        assertEquals("本次生成已停止", generationLabel(GenerationState.CANCELLED))
        assertEquals("本次生成失败，请检查连接后重试", generationLabel(GenerationState.FAILED))
        assertEquals("当前 Gateway 不支持停止生成", generationLabel(GenerationState.UNSUPPORTED))
        assertEquals("生成结果尚未确认，请先刷新会话核实，避免重复发送", generationLabel(GenerationState.OUTCOME_UNKNOWN))
    }

    @Test
    fun formatAttachmentSizeHandlesBoundaryValues() {
        assertEquals("0 B", formatAttachmentSize(0))
        assertEquals("512 B", formatAttachmentSize(512))
        assertEquals("1023 B", formatAttachmentSize(1023))
        assertEquals("1 KB", formatAttachmentSize(1024))
        assertEquals("1023 KB", formatAttachmentSize(1024 * 1024 - 1))
        assertTrue(formatAttachmentSize(1024 * 1024).contains("1.0 MB"))
        assertTrue(formatAttachmentSize(5 * 1024 * 1024).contains("5.0 MB"))
    }
}
