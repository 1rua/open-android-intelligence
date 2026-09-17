package com.openandroidintelligence.conversation.workbench

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.openandroidintelligence.conversation.state.TimelineEntry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MessageTimelineStreamingTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun thinkingIndicatorRendersLabelAndDots() {
        compose.setContent {
            MaterialTheme {
                ThinkingIndicator()
            }
        }
        compose.onNodeWithText("AI 正在思考").assertIsDisplayed()
    }

    @Test
    fun assistantStreamingMessageRendersStreamingStatusAndContent() {
        val entry = TimelineEntry(
            key = "msg_stream",
            sender = "assistant",
            text = "正在处理中...",
            isUser = false,
            timestamp = 1000L,
            pendingAcceptance = false,
            batchGroupId = null,
            isStreaming = true,
        )
        compose.setContent {
            MaterialTheme {
                MessageTimeline(listOf(entry))
            }
        }
        compose.onNodeWithText("正在输出…").assertIsDisplayed()
        compose.onNodeWithText("正在处理中...", substring = true).assertIsDisplayed()
    }

    @Test
    fun assistantStreamingBlankMessageRendersCursorWithoutCrashing() {
        val entry = TimelineEntry(
            key = "msg_stream_empty",
            sender = "assistant",
            text = "",
            isUser = false,
            timestamp = 1000L,
            pendingAcceptance = false,
            batchGroupId = null,
            isStreaming = true,
        )
        compose.setContent {
            MaterialTheme {
                MessageTimeline(listOf(entry))
            }
        }
        compose.onNodeWithText("正在输出…").assertIsDisplayed()
    }

    @Test
    fun assistantStreamingCodeBlockRendersCodeAndStreamingStatus() {
        val entry = TimelineEntry(
            key = "msg_stream_code",
            sender = "assistant",
            text = "Here is code:\n```kotlin\nval x = 42\n```",
            isUser = false,
            timestamp = 1000L,
            pendingAcceptance = false,
            batchGroupId = null,
            isStreaming = true,
        )
        compose.setContent {
            MaterialTheme {
                MessageTimeline(listOf(entry))
            }
        }
        compose.onNodeWithText("正在输出…").assertIsDisplayed()
        compose.onNodeWithText("kotlin").assertIsDisplayed()
        compose.onNodeWithText("val x = 42", substring = true).assertIsDisplayed()
    }
}

