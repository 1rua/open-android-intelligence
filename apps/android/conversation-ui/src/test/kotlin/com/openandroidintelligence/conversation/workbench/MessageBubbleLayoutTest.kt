package com.openandroidintelligence.conversation.workbench

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.*
import com.openandroidintelligence.conversation.model.AttachmentLocalState
import com.openandroidintelligence.conversation.model.TimelineAttachment
import com.openandroidintelligence.conversation.ports.AgentMessageStatus
import com.openandroidintelligence.conversation.state.TimelineEntry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MessageBubbleLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun shortMessagesWrapIndependentlyOfLongMessagesAndMetadata() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp)) {
                    MessageTimeline(listOf(entry("short", "好"), entry("long", "很长的内容 ".repeat(24))))
                }
            }
        }
        val short = compose.onNodeWithTag("message-bubble:short").getUnclippedBoundsInRoot()
        val long = compose.onNodeWithTag("message-bubble:long").getUnclippedBoundsInRoot()
        assertTrue(short.width < long.width / 2)
        assertTrue(long.width.value <= 360f * 0.86f + 1)
        assertEquals(long.right.value, short.right.value, 1f)
    }

    @Test
    @Config(sdk = [31, 35], qualifiers = "w1280dp-h960dp-mdpi")
    fun tabletMessagesRetainTheAbsoluteWidthLimit() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(1000.dp)) {
                    MessageTimeline(listOf(entry("wide", "长消息 ".repeat(180)), entry("short", "好")))
                }
            }
        }
        compose.onNodeWithTag("message-bubble:wide").assertWidthIsEqualTo(640.dp)
        assertTrue(compose.onNodeWithTag("message-bubble:short").getUnclippedBoundsInRoot().width < 100.dp)
    }

    @Test fun streamedAssistantBubbleGrowsAndKeepsItsFinalWidth() {
        val text = mutableStateOf("好")
        val streaming = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp)) {
                    TimelineRow(entry("agent", text.value, user = false).copy(isStreaming = streaming.value), { _, _ -> })
                }
            }
        }
        fun bounds() = compose.onNodeWithTag("message-bubble:agent").getUnclippedBoundsInRoot()
        val initial = bounds()
        compose.runOnIdle { text.value = "这是一段逐渐增长的回复" }
        val grown = bounds()
        assertTrue(grown.width > initial.width)
        assertEquals(initial.left.value, grown.left.value, 0.5f)
        compose.runOnIdle { streaming.value = false }
        // Removing the small streaming cursor must not resize the whole bubble.
        assertEquals(grown.width.value, bounds().width.value, 8f)
    }

    @Test fun landscapePortraitAndExtremeImagesKeepTheirRatioWithinBothLimits() {
        val sizes = listOf(400 to 200, 200 to 400, 16 to 1600, 1600 to 16)
        val selected = mutableStateOf(0)
        val attachments = sizes.mapIndexed { index, (width, height) ->
            TimelineAttachment("image$index", "image$index.png", "image/png", previewBytes = png(width, height),
                localState = AttachmentLocalState.SAVED)
        }
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp)) {
                    TimelineRow(entry("image", "").copy(attachments = listOf(attachments[selected.value])), { _, _ -> })
                }
            }
        }
        sizes.forEachIndexed { index, (width, height) ->
            compose.runOnIdle { selected.value = index }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("attachment-image:image$index").fetchSemanticsNodes().isNotEmpty() }
            val bounds = compose.onNodeWithTag("attachment-image:image$index").getUnclippedBoundsInRoot()
            val ratio = width.toFloat() / height
            val expectedWidth = minOf(360f * 0.86f, 320f * ratio)
            assertEquals(expectedWidth, bounds.width.value, 1f)
            assertEquals(expectedWidth / ratio, bounds.height.value, 1f)
            assertTrue(bounds.width.value <= 360f * 0.86f + 1)
            assertTrue(bounds.height.value <= 321)
            compose.onNodeWithTag("message-bubble:image").assertDoesNotExist()
        }
    }

    @Test fun fileDownloadIsBelowTheCardBesideItsStatusAndRetainsTheAttachmentIdentity() {
        var downloaded: String? = null
        val caption = mutableStateOf("")
        val filename = "很长的附件文件名称".repeat(8) + ".zip"
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(300.dp)) {
                    TimelineRow(entry("file", caption.value).copy(attachments = listOf(
                        TimelineAttachment("file1", filename, "application/zip", localState = AttachmentLocalState.SAVED),
                    )), { _, _ -> }, onDownloadAttachment = { downloaded = it })
                }
            }
        }
        compose.onNodeWithTag("message-bubble:file").assertDoesNotExist()
        listOf("", "附件说明").forEach { text ->
            compose.runOnIdle { caption.value = text }
            val name = compose.onNodeWithText(filename).getUnclippedBoundsInRoot()
            val download = compose.onNodeWithText("下载").getUnclippedBoundsInRoot()
            val status = compose.onNodeWithText("Agent 已接收").getUnclippedBoundsInRoot()
            assertTrue(download.top >= name.bottom)
            assertEquals(((download.top + download.bottom) / 2).value, ((status.top + status.bottom) / 2).value, 1f)
        }
        compose.onNodeWithText("附件说明").assertIsDisplayed()
        compose.onNodeWithText("下载").performClick()
        assertEquals("file1", downloaded)
    }

    @Test fun mixedImageTextAndMarkdownCodeStayVisibleWithinTheMessageLimit() {
        compose.setContent {
            MaterialTheme {
                Box(Modifier.width(300.dp)) {
                    TimelineRow(entry("mixed", "说明\n```kotlin\n" + "val text = 1; ".repeat(50) +
                        "\n```\n\n> | 项目 | 状态 |\n> | --- | --- |\n> | 文件 | 正常 |", user = false)
                        .copy(attachments = listOf(TimelineAttachment("img", "preview.png", "image/png", previewBytes = png(400, 200)))),
                        { _, _ -> })
                }
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("attachment-image:img").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("说明").assertIsDisplayed()
        compose.onNodeWithContentDescription("复制代码").assertIsDisplayed()
        compose.onNodeWithText("项目").assertIsDisplayed()
        compose.onNodeWithText("正常").assertIsDisplayed()
        assertTrue(compose.onNodeWithTag("message-bubble:mixed").getUnclippedBoundsInRoot().width.value <= 259)
    }

    private fun entry(id: String, text: String, user: Boolean = true) = TimelineEntry(
        id, if (user) "user" else "assistant", text, user, 1, false, null,
        messageStatus = if (user) AgentMessageStatus.DELIVERED else null,
    )

    private fun png(width: Int, height: Int): ByteArray = ByteArrayOutputStream().use { output ->
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
        bitmap.recycle()
        output.toByteArray()
    }
}
