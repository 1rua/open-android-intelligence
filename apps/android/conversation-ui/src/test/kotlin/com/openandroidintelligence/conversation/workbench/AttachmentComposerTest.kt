package com.openandroidintelligence.conversation.workbench

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.openandroidintelligence.conversation.model.AttachmentDraft
import com.openandroidintelligence.conversation.model.AttachmentDraftId
import com.openandroidintelligence.conversation.model.AttachmentState
import com.openandroidintelligence.conversation.model.GenerationState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
// ModalBottomSheet hit coordinates depend on native window geometry.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AttachmentComposerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sharedComposerPreviewsAttachmentMetadataAndSendsAnAttachmentOnlyDraft() {
        var sends = 0
        render(draft(AttachmentState.VERIFIED), onSend = { sends++ })
        compose.onNodeWithText("气泡附件.pdf").assertIsDisplayed()
        compose.onNodeWithText("已核验，可发送").assertIsDisplayed()
        compose.onNodeWithContentDescription("发送").assertIsEnabled().performClick()
        assertEquals(1, sends)
    }

    @Test fun retryAndRemoveReachTheIdentityOfTheDisplayedAttachment() {
        var retry: String? = null
        var removed: String? = null
        render(draft(AttachmentState.RETRYABLE_FAILURE), onRetry = { retry = it }, onRemove = { removed = it })
        compose.onNodeWithText("上传失败，可重试").assertIsDisplayed()
        compose.onNodeWithContentDescription("重试上传").performClick()
        assertEquals("bubble-draft", retry)
        compose.onNodeWithContentDescription("移除附件").performClick()
        assertEquals("bubble-draft", removed)
    }

    @Test fun unknownUploadOffersVerificationAndRemoval() {
        render(draft(AttachmentState.OUTCOME_UNKNOWN))
        compose.onNodeWithText("上传结果未知，请先核实").assertIsDisplayed()
        compose.onNodeWithContentDescription("核实附件上传结果").assertIsDisplayed()
        compose.onNodeWithContentDescription("移除附件").assertIsDisplayed()
    }

    @Test fun offlineAttachmentOnlyDraftKeepsItsPreviewAndDisablesSend() {
        render(draft(AttachmentState.VERIFIED), canSend = false)
        compose.onNodeWithText("气泡附件.pdf").assertIsDisplayed()
        compose.onNodeWithContentDescription("发送").assertIsNotEnabled()
    }

    @Test fun everySystemPickerChoiceReachesTheHostCallback() = selectEverySystemPickerChoice()

    @Test
    @Config(qualifiers = "w320dp-h470dp-mdpi")
    fun compactPickerExpandsToReachEverySystemChoice() = selectEverySystemPickerChoice(expandSheet = true)

    private fun selectEverySystemPickerChoice(expandSheet: Boolean = false) {
        val selected = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                ComposerBar("", {}, GenerationState.IDLE, false, {}, {},
                    onPickCamera = { selected += "camera" }, onPickGallery = { selected += "gallery" },
                    onPickDocument = { selected += "document" }, onVoiceInput = {}, voiceInputEnabled = false)
            }
        }
        listOf("拍照", "相册选择", "文档选择").forEach { title ->
            compose.onNodeWithContentDescription("添加附件").performClick()
            // ModalBottomSheet is hosted in its own window and animates in. Wait for it to
            // become interactive before selecting an option, then let its dismiss transition
            // finish before reopening it for the next choice.
            compose.waitForIdle()
            if (expandSheet) {
                // Compact windows open a partial sheet; drag its visible surface up to expand it.
                compose.onNode(isDialog()).performTouchInput {
                    swipeUp(startY = height * 0.75f, endY = height * 0.15f)
                }
                compose.waitForIdle()
            }
            compose.onNodeWithText(title).assertIsDisplayed().performClick()
            compose.waitForIdle()
        }
        assertEquals(listOf("camera", "gallery", "document"), selected)
    }

    private fun render(
        attachment: AttachmentDraft,
        canSend: Boolean = true,
        onSend: () -> Unit = {},
        onRetry: (String) -> Unit = {},
        onRemove: (String) -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                ComposerBar("", {}, GenerationState.IDLE, canSend, onSend, {}, {}, {}, {}, {},
                    attachments = listOf(attachment), onRetryAttachment = onRetry, onRemoveAttachment = onRemove,
                    voiceInputEnabled = false)
            }
        }
    }

    private fun draft(state: AttachmentState) = AttachmentDraft(
        AttachmentDraftId("bubble-draft"), "气泡附件.pdf", "application/pdf", 3, "0".repeat(64), state,
    )
}
