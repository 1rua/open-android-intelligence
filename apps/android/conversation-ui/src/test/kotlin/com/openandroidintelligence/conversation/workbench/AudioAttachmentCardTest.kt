package com.openandroidintelligence.conversation.workbench

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.openandroidintelligence.conversation.model.AttachmentLocalState
import com.openandroidintelligence.conversation.model.TimelineAttachment
import com.openandroidintelligence.conversation.state.TimelineEntry
import com.openandroidintelligence.conversation.voice.formatVoiceDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 语音附件在时间线里的呈现契约：
 * - 语音不是图片，不能进入大图预览，也不出现"图片附件"这类误导文案；
 * - 时长芯片只显示真实观察到的时长，没有就说没有，不用 0:00 冒充；
 * - 文件名、本地状态与下载入口与普通文件一致，语音不会因此丢掉可保存能力。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AudioAttachmentCardTest {

    @get:Rule val compose = createComposeRule()

    private fun voiceAttachment(durationMs: Long? = null) = TimelineAttachment(
        draftId = "att_voice",
        filename = "voice_1.m4a",
        mediaType = "audio/mp4",
        localState = AttachmentLocalState.SAVED,
        audioDurationMs = durationMs,
    )

    private fun render(attachment: TimelineAttachment, onDownload: (String) -> Unit = {}) {
        val entry = TimelineEntry(
            key = "message_voice",
            sender = "user",
            text = "",
            isUser = true,
            timestamp = 1L,
            pendingAcceptance = false,
            batchGroupId = null,
            attachments = listOf(attachment),
        )
        compose.setContent { MaterialTheme { MessageTimeline(listOf(entry), onDownloadAttachment = onDownload) } }
    }

    @Test
    fun voiceMessageIsRecognisedAsAudioAndNotAsAnImage() {
        val attachment = voiceAttachment()

        assertTrue(attachment.isAudio)
        assertFalse("语音不应走图片分支", attachment.isImage)
        assertTrue(TimelineAttachment("a", "record.dat", "Audio/MP4").isAudio)
        assertTrue(TimelineAttachment("b", "voice.m4a", "").isAudio)
        assertFalse(TimelineAttachment("c", "note.pdf", "application/pdf").isAudio)
    }

    @Test
    fun theChipShowsTheFilenameAndTheRealDuration() {
        render(voiceAttachment(durationMs = 65_000L))

        compose.onNodeWithText("voice_1.m4a").assertIsDisplayed()
        compose.onNodeWithText("1:05").assertIsDisplayed()
        compose.onNodeWithTag("attachment-audio-duration:att_voice").assertIsDisplayed()
    }

    @Test
    fun withoutAKnownDurationNoNumberIsInvented() {
        render(voiceAttachment(durationMs = null))

        compose.onNodeWithText("voice_1.m4a").assertIsDisplayed()
        compose.onNodeWithTag("attachment-audio-duration:att_voice").assertDoesNotExist()
        compose.onNodeWithText("0:00").assertDoesNotExist()
    }

    @Test
    fun aZeroRecordedDurationIsTreatedAsUnknown() {
        render(voiceAttachment(durationMs = 0L))

        compose.onNodeWithTag("attachment-audio-duration:att_voice").assertDoesNotExist()
    }

    @Test
    fun voiceNeverOffersTheImageLightboxEntry() {
        render(voiceAttachment(durationMs = 3_000L))

        compose.onNodeWithContentDescription("查看大图", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("attachment-image:att_voice", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun theDownloadActionStillUsesTheAttachmentIdentity() {
        var downloaded: String? = null
        render(voiceAttachment(durationMs = 3_000L), onDownload = { downloaded = it })

        compose.onNodeWithText("下载").performClick()

        assertEquals("att_voice", downloaded)
    }

    @Test
    fun durationsAreFormattedOnMinuteAndSecondBoundaries() {
        assertEquals("0:00", formatVoiceDuration(0L))
        assertEquals("0:07", formatVoiceDuration(7_000L))
        assertEquals("1:05", formatVoiceDuration(65_000L))
        assertEquals("10:00", formatVoiceDuration(600_000L))
        // 负数不应出现，按 0 秒处理而不是显示 "-1:59"。
        assertEquals("0:00", formatVoiceDuration(-1_000L))
    }
}
