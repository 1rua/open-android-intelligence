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
    fun versionMismatchIsNamedAsSuchInsteadOfBeingReportedAsANetworkProblem() {
        // 2026-09-22 真机故障：App 与插件各算出的核心 Schema 摘要不同，Gateway 按契约 §4
        // 拒绝协商（406），而旧文案只给了兜底句，用户以为是自己网络不通。
        val mismatch = readableFailure("PROTOCOL_INCOMPATIBLE:406")
        assertTrue("版本不一致必须指向两端版本", mismatch.contains("版本"))
        assertTrue("必须给出可执行的下一步", mismatch.contains("升级") || mismatch.contains("重新登录"))

        val hostMismatch = readableFailure("HOST_INCOMPATIBLE:503")
        assertTrue("宿主不兼容必须指向 Agent 端", hostMismatch.contains("宿主") || hostMismatch.contains("插件"))

        val incompleteHandshake = readableFailure("NEGOTIATION_FAILED:400")
        assertTrue("协商失败必须点名协商", incompleteHandshake.contains("协商"))
    }

    @Test
    fun statusOnlyRefusalIsStillReportedAsAVersionMismatchButNotEveryOccurrenceOf406() {
        // 登录时协商已失效：`GatewayAuthClient` 在非 2xx 时只能回落到状态码，而契约把
        // 406 专属给 PROTOCOL_INCOMPATIBLE，所以这条路径也必须指向「两端版本不一致」。
        val loginRefusal = readableFailure("AUTHENTICATION_FAILED:406")
        assertTrue("只带状态码的拒绝必须指向版本", loginRefusal.contains("版本"))

        val bare = readableFailure("406")
        assertTrue("裸状态码也必须被识别", bare.contains("版本"))

        // 反面：恰好含这三个数字的标识不能被误判成版本问题，否则会给用户错误的升级指引。
        val unrelated = readableFailure("SEND_FAILED:406001", "兜底说明")
        assertEquals("兜底说明", unrelated)
    }

    @Test
    fun moreSpecificCodesWinOverTheNegotiationFailedPrefix() {
        // `NEGOTIATION_FAILED:` 前缀会包着更具体的原因：TLS 身份缺失必须命中它自己的说明，
        // 否则安全要求会被说成笼统的「协商失败」，用户会去重试而不是检查证书。
        val missingIdentity = readableFailure("NEGOTIATION_FAILED:missing-tls-identity")
        assertTrue("必须命中 TLS 身份说明", missingIdentity.contains("TLS") || missingIdentity.contains("身份"))
        assertTrue("不能被泛化的协商失败说明覆盖", !missingIdentity.contains("没有完成协议协商"))
    }

    @Test
    fun callersCanSupplyTheirOwnFallbackWithoutLosingKnownCodes() {
        val connectionFallback = "无法连接 Gateway。请检查地址与网络后重试。"
        assertEquals(
            "未知码必须使用调用方给的兜底文案",
            connectionFallback,
            readableFailure("SOME_UNKNOWN_GATEWAY_INTERNAL_ERROR", connectionFallback),
        )
        assertEquals(
            "已知码不受自定义兜底影响",
            readableFailure("CURSOR_EXPIRED"),
            readableFailure("CURSOR_EXPIRED", connectionFallback),
        )
        assertEquals(
            "默认兜底仍是内容加载场景的说明",
            CONTENT_FAILURE_FALLBACK,
            readableFailure("SOME_UNKNOWN_GATEWAY_INTERNAL_ERROR"),
        )
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
