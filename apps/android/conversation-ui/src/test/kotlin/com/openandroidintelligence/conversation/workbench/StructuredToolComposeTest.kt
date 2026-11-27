package com.openandroidintelligence.conversation.workbench

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.openandroidintelligence.conversation.model.NativeExecutionStatus
import com.openandroidintelligence.conversation.state.TimelineEntry
import com.openandroidintelligence.gateway.diagnostics.GatewayLog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StructuredToolComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun completedNativeResultIsNotReportedAsExecutionSuccess() {
        compose.setContent {
            MaterialTheme {
                MarkdownToolCallView(TimelineBlock.ToolCallBlock("read", "{}", "result",
                    executionStatus = NativeExecutionStatus.COMPLETED))
            }
        }
        compose.onNodeWithText("已完成").assertIsDisplayed()
        compose.onNodeWithText("执行成功").assertDoesNotExist()
    }

    @Test
    fun missingNativeResultRemainsUnknownInsteadOfCompletedOrFailed() {
        compose.setContent {
            MaterialTheme {
                MarkdownToolCallView(TimelineBlock.ToolCallBlock("read", "{}",
                    executionStatus = NativeExecutionStatus.UNKNOWN))
            }
        }
        compose.onNodeWithText("结果未知").assertIsDisplayed()
        compose.onNodeWithText("执行成功").assertDoesNotExist()
        compose.onNodeWithText("执行失败").assertDoesNotExist()
    }

    @Test
    fun compositionEvidenceRecordsFirstAndSettledBoundariesWithoutTokenLogsOrBody() {
        val previousEnabled = GatewayLog.protocolEvidenceEnabled
        val previousSink = GatewayLog.sink
        val logs = mutableListOf<String>()
        GatewayLog.protocolEvidenceEnabled = true
        GatewayLog.sink = { tag, message -> if (tag == "OaiE2E") logs += message }
        try {
            val current = mutableStateOf(TimelineEntry("native-message", "assistant", "private synthetic one", false,
                0L, false, null, isStreaming = true, generationId = "generation"))
            val timing = ExecutionTimingStore { 0L }
            compose.setContent {
                MaterialTheme {
                    TimelineDisplayRow(TimelineDisplayItem.Single(current.value), { _, _ -> }, timing)
                }
            }
            compose.waitForIdle()
            assertEquals(1, logs.count { "T9.compose_applied" in it })
            compose.runOnIdle { current.value = current.value.copy(text = "private synthetic two") }
            compose.waitForIdle()
            assertEquals(1, logs.count { "T9.compose_applied" in it })
            compose.runOnIdle { current.value = current.value.copy(isStreaming = false) }
            compose.waitForIdle()
            assertEquals(2, logs.count { "T9.compose_applied" in it })
            assertFalse(logs.any { "private synthetic" in it })
        } finally {
            GatewayLog.protocolEvidenceEnabled = previousEnabled
            GatewayLog.sink = previousSink
        }
    }
}
