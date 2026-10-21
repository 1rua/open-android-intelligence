package com.openandroidintelligence.conversation.workbench

import android.content.ClipboardManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.openandroidintelligence.conversation.state.TimelineEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 消息级交互：逐条复制、任务列表复选框与嵌套缩进在真实 Compose 布局中的表现。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MarkdownInteractionComposeTest {

    @get:Rule
    val compose = createComposeRule()

    private fun entry(
        key: String,
        text: String,
        isUser: Boolean,
    ) = TimelineEntry(
        key = key,
        sender = if (isUser) "user" else "assistant",
        text = text,
        isUser = isUser,
        timestamp = 1_700_000_000_000L,
        pendingAcceptance = false,
        batchGroupId = null,
    )

    @Test
    fun userMessageCopyButtonCopiesRawMarkdownAndShowsFeedback() {
        val raw = "**加粗** 与 [链接](https://example.com)"
        compose.setContent {
            MaterialTheme {
                MessageTimeline(listOf(entry("u1", raw, isUser = true)))
            }
        }

        compose.onNodeWithText("复制").assertIsDisplayed().performClick()
        compose.onNodeWithText("复制成功").assertIsDisplayed()

        val clipboard = RuntimeEnvironment.getApplication()
            .getSystemService(ClipboardManager::class.java)
        assertEquals(raw, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun assistantMessageCopyButtonIsPresentBelowContent() {
        val raw = "## 结论\n\n今天天气不错。"
        compose.setContent {
            MaterialTheme {
                MessageTimeline(listOf(entry("a1", raw, isUser = false)))
            }
        }

        compose.onNodeWithText("复制").assertIsDisplayed().performClick()
        compose.onNodeWithText("复制成功").assertIsDisplayed()

        val clipboard = RuntimeEnvironment.getApplication()
            .getSystemService(ClipboardManager::class.java)
        assertEquals(raw, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun taskListRendersCheckboxSemantics() {
        val markdown = """
            - [x] 已完成项
            - [ ] 未完成项
        """.trimIndent()

        compose.setContent {
            MaterialTheme {
                MarkdownDocumentView(parseMarkdownBlocks(markdown), isStreaming = false)
            }
        }

        compose.onNodeWithContentDescription("已完成").assertIsDisplayed()
        compose.onNodeWithContentDescription("未完成").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("已完成").assertCountEquals(1)
    }

    @Test
    fun nestedListItemsAreIndentedFurtherThanTheirParent() {
        val markdown = """
            - 一级项
              - 二级项
                - 三级项
        """.trimIndent()

        compose.setContent {
            MaterialTheme {
                MarkdownDocumentView(parseMarkdownBlocks(markdown), isStreaming = false)
            }
        }

        val firstLeft = compose.onNodeWithText("一级项").getBoundsInRoot().left
        val secondLeft = compose.onNodeWithText("二级项").getBoundsInRoot().left
        val thirdLeft = compose.onNodeWithText("三级项").getBoundsInRoot().left

        assertTrue("二级项应比一级项更靠右", secondLeft > firstLeft)
        assertTrue("三级项应比二级项更靠右", thirdLeft > secondLeft)
    }

    @Test
    fun nestedBlockquoteDoesNotLeakMarkerCharacters() {
        val markdown = """
            > 一级引用
            >> 二级引用
        """.trimIndent()

        compose.setContent {
            MaterialTheme {
                MarkdownDocumentView(parseMarkdownBlocks(markdown), isStreaming = false)
            }
        }

        compose.onNodeWithText("一级引用").assertIsDisplayed()
        compose.onNodeWithText("二级引用").assertIsDisplayed()
    }
}
