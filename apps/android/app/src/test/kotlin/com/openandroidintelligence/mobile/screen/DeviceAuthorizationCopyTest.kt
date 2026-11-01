package com.openandroidintelligence.mobile.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAuthorizationCopyTest {
    @Test fun queryConfirmationNeverCallsSmsOrNotificationsASendOperation() {
        assertEquals("读取短信", deviceRequestAction("org.openandroidintelligence.sms.query@1.0.0", "query", "{}"))
        assertEquals("读取通知", deviceRequestAction("org.openandroidintelligence.notifications.query@1.0.0", "query", "{}"))
        assertEquals("读取通话记录", deviceRequestAction("org.openandroidintelligence.call-log.query@1.0.0", "query", "{}"))
    }

    @Test fun parametersShowTheActualSenderAndTimeScopeWithoutInventingFilters() {
        val lines = deviceRequestParameterLines("""{"senders":["TEST-SENDER"],"receivedAfter":"2026-11-01T00:00:00Z","limit":2}""")
        assertEquals(listOf("短信发送方：[\"TEST-SENDER\"]", "起始时间：2026-11-01T00:00:00Z", "最多读取条数：2"), lines)
        assertTrue(deviceRequestParameterLines("{}").single().contains("未指定额外筛选参数"))
    }

    @Test fun defaultDetailsDoNotEchoInputTextOrInternalScreenReferences() {
        val lines = deviceRequestParameterLines("""{"nodeId":"internal-node","expectedRevision":"revision-secret","text":"TEST-TEXT"}""")
        val rendered = lines.joinToString("\n")
        assertFalse(rendered.contains("internal-node"))
        assertFalse(rendered.contains("revision-secret"))
        assertFalse(rendered.contains("TEST-TEXT"))
        assertTrue(rendered.contains("9 字符"))
    }

    @Test fun unsupportedOrOversizedParametersAreExplicitAndBounded() {
        assertTrue(deviceRequestParameterLines("not-json").single().contains("参数无法解析"))
        assertTrue(deviceRequestParameterLines("""{"query":"${"x".repeat(200)}"}""").single().endsWith("完整内容见技术详情）"))
    }
}
