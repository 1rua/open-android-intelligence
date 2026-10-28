package com.openandroidintelligence.conversation.workbench

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
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

    @Test fun choosingQueuePreservesTheDraftAndWaitsForTheSendAction() {
        val original = " /host-command 'a b'\n 保留末尾空白  "
        var draft by mutableStateOf(original)
        var queued = 0
        var steered = 0
        var ordinary = 0
        compose.setContent {
            MaterialTheme {
                ComposerBar(
                    draft = draft,
                    onDraftChange = { draft = it },
                    generation = GenerationState.RUNNING,
                    canSend = true,
                    onSend = { ordinary++ },
                    onQueue = { queued++; draft = "" },
                    onSteer = { steered++; draft = "" },
                    onStop = {}, onPickCamera = {}, onPickGallery = {}, onPickDocument = {}, onVoiceInput = {},
                )
            }
        }

        compose.onNodeWithContentDescription("排队等待当前任务完成").performClick()
        assertEquals("选择模式不得提交消息", 0, queued + steered + ordinary)
        assertEquals(original, draft)
        compose.onNode(hasSetTextAction()).assertTextEquals(original)
        compose.onNodeWithContentDescription("排队等待当前任务完成").assertIsSelected()
        compose.onNodeWithContentDescription("发送").assertIsEnabled().performClick()
        assertEquals(1, queued)
        assertEquals(0, steered + ordinary)
    }

    @Test fun queuedGenerationCanSelectSteeringWithoutAnAdvertisedCapability() {
        var queued = 0
        var steered = 0
        compose.setContent {
            MaterialTheme {
                ComposerBar(
                    draft = "继续处理当前任务",
                    onDraftChange = {},
                    generation = GenerationState.QUEUED,
                    canSend = true,
                    onSend = {},
                    onQueue = { queued++ },
                    onSteer = { steered++ },
                    onStop = {}, onPickCamera = {}, onPickGallery = {}, onPickDocument = {}, onVoiceInput = {},
                )
            }
        }

        compose.onNodeWithContentDescription("引导当前任务").assertIsEnabled().assertIsSelected()
        compose.onNodeWithContentDescription("排队等待当前任务完成").performClick()
        compose.onNodeWithContentDescription("引导当前任务").performClick().assertIsSelected()
        assertEquals(0, queued + steered)
        compose.onNodeWithContentDescription("发送").assertIsEnabled().performClick()
        assertEquals(1, steered)
        assertEquals(0, queued)
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

    @Test fun runningWithTextCanSelectQueueAndSendWithoutCancellingCurrentGeneration() {
        var sends = 0
        var stops = 0
        render(
            draft = "继续补充",
            generation = GenerationState.RUNNING,
            canSend = true,
            onSend = { sends++ },
            onStop = { stops++ },
        )

        compose.onNodeWithContentDescription("发送").assertIsEnabled()
        compose.onNodeWithContentDescription("排队等待当前任务完成").assertIsEnabled().performClick()
        assertEquals(0, sends)
        compose.onNodeWithContentDescription("发送").performClick()

        assertEquals(1, sends)
        assertEquals(0, stops)
    }

    @Test fun queuedGenerationWithTextWaitsForSendAfterChoosingQueue() {
        var sends = 0
        var stops = 0
        render(
            draft = "补充说明",
            generation = GenerationState.QUEUED,
            canSend = true,
            onSend = { sends++ },
            onStop = { stops++ },
        )

        compose.onNodeWithContentDescription("发送").assertIsEnabled()
        compose.onNodeWithContentDescription("排队等待当前任务完成").assertIsEnabled().performClick()
        assertEquals(0, sends)
        compose.onNodeWithContentDescription("发送").performClick()

        assertEquals(1, sends)
        assertEquals(0, stops)
    }

    @Test fun queuedGenerationWithAttachmentKeepsBothModesSelectableAndWaitsForSend() {
        var sends = 0
        var stops = 0
        render(
            generation = GenerationState.QUEUED,
            canSend = true,
            attachments = listOf(verifiedAttachment()),
            onSend = { sends++ },
            onStop = { stops++ },
        )

        compose.onNodeWithContentDescription("发送").assertIsEnabled()
        compose.onNodeWithContentDescription("引导当前任务").assertIsEnabled()
        compose.onNodeWithContentDescription("排队等待当前任务完成").assertIsEnabled().performClick()
        assertEquals(0, sends)
        compose.onNodeWithContentDescription("发送").performClick()

        assertEquals(1, sends)
        assertEquals(0, stops)
    }

    @Test fun submittedAttachmentDoesNotCountAsPendingInputDuringActiveGeneration() {
        var sends = 0
        var stops = 0
        var removals = 0
        val attachment = verifiedAttachment()
        render(
            generation = GenerationState.RUNNING,
            canSend = false,
            attachments = listOf(attachment),
            inFlightAttachmentIds = setOf(attachment.id.value),
            onSend = { sends++ },
            onStop = { stops++ },
            onRemoveAttachment = { removals++ },
        )

        compose.onNodeWithContentDescription("停止生成").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("移除附件").assertIsNotEnabled()

        assertEquals(0, sends)
        assertEquals(1, stops)
        assertEquals(0, removals)
    }

    @Test fun cancelUnknownAndSubmittingStatesKeepSendSafetyGates() {
        var sends = 0
        var draft by mutableStateOf("稍后发送")
        var generation by mutableStateOf(GenerationState.CANCEL_REQUESTED)
        var canSend by mutableStateOf(true)
        var hasUnknownOutcome by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                ComposerBar(
                    draft = draft,
                    onDraftChange = {},
                    generation = generation,
                    canSend = canSend,
                    hasUnknownSubmissionOutcome = hasUnknownOutcome,
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

        compose.runOnIdle {
            draft = "待核实的消息"
            canSend = true
            hasUnknownOutcome = true
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("发送").assertIsNotEnabled()
        assertEquals(0, sends)

        // A reconciled authoritative failure releases the real composer action, preserving the draft.
        compose.runOnIdle {
            hasUnknownOutcome = false
            generation = GenerationState.FAILED
        }
        compose.onNodeWithContentDescription("发送").assertIsEnabled().performClick()
        assertEquals(1, sends)
    }

    @Test fun aBusyComposerSubmitsTheSelectedModeOnlyFromTheSendAction() {
        var queued = 0
        var steered = 0
        var sends = 0
        compose.setContent {
            MaterialTheme {
                ComposerBar(
                    draft = "补充约束",
                    onDraftChange = {},
                    generation = GenerationState.RUNNING,
                    canSend = true,
                    onSend = { sends++ },
                    onQueue = { queued++ },
                    onSteer = { steered++ },
                    onStop = {},
                    onPickCamera = {},
                    onPickGallery = {},
                    onPickDocument = {},
                    onVoiceInput = {},
                    voiceInputEnabled = false,
                )
            }
        }

        compose.onNodeWithContentDescription("发送").assertIsEnabled()
        compose.onNodeWithContentDescription("排队等待当前任务完成").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("引导当前任务").assertIsEnabled().performClick()
        assertEquals(0, queued + steered + sends)
        compose.onNodeWithContentDescription("发送").performClick()
        assertEquals(0, queued)
        assertEquals(1, steered)
        assertEquals(0, sends)

        compose.onNodeWithContentDescription("排队等待当前任务完成").performClick().assertIsSelected()
        assertEquals(0, queued)
        compose.onNodeWithContentDescription("发送").performClick()
        assertEquals(1, queued)
        assertEquals(1, steered)
        assertEquals(0, sends)
    }

    @Test fun slashCommandsUseTheSameUserSelectedDeliveryControls() {
        var sends = 0
        render(
            draft = "/approve once",
            generation = GenerationState.RUNNING,
            canSend = true,
            onSend = { sends++ },
        )

        compose.onNodeWithContentDescription("引导当前任务").assertIsEnabled().assertIsSelected()
        compose.onNodeWithContentDescription("排队等待当前任务完成").performClick().assertIsSelected()
        assertEquals(0, sends)
        compose.onNodeWithContentDescription("发送").assertIsEnabled().performClick()
        assertEquals(1, sends)
    }

    private fun render(
        draft: String = "",
        generation: GenerationState,
        canSend: Boolean = false,
        attachments: List<AttachmentDraft> = emptyList(),
        inFlightAttachmentIds: Set<String> = emptySet(),
        hasUnknownSubmissionOutcome: Boolean = false,
        onRemoveAttachment: (String) -> Unit = {},
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
                    inFlightAttachmentIds = inFlightAttachmentIds,
                    hasUnknownSubmissionOutcome = hasUnknownSubmissionOutcome,
                    onRemoveAttachment = onRemoveAttachment,
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
