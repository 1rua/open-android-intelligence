package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test fun aReturnedConversationCanStillCancelItsFrozenUpload() = runTest {
        verifyFrozenUploadStartsInItsOriginalConversation(false,cancelAfterReturn=true)
    }

    @Test fun aFailedCreationKeepsTheFrozenBlankSubmissionRecoverable() = runTest {
        verifyFrozenUploadStartsInItsOriginalConversation(true,creationFails=true)
    }

    @Test
    fun anUnsentAttachmentCompletedInAnotherConversationSendsOnceAfterReturning() = runTest {
        verifyUnsentAttachmentAfterReturn(AttachmentState.VERIFIED)
    }

    @Test
    fun anUnsentAttachmentFailedInAnotherConversationRestoresItsFailure() = runTest {
        verifyUnsentAttachmentAfterReturn(AttachmentState.RETRYABLE_FAILURE)
    }

    @Test
    fun anUnsentAttachmentRejectedInAnotherConversationRestoresItsTerminalFailure() = runTest {
        verifyUnsentAttachmentAfterReturn(AttachmentState.TERMINAL_FAILURE)
    }

    @Test
    fun anUnsentAttachmentCancelledInAnotherConversationStaysCancelled() = runTest {
        verifyUnsentAttachmentAfterReturn(AttachmentState.CANCELLED)
    }

    @Test
    fun retryingAReturnedAttachmentReleasesItsOriginalSubmissionOnlyOnce() = runTest {
        verifyUnsentAttachmentAfterReturn(AttachmentState.RETRYABLE_FAILURE, retryAfterReturn = true)
    }

    @Test
    fun delayedAttachmentRetryLookupFailureStaysWithOriginalConversation() = runTest {
        verifyDelayedRetryLookupFailure(switchConversation = true)
    }

    @Test
    fun attachmentRetryLookupFailureStillUpdatesItsActiveConversation() = runTest {
        verifyDelayedRetryLookupFailure(switchConversation = false)
    }

    @Test
    fun delayedAttachmentRetryLookupFailurePreservesUnknownSendOutcome() = runTest {
        verifyDelayedRetryLookupFailure(switchConversation = true, unknownSendOutcome = true)
    }

    private fun TestScope.verifyDelayedRetryLookupFailure(
        switchConversation: Boolean,
        unknownSendOutcome: Boolean = false,
    ) {
        val recorded = RecordingRepository()
        val lookupStarted = CompletableDeferred<Pair<String, ClientMessageId>>()
        val lookup = CompletableDeferred<TimelineMessage?>()
        val repository = object : ConversationRepository by recorded, MessageOutcomeQuery {
            override suspend fun submitMessage(conversationId: String, message: OutgoingMessage): MessageAcceptance {
                recorded.submitMessage(conversationId, message)
                if (unknownSendOutcome) throw java.io.IOException("RESPONSE_LOST")
                error("SEND_REJECTED")
            }
            override suspend fun queryMessage(conversationId: String, clientMessageId: ClientMessageId): TimelineMessage? {
                lookupStarted.complete(conversationId to clientMessageId)
                return lookup.await()
            }
        }
        val atoms = mutableMapOf<String, List<LocalSendAtom>>()
        val persistence = object : WorkbenchPersistence {
            override fun load(): WorkbenchCheckpoint? = null
            override fun save(checkpoint: WorkbenchCheckpoint) = Unit
            override fun loadSendAtoms(conversationId: String) = atoms[conversationId].orEmpty()
            override fun saveSendAtoms(conversationId: String, values: List<LocalSendAtom>) {
                atoms[conversationId] = values
            }
        }
        val upload = MutableStateFlow(AttachmentDraftState(AttachmentDraftId("photo_a"), AttachmentState.VERIFIED))
        val controller = WorkbenchController(
            workbenchTestScope(), repository,
            object : AgentCommandCatalogRepository {
                override suspend fun get(gatewayId: String, languageCode: String) =
                    AgentCommandCatalog(CatalogVersion("v1"), emptyList())
            },
            { ConversationScope("profile", "gateway", "account", "install") },
            attachmentCoordinator = UploadingAttachmentCoordinator(upload), persistence = persistence,
            persistenceDispatcher = kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler),
            replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false),
        )
        try {
            runCurrent()
            controller.openThread("conv_a")
            runCurrent()
            controller.editDraft("A 的待恢复附件消息")
            controller.addAttachment(LocalAttachmentSelection("photo.png", "image/png",
                AttachmentContentSource { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }))
            runCurrent()
            controller.sendDraft()
            runCurrent()
            val original = atoms.getValue("conv_a").single()
            assertEquals(if (unknownSendOutcome) LocalSubmissionState.OUTCOME_UNKNOWN else LocalSubmissionState.FAILED,
                original.submissionState)
            assertEquals(1, recorded.submissions.size)

            controller.retryAttachment("photo_a")
            runCurrent()
            assertTrue("必须在生产重试入口等到原消息查询开始", lookupStarted.isCompleted)
            assertEquals("conv_a" to original.clientMessageId, lookupStarted.getCompleted())
            assertTrue("查询结果必须延迟到会话切换之后", !lookup.isCompleted)
            if (switchConversation) {
                controller.openThread("conv_b")
                runCurrent()
                controller.editDraft("B 的草稿不能被 A 的查询改动")
                controller.updateRemoteCapabilities(online = false, send = true, delete = false,
                    archive = false, rename = false, approvals = false)
                controller.sendDraft()
                runCurrent()
                assertEquals("AGENT_OFFLINE", controller.state.value.notice)
            }
            val beforeFailure = controller.state.value
            val bAtoms = atoms["conv_b"].orEmpty()
            lookup.completeExceptionally(java.io.IOException("LOOKUP_FAILED"))
            runCurrent()

            val notice = "ATTACHMENT_RECOVERY_FAILED:LOOKUP_FAILED"
            assertEquals("只更新 A 的原发送记录，不能改身份、内容或原发送终态",
                original.copy(submissionErrorCode = notice), atoms.getValue("conv_a").single())
            assertEquals("查询失败不能触发重复发送", 1, recorded.submissions.size)
            assertEquals(bAtoms, atoms["conv_b"].orEmpty())
            if (switchConversation) {
                assertEquals("A 的迟到查询失败不能改变 B 的任何页面状态", beforeFailure, controller.state.value)
                controller.openThread("conv_a")
                runCurrent()
            } else {
                assertEquals(notice, controller.state.value.notice)
                assertEquals(beforeFailure.composer, controller.state.value.composer)
            }
            assertEquals("conv_a", controller.state.value.activeThreadId)
            val aRow = (controller.state.value.timeline as Loadable.Ready).value
                .single { it.clientMessageId == original.clientMessageId }
            if (!unknownSendOutcome) assertEquals("回切 A 后错误仍显示在 A 的原消息", notice, aRow.submissionFailureCode)
            assertEquals(unknownSendOutcome, controller.state.value.hasUnknownSubmissionOutcome)
        } finally {
            controller.cancel()
        }
    }

    private fun TestScope.verifyUnsentAttachmentAfterReturn(
        outcome: AttachmentState,
        retryAfterReturn: Boolean = false,
    ) {
        val acceptanceGate = CompletableDeferred<Unit>()
        val repository = RecordingRepository(acceptanceGate)
        val upload = MutableStateFlow(AttachmentDraftState(AttachmentDraftId("photo_a"), AttachmentState.UPLOADING))
        val coordinator = UploadingAttachmentCoordinator(upload)
        val controller = WorkbenchController(
            workbenchTestScope(), repository,
            object : AgentCommandCatalogRepository {
                override suspend fun get(gatewayId: String, languageCode: String) =
                    AgentCommandCatalog(CatalogVersion("v1"), emptyList())
            },
            { ConversationScope("profile", "gateway", "account", "install") },
            attachmentCoordinator = coordinator,
            replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false),
        )
        try {
            runCurrent()
            controller.openThread("conv_a")
            runCurrent()
            controller.editDraft("A 尚未发送的附件问题")
            controller.addAttachment(LocalAttachmentSelection("photo.png", "image/png",
                AttachmentContentSource { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }))
            runCurrent()
            val original = controller.state.value.attachments.single()
            assertEquals(AttachmentState.UPLOADING, original.state)

            controller.openThread("conv_b")
            controller.editDraft("B 尚未发送的草稿")
            runCurrent()
            val completion = AttachmentDraftState(
                original.id, outcome,
                progress = if (outcome == AttachmentState.VERIFIED) 1f else 0.5f,
                transferredBytes = if (outcome == AttachmentState.VERIFIED) 3 else 1,
                totalBytes = 3,
                errorMessage = when (outcome) {
                    AttachmentState.RETRYABLE_FAILURE -> "UPLOAD_INTERRUPTED"
                    AttachmentState.TERMINAL_FAILURE -> "UPLOAD_REJECTED"
                    else -> null
                },
            )
            upload.value = completion
            runCurrent()
            assertEquals("B 尚未发送的草稿", controller.state.value.draft)
            assertTrue("A 的附件不得显示在 B", controller.state.value.attachments.isEmpty())
            assertTrue("尚未点击发送不得提交", repository.submissions.isEmpty())

            controller.openThread("conv_a")
            val expected = original.copy(
                state = completion.state, progress = completion.progress,
                transferredBytes = completion.transferredBytes, totalBytes = completion.totalBytes,
                errorMessage = completion.errorMessage,
            )
            assertEquals("回切不能先恢复已经过期的内存快照", expected, controller.state.value.attachments.single())
            runCurrent()
            assertEquals(expected, controller.state.value.attachments.single())
            assertEquals("A 尚未发送的附件问题", controller.state.value.draft)
            controller.sendDraft()
            controller.sendDraft()
            val clientId = (controller.state.value.timeline as Loadable.Ready).value
                .single { it.isUser }.clientMessageId
            runCurrent()

            if (outcome != AttachmentState.VERIFIED) {
                assertTrue("失败或取消的附件不能提交", repository.submissions.isEmpty())
                if (retryAfterReturn) {
                    controller.retryAttachment(original.id.value)
                    runCurrent()
                } else {
                    controller.cancelPendingSubmission()
                    controller.openThread("conv_b")
                    runCurrent()
                    assertEquals("B 尚未发送的草稿", controller.state.value.draft)
                    assertTrue(repository.submissions.isEmpty())
                    return
                }
            }
            assertEquals("附件验证后必须已进入实际提交，回执仍在等待", 1, repository.submissions.size)
            // 等待回执时再次收到状态更新，也不能释放第二次提交。
            upload.value = AttachmentDraftState(original.id, AttachmentState.VERIFIED, progress = 1f,
                transferredBytes = 3)
            runCurrent()
            assertEquals(1, repository.submissions.size)
            acceptanceGate.complete(Unit)
            runCurrent()
            val (threadId, message) = repository.submissions.single()
            assertEquals("conv_a", threadId)
            assertEquals(clientId, message.clientMessageId)
            assertEquals("A 尚未发送的附件问题", message.text)
            assertEquals(listOf("remote_photo_a"), message.attachmentIds)
            controller.openThread("conv_b")
            runCurrent()
            assertEquals("B 尚未发送的草稿", controller.state.value.draft)
            assertTrue(controller.state.value.attachments.isEmpty())
            controller.openThread("conv_a")
            runCurrent()
            assertTrue("已发送附件不得重新成为输入草稿", controller.state.value.attachments.isEmpty())
            assertEquals(1, repository.submissions.size)
        } finally {
            controller.cancel()
        }
    }

    private fun TestScope.verifyFrozenUploadStartsInItsOriginalConversation(fromBlank: Boolean,
        cancelAfterReturn: Boolean = false, creationFails: Boolean = false) {
        val repository = RecordingRepository().apply { failCreation=creationFails }
        var saved: WorkbenchCheckpoint? = null
        val atoms = mutableMapOf<String,List<LocalSendAtom>>()
        val persistence = object:WorkbenchPersistence {
            override fun load():WorkbenchCheckpoint?=null
            override fun save(checkpoint:WorkbenchCheckpoint) { saved=checkpoint }
            override fun loadSendAtoms(conversationId:String)=atoms[conversationId].orEmpty()
            override fun saveSendAtoms(conversationId:String,values:List<LocalSendAtom>) { atoms[conversationId]=values }
        }
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
            persistence=persistence,persistenceDispatcher=kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler),
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

            if (cancelAfterReturn) {
                controller.openThread("conv_a"); runCurrent()
                controller.cancelPendingSubmission()
                controller.openThread("conv_b"); runCurrent()
            }
            attachmentState.value = AttachmentDraftState(
                AttachmentDraftId("photo_a"), AttachmentState.VERIFIED, progress = 1f,
            )
            runCurrent()

            if (creationFails) {
                runCurrent()
                assertTrue("创建失败后原客户端身份和冻结内容必须仍可恢复", saved?.pendingSubmissions?.any {
                    it.clientMessageId == originalClientMessageId && it.text == frozenText && it.conversationId == null
                } == true)
                assertTrue(repository.submissions.isEmpty())
                return
            }
            if (cancelAfterReturn) {
                assertTrue("回切后明确取消仍必须阻止上传完成自动发送",repository.submissions.isEmpty())
                assertEquals(nextDraft,controller.state.value.draft)
                return
            }
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
        override fun retry(draftId: String) {
            state.value = AttachmentDraftState(AttachmentDraftId(draftId), AttachmentState.VERIFIED, progress = 1f)
        }
        override suspend fun discard(draftId: String) = Unit
        override suspend fun releaseAfterSubmit(draftId: String) = Unit
        override fun remoteAttachmentId(draftId: String) = "remote_photo_a"
    }

    private class RecordingRepository(private val acceptanceGate: CompletableDeferred<Unit>? = null) : ConversationRepository {
        val submissions = mutableListOf<Pair<String, OutgoingMessage>>()
        var failCreation=false

        override suspend fun listConversations(scope: ConversationScope, page: PageRequest) =
            ConversationPage(emptyList(), null)

        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String):Conversation {
            if (failCreation) error("SYNTHETIC_CREATION_FAILED")
            return Conversation(ConversationId("conv_created"), "", 0)
        }

        override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)

        override suspend fun submitBatch(batch: MessageBatch) =
            error("该行为不使用批量发送")

        override suspend fun submitMessage(message: OutgoingMessage) =
            error("测试需要检查消息的目标会话")

        override suspend fun submitMessage(conversationId: String, message: OutgoingMessage): MessageAcceptance {
            submissions += conversationId to message
            acceptanceGate?.await()
            return MessageAcceptance("msg_${message.clientMessageId.value}", message.clientMessageId.value)
        }

        override fun observeEvents(scope: ConversationScope) = emptyFlow<VerifiedConversationEvent>()

        override suspend fun cancelGeneration(generationId: String, requestId: String) =
            CancelGenerationResult(CancelGenerationOutcome.CANCELLED, "已停止生成")
    }
}
