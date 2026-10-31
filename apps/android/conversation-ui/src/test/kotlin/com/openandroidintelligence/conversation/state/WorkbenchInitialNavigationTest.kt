package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchInitialNavigationTest {
    @Test fun coldStartKeepsTheLastConversationClosedUntilTheUserOpensIt() = runTest {
        val repository = Host()
        val persistence = MemoryPersistence(checkpoint())
        val controller = controller(repository, persistence)
        try {
            assertNull("冷启动不应先展示上次会话", controller.state.value.activeThreadId)
            assertEquals("", controller.state.value.activeThreadTitle)
            assertEquals("", controller.state.value.draft)
            assertTrue(rows(controller).isEmpty())
            runCurrent()
            assertNull("列表同步完成也不能代替用户选择旧会话", controller.state.value.activeThreadId)
            assertEquals(listOf("conv_a", "conv_b"),
                (controller.state.value.threads as Loadable.Ready).value.map { it.id.value })
            assertTrue(repository.historyReads.isEmpty())

            controller.openThread("conv_b")
            runCurrent()
            assertEquals("conv_b", controller.state.value.activeThreadId)
            assertEquals(listOf("缓存中的旧消息"), rows(controller).map { it.text })
            assertEquals(listOf("conv_b"), repository.historyReads)
        } finally {
            controller.close()
        }
    }

    @Test fun coldStartWithoutACheckpointDoesNotSelectTheFirstConversationOnRefreshOrForeground() = runTest {
        val repository = Host()
        val controller = controller(repository)
        try {
            runCurrent()
            assertNull("无 checkpoint 时也不得自动选择第一条", controller.state.value.activeThreadId)
            controller.refreshThreads()
            controller.onForegrounded()
            runCurrent()
            assertNull(controller.state.value.activeThreadId)
            assertTrue(repository.historyReads.isEmpty())
            controller.openThread("conv_a")
            runCurrent()
            controller.onForegrounded()
            runCurrent()
            assertEquals("用户选择后后台恢复仍保留当前页", "conv_a", controller.state.value.activeThreadId)
        } finally {
            controller.close()
        }
    }

    @Test fun emptyColdStartPreservesTheSavedDraftAttachmentsAndUnknownSendUntilManualOpen() = runTest {
        val saved = checkpoint().copy(attachments = listOf(oldAttachment), submission =
            SavedSubmission("待确认的旧消息", emptyList(), 6, "conv_b", "client_old"))
        val persistence = MemoryPersistence(saved)
        val atom = LocalSendAtom(ConversationId("conv_b"), ClientMessageId("client_old"),
            listOf(MessagePart.Text("待确认的旧消息")), 2, submissionState = LocalSubmissionState.OUTCOME_UNKNOWN)
        persistence.saveSendAtoms("conv_b", listOf(atom))
        val repository = Host()
        val controller = controller(repository, persistence)
        try {
            runCurrent()
            controller.onForegrounded()
            runCurrent()
            assertNull(controller.state.value.activeThreadId)
            assertEquals("", controller.state.value.draft)
            assertTrue(controller.state.value.attachments.isEmpty())
            assertEquals(saved.draft, persistence.load().draft)
            assertEquals(saved.attachments, persistence.load().attachments)
            assertEquals(saved.submission, persistence.load().submission)
            assertEquals(listOf(atom), persistence.loadSendAtoms("conv_b"))
            assertEquals(listOf(oldMessage), persistence.loadTimeline("conv_b")?.messages)

            controller.openThread("conv_b")
            runCurrent()
            assertEquals(saved.draft, controller.state.value.draft)
            assertEquals(saved.attachments, controller.state.value.attachments)
            assertEquals(listOf("缓存中的旧消息", "待确认的旧消息"), rows(controller).map { it.text })
            assertTrue(controller.state.value.hasRecoverableSubmissions)
            assertEquals(ClientMessageId("client_old"), persistence.loadSendAtoms("conv_b").single().clientMessageId)
            assertEquals(0, repository.sends)
        } finally {
            controller.close()
        }
    }

    @Test fun expectedAgentDiscoveryDoesNotStartRemoteReadsOrShowASyncError() = runTest {
        val repository = Host().apply { ready = false }
        val controller = controller(repository, MemoryPersistence(checkpoint()), restoreLastThread = true)
        try {
            runCurrent()
            controller.onForegrounded()
            controller.loadCatalog()
            runCurrent()
            assertEquals("discovery 完成前不读列表", 0, repository.listReads)
            assertEquals("discovery 完成前不读命令", 0, repository.catalogReads)
            assertTrue("discovery 完成前不拉历史", repository.historyReads.isEmpty())
            assertNull(controller.state.value.notice)
            assertEquals(listOf("缓存中的旧消息"), rows(controller).map { it.text })

            repository.ready = true
            controller.onForegrounded()
            controller.loadCatalog()
            runCurrent()
            assertTrue(repository.listReads > 0)
            assertTrue(repository.catalogReads > 0)
            assertEquals("conv_b", controller.state.value.activeThreadId)
            assertEquals(listOf("conv_b"), repository.historyReads)
            assertNull(controller.state.value.notice)
        } finally {
            controller.close()
        }
    }

    @Test fun blankPageInputAndAttachmentsSurviveControllerHandoffWithoutSelectingAHistoryThread() = runTest {
        val saved = checkpoint().copy(threadId = null, title = "", messages = emptyList(),
            draft = "用户刚在空白页输入的文字", attachments = listOf(oldAttachment))
        val persistence = MemoryPersistence(saved)
        val repository = Host()
        val controller = controller(repository, persistence)
        try {
            assertNull(controller.state.value.activeThreadId)
            assertEquals(saved.draft, controller.state.value.draft)
            assertEquals(saved.attachments, controller.state.value.attachments)
            runCurrent()
            controller.onForegrounded()
            runCurrent()
            assertNull("接管后仍然是用户正在编辑的新页", controller.state.value.activeThreadId)
            assertEquals(saved.draft, controller.state.value.draft)
            assertEquals(saved.attachments, controller.state.value.attachments)
            assertEquals(saved.draft, persistence.load().draft)
            assertTrue(repository.historyReads.isEmpty())
        } finally {
            controller.close()
        }
    }

    @Test fun blankPageDoesNotDisplayAnotherThreadsFailedSubmissionButKeepsItDurable() = runTest {
        val submission = SavedSubmission("旧会话尚未确认的正文", emptyList(), 6, "conv_b", "client_old")
        val saved = checkpoint().copy(threadId = null, title = "", messages = emptyList(), draft = "",
            submission = submission)
        val persistence = MemoryPersistence(saved)
        val atom = LocalSendAtom(ConversationId("conv_b"), ClientMessageId("client_old"),
            listOf(MessagePart.Text(submission.text)), 2, submissionState = LocalSubmissionState.OUTCOME_UNKNOWN)
        persistence.saveSendAtoms("conv_b", listOf(atom))
        val repository = Host()
        val controller = controller(repository, persistence, restoreLastThread = false)
        try {
            assertNull(controller.state.value.activeThreadId)
            assertEquals("新空白页不能回填其他会话的失败正文", "", controller.state.value.draft)
            assertEquals(ComposerState.EDITING, controller.state.value.composer)
            assertNull("旧会话的发送失败不能显示为新页提示", controller.state.value.notice)
            assertTrue(rows(controller).isEmpty())
            runCurrent()
            controller.retryPendingSubmissions()
            runCurrent()
            assertNull(controller.state.value.activeThreadId)
            assertEquals("", controller.state.value.draft)
            assertEquals(ComposerState.EDITING, controller.state.value.composer)
            assertNull(controller.state.value.notice)
            assertFalse(controller.state.value.hasRecoverableSubmissions)
            assertEquals("", persistence.load().draft)
            assertEquals("旧提交仍需按原会话和消息身份恢复", submission, persistence.load().submission)
            assertEquals(listOf(atom), persistence.loadSendAtoms("conv_b"))
            assertEquals(0, repository.sends)
        } finally {
            controller.close()
        }
    }

    @Test fun manuallyOpeningCachedHistoryWhileWaitingForAgentDoesNotStartRemoteReads() = runTest {
        val repository = Host().apply { ready = false }
        val controller = controller(repository, MemoryPersistence(checkpoint()))
        try {
            runCurrent()
            controller.openThread("conv_b")
            runCurrent()
            assertEquals("conv_b", controller.state.value.activeThreadId)
            assertEquals(listOf("缓存中的旧消息"), rows(controller).map { it.text })
            assertTrue(repository.historyReads.isEmpty())
            assertNull(controller.state.value.notice)
            repository.ready = true
            controller.onForegrounded()
            runCurrent()
            assertEquals("conv_b", controller.state.value.activeThreadId)
            assertEquals(listOf("conv_b"), repository.historyReads)
        } finally {
            controller.close()
        }
    }

    @Test fun omittedOptionsKeepThePreviousConversationDraftAndAutomaticReads() = runTest {
        val repository = Host()
        val saved = checkpoint().copy(attachments = listOf(oldAttachment))
        val controller = WorkbenchController(
            this, repository,
            object : AgentCommandCatalogRepository {
                override suspend fun get(gatewayId: String, languageCode: String) = repository.catalog()
            },
            { ConversationScope("profile", "gateway", "account", "install") },
            persistence = MemoryPersistence(saved),
            persistenceDispatcher = UnconfinedTestDispatcher(testScheduler),
            replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false),
        )
        try {
            assertEquals("conv_b", controller.state.value.activeThreadId)
            assertEquals(saved.title, controller.state.value.activeThreadTitle)
            assertEquals(saved.draft, controller.state.value.draft)
            assertEquals(saved.attachments, controller.state.value.attachments)
            assertEquals(listOf("缓存中的旧消息"), rows(controller).map { it.text })
            runCurrent()
            assertTrue(repository.listReads > 0)
            assertTrue(repository.catalogReads > 0)
            assertEquals(listOf("conv_b"), repository.historyReads)
            controller.onForegrounded()
            runCurrent()
            assertEquals("conv_b", controller.state.value.activeThreadId)
        } finally {
            controller.close()
        }
    }

    @Test fun retirementForwardsHistorySelectionAndDraftEditsToTheReplacement() = runTest {
        val old = controller(Host())
        val next = controller(Host())
        try {
            runCurrent()
            old.redirectRetiredNavigation(next::openThread, next::editDraft)
            old.closeAndAwait()
            old.openThread("conv_b")
            old.editDraft("交接期间的输入")
            runCurrent()
            assertEquals("conv_b", next.state.value.activeThreadId)
            assertEquals("交接期间的输入", next.state.value.draft)
        } finally {
            old.close()
            next.close()
        }
    }

    private fun TestScope.controller(repository: Host, persistence: WorkbenchPersistence? = null,
        restoreLastThread: Boolean = false) = WorkbenchController(
        this,
        repository,
        object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) = repository.catalog()
        },
        { ConversationScope("profile", "gateway", "account", "install") },
        persistence = persistence,
        restoreLastThread = restoreLastThread,
        canReadConversations = { repository.ready },
        persistenceDispatcher = UnconfinedTestDispatcher(testScheduler),
        replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false),
    )

    private fun rows(controller: WorkbenchController): List<TimelineEntry> =
        (controller.state.value.timeline as? Loadable.Ready)?.value.orEmpty()

    private fun checkpoint() = WorkbenchCheckpoint(
        threads = summaries,
        threadId = "conv_b",
        title = "上次会话",
        messages = listOf(oldMessage),
        revisions = emptyMap(),
        draft = "上次会话未发送的草稿",
        draftRevision = 7,
        attachments = emptyList(),
        submission = null,
        renamedThreads = emptySet(),
        eventIds = emptySet(),
    )

    private class MemoryPersistence(private var checkpoint: WorkbenchCheckpoint) : AwaitableWorkbenchPersistence {
        private val timelines = linkedMapOf("conv_b" to TimelinePage(checkpoint.messages, null))
        private val atoms = linkedMapOf<String, List<LocalSendAtom>>()
        override fun load() = checkpoint
        override fun save(checkpoint: WorkbenchCheckpoint) {
            this.checkpoint = checkpoint
            checkpoint.threadId?.let { id ->
                val messages = (timelines[id]?.messages.orEmpty() + checkpoint.messages).associateBy { it.id }.values.toList()
                timelines[id] = TimelinePage(messages, null)
            }
        }
        override fun loadSendAtoms(conversationId: String) = atoms[conversationId].orEmpty()
        override fun saveSendAtoms(conversationId: String, atoms: List<LocalSendAtom>) {
            this.atoms[conversationId] = atoms.toList()
        }
        override suspend fun loadTimeline(conversationId: String) = timelines[conversationId]
        override suspend fun loadKnownMediaMessages() = emptyList<TimelineMessage>()
        override suspend fun saveCheckpoint(checkpoint: WorkbenchCheckpoint) = save(checkpoint)
        override suspend fun saveSendAtomsDurably(conversationId: String, atoms: List<LocalSendAtom>) =
            saveSendAtoms(conversationId, atoms)
        override suspend fun flush() = Unit
        override suspend fun closeStore() = Unit
    }

    private class Host : ConversationRepository {
        var ready = true
        var listReads = 0
        var catalogReads = 0
        var sends = 0
        val historyReads = mutableListOf<String>()
        suspend fun catalog(): AgentCommandCatalog {
            catalogReads++
            check(ready) { "NO_ACTIVE_AGENT_SELECTED" }
            return AgentCommandCatalog(CatalogVersion("test"), emptyList())
        }
        private val events = MutableSharedFlow<VerifiedConversationEvent>(extraBufferCapacity = 8)
        override suspend fun listConversations(scope: ConversationScope, page: PageRequest): ConversationPage {
            listReads++
            check(ready) { "NO_ACTIVE_AGENT_SELECTED" }
            return ConversationPage(summaries, null)
        }
        override suspend fun timeline(conversationId: String, page: PageRequest): TimelinePage {
            historyReads += conversationId
            check(ready) { "NO_ACTIVE_AGENT_SELECTED" }
            return TimelinePage(emptyList(), null)
        }
        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation =
            error("此测试不创建远端会话")
        override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance {
            sends++
            error("恢复导航不得自动发送消息")
        }
        override suspend fun submitBatch(batch: MessageBatch): BatchAcceptance =
            error("恢复导航不得自动发送批次")
        override suspend fun cancelGeneration(generationId: String, requestId: String) =
            CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
        override fun observeEvents(scope: ConversationScope) = events
    }

    private companion object {
        val summaries = listOf(
            ConversationSummary(ConversationId("conv_a"), "较新的会话", 20),
            ConversationSummary(ConversationId("conv_b"), "上次会话", 10),
        )
        val oldAttachment = AttachmentDraft(AttachmentDraftId("attachment_old"), "未发送的附件.txt", "text/plain",
            5, "a".repeat(64), AttachmentState.VERIFIED)
        val oldMessage = TimelineMessage("server_old", "assistant", listOf(MessagePart.Text("缓存中的旧消息")),
            1, conversationId = ConversationId("conv_b"))
    }
}
