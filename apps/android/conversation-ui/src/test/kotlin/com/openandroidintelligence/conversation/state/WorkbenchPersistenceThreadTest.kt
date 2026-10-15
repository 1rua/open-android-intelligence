package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

/** 使用真实 UI/IO 线程验证磁盘端口边界，不能靠测试调度器的同线程执行冒充 IO。 */
class WorkbenchPersistenceThreadTest {
    @Test fun coldHistoryAndSendCommitsNeverReadWriteOrEncodeOnTheUiThread() = runBlocking {
        val ui=Executors.newSingleThreadExecutor { command -> Thread(command,"工作台测试UI") }.asCoroutineDispatcher()
        val lifecycle=SupervisorJob()
        val scope=CoroutineScope(lifecycle+ui)
        val uiThread=withContext(ui) { Thread.currentThread() }
        val persistence=ThreadCheckingPersistence(uiThread)
        val host=Host()
        val controller=withContext(Dispatchers.IO) {
            WorkbenchController(scope,host,object:AgentCommandCatalogRepository {
                override suspend fun get(gatewayId:String,languageCode:String):AgentCommandCatalog {
                    host.initialRequests.await()
                    return AgentCommandCatalog(CatalogVersion("test"),emptyList())
                }
            },{ConversationScope("profile","gateway","account","install")},persistence=persistence,
                replyTimeouts=WorkbenchController.ReplyTimeouts(enabled=false))
        }
        try {
            withContext(ui) { controller.openThread("conv_io") }
            withTimeout(5_000) { persistence.coldReadStarted.await() }
            withContext(ui) {
                assertEquals(Loadable.Loading,controller.state.value.timeline)
                controller.editDraft("恢复完成前保留的新草稿")
                controller.sendDraft()
                assertTrue("磁盘历史尚未恢复时不能提交消息",host.sent.isEmpty())
            }
            persistence.coldReadGate.countDown()
            withTimeout(5_000) { controller.state.first { state ->
                state.timeline is Loadable.Ready && state.hasRecoverableSubmissions
            } }
            withContext(ui) {
                val rows=(controller.state.value.timeline as Loadable.Ready).value
                assertEquals(listOf("已保存的旧消息"),rows.map { it.text })
                assertEquals("恢复完成前保留的新草稿",controller.state.value.draft)
                controller.editDraft("新的已确认提交")
                controller.sendDraft()
            }
            val sent=withTimeout(5_000) { host.submitted.await() }
            withTimeout(5_000) { controller.state.first { state ->
                (state.timeline as? Loadable.Ready)?.value?.any { it.key=="server_${sent.clientMessageId.value}" } == true
            } }
            withContext(ui) { controller.flushPendingPersistence() }
            assertTrue(persistence.calls.any { it=="read-atoms" })
            assertTrue(persistence.calls.any { it=="save-atoms" })
            assertTrue(persistence.calls.any { it=="encode-checkpoint" })
            assertTrue(persistence.calls.any { it=="encode-atoms" })
            assertTrue("所有存储调用都必须离开 UI 线程",persistence.uiViolations.isEmpty())
            host.events.emit(VerifiedConversationEvent.TimelineTombstoned("evt_deleted",4,"server_${sent.clientMessageId.value}",4,
                ConversationId("conv_io")))
            withTimeout(5_000) { controller.state.first { state ->
                (state.timeline as? Loadable.Ready)?.value?.none { it.key=="server_${sent.clientMessageId.value}" } == true
            } }
            withContext(ui) { controller.closeAndAwait() }
            assertTrue("删除与关闭也不能回到 UI 线程读写存储",persistence.uiViolations.isEmpty())
        } finally {
            persistence.coldReadGate.countDown()
            controller.close()
            lifecycle.cancelAndJoin()
            ui.close()
        }
    }

    private class ThreadCheckingPersistence(private val uiThread:Thread):WorkbenchPersistence {
        val calls=CopyOnWriteArrayList<String>()
        val uiViolations=CopyOnWriteArrayList<String>()
        val coldReadStarted=CompletableDeferred<Unit>()
        val coldReadGate=CountDownLatch(1)
        private var checkpoint:WorkbenchCheckpoint?=null
        private val atoms=ConcurrentHashMap<String,List<LocalSendAtom>>().apply {
            put("conv_io",listOf(LocalSendAtom(ConversationId("conv_io"),ClientMessageId("cm_saved"),
                listOf(MessagePart.Text("已保存的旧消息")),1,submissionState=LocalSubmissionState.PREPARED)))
        }
        private fun record(operation:String) {
            calls+=operation
            if (Thread.currentThread()===uiThread) {
                uiViolations+=operation
                error("PERSISTENCE_ON_UI:$operation")
            }
        }
        override fun load():WorkbenchCheckpoint? { record("load-checkpoint"); return checkpoint }
        override fun save(checkpoint:WorkbenchCheckpoint) {
            record("save-checkpoint")
            record("encode-checkpoint")
            ByteArrayOutputStream().use { bytes -> DataOutputStream(bytes).use { it.writeUTF(checkpoint.draft) } }
            this.checkpoint=checkpoint
        }
        override fun loadSendAtoms(conversationId:String):List<LocalSendAtom> {
            record("read-atoms")
            if (conversationId=="conv_io") {
                coldReadStarted.complete(Unit)
                check(coldReadGate.await(5,TimeUnit.SECONDS)) { "TEST_COLD_READ_NOT_RELEASED" }
            }
            return atoms[conversationId].orEmpty().toList()
        }
        override fun saveSendAtoms(conversationId:String,atoms:List<LocalSendAtom>) {
            record("save-atoms")
            record("encode-atoms")
            ByteArrayOutputStream().use { bytes -> DataOutputStream(bytes).use { output ->
                atoms.forEach { output.writeUTF(it.clientMessageId.value) }
            } }
            this.atoms[conversationId]=atoms.toList()
        }
    }

    private class Host:ConversationRepository {
        val initialRequests=CompletableDeferred<Unit>()
        val sent=CopyOnWriteArrayList<OutgoingMessage>()
        val submitted=CompletableDeferred<OutgoingMessage>()
        val events=MutableSharedFlow<VerifiedConversationEvent>(extraBufferCapacity=16)
        override suspend fun listConversations(scope:ConversationScope,page:PageRequest):ConversationPage {
            initialRequests.await()
            return ConversationPage(emptyList(),null)
        }
        override suspend fun timeline(conversationId:String,page:PageRequest)=TimelinePage(emptyList(),null)
        override suspend fun submitMessage(message:OutgoingMessage)=submitMessage("conv_io",message)
        override suspend fun submitMessage(conversationId:String,message:OutgoingMessage):MessageAcceptance {
            sent+=message
            submitted.complete(message)
            return MessageAcceptance("server_${message.clientMessageId.value}",message.clientMessageId.value)
        }
        override fun observeEvents(scope:ConversationScope)=events
        override suspend fun submitBatch(batch:MessageBatch):BatchAcceptance=error("本测试不合并消息")
        override suspend fun createConversation(scope:ConversationScope,clientConversationId:String):Conversation=error("本测试不创建会话")
        override suspend fun cancelGeneration(generationId:String,requestId:String)=CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }
}
