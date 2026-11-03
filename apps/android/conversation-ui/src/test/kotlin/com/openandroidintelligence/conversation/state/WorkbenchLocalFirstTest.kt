package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WorkbenchLocalFirstTest {
    private val account = ConversationScope("profile", "core", "account", "install")
    private val savedThread = ConversationSummary(ConversationId("cached"), "已保存的会话", 1)

    @Test fun aNonAuthoritativeEmptyListCannotEraseTheReadableLocalList() = runTest {
        val store = MemoryStore(WorkbenchCheckpoint(listOf(savedThread), null, "", emptyList(), emptyMap(),
            "", 0, emptyList(), null, emptySet(), emptySet()))
        val host = Host().apply { conversations = ConversationPage(emptyList(), null, authoritative = false) }
        val controller = WorkbenchController(backgroundScope, host, catalog, { account }, persistence = store,
            restoreLastThread = false, persistenceDispatcher = StandardTestDispatcher(testScheduler))
        try {
            assertEquals(listOf("cached"), (controller.state.value.threads as Loadable.Ready).value.map { it.id.value })
            runCurrent()
            assertEquals("非权威空结果不得擦除可读镜像", listOf("cached"),
                (controller.state.value.threads as? Loadable.Ready)?.value.orEmpty().map { it.id.value })
        } finally { controller.close() }
    }

    @Test fun returningToAnAlreadyReadConversationPublishesMemoryBeforeAnyIo() = runTest {
        val host = Host().apply {
            conversations = ConversationPage(listOf("a", "b").map { ConversationSummary(ConversationId(it), it, 1) }, null)
            messages["a"] = listOf(TimelineMessage("user-a", "user", listOf(MessagePart.Text("会话 A 的内容")), 1,
                conversationId = ConversationId("a")))
            messages["b"] = listOf(TimelineMessage("user-b", "user", listOf(MessagePart.Text("会话 B 的内容")), 2,
                conversationId = ConversationId("b")))
        }
        val controller = WorkbenchController(backgroundScope, host, catalog, { account }, persistence = MemoryStore(null),
            restoreLastThread = false, persistenceDispatcher = StandardTestDispatcher(testScheduler))
        try {
            runCurrent()
            controller.openThread("a"); runCurrent()
            assertEquals("会话 A 的内容", (controller.state.value.timeline as Loadable.Ready).value.single().text)
            controller.openThread("b"); runCurrent()
            host.historyGate = CompletableDeferred()
            controller.openThread("a")
            assertEquals("内存命中不能先发布 Loading 或等待 IO", listOf("会话 A 的内容"),
                (controller.state.value.timeline as? Loadable.Ready)?.value.orEmpty().map { it.text })
            assertFalse(host.historyGate!!.isCompleted)
        } finally { host.historyGate?.complete(Unit); controller.close() }
    }

    @Test fun localListWithoutACheckpointIsReadableBeforeAgentDiscovery() = runTest {
        val store = MemoryStore(null).apply { localConversations = ConversationPage(listOf(savedThread), null, authoritative = false) }
        val controller = WorkbenchController(backgroundScope, Host(), catalog, { account }, persistence = store,
            restoreLastThread = false, canReadConversations = { false }, persistenceDispatcher = StandardTestDispatcher(testScheduler))
        try {
            runCurrent()
            assertEquals("本地列表不能依赖远端 Agent 发现", listOf("cached"),
                (controller.state.value.threads as? Loadable.Ready)?.value.orEmpty().map { it.id.value })
        } finally { controller.close() }
    }

    @Test fun anUnavailableAgentKeepsTheDraftReadableButCannotAcceptASend() = runTest {
        val controller = WorkbenchController(backgroundScope, Host(), catalog, { account }, restoreLastThread = false)
        try {
            runCurrent()
            controller.editDraft("离线时保留的草稿")
            controller.setRemoteConversationOnline(false)
            assertFalse("在线 Core 不能冒充可投递的 Agent", controller.state.value.canSend)
            controller.sendDraft()
            assertEquals("离线时保留的草稿", controller.state.value.draft)
            controller.setRemoteConversationOnline(true)
            assertTrue(controller.state.value.canSend)
        } finally { controller.close() }
    }

    @Test fun restoringAnExplicitEmptyDraftDoesNotRefillAnOlderSubmission() = runTest {
        val saved = WorkbenchCheckpoint(listOf(savedThread), "cached", "已保存的会话", emptyList(), emptyMap(),
            "", 4, emptyList(), SavedSubmission("旧发送内容", emptyList(), 3, "cached", "client-old"),
            emptySet(), emptySet(), draftExplicit = true)
        val controller = WorkbenchController(backgroundScope, Host(), catalog, { account }, persistence = MemoryStore(saved),
            persistenceDispatcher = StandardTestDispatcher(testScheduler))
        try {
            assertEquals("已保存的空草稿不能由旧发送推断回填", "", controller.state.value.draft)
        } finally { controller.close() }
    }

    @Test fun switchingCachedConversationsKeepsEachDraftWithItsOwnConversation() = runTest {
        val controller = WorkbenchController(backgroundScope, Host(), catalog, { account }, restoreLastThread=false)
        try {
            runCurrent(); controller.openThread("a"); runCurrent(); controller.editDraft("A 的草稿")
            controller.openThread("b"); runCurrent(); controller.editDraft("B 的草稿")
            controller.openThread("a")
            assertEquals("A 的草稿", controller.state.value.draft)
            controller.openThread("b")
            assertEquals("B 的草稿", controller.state.value.draft)
        } finally { controller.close() }
    }

    @Test fun cachedHistoryUsesTheNegotiatedEventSynchronizerWithoutASecondFullRead() = runTest {
        val message=TimelineMessage("saved","assistant",listOf(MessagePart.Text("已保存正文")),1,
            conversationId=ConversationId("cached"))
        val checkpoint=WorkbenchCheckpoint(listOf(savedThread),"cached","缓存",listOf(message),emptyMap(),"",0,
            emptyList(),null,emptySet(),emptySet())
        val host=Host()
        val eventRepository=object:ConversationRepository by host, EventSynchronizedHistory {
            override fun observesHistory(conversationId:String)=true
        }
        val controller=WorkbenchController(backgroundScope,eventRepository,catalog,{account},persistence=MemoryStore(checkpoint),
            restoreLastThread=false,persistenceDispatcher=StandardTestDispatcher(testScheduler))
        try {
            runCurrent(); controller.openThread("cached"); runCurrent()
            assertEquals("事件同步已负责补偿时不重复完整历史请求",0,host.fullHistoryReads)
            assertEquals("已保存正文",(controller.state.value.timeline as Loadable.Ready).value.single().text)
        } finally { controller.close() }
    }

    @Test fun rebuildingOneConversationRestoresOnlyItsOwnAttachmentDrafts() = runTest {
        val a=AttachmentDraft(AttachmentDraftId("attachment-a"),"a.png","image/png",1,"",AttachmentState.UPLOADING)
        val b=AttachmentDraft(AttachmentDraftId("attachment-b"),"b.png","image/png",1,"",AttachmentState.UPLOADING)
        val saved=WorkbenchCheckpoint(listOf(savedThread),"cached","缓存",emptyList(),emptyMap(),"B",2,listOf(b),null,
            emptySet(),emptySet(),draftExplicit=true,
            composerDrafts=listOf(SavedComposerDraft("other","A",1,listOf(a)),SavedComposerDraft("cached","B",2,listOf(b))))
        val drafts=object:AttachmentDraftCoordinator {
            override fun restoredDrafts()=listOf(a,b)
            override fun remoteAttachmentId(draftId:String):String?=null
            override suspend fun prepare(selection:LocalAttachmentSelection):AttachmentDraft=error("未准备附件")
            override suspend fun armSubmission(draftId:String,revision:Long):PendingSubmissionIntent=error("未发送")
            override suspend fun cancelSubmission(intentId:String)=CancelSubmissionResult(true)
            override fun observe(draftId:String)=kotlinx.coroutines.flow.emptyFlow<AttachmentDraftState>()
            override fun retry(draftId:String)=Unit
            override suspend fun discard(draftId:String)=Unit
            override suspend fun releaseAfterSubmit(draftId:String)=Unit
        }
        val controller=WorkbenchController(backgroundScope,Host(),catalog,{account},attachmentCoordinator=drafts,
            persistence=MemoryStore(saved),persistenceDispatcher=StandardTestDispatcher(testScheduler))
        try { assertEquals(listOf("attachment-b"),controller.state.value.attachments.map { it.id.value }) }
        finally { controller.close() }
    }

    @Test fun aRebuiltControllerDisplaysTheDurableFrozenMessageWithoutAutomaticallySendingIt() = runTest {
        val saved=WorkbenchCheckpoint(listOf(savedThread),"cached","缓存",emptyList(),emptyMap(),"",4,emptyList(),null,
            emptySet(),emptySet(),draftExplicit=true,
            pendingSubmissions=listOf(SavedSubmission("持久冻结消息",listOf("attachment-local"),4,"cached","frozen-client")))
        val controller=WorkbenchController(backgroundScope,Host(),catalog,{account},persistence=MemoryStore(saved),
            persistenceDispatcher=StandardTestDispatcher(testScheduler))
        try {
            runCurrent()
            assertEquals(listOf("持久冻结消息"),(controller.state.value.timeline as? Loadable.Ready)?.value.orEmpty().filter { it.isUser }.map { it.text })
            assertEquals("",controller.state.value.draft)
            assertTrue(controller.state.value.hasRecoverableSubmissions)
        } finally { controller.close() }
    }

    @Test fun aForwardedEditCannotReviveADraftAlreadyFrozenByTheReplacementController() = runTest {
        val controller=WorkbenchController(backgroundScope,Host(),catalog,{account},restoreLastThread=false)
        try {
            runCurrent();controller.openThread("cached");runCurrent()
            controller.editDraft("发送过的内容")
            val version=controller.state.value.draftVersion
            controller.sendDraft()
            controller.editDraftAtVersion("发送过的内容",version)
            assertEquals("交接旧输入不能复活已经冻结的草稿","",controller.state.value.draft)
        } finally { controller.close() }
    }

    private val catalog = object : AgentCommandCatalogRepository {
        override suspend fun get(gatewayId: String, languageCode: String) =
            AgentCommandCatalog(CatalogVersion("test"), emptyList())
    }
    private class MemoryStore(var checkpoint: WorkbenchCheckpoint?) : AwaitableWorkbenchPersistence {
        var localConversations: ConversationPage? = null
        override suspend fun loadConversations() = localConversations
        override suspend fun loadTimeline(conversationId: String): TimelinePage? = null
        override suspend fun loadKnownMediaMessages() = emptyList<TimelineMessage>()
        override suspend fun saveCheckpoint(checkpoint: WorkbenchCheckpoint) = save(checkpoint)
        override suspend fun saveSendAtomsDurably(conversationId: String, atoms: List<LocalSendAtom>) = saveSendAtoms(conversationId, atoms)
        override suspend fun flush() = Unit
        override suspend fun closeStore() = Unit
        private val atoms = mutableMapOf<String, List<LocalSendAtom>>()
        override fun load() = checkpoint
        override fun save(checkpoint: WorkbenchCheckpoint) { this.checkpoint = checkpoint }
        override fun loadSendAtoms(conversationId: String) = atoms[conversationId].orEmpty()
        override fun saveSendAtoms(conversationId: String, atoms: List<LocalSendAtom>) { this.atoms[conversationId] = atoms }
    }
    private class Host : ConversationRepository {
        var conversations = ConversationPage(emptyList(), null)
        val messages = mutableMapOf<String, List<TimelineMessage>>()
        var historyGate: CompletableDeferred<Unit>? = null
        var fullHistoryReads = 0
        override suspend fun listConversations(scope: ConversationScope, page: PageRequest) = conversations
        override suspend fun timeline(conversationId: String, page: PageRequest): TimelinePage {
            fullHistoryReads++
            historyGate?.await()
            return TimelinePage(messages[conversationId].orEmpty(), null)
        }
        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation = error("未创建会话")
        override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance = error("未发送消息")
        override suspend fun submitBatch(batch: MessageBatch): BatchAcceptance = error("未发送批次")
        override suspend fun cancelGeneration(generationId: String, requestId: String) = CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
        override fun observeEvents(scope: ConversationScope) = emptyFlow<VerifiedConversationEvent>()
    }
}
