package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.AttachmentDraft
import com.openandroidintelligence.conversation.model.AttachmentDraftId
import com.openandroidintelligence.conversation.model.AttachmentState
import com.openandroidintelligence.conversation.model.CatalogVersion
import com.openandroidintelligence.conversation.model.ComposerState
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.ports.AgentCommandCatalog
import com.openandroidintelligence.conversation.ports.AgentCommandCatalogRepository
import com.openandroidintelligence.conversation.ports.AttachmentDraftCoordinator
import com.openandroidintelligence.conversation.ports.AttachmentDraftState
import com.openandroidintelligence.conversation.ports.CancelGenerationOutcome
import com.openandroidintelligence.conversation.ports.CancelGenerationResult
import com.openandroidintelligence.conversation.ports.Conversation
import com.openandroidintelligence.conversation.ports.ConversationPage
import com.openandroidintelligence.conversation.ports.ConversationRepository
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.ports.ConversationSummary
import com.openandroidintelligence.conversation.ports.LocalAttachmentSelection
import com.openandroidintelligence.conversation.ports.MessageAcceptance
import com.openandroidintelligence.conversation.ports.MessageBatch
import com.openandroidintelligence.conversation.ports.OutgoingMessage
import com.openandroidintelligence.conversation.ports.PageRequest
import com.openandroidintelligence.conversation.ports.TimelinePage
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 助手草稿交接进主工作台的行为回归。
 *
 * 交接只搬运状态：文字草稿与**已校验**附件进入主 App 输入栏，不发送消息、不新建会话。
 * 未校验（仍在暂存/上传/失败）的附件不迁移，调用方据此如实提示；绝不能只搬一半。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchAssistantDraftAdoptionTest {

    private fun draft(id: String, state: AttachmentState) = AttachmentDraft(
        id = AttachmentDraftId(id),
        filename = "$id.png",
        mediaType = "image/png",
        sizeBytes = 3L,
        sha256 = "0".repeat(64),
        state = state,
    )

    /** 只观察已知草稿的协调器；`observe` 的调用次数用来证明接管后仍会跟踪校验状态。 */
    private fun coordinator(ids: List<String>): Pair<AttachmentDraftCoordinator, AtomicInteger> {
        val observed = AtomicInteger()
        val flows = ids.associateWith {
            MutableStateFlow(AttachmentDraftState(AttachmentDraftId(it), AttachmentState.VERIFIED))
        }
        val coordinator = object : AttachmentDraftCoordinator {
            override suspend fun prepare(selection: LocalAttachmentSelection) = error("unused")
            override suspend fun armSubmission(draftId: String, revision: Long) = error("unused")
            override suspend fun cancelSubmission(intentId: String) = error("unused")
            override fun observe(draftId: String): Flow<AttachmentDraftState> {
                observed.incrementAndGet()
                return flows[draftId] ?: error("unknown draft $draftId")
            }
            override fun retry(draftId: String) = Unit
            override suspend fun discard(draftId: String) = Unit
            override suspend fun releaseAfterSubmit(draftId: String) = Unit
            override fun remoteAttachmentId(draftId: String): String? = null
        }
        return coordinator to observed
    }

    private fun controller(scope: CoroutineScope, coordinator: AttachmentDraftCoordinator): WorkbenchController =
        WorkbenchController(
            scope = scope,
            repository = EmptyRepository(),
            catalogRepository = object : AgentCommandCatalogRepository {
                override suspend fun get(gatewayId: String, languageCode: String) =
                    AgentCommandCatalog(CatalogVersion("v1"), emptyList())
            },
            scopeFactory = { ConversationScope("profile", "gateway", "account", "install") },
            attachmentCoordinator = coordinator,
            replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false),
        )

    @Test
    fun adoptedTextAndVerifiedAttachmentsLandInTheComposer() = runTest {
        val (coordinator, observed) = coordinator(listOf("a1"))
        val controller = controller(CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)), coordinator)
        try {
            runCurrent()

            val skipped = controller.adoptAssistantDraft("浮层里写的草稿", listOf(draft("a1", AttachmentState.VERIFIED)))
            runCurrent()

            assertEquals("全部附件都已校验时不应有跳过项", 0, skipped)
            val state = controller.state.value
            assertEquals("浮层里写的草稿", state.draft)
            assertEquals(listOf("a1"), state.attachments.map { it.id.value })
            assertTrue("接管的附件必须被观察，后续校验/失败才能反映到输入栏", observed.get() >= 1)
            assertEquals("接管不得自动发送", ComposerState.EDITING, state.composer)
        } finally {
            controller.close()
        }
    }

    @Test
    fun attachmentsThatAreNotVerifiedAreNotMigrated() = runTest {
        val (coordinator, _) = coordinator(listOf("a1"))
        val controller = controller(CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)), coordinator)
        try {
            runCurrent()

            val skipped = controller.adoptAssistantDraft(
                "只带已就绪的附件",
                listOf(
                    draft("a1", AttachmentState.VERIFIED),
                    draft("a2", AttachmentState.UPLOADING),
                    draft("a3", AttachmentState.RETRYABLE_FAILURE),
                ),
            )
            runCurrent()

            assertEquals("两个附件未就绪，必须如实计数", 2, skipped)
            assertEquals(listOf("a1"), controller.state.value.attachments.map { it.id.value })
        } finally {
            controller.close()
        }
    }

    @Test
    fun adoptionSurvivesReturningToTheSameThread() = runTest {
        val (coordinator, _) = coordinator(listOf("a1"))
        val controller = controller(CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)), coordinator)
        try {
            runCurrent()
            controller.openThread("conv_a")
            runCurrent()
            controller.adoptAssistantDraft("交接草稿", listOf(draft("a1", AttachmentState.VERIFIED)))
            runCurrent()

            controller.openThread("conv_b")
            runCurrent()
            controller.openThread("conv_a")
            runCurrent()

            assertEquals("交接结果不能被会话切换丢掉", "交接草稿", controller.state.value.draft)
            assertEquals(listOf("a1"), controller.state.value.attachments.map { it.id.value })
        } finally {
            controller.close()
        }
    }

    @Test
    fun anEmptyAssistantDraftNeverErasesWhatTheMainAppAlreadyWrote() = runTest {
        val (coordinator, _) = coordinator(emptyList())
        val controller = controller(CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)), coordinator)
        try {
            runCurrent()
            controller.editDraft("hello")

            // 助手侧什么都没写：上滑交接不能把主 App 已经输入的内容抹掉。
            val skipped = controller.adoptAssistantDraft("", emptyList())
            runCurrent()

            assertEquals(0, skipped)
            assertEquals("助手草稿为空时保留主 App 现有草稿", "hello", controller.state.value.draft)
            assertTrue(controller.state.value.attachments.isEmpty())
        } finally {
            controller.close()
        }
    }

    @Test
    fun aNonBlankAssistantDraftWinsOverTheMainAppText() = runTest {
        val (coordinator, _) = coordinator(emptyList())
        val controller = controller(CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)), coordinator)
        try {
            runCurrent()
            controller.editDraft("hello")

            controller.adoptAssistantDraft("浮层里的新草稿", emptyList())
            runCurrent()

            assertEquals("浮层里的新草稿", controller.state.value.draft)
        } finally {
            controller.close()
        }
    }

    @Test
    fun handoffWaitsForUploadingAttachmentsInsteadOfDroppingThem() = runTest {
        val uploading = MutableStateFlow(
            AttachmentDraftState(AttachmentDraftId("a1"), AttachmentState.UPLOADING),
        )
        val coordinator = object : AttachmentDraftCoordinator {
            override suspend fun prepare(selection: LocalAttachmentSelection) = draft("a1", AttachmentState.UPLOADING)
            override suspend fun armSubmission(draftId: String, revision: Long) = error("unused")
            override suspend fun cancelSubmission(intentId: String) = error("unused")
            override fun observe(draftId: String): Flow<AttachmentDraftState> = uploading
            override fun retry(draftId: String) = Unit
            override suspend fun discard(draftId: String) = Unit
            override suspend fun releaseAfterSubmit(draftId: String) = Unit
            override fun remoteAttachmentId(draftId: String): String? = null
        }
        val controller = controller(CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)), coordinator)
        try {
            runCurrent()
            controller.addAttachment(
                LocalAttachmentSelection(
                    filename = "shot.png",
                    mediaType = "image/png",
                    contentSource = com.openandroidintelligence.conversation.ports.AttachmentContentSource {
                        java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3))
                    },
                ),
            )
            runCurrent()
            assertEquals(
                "前置条件：附件仍在上传",
                AttachmentState.UPLOADING,
                controller.state.value.attachments.single().state,
            )

            // 上传完成前不能判定为就绪：直接迁移会让浮层关闭时把它一起丢掉。
            assertFalse(controller.awaitHandoffAttachments(timeoutMillis = 50L))

            uploading.value = AttachmentDraftState(AttachmentDraftId("a1"), AttachmentState.VERIFIED)
            runCurrent()
            assertTrue("校验完成后交接应立即就绪", controller.awaitHandoffAttachments(timeoutMillis = 50L))
        } finally {
            controller.close()
        }
    }

    @Test
    fun aFailedAttachmentEndsTheHandoffWaitWithoutReachingTheTimeout() = runTest {
        val failed = MutableStateFlow(
            AttachmentDraftState(AttachmentDraftId("a1"), AttachmentState.RETRYABLE_FAILURE),
        )
        val coordinator = object : AttachmentDraftCoordinator {
            override suspend fun prepare(selection: LocalAttachmentSelection) = draft("a1", AttachmentState.RETRYABLE_FAILURE)
            override suspend fun armSubmission(draftId: String, revision: Long) = error("unused")
            override suspend fun cancelSubmission(intentId: String) = error("unused")
            override fun observe(draftId: String): Flow<AttachmentDraftState> = failed
            override fun retry(draftId: String) = Unit
            override suspend fun discard(draftId: String) = Unit
            override suspend fun releaseAfterSubmit(draftId: String) = Unit
            override fun remoteAttachmentId(draftId: String): String? = null
        }
        val controller = controller(CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)), coordinator)
        try {
            runCurrent()
            controller.addAttachment(
                LocalAttachmentSelection(
                    filename = "shot.png",
                    mediaType = "image/png",
                    contentSource = com.openandroidintelligence.conversation.ports.AttachmentContentSource {
                        java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3))
                    },
                ),
            )
            runCurrent()

            assertFalse("校验失败必须立即按严格交接拒绝，不必等满上限", controller.awaitHandoffAttachments(timeoutMillis = 5_000L))
        } finally {
            controller.close()
        }
    }

    @Test
    fun handoffWithNoAttachmentsIsReadyImmediately() = runTest {
        val (coordinator, _) = coordinator(emptyList())
        val controller = controller(CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)), coordinator)
        try {
            runCurrent()
            assertTrue("没有附件时交接不必等待", controller.awaitHandoffAttachments(timeoutMillis = 50L))
        } finally {
            controller.close()
        }
    }

    private class EmptyRepository : ConversationRepository {
        private val events = MutableSharedFlow<VerifiedConversationEvent>(extraBufferCapacity = 4)

        override suspend fun listConversations(scope: ConversationScope, page: PageRequest) =
            ConversationPage(
                listOf(ConversationSummary(ConversationId("conv_a"), "A", 1)),
                null,
            )

        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation =
            error("unused")

        override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)

        override suspend fun submitBatch(batch: MessageBatch) = error("unused")

        override suspend fun submitMessage(message: OutgoingMessage) = error("unused")

        override suspend fun submitMessage(conversationId: String, message: OutgoingMessage): MessageAcceptance =
            error("unused")

        override fun observeEvents(scope: ConversationScope) = events

        override suspend fun cancelGeneration(generationId: String, requestId: String): CancelGenerationResult =
            CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }
}
