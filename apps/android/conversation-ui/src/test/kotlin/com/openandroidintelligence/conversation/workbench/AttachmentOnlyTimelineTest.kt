package com.openandroidintelligence.conversation.workbench

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsNotEnabled
import com.openandroidintelligence.conversation.model.AttachmentLocalState
import com.openandroidintelligence.conversation.model.TimelineAttachment
import com.openandroidintelligence.conversation.state.TimelineEntry
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AttachmentOnlyTimelineTest {
    @get:Rule val compose = createComposeRule()

    @Test fun attachmentOnlyAgentMessageShowsItsFileWithoutEmptyTextPlaceholder() {
        render("att_agent", false)
        compose.onNodeWithText("原件.pdf").assertIsDisplayed()
        compose.onNodeWithText("此消息没有可显示的文本").assertDoesNotExist()
    }

    @Test fun historicalAttachmentOnlyUserMessageDoesNotBecomeAnEmptyTextMessage() {
        render("media_user", true)
        compose.onNodeWithText("原件.pdf").assertIsDisplayed()
        compose.onNodeWithText("此消息没有可显示的文本").assertDoesNotExist()
    }

    @Test fun downloadUsesTheAttachmentIdentityAndHasNoOfflineRetentionConfirmation() {
        var downloaded: String? = null
        val entry = TimelineEntry("message", "assistant", "", false, 1L, false, null,
            attachments = listOf(TimelineAttachment("att_download", "原件.pdf", "application/pdf", localState = AttachmentLocalState.SAVED)))
        compose.setContent { MaterialTheme { MessageTimeline(listOf(entry), onDownloadAttachment = { downloaded = it }) } }
        compose.onNodeWithText("下载").performClick()
        assertEquals("att_download", downloaded)
        compose.onNodeWithText("保留离线副本").assertDoesNotExist()
        compose.onNodeWithText("已保留加密离线副本").assertDoesNotExist()
    }

    @Test fun unavailableOriginalKeepsItsMetadataAndDisablesDownloading() {
        val entry = TimelineEntry("message", "assistant", "", false, 1L, false, null,
            attachments = listOf(TimelineAttachment("att_missing", "原件.pdf", "application/pdf", localState = AttachmentLocalState.UNAVAILABLE)))
        compose.setContent { MaterialTheme { MessageTimeline(listOf(entry), onDownloadAttachment = {}) } }
        compose.onNodeWithText("原件.pdf").assertIsDisplayed()
        compose.onNodeWithText("原件不可用，附件信息仍保留").assertIsDisplayed()
        compose.onNodeWithText("下载").assertIsNotEnabled()
        compose.onNodeWithText("此消息没有可显示的文本").assertDoesNotExist()
    }

    private fun render(id: String, user: Boolean) {
        val entry = TimelineEntry("message", if (user) "user" else "assistant", "", user, 1L,
            false, null, attachments = listOf(TimelineAttachment(id, "原件.pdf", "application/pdf")))
        compose.setContent { MaterialTheme { MessageTimeline(listOf(entry)) } }
    }
}
