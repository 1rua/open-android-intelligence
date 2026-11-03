package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import java.io.ByteArrayInputStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchPendingAttachmentSwitchTest {
    @Test
    fun uploadingAttachmentSubmissionStaysWithItsConversationAcrossSwitches() = runTest {
        verifyFrozenUploadStartsInItsOriginalConversation(false)
    }

    @Test fun anUploadSubmittedFromBlankCannotUseALaterSelectedConversation() = runTest {
        verifyFrozenUploadStartsInItsOriginalConversation(true)
    }

    private fun TestScope.verifyFrozenUploadStartsInItsOriginalConversation(fromBlank: Boolean) {
        val repository = RecordingRepository()
        val attachmentState = MutableStateFlow(
            AttachmentDraftState(AttachmentDraftId("photo_a"), AttachmentState.UPLOADING),
        )
        val coordinator = UploadingAttachmentCoordinator(attachmentState)
        val controller = WorkbenchController(
            workbenchTestScope(),
            repository,
            object : AgentCommandCatalogRepository {
                override suspend fun get(gatewayId: String, languageCode: String) =
                    AgentCommandCatalog(CatalogVersion("v1"), emptyList())
            },
            { ConversationScope("profile", "gateway", "account", "install") },
            attachmentCoordinator = coordinator,
            replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false),
        )
        val preview = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        val frozenText = "请查看这张图片"
        val nextDraft = "B 会话的新草稿"

        try {
            runCurrent()
            if (!fromBlank) controller.openThread("conv_a")
            runCurrent()
            controller.addAttachment(
                LocalAttachmentSelection(
                    filename = "photo.png",
                    mediaType = "image/png",
                    contentSource = AttachmentContentSource {
                        ByteArrayInputStream(byteArrayOf(1, 2, 3))
                    },
                    previewBytes = preview,
                ),
            )
            runCurrent()
            assertEquals(AttachmentState.UPLOADING, controller.state.value.attachments.single().state)

            controller.editDraft(frozenText)
            controller.sendDraft()
            val frozenEntry = (controller.state.value.timeline as Loadable.Ready).value
                .single { it.isUser && it.text == frozenText }
            val originalClientMessageId = requireNotNull(frozenEntry.clientMessageId).value
            assertEquals(ComposerState.WAITING_ATTACHMENTS, controller.state.value.composer)

            controller.openThread("conv_b")
            controller.editDraft(nextDraft)
            runCurrent()

            attachmentState.value = AttachmentDraftState(
                AttachmentDraftId("photo_a"), AttachmentState.VERIFIED, progress = 1f,
            )
            runCurrent()

            val bRows = when (val timeline = controller.state.value.timeline) {
                is Loadable.Ready -> timeline.value
                else -> emptyList()
            }
            val bContainsASend = bRows.any {
                it.clientMessageId?.value == originalClientMessageId || it.text == frozenText
            }

            val bDraft = controller.state.value.draft
            val originalThread = if (fromBlank) "conv_created" else "conv_a"
            controller.openThread(originalThread)
            runCurrent()
            val aRows = when (val timeline = controller.state.value.timeline) {
                is Loadable.Ready -> timeline.value
                else -> emptyList()
            }
            val reopenedAEntry = aRows.singleOrNull {
                it.clientMessageId?.value == originalClientMessageId
            }

            val actual = SwitchOutcome(
                submissions = repository.submissions.map { (conversationId, message) ->
                    SubmittedMessage(
                        conversationId = conversationId,
                        clientMessageId = message.clientMessageId.value,
                        text = message.text,
                        attachmentIds = message.attachmentIds,
                    )
                },
                bContainsASend = bContainsASend,
                bDraft = bDraft,
                reopenedAContainsOriginalSend = reopenedAEntry != null,
                reopenedAImagePreview = reopenedAEntry?.attachments?.singleOrNull()?.previewBytes?.toList(),
            )
            val expected = SwitchOutcome(
                submissions = listOf(
                    SubmittedMessage(originalThread, originalClientMessageId, frozenText, listOf("remote_photo_a")),
                ),
                bContainsASend = false,
                bDraft = nextDraft,
                reopenedAContainsOriginalSend = true,
                reopenedAImagePreview = preview.toList(),
            )

            assertEquals("会话切换不得取消 A 的冻结发送、污染 B 或丢失 A 的预览", expected, actual)
        } finally {
            controller.cancel()
        }
    }

    private data class SubmittedMessage(
        val conversationId: String,
        val clientMessageId: String,
        val text: String,
        val attachmentIds: List<String>,
    )

    private data class SwitchOutcome(
        val submissions: List<SubmittedMessage>,
        val bContainsASend: Boolean,
        val bDraft: String,
        val reopenedAContainsOriginalSend: Boolean,
        val reopenedAImagePreview: List<Byte>?,
    )

    private class UploadingAttachmentCoordinator(
        private val state: MutableStateFlow<AttachmentDraftState>,
    ) : AttachmentDraftCoordinator {
        override suspend fun prepare(selection: LocalAttachmentSelection): AttachmentDraft {
            val sourceBytes = selection.contentSource.openStream().use { it.readBytes() }
            return AttachmentDraft(
                id = state.value.draftId,
                filename = selection.filename,
                mediaType = selection.mediaType,
                sizeBytes = sourceBytes.size.toLong(),
                sha256 = "sha256:" + "0".repeat(64),
                state = state.value.state,
            )
        }

        override suspend fun armSubmission(draftId: String, revision: Long) = PendingSubmissionIntent(
            intentId = SubmitIntentId("intent_$draftId"),
            clientMessageId = ClientMessageId("client_$draftId"),
            draftRevision = revision,
            text = "",
        )

        override suspend fun cancelSubmission(intentId: String) = CancelSubmissionResult(true)
        override fun observe(draftId: String): Flow<AttachmentDraftState> = state
        override fun retry(draftId: String) = Unit
        override suspend fun discard(draftId: String) = Unit
        override suspend fun releaseAfterSubmit(draftId: String) = Unit
        override fun remoteAttachmentId(draftId: String) = "remote_photo_a"
    }

    private class RecordingRepository : ConversationRepository {
        val submissions = mutableListOf<Pair<String, OutgoingMessage>>()

        override suspend fun listConversations(scope: ConversationScope, page: PageRequest) =
            ConversationPage(emptyList(), null)

        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) =
            Conversation(ConversationId("conv_created"), "新对话", 0)

        override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)

        override suspend fun submitBatch(batch: MessageBatch) =
            error("该行为不使用批量发送")

        override suspend fun submitMessage(message: OutgoingMessage) =
            error("测试需要检查消息的目标会话")

        override suspend fun submitMessage(conversationId: String, message: OutgoingMessage): MessageAcceptance {
            submissions += conversationId to message
            return MessageAcceptance("msg_${message.clientMessageId.value}", message.clientMessageId.value)
        }

        override fun observeEvents(scope: ConversationScope) = emptyFlow<VerifiedConversationEvent>()

        override suspend fun cancelGeneration(generationId: String, requestId: String) =
            CancelGenerationResult(CancelGenerationOutcome.CANCELLED, "已停止生成")
    }
}
