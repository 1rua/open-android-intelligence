package com.openandroidintelligence.conversation.workbench

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-420dpi")
class ComposerBarSendStopTest {
    @get:Rule val compose = createComposeRule()

    @Test fun idleComposerWithoutInputShowsDisabledSendAction() {
        render(generation = GenerationState.IDLE)

        compose.onNodeWithContentDescription("发送").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test fun runningWithoutInputShowsStopAndCallsOnlyStop() {
        var sends = 0
        var stops = 0
        render(
            generation = GenerationState.RUNNING,
            onSend = { sends++ },
            onStop = { stops++ },
        )

        compose.onNodeWithContentDescription("停止生成").assertIsEnabled().performClick()

        assertEquals(0, sends)
        assertEquals(1, stops)
    }

    @Test fun queuedWithoutInputAlsoShowsStop() {
        var stops = 0
        render(
            generation = GenerationState.QUEUED,
            onStop = { stops++ },
        )

        compose.onNodeWithContentDescription("停止生成").assertIsEnabled().performClick()

        assertEquals(1, stops)
    }

    @Test fun runningWithTextShowsSendAndDoesNotCancelCurrentGeneration() {
        var sends = 0
        var stops = 0
        render(
            draft = "继续补充",
            generation = GenerationState.RUNNING,
            canSend = true,
            onSend = { sends++ },
            onStop = { stops++ },
        )

        compose.onNodeWithContentDescription("发送").assertIsEnabled().performClick()

        assertEquals(1, sends)
        assertEquals(0, stops)
    }

    @Test fun queuedGenerationWithTextShowsSendAction() {
        var sends = 0
        var stops = 0
        render(
            draft = "补充说明",
            generation = GenerationState.QUEUED,
            canSend = true,
            onSend = { sends++ },
            onStop = { stops++ },
        )

        compose.onNodeWithContentDescription("发送").assertIsEnabled().performClick()

        assertEquals(1, sends)
        assertEquals(0, stops)
    }

    @Test fun queuedGenerationWithAttachmentShowsSendAction() {
        var sends = 0
        var stops = 0
        render(
            generation = GenerationState.QUEUED,
            canSend = true,
            attachments = listOf(verifiedAttachment()),
            onSend = { sends++ },
            onStop = { stops++ },
        )

        compose.onNodeWithContentDescription("发送").assertIsEnabled().performClick()

        assertEquals(1, sends)
        assertEquals(0, stops)
    }

    @Test fun cancelUnknownAndSubmittingStatesKeepSendSafetyGates() {
        var sends = 0
        var draft by mutableStateOf("稍后发送")
        var generation by mutableStateOf(GenerationState.CANCEL_REQUESTED)
        var canSend by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                ComposerBar(
                    draft = draft,
                    onDraftChange = {},
                    generation = generation,
                    canSend = canSend,
                    onSend = { sends++ },
                    onStop = {},
                    onPickCamera = {},
                    onPickGallery = {},
                    onPickDocument = {},
                    onVoiceInput = {},
                    voiceInputEnabled = false,
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("等待取消确认").assertIsNotEnabled()

        compose.runOnIdle {
            draft = "结果未确认"
            generation = GenerationState.OUTCOME_UNKNOWN
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("发送").assertIsNotEnabled()

        compose.runOnIdle {
            draft = "正在提交"
            generation = GenerationState.RUNNING
            canSend = false
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("发送").assertIsNotEnabled()
        assertEquals(0, sends)
    }

    private fun render(
        draft: String = "",
        generation: GenerationState,
        canSend: Boolean = false,
        attachments: List<AttachmentDraft> = emptyList(),
        onSend: () -> Unit = {},
        onStop: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                ComposerBar(
                    draft = draft,
                    onDraftChange = {},
                    generation = generation,
                    canSend = canSend,
                    onSend = onSend,
                    onStop = onStop,
                    onPickCamera = {},
                    onPickGallery = {},
                    onPickDocument = {},
                    onVoiceInput = {},
                    attachments = attachments,
                    voiceInputEnabled = false,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun verifiedAttachment() = AttachmentDraft(
        id = AttachmentDraftId("active-run-attachment"),
        filename = "报告.pdf",
        mediaType = "application/pdf",
        sizeBytes = 3,
        sha256 = "0".repeat(64),
        state = AttachmentState.VERIFIED,
    )
}
