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
class MarkdownRenderingComposeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun tableRendersHeadersAndCellsInCompose() {
        val markdownTable = """
            | 服务项 | 状态 | 延迟 |
            | :--- | :---: | ---: |
            | 网关链路 | 在线 | 12ms |
            | 存储引擎 | 正常 | 4ms |
        """.trimIndent()

        val entry = TimelineEntry(
            key = "msg_table",
            sender = "assistant",
            text = markdownTable,
            isUser = false,
            timestamp = 1000L,
            pendingAcceptance = false,
            batchGroupId = null,
            isStreaming = false,
        )

        compose.setContent {
            MaterialTheme {
                MessageTimeline(listOf(entry))
            }
        }

        compose.onNodeWithText("服务项", substring = true).assertIsDisplayed()
        compose.onNodeWithText("状态", substring = true).assertIsDisplayed()
        compose.onNodeWithText("延迟", substring = true).assertIsDisplayed()
        compose.onNodeWithText("网关链路", substring = true).assertIsDisplayed()
        compose.onNodeWithText("在线", substring = true).assertIsDisplayed()
        compose.onNodeWithText("12ms", substring = true).assertIsDisplayed()
    }

    @Test
    fun headingRendersTextInCompose() {
        val markdownHeadings = """
            # 遥测总览
            ## 子系统健康度
            ### 探针明细
        """.trimIndent()

        val entry = TimelineEntry(
            key = "msg_heading",
            sender = "assistant",
            text = markdownHeadings,
            isUser = false,
            timestamp = 1000L,
            pendingAcceptance = false,
            batchGroupId = null,
            isStreaming = false,
        )

        compose.setContent {
            MaterialTheme {
                MessageTimeline(listOf(entry))
            }
        }

        compose.onNodeWithText("遥测总览", substring = true).assertIsDisplayed()
        compose.onNodeWithText("子系统健康度", substring = true).assertIsDisplayed()
        compose.onNodeWithText("探针明细", substring = true).assertIsDisplayed()
    }

    @Test
    fun blockquoteRendersTextInCompose() {
        val markdownQuote = "> 系统处于全模态就绪状态，未发现协议异常。"

        val entry = TimelineEntry(
            key = "msg_quote",
            sender = "assistant",
            text = markdownQuote,
            isUser = false,
            timestamp = 1000L,
            pendingAcceptance = false,
            batchGroupId = null,
            isStreaming = false,
        )

        compose.setContent {
            MaterialTheme {
                MessageTimeline(listOf(entry))
            }
        }

        compose.onNodeWithText("系统处于全模态就绪状态", substring = true).assertIsDisplayed()
    }

    @Test
    fun listsRenderItemsInCompose() {
        val markdownLists = """
            - 动态取色
            - 双工管道

            1. 第一阶段
            2. 第二阶段
        """.trimIndent()

        val entry = TimelineEntry(
            key = "msg_lists",
            sender = "assistant",
            text = markdownLists,
            isUser = false,
            timestamp = 1000L,
            pendingAcceptance = false,
            batchGroupId = null,
            isStreaming = false,
        )

        compose.setContent {
            MaterialTheme {
                MessageTimeline(listOf(entry))
            }
        }

        compose.onNodeWithText("动态取色", substring = true).assertIsDisplayed()
        compose.onNodeWithText("双工管道", substring = true).assertIsDisplayed()
        compose.onNodeWithText("第一阶段", substring = true).assertIsDisplayed()
        compose.onNodeWithText("第二阶段", substring = true).assertIsDisplayed()
    }

    @Test
    fun streamingComplexMarkdownRendersStreamingStatusAndContent() {
        val fullMarkdown = """
            ### 🔍 代码审查与架构规范验证报告

            - **跨 App 临时覆盖**：具备独立浮层。

            > 系统处于全模态就绪状态。

            ```kotlin
            fun test() = true
            ```
        """.trimIndent()

        val entry = TimelineEntry(
            key = "msg_stream_full",
            sender = "assistant",
            text = fullMarkdown,
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
        compose.onNodeWithText("代码审查与架构规范验证报告", substring = true).assertIsDisplayed()
        compose.onNodeWithText("跨 App 临时覆盖", substring = true).assertIsDisplayed()
        compose.onNodeWithText("系统处于全模态就绪状态", substring = true).assertIsDisplayed()
        compose.onNodeWithText("kotlin").assertIsDisplayed()
        compose.onNodeWithText("fun test() = true", substring = true).assertIsDisplayed()
    }
}

