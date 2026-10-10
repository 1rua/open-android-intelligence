package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchSendPersistenceTest {
    @Test fun acceptedMessageSurvivesSwitchingAwayBeforeHostHistoryIsAvailable() = runTest {
        val repository = EmptyHostHistory()
        val controller = controller(repository)
        runCurrent()
        controller.openThread("conv_a")
        runCurrent()
        controller.editDraft("尚未收到回复的用户消息")
        controller.sendDraft()
        runCurrent()
        assertEquals(listOf("尚未收到回复的用户消息"), userTexts(controller))

        controller.openThread("conv_b")
        runCurrent()
        assertTrue(userTexts(controller).isEmpty())
        controller.openThread("conv_a")
        runCurrent()

        assertEquals("空的宿主历史不能删除已提交消息", listOf("尚未收到回复的用户消息"), userTexts(controller))
        controller.close()
    }

    @Test fun delayedBatchAcceptanceBelongsToTheOriginalConversation() = runTest {
        val repository = EmptyHostHistory()
        val controller = controller(repository, batched = true)
        runCurrent(); controller.openThread("conv_a"); runCurrent()
        controller.editDraft("只属于 A 的消息"); controller.sendDraft(); runCurrent()
        val originalTime = (controller.state.value.timeline as Loadable.Ready).value.single { it.isUser }.timestamp
        controller.openThread("conv_b"); runCurrent()
        advanceTimeBy(1_501); runCurrent()
        assertEquals(1, repository.batches.size)
        assertTrue("A 的回执不能写到 B", userTexts(controller).isEmpty())
        controller.openThread("conv_a"); runCurrent()
        assertEquals(listOf("只属于 A 的消息"), userTexts(controller))
        assertEquals(originalTime, (controller.state.value.timeline as Loadable.Ready).value.single { it.isUser }.timestamp)
        controller.close()
    }

    @Test fun completionBeforeHttpAcceptanceIsNotDowngradedByTheLateReceipt() = runTest {
        val repository = EmptyHostHistory().apply { batchGate = CompletableDeferred() }
        val controller = controller(repository, batched = true)
        runCurrent(); controller.openThread("conv_a"); runCurrent()
        controller.editDraft("立即完成的请求"); controller.sendDraft(); runCurrent()
        advanceTimeBy(1_501); runCurrent()
        val member = repository.batches.single().messages.single()
        repository.events.emit(VerifiedConversationEvent.MessageStatus(
            "evt_completed", 3, ConversationId("conv_a"), "server_${member.clientMessageId.value}",
            member.clientMessageId, AgentMessageStatus.COMPLETED, 3, null,
        ))
        runCurrent()
        repository.batchGate!!.complete(Unit); runCurrent()
        val row = (controller.state.value.timeline as Loadable.Ready).value.single { it.isUser }
        assertEquals(AgentMessageStatus.COMPLETED, row.messageStatus)
        assertEquals("完成回执不能重新进入等待回复", GenerationState.COMPLETED, controller.state.value.generation)
        controller.close()
    }

    @Test fun cancellationBeforeAcceptanceStaysTerminalAndANewSendCanStart() = runTest {
        val repository = EmptyHostHistory().apply { batchGate = CompletableDeferred() }
        val controller = controller(repository, batched = true)
        runCurrent(); controller.openThread("conv_a"); runCurrent()
        controller.editDraft("已经取消的回合"); controller.sendDraft(); runCurrent()
        advanceTimeBy(1_501); runCurrent()
        repository.events.emit(VerifiedConversationEvent.GenerationCancelled(
            "evt_cancelled", 3, "gen_old", ConversationId("conv_a"),
        ))
        runCurrent()
        repository.batchGate!!.complete(Unit); runCurrent()
        assertEquals("迟到回执不能撤销取消", GenerationState.CANCELLED, controller.state.value.generation)
        repository.batchGate = null
        controller.editDraft("下一回合"); controller.sendDraft(); runCurrent()
        advanceTimeBy(1_501); runCurrent()
        assertEquals("新提交可以正常排队", GenerationState.QUEUED, controller.state.value.generation)
        controller.close()
    }

    @Test fun acceptanceStorageFailureKeepsTheFrozenBatchForQueryRecovery() = runTest {
        val persistence = MemoryPersistence().apply { failNextAcceptanceWrite = true }
        val repository = EmptyHostHistory()
        val controller = controller(repository, persistence, true)
        runCurrent(); controller.openThread("conv_a"); runCurrent()
        controller.editDraft("已受理但本地写盘失败"); controller.sendDraft(); runCurrent()
        advanceTimeBy(1_501); runCurrent()
        controller.retryPendingSubmissions(); runCurrent()

        assertEquals("写盘失败后仍能按原身份查询", 1, repository.queries)
        assertEquals("已受理请求不再次执行", 1, repository.batches.size)
        assertEquals(LocalSubmissionState.ACCEPTED, persistence.loadSendAtoms("conv_a").single().submissionState)
        assertEquals(listOf("已受理但本地写盘失败"), userTexts(controller))
        controller.close()
    }

    @Test fun cancelledTransportReleasesTheBatchGuardForConfirmedRecovery() = runTest {
        val repository = EmptyHostHistory().apply { cancelBatchRequest = true }
        val controller = controller(repository, batched = true)
        runCurrent(); controller.openThread("conv_a"); runCurrent()
        controller.editDraft("请求中断后确认恢复"); controller.sendDraft(); runCurrent()
        advanceTimeBy(1_501); runCurrent()
        repository.cancelBatchRequest = false
        controller.retryPendingSubmissions(); runCurrent()

        assertEquals(1, repository.queries)
        assertEquals("取消后仍可恢复同一个批次", 2, repository.batches.size)
        assertEquals(repository.batches.first().batchId, repository.batches.last().batchId)
        assertEquals(listOf("请求中断后确认恢复"), userTexts(controller))
        controller.close()
    }

    @Test fun preparedBatchSurvivesRestartWithoutAutomaticallySending() = runTest {
        val persistence = MemoryPersistence()
        val firstRepository = EmptyHostHistory()
        val first = controller(firstRepository, persistence, true)
        runCurrent(); first.openThread("conv_a"); runCurrent()
        first.editDraft("重启后需要确认"); first.sendDraft(); runCurrent()
        first.close()
        val secondRepository = EmptyHostHistory()
        val second = controller(secondRepository, persistence, true)
        runCurrent()
        assertEquals(listOf("重启后需要确认"), userTexts(second))
        assertTrue(secondRepository.batches.isEmpty())
        assertEquals(LocalSubmissionState.OUTCOME_UNKNOWN, persistence.loadSendAtoms("conv_a").single().submissionState)
        second.retryPendingSubmissions(); runCurrent()
        assertEquals(1, secondRepository.queries)
        assertEquals(1, secondRepository.batches.size)
        assertEquals(persistence.loadSendAtoms("conv_a").single().clientMessageId, secondRepository.batches.single().messages.single().clientMessageId)
        second.close()
    }

    @Test fun recoveryQueriesAcceptedBatchInsteadOfExecutingItAgain() = runTest {
        val persistence = MemoryPersistence()
        val repository = EmptyHostHistory().apply { loseBatchReceipt = true }
        val first = controller(repository, persistence, true)
        runCurrent(); first.openThread("conv_a"); runCurrent()
        first.editDraft("只运行一次"); first.sendDraft(); runCurrent()
        advanceTimeBy(1_501); runCurrent()
        assertEquals(1, repository.batches.size)
        first.close()
        repository.loseBatchReceipt = false
        val second = controller(repository, persistence, true)
        runCurrent()
        second.retryPendingSubmissions(); runCurrent()
        assertEquals(1, repository.queries)
        assertEquals("已经受理的批次不得再次执行", 1, repository.batches.size)
        assertEquals(listOf("只运行一次"), userTexts(second))
        second.close()
    }

    @Test fun anUnknownOrdinarySendIsQueriedBeforeRetryAfterRestart() = runTest {
        val persistence = MemoryPersistence()
        val repository = EmptyHostHistory().apply { loseMessageReceipt = true }
        val first = controller(repository, persistence)
        runCurrent(); first.openThread("conv_a"); runCurrent()
        first.editDraft("普通消息的回执丢失"); first.sendDraft(); runCurrent()
        val originalId = repository.messages.single().clientMessageId
        first.close()

        repository.loseMessageReceipt = false
        val second = controller(repository, persistence)
        runCurrent()
        assertTrue(second.state.value.hasRecoverableSubmissions)
        second.retryPendingSubmissions(); runCurrent()

        assertEquals("重启后先按原身份查询", 1, repository.queries)
        assertEquals("已受理的普通消息不能再次执行", 1, repository.messages.size)
        assertEquals(originalId, persistence.loadSendAtoms("conv_a").single().clientMessageId)
        assertEquals(LocalSubmissionState.ACCEPTED, persistence.loadSendAtoms("conv_a").single().submissionState)
        assertEquals(listOf("普通消息的回执丢失"), userTexts(second))
        second.close()
    }

    @Test fun ordinaryRecoveryDoesNotReplaceANewerDraft() = runTest {
        val persistence = MemoryPersistence()
        val repository = EmptyHostHistory().apply { loseMessageReceipt = true }
        val first = controller(repository, persistence)
        runCurrent(); first.openThread("conv_a"); runCurrent()
        first.editDraft("等待查询的旧消息"); first.sendDraft(); runCurrent()
        first.editDraft("用户刚写的新草稿")
        first.close()
        repository.loseMessageReceipt = false
        val restarted = controller(repository, persistence)
        runCurrent(); restarted.retryPendingSubmissions(); runCurrent()

        assertEquals(1, repository.queries)
        assertEquals(1, repository.messages.size)
        assertEquals("用户刚写的新草稿", restarted.state.value.draft)
        assertEquals(listOf("等待查询的旧消息"), userTexts(restarted))
        restarted.close()
    }

    @Test fun aFailedOutcomeQueryCannotSendTheUnknownMessageAgain() = runTest {
        val repository = EmptyHostHistory().apply { loseMessageReceipt = true }
        val controller = controller(repository)
        runCurrent(); controller.openThread("conv_a"); runCurrent()
        controller.editDraft("查询失败时先保留"); controller.sendDraft(); runCurrent()
        repository.queryFailure = true
        controller.sendDraft(); runCurrent()

        assertEquals(1, repository.queries)
        assertEquals("查询失败不能自动重发", 1, repository.messages.size)
        assertEquals(listOf("查询失败时先保留"), userTexts(controller))
        assertTrue(controller.state.value.notice?.startsWith("SEND_OUTCOME_UNKNOWN:") == true)
        controller.close()
    }

    @Test fun anExplicitlyAbsentOrdinaryMessageRetriesWithTheOriginalIdentity() = runTest {
        val repository = EmptyHostHistory().apply {
            loseMessageReceipt = true
            commitOrdinaryMessage = false
        }
        val controller = controller(repository)
        runCurrent(); controller.openThread("conv_a"); runCurrent()
        controller.editDraft("查询不存在后重试"); controller.sendDraft(); runCurrent()
        val originalId = repository.messages.single().clientMessageId
        repository.loseMessageReceipt = false
        repository.commitOrdinaryMessage = true
        controller.sendDraft(); runCurrent()

        assertEquals(1, repository.queries)
        assertEquals(listOf(originalId, originalId), repository.messages.map { it.clientMessageId })
        assertEquals(listOf("查询不存在后重试"), userTexts(controller))
        controller.close()
    }

    @Test fun anotherThreadsLateReceiptCannotEraseAnUnknownSendIdentity() = runTest {
        val persistence = MemoryPersistence()
        val repository = EmptyHostHistory().apply {
            ordinaryGates["conv_a"] = CompletableDeferred()
            loseReceiptOnConversation = "conv_b"
        }
        val first = controller(repository, persistence)
        runCurrent(); first.openThread("conv_a"); runCurrent()
        first.editDraft("A 的慢请求"); first.sendDraft(); runCurrent()
        first.openThread("conv_b"); runCurrent()
        first.editDraft("B 的未知请求"); first.sendDraft(); runCurrent()
        val originalId = repository.messages.last().clientMessageId
        repository.ordinaryGates.getValue("conv_a").complete(Unit); runCurrent()
        first.close()
        repository.loseReceiptOnConversation = null
        val restarted = controller(repository, persistence)
        runCurrent(); restarted.sendDraft(); runCurrent()

        assertEquals("B 的结果必须先查询", 1, repository.queries)
        assertEquals("A 的回执不能导致 B 重复提交", 2, repository.messages.size)
        assertEquals(originalId, persistence.loadSendAtoms("conv_b").single().clientMessageId)
        restarted.close()
    }

    @Test fun anotherThreadsLateFailureCannotChangeTheCurrentComposerOrNotice() = runTest {
        val repository = EmptyHostHistory().apply {
            ordinaryGates["conv_a"] = CompletableDeferred()
            loseReceiptOnConversation = "conv_a"
        }
        val controller = controller(repository)
        runCurrent(); controller.openThread("conv_a"); runCurrent()
        controller.editDraft("A 的迟到错误"); controller.sendDraft(); runCurrent()
        controller.openThread("conv_b"); runCurrent()
        controller.editDraft("B 的新草稿")
        repository.ordinaryGates.getValue("conv_a").complete(Unit); runCurrent()

        assertEquals("B 的新草稿", controller.state.value.draft)
        assertEquals(ComposerState.EDITING, controller.state.value.composer)
        assertNull("A 的错误不能提示到 B", controller.state.value.notice)
        assertTrue(userTexts(controller).isEmpty())
        controller.close()
    }

    private fun TestScope.controller(repository: ConversationRepository, persistence: WorkbenchPersistence? = null, batched: Boolean = false) = WorkbenchController(
        this,
        repository,
        object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) =
                AgentCommandCatalog(CatalogVersion("test"), emptyList())
        },
        { ConversationScope("profile", "gateway", "account", "install") },
        persistence = persistence,
        supportsMessageBatches = batched,
        replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false),
    )

    private fun userTexts(controller: WorkbenchController): List<String> =
        (controller.state.value.timeline as? Loadable.Ready)?.value.orEmpty().filter { it.isUser }.map { it.text }

    private class MemoryPersistence : WorkbenchPersistence {
        var failNextAcceptanceWrite = false
        private var checkpoint: WorkbenchCheckpoint? = null
        private val atoms = linkedMapOf<String, List<LocalSendAtom>>()
        override fun load() = checkpoint
        override fun save(checkpoint: WorkbenchCheckpoint) { this.checkpoint = checkpoint }
        override fun loadSendAtoms(conversationId: String) = atoms[conversationId].orEmpty()
        override fun saveSendAtoms(conversationId: String, atoms: List<LocalSendAtom>) {
            if (failNextAcceptanceWrite && atoms.any { it.submissionState == LocalSubmissionState.ACCEPTED }) {
                failNextAcceptanceWrite = false
                error("MIRROR_WRITE_FAILED")
            }
            this.atoms[conversationId] = atoms.toList()
        }
    }

    private class EmptyHostHistory : ConversationRepository, MessageOutcomeQuery {
        val events = MutableSharedFlow<VerifiedConversationEvent>(extraBufferCapacity = 16)
        val batches = mutableListOf<MessageBatch>()
        val messages = mutableListOf<OutgoingMessage>()
        var queries = 0
        var batchGate: CompletableDeferred<Unit>? = null
        var loseBatchReceipt = false
        var loseMessageReceipt = false
        var commitOrdinaryMessage = true
        var queryFailure = false
        var cancelBatchRequest = false
        var loseReceiptOnConversation: String? = null
        val ordinaryGates = mutableMapOf<String, CompletableDeferred<Unit>>()
        private val accepted = linkedMapOf<String, TimelineMessage>()
        override suspend fun listConversations(scope: ConversationScope, page: PageRequest) = ConversationPage(
            listOf("conv_a", "conv_b").mapIndexed { index, id -> ConversationSummary(ConversationId(id), id, index.toLong()) },
            null,
        )
        override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) =
            Conversation(ConversationId("conv_a"), "conv_a", 0)
        override suspend fun submitMessage(message: OutgoingMessage) = submitMessage("conv_a", message)
        override suspend fun submitMessage(conversationId: String, message: OutgoingMessage): MessageAcceptance {
            messages += message
            if (commitOrdinaryMessage) {
                accepted[message.clientMessageId.value] = TimelineMessage(
                    "server_${message.clientMessageId.value}", "user", emptyList(), 0, "queued",
                    ConversationId(conversationId), clientMessageId = message.clientMessageId,
                )
            }
            ordinaryGates[conversationId]?.await()
            if (loseMessageReceipt || loseReceiptOnConversation == conversationId) throw java.io.IOException("RESPONSE_LOST")
            return MessageAcceptance("server_${message.clientMessageId.value}", message.clientMessageId.value)
        }
        override suspend fun submitBatch(batch: MessageBatch): BatchAcceptance {
            batches += batch
            if (cancelBatchRequest) throw kotlinx.coroutines.CancellationException("REQUEST_CANCELLED")
            batch.messages.forEach { message -> accepted[message.clientMessageId.value] = TimelineMessage(
                "server_${message.clientMessageId.value}", "user", emptyList(), 0, "queued",
                ConversationId(batch.clientConversationId!!), clientMessageId = message.clientMessageId,
            ) }
            batchGate?.await()
            if (loseBatchReceipt) throw java.io.IOException("RESPONSE_LOST")
            return BatchAcceptance(batch.batchId, batch.messages.associate { it.clientMessageId.value to "server_${it.clientMessageId.value}" })
        }
        override suspend fun queryMessage(conversationId: String, clientMessageId: ClientMessageId): TimelineMessage? {
            queries++
            if (queryFailure) throw java.io.IOException("QUERY_UNAVAILABLE")
            return accepted[clientMessageId.value]?.takeIf { it.conversationId?.value == conversationId }
        }
        override suspend fun cancelGeneration(generationId: String, requestId: String) = CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
        override fun observeEvents(scope: ConversationScope) = events
    }
}
