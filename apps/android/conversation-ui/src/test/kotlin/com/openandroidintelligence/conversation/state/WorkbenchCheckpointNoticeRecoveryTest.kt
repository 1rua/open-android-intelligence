package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.gateway.diagnostics.GatewayLog
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** 经公开草稿/事件入口触发，断言真实持久化端口的失败边界和通知归属。 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WorkbenchCheckpointNoticeRecoveryTest {
    @Test fun successfulRetryClearsOnlyTheCheckpointFailureAndCanBeLoaded() = runTest {
        val fixture = Fixture(this)
        try {
            fixture.failDraft("失败草稿")
            assertNotEquals("失败写入不能替换持久快照", "失败草稿", fixture.storage.load()!!.draft)
            fixture.controller.editDraft("恢复草稿"); runCurrent()
            fixture.controller.flushPendingPersistence()
            assertEquals("恢复草稿", fixture.storage.load()!!.draft)
            assertNull("真实保存端口成功后应清除本次 checkpoint 错误", fixture.controller.state.value.notice)
        } finally { fixture.controller.close() }
    }

    @Test fun sameValueEventFailureSurvivesAnUnrelatedCheckpointRecovery() = runTest {
        val fixture = Fixture(this)
        try {
            fixture.failDraft("失败草稿")
            val checkpointNotice = fixture.controller.state.value.notice
            fixture.storage.rejectAtomReads = true
            fixture.host.events.emit(event("未提交事件")); runCurrent()
            assertEquals(1, fixture.storage.atomReadFailures)
            assertEquals("两种来源故意使用同一错误码", checkpointNotice, fixture.controller.state.value.notice)
            fixture.controller.editDraft("checkpoint 已恢复"); runCurrent()
            assertEquals("checkpoint 已恢复", fixture.storage.load()!!.draft)
            assertEquals("事件读取/提交仍未恢复，不能借 checkpoint 的成功清理", checkpointNotice, fixture.controller.state.value.notice)
            assertFalse("事件失败不能被当作本地提交完成", "未提交事件" in fixture.storage.load()!!.eventIds)
        } finally { fixture.controller.close() }
    }

    @Test fun aLaterCheckpointFailureIsStillVisibleUntilItsOwnRetrySucceeds() = runTest {
        val fixture = Fixture(this)
        try {
            fixture.failDraft("第一次失败")
            fixture.failDraft("第二次失败")
            assertEquals(2, fixture.storage.checkpointFailures)
            assertTrue(fixture.controller.state.value.notice.orEmpty().startsWith("LOCAL_SAVE_FAILED:"))
            fixture.controller.editDraft("最终恢复"); runCurrent()
            assertEquals("最终恢复", fixture.storage.load()!!.draft)
            assertNull(fixture.controller.state.value.notice)
        } finally { fixture.controller.close() }
    }

    @Test fun anEarlierSuccessfulWriteCannotClearALaterQueuedFailure() = runTest {
        val fixture = Fixture(this)
        val release = CompletableDeferred<Unit>()
        try {
            fixture.failDraft("原失败")
            fixture.storage.blockNextCheckpoint = release
            fixture.controller.editDraft("先成功"); runCurrent()
            fixture.storage.rejectDraft = "队列后续失败"
            fixture.controller.editDraft("队列后续失败"); runCurrent()
            release.complete(Unit); runCurrent()
            assertEquals("先成功", fixture.storage.load()!!.draft)
            assertEquals(2, fixture.storage.checkpointFailures)
            assertTrue("队列后续失败时不能经过成功清理分支", fixture.controller.state.value.notice.orEmpty().startsWith("LOCAL_SAVE_FAILED:"))
        } finally { release.complete(Unit); fixture.controller.close() }
    }

    @Test fun anActiveWriterKeepsOneWriterAndCommitsTheLatestQueuedSnapshotOnce() = runTest {
        val fixture = Fixture(this)
        val release = CompletableDeferred<Unit>()
        try {
            fixture.failDraft("失败草稿")
            val before = fixture.storage.checkpointWrites
            fixture.storage.blockNextCheckpoint = release
            fixture.controller.editDraft("恢复中的快照"); runCurrent()
            fixture.controller.editDraft("排队最新快照"); runCurrent()
            val flushing = backgroundScope.launch { fixture.controller.flushPendingPersistence() }
            runCurrent()
            assertEquals("已有 writer 活跃时不得启动并行 writer", before + 1, fixture.storage.checkpointWrites)
            assertEquals(1, fixture.storage.maxActiveWrites)
            release.complete(Unit); runCurrent()
            flushing.join()
            assertEquals("失败后已有 pending 快照应只写一次", before + 2, fixture.storage.checkpointWrites)
            assertEquals("排队最新快照", fixture.storage.load()!!.draft)
            assertNull(fixture.controller.state.value.notice)
        } finally { release.complete(Unit); fixture.controller.close() }
    }

    @Test fun accountSnapshotAfterThreadSwitchRecoversThePreviousThreadsDraft() = runTest {
        val fixture = Fixture(this)
        val release = CompletableDeferred<Unit>()
        try {
            fixture.storage.blockNextCheckpoint = release
            fixture.storage.failNextCheckpoint = true
            fixture.controller.editDraft("旧会话待恢复草稿"); runCurrent()
            fixture.controller.openThread("other"); runCurrent()
            release.complete(Unit); runCurrent()
            assertTrue(fixture.controller.state.value.notice.orEmpty().startsWith("LOCAL_SAVE_FAILED:"))
            fixture.controller.flushPendingPersistence(); runCurrent()
            val recovered = fixture.storage.load()!!
            assertEquals("other", recovered.threadId)
            assertEquals("旧会话待恢复草稿", recovered.composerDrafts.single { it.threadId == "chat" }.text)
            assertNull("新账号级快照已含旧会话草稿，切换不能永久保留旧失败通知", fixture.controller.state.value.notice)
        } finally { release.complete(Unit); fixture.controller.close() }
    }

    @Test fun aStaleComposerVersionCannotPretendToRecoverPersistence() = runTest {
        val fixture = Fixture(this)
        try {
            val oldVersion = fixture.controller.state.value.draftVersion
            fixture.failDraft("尚未保存")
            fixture.controller.editDraftAtVersion("旧编辑结果", oldVersion); runCurrent()
            assertEquals("尚未保存", fixture.controller.state.value.draft)
            assertNotEquals("旧编辑结果", fixture.storage.load()!!.draft)
            assertTrue(fixture.controller.state.value.notice.orEmpty().startsWith("LOCAL_SAVE_FAILED:"))
            fixture.controller.editDraftAtVersion("当前版本恢复", fixture.controller.state.value.draftVersion); runCurrent()
            assertEquals("当前版本恢复", fixture.storage.load()!!.draft)
            assertNull(fixture.controller.state.value.notice)
        } finally { fixture.controller.close() }
    }

    @Test fun checkpointRecoveryPreservesANewerDifferentNotice() = runTest {
        val fixture = Fixture(this)
        try {
            fixture.failDraft("失败草稿")
            fixture.controller.setRemoteConversationOnline(false)
            fixture.controller.sendDraft(); runCurrent()
            assertEquals("AGENT_OFFLINE", fixture.controller.state.value.notice)
            fixture.controller.editDraft("保存恢复"); runCurrent()
            assertEquals("保存恢复", fixture.storage.load()!!.draft)
            assertEquals("AGENT_OFFLINE", fixture.controller.state.value.notice)
        } finally { fixture.controller.close() }
    }

    @Test fun cancelledRecoveryCannotClearTheFailureOrClaimASuccessfulWrite() = runTest {
        val fixture = Fixture(this)
        val release = CompletableDeferred<Unit>()
        try {
            fixture.failDraft("未恢复草稿")
            val notice = fixture.controller.state.value.notice
            fixture.storage.blockNextCheckpoint = release
            fixture.controller.editDraft("取消中的恢复"); runCurrent()
            fixture.controller.close(); runCurrent()
            try {
                fixture.controller.flushPendingPersistence()
                fail("取消的 writer 不能被当作保存成功")
            } catch (failure: IOException) { assertEquals("LOCAL_SAVE_INTERRUPTED", failure.message) }
            assertEquals(notice, fixture.controller.state.value.notice)
            assertNotEquals("取消中的恢复", fixture.storage.load()!!.draft)
        } finally { release.complete(Unit); fixture.controller.close() }
    }

    @Test fun persistenceDiagnosticsUseOnlyBoundedWhitelistFields() = runTest {
        val previous = GatewayLog.sink
        val logs = mutableListOf<Pair<String, String>>()
        GatewayLog.sink = { tag, message -> if (tag == "OaiPersistence") logs += tag to message }
        val fixture = Fixture(this)
        try {
            fixture.storage.failureMessage = "凭据与正文合成哨兵-not-for-log"
            fixture.failDraft("第一次失败")
            fixture.failDraft("重复失败")
            assertEquals("同阶段同类型连续错误只记录一次", 1, logs.size)
            assertEquals("phase=CHECKPOINT_WRITE result=failed kind=IO code=LOCAL_PERSISTENCE_FAILED", logs.single().second)
            fixture.controller.editDraft("恢复"); runCurrent()
            assertEquals(2, logs.size)
            assertEquals("phase=CHECKPOINT_WRITE result=recovered code=LOCAL_PERSISTENCE_RECOVERED", logs.last().second)
            assertFalse(logs.any { it.second.contains("凭据与正文") || it.second.contains("草稿") })
        } finally { fixture.controller.close(); GatewayLog.sink = previous }
    }

    private class Fixture(val scope: TestScope) {
        val host = Host()
        val storage = Storage()
        private val dispatcher = UnconfinedTestDispatcher(scope.testScheduler)
        val controller = WorkbenchController(scope.backgroundScope, host, object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) =
                AgentCommandCatalog(CatalogVersion("synthetic"), emptyList())
        }, { ConversationScope("profile", "gateway", "account", "install") },
            persistence = storage, mediaDispatcher = dispatcher, persistenceDispatcher = dispatcher,
            replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false))

        init { scope.runCurrent(); controller.openThread("chat"); scope.runCurrent() }

        fun failDraft(text: String) {
            storage.failNextCheckpoint = true
            controller.editDraft(text); scope.runCurrent()
            assertTrue(controller.state.value.notice.orEmpty().startsWith("LOCAL_SAVE_FAILED:"))
        }
    }

    private class Storage : AwaitableWorkbenchPersistence {
        private var checkpoint: WorkbenchCheckpoint? = null
        private val atoms = mutableMapOf<String, List<LocalSendAtom>>()
        var failNextCheckpoint = false
        var rejectDraft: String? = null
        var rejectAtomReads = false
        var atomReadFailures = 0
        var checkpointFailures = 0
        var checkpointWrites = 0
        private var activeWrites = 0
        var maxActiveWrites = 0
        var failureMessage = "SYNTHETIC_STORAGE_FAILURE"
        var blockNextCheckpoint: CompletableDeferred<Unit>? = null
        override fun load() = checkpoint
        override fun save(checkpoint: WorkbenchCheckpoint) { this.checkpoint = checkpoint }
        override fun loadSendAtoms(conversationId: String): List<LocalSendAtom> {
            if (rejectAtomReads) { atomReadFailures++; throw IOException(failureMessage) }
            return atoms[conversationId].orEmpty()
        }
        override fun saveSendAtoms(conversationId: String, atoms: List<LocalSendAtom>) { this.atoms[conversationId] = atoms }
        override suspend fun loadTimeline(conversationId: String): TimelinePage? = null
        override suspend fun loadKnownMediaMessages() = emptyList<TimelineMessage>()
        override suspend fun saveCheckpoint(checkpoint: WorkbenchCheckpoint) {
            checkpointWrites++
            activeWrites++
            maxActiveWrites = maxOf(maxActiveWrites, activeWrites)
            try {
                val release = blockNextCheckpoint
                blockNextCheckpoint = null
                release?.await()
                if (failNextCheckpoint || checkpoint.draft == rejectDraft) {
                    failNextCheckpoint = false
                    checkpointFailures++
                    throw IOException(failureMessage)
                }
                save(checkpoint)
            } finally { activeWrites-- }
        }
        override suspend fun saveSendAtomsDurably(conversationId: String, atoms: List<LocalSendAtom>) = saveSendAtoms(conversationId, atoms)
        override suspend fun flush() = Unit
        override suspend fun closeStore() = Unit
    }

    private class Host : ConversationRepository {
        val events = MutableSharedFlow<VerifiedConversationEvent>(extraBufferCapacity = 16)
        override suspend fun listConversations(scope: ConversationScope, page: PageRequest) =
            ConversationPage(listOf("chat", "other").map { ConversationSummary(ConversationId(it), it, 1) }, null)
        override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
        override fun observeEvents(scope: ConversationScope) = events
        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation = error("合成只读仓储")
        override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance = error("合成只读仓储")
        override suspend fun submitBatch(batch: MessageBatch): BatchAcceptance = error("合成只读仓储")
        override suspend fun cancelGeneration(generationId: String, requestId: String) = CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }

    companion object {
        private fun event(id: String) = VerifiedConversationEvent.TimelineUpsert(id, 1, 1,
            TimelineMessage(id, "assistant", listOf(MessagePart.Text("合成事件")), 1, "CONFIRMED", ConversationId("chat"), contentRevision = 1))
    }
}
