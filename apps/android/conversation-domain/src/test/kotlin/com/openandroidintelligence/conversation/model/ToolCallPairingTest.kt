package com.openandroidintelligence.conversation.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCallPairingTest {

    @Test
    fun `normalizeCallId裁剪空白并拒绝空串`() {
        assertNull(ToolCallPairing.normalizeCallId(null))
        assertNull(ToolCallPairing.normalizeCallId(""))
        assertNull(ToolCallPairing.normalizeCallId("   \t\n "))
        assertEquals("call_123", ToolCallPairing.normalizeCallId("  call_123  "))
    }

    @Test
    fun `isPlaceholderToolName识别空名和tool占位符且不误杀真实工具名`() {
        assertTrue(ToolCallPairing.isPlaceholderToolName(null))
        assertTrue(ToolCallPairing.isPlaceholderToolName(""))
        assertTrue(ToolCallPairing.isPlaceholderToolName("   "))
        assertTrue(ToolCallPairing.isPlaceholderToolName("tool"))
        assertTrue(ToolCallPairing.isPlaceholderToolName("TOOL"))
        assertFalse(ToolCallPairing.isPlaceholderToolName("bash"))
        assertFalse(ToolCallPairing.isPlaceholderToolName("read_file"))
        assertFalse(ToolCallPairing.isPlaceholderToolName("commandExecution"))
    }

    @Test
    fun `isProvisionalGenerationId仅匹配临时生成标识`() {
        assertTrue(ToolCallPairing.isProvisionalGenerationId("request:req-1"))
        assertTrue(ToolCallPairing.isProvisionalGenerationId("remote-execution:sess-1"))
        assertTrue(ToolCallPairing.isProvisionalGenerationId("history-turn:sess-1"))
        assertFalse(ToolCallPairing.isProvisionalGenerationId("turn-1"))
        assertFalse(ToolCallPairing.isProvisionalGenerationId("g-one"))
        assertFalse(ToolCallPairing.isProvisionalGenerationId(null))
    }

    @Test
    fun `mergeWith与applyNewerSnapshot遵循非破坏性合并且允许UNKNOWN升级为终态`() {
        val initial = NativeExecutionStep(
            stepId = "call_1",
            eventId = "ev_1",
            toolName = "bash",
            arguments = """{"cmd":"ls -la"}""",
            output = null,
            status = NativeExecutionStatus.RUNNING,
            revision = 1L,
            sourceMessageId = "msg-1",
            occurredAt = 1_000L,
            orderSequence = 5L,
        )

        // 1. 携带占位名、空参数与结果的快照：不得破坏已有有效工具名、参数、宿主与顺序
        val resultSnapshot = NativeExecutionStep(
            stepId = "call_1",
            eventId = "ev_2",
            toolName = "tool",
            arguments = "",
            output = "total 8",
            status = NativeExecutionStatus.COMPLETED,
            revision = 2L,
            sourceMessageId = null,
            occurredAt = 2_000L,
            orderSequence = 9L,
        )
        val merged = initial.applyNewerSnapshot(resultSnapshot)
        assertEquals("bash", merged.toolName)
        assertEquals("""{"cmd":"ls -la"}""", merged.arguments)
        assertEquals("total 8", merged.output)
        assertEquals(NativeExecutionStatus.COMPLETED, merged.status)
        assertEquals("msg-1", merged.sourceMessageId)
        assertEquals(1_000L, merged.occurredAt)
        assertEquals(5L, merged.orderSequence)

        // 2. 已完成步骤不能被后续无输出 RUNNING/UNKNOWN 快照降级或抹除输出
        val downgrade = NativeExecutionStep(
            stepId = "call_1",
            eventId = "ev_3",
            toolName = "tool",
            arguments = "",
            output = null,
            status = NativeExecutionStatus.RUNNING,
            revision = 3L,
        )
        val preserved = merged.mergeWith(downgrade)
        assertEquals(NativeExecutionStatus.COMPLETED, preserved.status)
        assertEquals("total 8", preserved.output)
        assertEquals("bash", preserved.toolName)

        // 3. UNKNOWN（未返回结果）在迟到结果到达时可升级为 COMPLETED / FAILED
        val unknownStep = initial.copy(status = NativeExecutionStatus.UNKNOWN, revision = 2L)
        val lateFailure = NativeExecutionStep(
            stepId = "call_1",
            eventId = "ev_4",
            toolName = "tool",
            arguments = "",
            output = "permission denied",
            status = NativeExecutionStatus.FAILED,
            revision = 3L,
        )
        val upgraded = unknownStep.applyNewerSnapshot(lateFailure)
        assertEquals(NativeExecutionStatus.FAILED, upgraded.status)
        assertEquals("permission denied", upgraded.output)
        assertEquals("bash", upgraded.toolName)
    }
}
