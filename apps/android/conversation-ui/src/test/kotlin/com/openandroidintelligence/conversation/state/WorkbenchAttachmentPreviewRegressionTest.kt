package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import java.io.ByteArrayInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchAttachmentPreviewRegressionTest {
    @Test fun sentImagePreviewSurvivesSwitchingAwayAfterTheAgentReply() = runTest {
        val repository = PreviewHost()
        val controller = createController(repository)
        try {
            controller.openThread("conv_a")
            runCurrent()
            val preview = byteArrayOf(7, 3, 1, 9)
            sendImage(controller, preview)
            val reply = TimelineMessage("reply_a", "assistant", listOf(MessagePart.Text("已收到图片")),
                System.currentTimeMillis() + 1L, conversationId = ConversationId("conv_a"))
            repository.rows.getValue("conv_a").add(reply)
            repository.events.emit(VerifiedConversationEvent.TimelineUpsert(
                "evt_reply", 3, 1, reply,
            ))
            runCurrent()
            assertTrue("受理后原附件草稿已释放", controller.state.value.attachments.isEmpty())
            assertArrayEquals(preview, userRows(controller).single().attachments.single().previewBytes)

            controller.openThread("conv_b")
            runCurrent()
            assertTrue("A 的消息不能出现在 B", userRows(controller).isEmpty())
            controller.openThread("conv_a")
            runCurrent()

            val returned = userRows(controller).single()
            assertEquals("请说明图片", returned.text)
            assertEquals("remote_image", returned.attachments.single().draftId)
            assertArrayEquals("返回原会话后仍须展示原图片预览", preview,
                returned.attachments.single().previewBytes)
        } finally {
            controller.close()
        }
    }

    @Test fun anotherConversationCannotBorrowAnImageWithTheSameAttachmentIdentity() = runTest {
        val repository = PreviewHost()
        val controller = createController(repository)
        try {
            controller.openThread("conv_a")
            runCurrent()
            val preview = byteArrayOf(4, 5, 6)
            sendImage(controller, preview)
            repository.rows["conv_b"] = mutableListOf(TimelineMessage(
                "message_b", "user", listOf(MessagePart.Attachment(AttachmentDraftId("remote_image"),
                    "another.png", "image/png")), 1, conversationId = ConversationId("conv_b"),
            ))
            controller.openThread("conv_b")
            runCurrent()

            val other = userRows(controller).single().attachments.single()
            assertEquals("another.png", other.filename)
            assertNull("B 即使返回同名附件身份，也不能借用 A 的缩略图", other.previewBytes)
            controller.openThread("conv_a")
            runCurrent()
            assertArrayEquals(preview, userRows(controller).single().attachments.single().previewBytes)
        } finally {
            controller.close()
        }
    }

    @Test fun clearingMediaAlsoReleasesAnInactiveConversationsPreview() = runTest {
        val repository = PreviewHost()
        val controller = createController(repository)
        try {
            controller.openThread("conv_a")
            runCurrent()
            sendImage(controller, byteArrayOf(8, 9, 10))
            val ownedPreview = userRows(controller).single().attachments.single().previewBytes!!
            controller.openThread("conv_b")
            runCurrent()
            controller.clearRetainedMediaPreviews()
            assertArrayEquals("只清媒体应释放未显示会话的缩略图", ByteArray(ownedPreview.size), ownedPreview)
            controller.openThread("conv_a")
            runCurrent()

            val returned = userRows(controller).single()
            assertEquals("请说明图片", returned.text)
            assertEquals("remote_image", returned.attachments.single().draftId)
            assertNull("清理媒体不能删除正文与附件元数据，也不能恢复已清预览", returned.attachments.single().previewBytes)
        } finally {
            controller.close()
        }
    }

    @Test fun deletingAnInactiveMessageReleasesItsImagePreview() = runTest {
        val repository = PreviewHost()
        val controller = createController(repository)
        try {
            controller.openThread("conv_a")
            runCurrent()
            sendImage(controller, byteArrayOf(5, 8, 13))
            val ownedPreview = userRows(controller).single().attachments.single().previewBytes!!
            val messageId = repository.rows.getValue("conv_a").single().id
            controller.openThread("conv_b")
            runCurrent()
            repository.rows.getValue("conv_a").clear()
            repository.events.emit(VerifiedConversationEvent.TimelineTombstoned(
                "evt_deleted", 4, messageId, 4, ConversationId("conv_a"),
            ))
            runCurrent()

            assertArrayEquals("远端删除不能把图片留在未显示会话的缓存", ByteArray(ownedPreview.size), ownedPreview)
            controller.openThread("conv_a")
            runCurrent()
            assertTrue(userRows(controller).isEmpty())
        } finally {
            controller.close()
        }
    }

    @Test fun previewEvictionKeepsMessageTextAndAttachmentMetadata() = runTest {
        val repository = PreviewHost()
        val controller = createController(repository, uniqueRemoteIds = true)
        try {
            controller.openThread("conv_a")
            runCurrent()
            repeat(31) { sendImage(controller, byteArrayOf((it + 1).toByte())) }

            val rows = userRows(controller)
            assertEquals("预览容量不能限制消息或附件数量", 31, rows.size)
            val oldest = rows.single { it.attachments.single().draftId == "remote_draft_1" }
            assertEquals("请说明图片", oldest.text)
            assertEquals("photo.png", oldest.attachments.single().filename)
            assertNull("超过 30 项后应释放最久未使用的缩略图", oldest.attachments.single().previewBytes)
            val newest = rows.single { it.attachments.single().draftId == "remote_draft_31" }
            assertArrayEquals(byteArrayOf(31), newest.attachments.single().previewBytes)
        } finally {
            controller.close()
        }
    }

    @Test fun closingWorkbenchReleasesOnlyItsOwnedPreviewAndANewAccountHasNoCopy() = runTest {
        val repository = PreviewHost()
        val controller = createController(repository)
        val selectedPreview = byteArrayOf(2, 4, 6)
        controller.openThread("conv_a")
        runCurrent()
        sendImage(controller, selectedPreview)
        val ownedPreview = userRows(controller).single().attachments.single().previewBytes!!
        controller.close()

        assertArrayEquals("关闭账号工作台应释放其拥有的缩略图", ByteArray(ownedPreview.size), ownedPreview)
        assertArrayEquals("缓存只清自己的复制，不能改动选图调用方的数据", byteArrayOf(2, 4, 6), selectedPreview)
        val next = createController(repository, account = "another_account")
        try {
            next.openThread("conv_a")
            runCurrent()
            assertNull("另一个账号不能继承前一个工作台的缩略图", userRows(next).single().attachments.single().previewBytes)
        } finally {
            next.close()
        }
    }

    @Test fun anOversizedPreviewIsOmittedWithoutRejectingTheAttachmentSend() = runTest {
        val repository = PreviewHost()
        val controller = createController(repository)
        try {
            controller.openThread("conv_a")
            runCurrent()
            sendImage(controller, ByteArray(256 * 1024 + 1) { 1 })

            val sent = userRows(controller).single()
            assertEquals("缩略图预算不能拒绝用户的附件提交", AgentMessageStatus.QUEUED, sent.messageStatus)
            assertEquals("remote_image", sent.attachments.single().draftId)
            assertNull("超过缩略图预算仅省略预览", sent.attachments.single().previewBytes)
            assertTrue(controller.state.value.attachments.isEmpty())
        } finally {
            controller.close()
        }
    }

    @Test fun aLateAcceptanceCannotKeepAPreviewAfterTheDurableMessageDeletion() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = PreviewHost().apply { acceptanceGate = gate }
        val persistence = DeletionAwarePersistence()
        val controller = createController(repository, persistence = persistence)
        try {
            controller.openThread("conv_a")
            runCurrent()
            sendImage(controller, byteArrayOf(2, 3, 5))
            val messageId = repository.rows.getValue("conv_a").single().id
            controller.openThread("conv_b")
            runCurrent()
            controller.openThread("conv_a")
            runCurrent()
            val ownedPreview = userRows(controller).single().attachments.single().previewBytes!!
            controller.openThread("conv_b")
            runCurrent()

            // 持久端口先记录删除屏障，符合生产镜像在向工作台发布事件前先落盘的契约。
            persistence.markDeleted(messageId, 4L)
            repository.rows.getValue("conv_a").clear()
            repository.events.emit(VerifiedConversationEvent.TimelineTombstoned(
                "evt_delete_before_receipt", 4, messageId, 4, ConversationId("conv_a"),
            ))
            runCurrent()
            gate.complete(Unit)
            runCurrent()

            assertArrayEquals("迟到回执建立消息身份时仍须遵守已落盘的删除", ByteArray(ownedPreview.size), ownedPreview)
            controller.openThread("conv_a")
            runCurrent()
            assertTrue(userRows(controller).isEmpty())
        } finally {
            gate.complete(Unit)
            runCurrent()
            controller.close()
        }
    }

    @Test fun aRepeatedContentRevisionCannotDiscardTheCurrentImagePreview() = runTest {
        val repository = PreviewHost()
        val controller = createController(repository)
        try {
            controller.openThread("conv_a")
            runCurrent()
            val preview = byteArrayOf(3, 5, 8)
            sendImage(controller, preview)
            val sent = repository.rows.getValue("conv_a").single()
            repository.events.emit(VerifiedConversationEvent.TimelineUpsert("evt_current", 5, 5, sent))
            runCurrent()
            val ownedPreview = userRows(controller).single().attachments.single().previewBytes!!
            controller.openThread("conv_b")
            runCurrent()

            // 旧修订和重复修订不能清理新内容仍引用的图片，也不能改写未显示会话的正文。
            listOf(4L, 5L).forEach { revision ->
                repository.events.emit(VerifiedConversationEvent.TimelineUpsert(
                    "evt_repeated_$revision", 5 + revision, revision,
                    sent.copy(parts = listOf(MessagePart.Text("过期的替换正文"))),
                ))
                runCurrent()
                assertArrayEquals("不新于已知内容的事件不能释放当前预览", preview, ownedPreview)
            }
            controller.openThread("conv_a")
            runCurrent()
            val returned = userRows(controller).single()
            assertEquals("请说明图片", returned.text)
            assertArrayEquals(preview, returned.attachments.single().previewBytes)
        } finally {
            controller.close()
        }
    }

    private fun TestScope.sendImage(controller: WorkbenchController, preview: ByteArray) {
        controller.addAttachment(LocalAttachmentSelection(
            "photo.png", "image/png", AttachmentContentSource { ByteArrayInputStream(byteArrayOf(1)) },
            previewBytes = preview,
        ))
        runCurrent()
        controller.editDraft("请说明图片")
        controller.sendDraft()
        runCurrent()
    }

    private fun TestScope.createController(repository: PreviewHost, account: String = "account", uniqueRemoteIds: Boolean = false,
        persistence: WorkbenchPersistence? = null) = WorkbenchController(
        this, repository,
        object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) =
                AgentCommandCatalog(CatalogVersion("test"), emptyList())
        },
        { ConversationScope("profile", "gateway", account, "install") },
        attachmentCoordinator = VerifiedImages(uniqueRemoteIds),
        persistence = persistence,
        replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false),
    )

    private fun userRows(controller: WorkbenchController) =
        (controller.state.value.timeline as? Loadable.Ready)?.value.orEmpty().filter { it.isUser }

    private class VerifiedImages(private val uniqueRemoteIds: Boolean) : AttachmentDraftCoordinator {
        private var nextId = 0
        override suspend fun prepare(selection: LocalAttachmentSelection) = AttachmentDraft(
            AttachmentDraftId("draft_${++nextId}"), selection.filename, selection.mediaType, 1,
            "sha256:" + "0".repeat(64), AttachmentState.VERIFIED,
        )
        override suspend fun armSubmission(draftId: String, revision: Long) =
            error("本回归通过工作台发送，不使用附件提交端口")
        override suspend fun cancelSubmission(intentId: String) = CancelSubmissionResult(true)
        override fun observe(draftId: String) = flowOf(AttachmentDraftState(
            AttachmentDraftId(draftId), AttachmentState.VERIFIED, 1f,
        ))
        override fun retry(draftId: String) = error("附件已经校验成功")
        override suspend fun discard(draftId: String) = Unit
        override suspend fun releaseAfterSubmit(draftId: String) = Unit
        override fun remoteAttachmentId(draftId: String) = if (uniqueRemoteIds) "remote_$draftId" else "remote_image"
    }

    private class PreviewHost : ConversationRepository {
        val events = MutableSharedFlow<VerifiedConversationEvent>(extraBufferCapacity = 16)
        val rows = linkedMapOf<String, MutableList<TimelineMessage>>()
        private var nextMessage = 0
        var acceptanceGate: CompletableDeferred<Unit>? = null
        override suspend fun listConversations(scope: ConversationScope, page: PageRequest) = ConversationPage(
            listOf("conv_a", "conv_b").map { ConversationSummary(ConversationId(it), it, 0) }, null,
        )
        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) =
            Conversation(ConversationId("conv_a"), "conv_a", 0)
        override suspend fun timeline(conversationId: String, page: PageRequest) =
            TimelinePage(rows[conversationId].orEmpty(), null)
        override suspend fun submitBatch(batch: MessageBatch) = error("含附件的消息不能进入批次")
        override suspend fun submitMessage(message: OutgoingMessage) = error("发送必须带所属会话")
        override suspend fun submitMessage(conversationId: String, message: OutgoingMessage): MessageAcceptance {
            val id = "message_${++nextMessage}"
            rows.getOrPut(conversationId) { mutableListOf() }.add(TimelineMessage(
                id, "user", listOf(MessagePart.Text(message.text)) + message.attachmentIds.map {
                    MessagePart.Attachment(AttachmentDraftId(it), "photo.png", "image/png")
                }, System.currentTimeMillis(), conversationId = ConversationId(conversationId),
                clientMessageId = message.clientMessageId,
            ))
            acceptanceGate?.await()
            return MessageAcceptance(id, message.clientMessageId.value)
        }
        override fun observeEvents(scope: ConversationScope) = events
        override suspend fun cancelGeneration(generationId: String, requestId: String) =
            CancelGenerationResult(CancelGenerationOutcome.CANCELLED)
    }

    /** 文件持久端口的内存夹具：先知道远端删除身份，回执补齐身份时即应用删除屏障。 */
    private class DeletionAwarePersistence : WorkbenchPersistence {
        private var checkpoint: WorkbenchCheckpoint? = null
        private val atoms = linkedMapOf<String, List<LocalSendAtom>>()
        private val tombstones = linkedMapOf<String, Long>()
        fun markDeleted(messageId: String, revision: Long) { tombstones[messageId] = revision }
        override fun load() = checkpoint
        override fun save(checkpoint: WorkbenchCheckpoint) { this.checkpoint = checkpoint }
        override fun loadSendAtoms(conversationId: String) = atoms[conversationId].orEmpty()
        override fun saveSendAtoms(conversationId: String, atoms: List<LocalSendAtom>) {
            this.atoms[conversationId] = atoms.map { atom ->
                tombstones[atom.messageId]?.let { atom.copy(parts = emptyList(), tombstoneRevision = it) } ?: atom
            }
        }
    }
}
