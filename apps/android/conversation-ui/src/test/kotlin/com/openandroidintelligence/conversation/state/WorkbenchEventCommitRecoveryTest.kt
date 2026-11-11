package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** 真实 WorkbenchController，存储只在 flush 注入一次失败，后续立刻恢复正常。 */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchEventCommitRecoveryTest {
    @Test fun normalFlushKeepsReceivingTheNextTerminalEvent() = runTest { scenario(false) }
    @Test fun oneFlushFailureCannotPermanentlyStopReceivingCriticalEvents() = runTest { scenario(true) }
    private suspend fun TestScope.scenario(injectFailure:Boolean) {
        val host=Host()
        val storage=Storage()
        val dispatcher=UnconfinedTestDispatcher(testScheduler)
        val controller=WorkbenchController(backgroundScope,host,object:AgentCommandCatalogRepository {
            override suspend fun get(gatewayId:String,languageCode:String)=AgentCommandCatalog(CatalogVersion("synthetic"),emptyList())
        },{ConversationScope("profile","gateway","account","install")},supportsConversationManagement=true,
            persistence=storage,mediaDispatcher=dispatcher,persistenceDispatcher=dispatcher,
            replyTimeouts=WorkbenchController.ReplyTimeouts(enabled=false))
        try {
            runCurrent();controller.openThread("chat");runCurrent()
            storage.failNextFlush=injectFailure
            host.events.emit(event("first",1));runCurrent()
            host.events.emit(event("terminal",2));runCurrent()
            advanceTimeBy(1001);runCurrent()
            val rows=(controller.state.value.timeline as? Loadable.Ready)?.value.orEmpty()
            val finalVisible=rows.any { it.key=="terminal" }
            println("BUG08_WORKBENCH injectFailure=$injectFailure flushFailures=${storage.flushFailures} eventSubscribers=${host.events.subscriptionCount.value} finalVisible=$finalVisible")
            assertEquals("合成提交故障必须实际发生",if(injectFailure) 1 else 0,storage.flushFailures)
            assertTrue("一次本地 flush 失败后，关键终态仍须自动应用，不能永久结束事件消费者",finalVisible)
        } finally {controller.close()}
    }
    @Test fun persistentStorageFailureStopsWithAVisibleErrorAndReentryCanApplyTheUnmarkedEvent() = runTest {
        val host=Host()
        val storage=Storage()
        val dispatcher=UnconfinedTestDispatcher(testScheduler)
        val controller=WorkbenchController(backgroundScope,host,object:AgentCommandCatalogRepository {
            override suspend fun get(gatewayId:String,languageCode:String)=AgentCommandCatalog(CatalogVersion("synthetic"),emptyList())
        },{ConversationScope("profile","gateway","account","install")},supportsConversationManagement=true,
            persistence=storage,mediaDispatcher=dispatcher,persistenceDispatcher=dispatcher,
            replyTimeouts=WorkbenchController.ReplyTimeouts(enabled=false))
        try {
            runCurrent();controller.openThread("chat");runCurrent()
            storage.rejectEvent="blocked"
            host.events.emit(event("blocked",1));runCurrent()
            advanceTimeBy(2000);runCurrent()
            assertEquals(4,storage.checkpointFailures)
            assertEquals(StreamHealth.FAILED,controller.state.value.streamHealth)
            assertTrue(controller.state.value.notice.orEmpty().startsWith("LOCAL_SAVE_FAILED:"))
            assertFalse("失败事件不能进入已确认集合", "blocked" in storage.checkpoint!!.eventIds)
            storage.rejectEvent=null
            controller.onForegrounded();runCurrent()
            host.events.emit(event("blocked",1));runCurrent()
            assertTrue("恢复存储后重新进入能应用原事件", "blocked" in storage.checkpoint!!.eventIds)
        } finally {controller.close()}
    }

    @Test fun aLogicFailureStopsImmediatelyAndDoesNotMarkTheEventHandled()=runTest {
        val host=Host()
        val storage=Storage()
        val dispatcher=UnconfinedTestDispatcher(testScheduler)
        val controller=WorkbenchController(backgroundScope,host,object:AgentCommandCatalogRepository {
            override suspend fun get(gatewayId:String,languageCode:String)=AgentCommandCatalog(CatalogVersion("synthetic"),emptyList())
        },{ConversationScope("profile","gateway","account","install")},supportsConversationManagement=true,
            persistence=storage,mediaDispatcher=dispatcher,persistenceDispatcher=dispatcher,
            replyTimeouts=WorkbenchController.ReplyTimeouts(enabled=false))
        try {
            runCurrent();controller.openThread("chat");runCurrent()
            storage.rejectEvent="illegal"
            storage.logicFailure=true
            host.events.emit(event("illegal",1));runCurrent()
            advanceTimeBy(2000);runCurrent()
            assertEquals(1,storage.checkpointFailures)
            assertEquals(StreamHealth.FAILED,controller.state.value.streamHealth)
            assertTrue(controller.state.value.notice.orEmpty().startsWith("EVENT_APPLY_FAILED:"))
            assertFalse("illegal" in storage.checkpoint!!.eventIds)
        } finally {controller.close()}
    }

    private fun event(id:String,revision:Long)=VerifiedConversationEvent.TimelineUpsert(id,revision,revision,
        TimelineMessage(id,"assistant",listOf(MessagePart.Text(id)),revision,"CONFIRMED",ConversationId("chat"),contentRevision=revision))
    private class Storage:AwaitableWorkbenchPersistence {
        var checkpoint:WorkbenchCheckpoint?=null
        var failNextFlush=false
        var flushFailures=0
        var checkpointFailures=0
        var rejectEvent:String?=null
        var logicFailure=false
        private val atoms=mutableMapOf<String,List<LocalSendAtom>>()
        override fun load()=checkpoint
        override fun save(checkpoint:WorkbenchCheckpoint){this.checkpoint=checkpoint}
        override fun loadSendAtoms(conversationId:String)=atoms[conversationId].orEmpty()
        override fun saveSendAtoms(conversationId:String,atoms:List<LocalSendAtom>){this.atoms[conversationId]=atoms}
        override suspend fun loadTimeline(conversationId:String):TimelinePage?=null
        override suspend fun loadKnownMediaMessages()=emptyList<TimelineMessage>()
        override suspend fun saveCheckpoint(checkpoint:WorkbenchCheckpoint){
            if(rejectEvent?.let {it in checkpoint.eventIds}==true){
                checkpointFailures++
                if(logicFailure)throw IllegalStateException("SYNTHETIC_LOGIC_FAILURE")
                throw IOException("SYNTHETIC_PERSISTENT_COMMIT_FAILED")
            }
            save(checkpoint)
        }
        override suspend fun saveSendAtomsDurably(conversationId:String,atoms:List<LocalSendAtom>)=saveSendAtoms(conversationId,atoms)
        override suspend fun flush(){if(failNextFlush){failNextFlush=false;flushFailures++;throw IOException("SYNTHETIC_LOCAL_FLUSH_FAILED")}}
        override suspend fun closeStore()=Unit
    }
    private class Host:ConversationRepository {
        val events=MutableSharedFlow<VerifiedConversationEvent>(extraBufferCapacity=16)
        override suspend fun listConversations(scope:ConversationScope,page:PageRequest)=ConversationPage(listOf(ConversationSummary(ConversationId("chat"),"chat",1)),null)
        override suspend fun timeline(conversationId:String,page:PageRequest)=TimelinePage(emptyList(),null)
        override fun observeEvents(scope:ConversationScope)=events
        override suspend fun createConversation(scope:ConversationScope,clientConversationId:String):Conversation=error("synthetic read only")
        override suspend fun submitMessage(message:OutgoingMessage):MessageAcceptance=error("synthetic read only")
        override suspend fun submitBatch(batch:MessageBatch):BatchAcceptance=error("synthetic read only")
        override suspend fun cancelGeneration(generationId:String,requestId:String)=CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }
}
