package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.mobile.conversations.EncryptedConversationMirror
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.state.*
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import java.io.File
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class EncryptedMirrorRecoveryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Application>()
    private val key=object:AesGcmKeyProvider {override fun getOrCreate()=SecretKeySpec(ByteArray(32){7},"AES");override fun delete(){}}
    private val scope=ConversationScope("profile_mirror","https://first.example","acct_same","install_one")
    @Test fun aCrashAfterCursorCommitBeforeControllerDeliveryRetainsCancellationAndRevisionedAssociations() = runTest {
        val testScope=scope.copy(profileId="cursor_cancel_crash")
        val dispatcher=StandardTestDispatcher(testScheduler)
        val mirror=EncryptedConversationMirror(context,testScope,key,dispatcher)
        val question=TimelineMessage("question","user",listOf(MessagePart.Text("request")),1,"delivered",ConversationId("conv_a"),clientMessageId=ClientMessageId("client_a"))
        val partial=TimelineMessage("partial","assistant",listOf(MessagePart.Text("partial output")),2,"STREAMING",ConversationId("conv_a"),generationId="gen_a")
        val checkpoint=WorkbenchCheckpoint(listOf(ConversationSummary(ConversationId("conv_a"),"A",1)),"conv_a","A",listOf(question,partial),
            emptyMap(),"",0,emptyList(),null,emptySet(),emptySet())
        mirror.save(checkpoint)
        mirror.saveTimeline("conv_a",TimelinePage(listOf(question,partial),null))
        mirror.applyAndCommit(VerifiedConversationEvent.MessageStatus("evt_delivered",2,ConversationId("conv_a"),"question",ClientMessageId("client_a"),AgentMessageStatus.DELIVERED,1,null,"gen_a"))
        mirror.applyAndCommit(VerifiedConversationEvent.GenerationCancelled("evt_cancel",3,"gen_a",ConversationId("conv_a")))
        mirror.applyAndCommit(VerifiedConversationEvent.MessageStatus("evt_failed",4,ConversationId("conv_a"),"question",ClientMessageId("client_a"),AgentMessageStatus.FAILED,2,AgentMessageErrorCode.AGENT_UNAVAILABLE))
        // An older surface's checkpoint cannot erase facts already committed by SSE.
        mirror.save(checkpoint)
        val reopened=EncryptedConversationMirror(context,testScope,key,dispatcher)
        assertEquals("evt_failed",reopened.loadCursor(testScope.accountId))
        assertEquals(listOf(SavedCancellation("conv_a","gen_a")),reopened.load()!!.cancellations)
        assertEquals(setOf("question","client_a"),reopened.load()!!.messageGenerations.map { it.messageId }.toSet())
        val offline=object : ConversationRepository by com.openandroidintelligence.mobile.conversations.OfflineMirrorRepository(reopened), GenerationTracker {
            override val generationId=MutableStateFlow<String?>("gen_a")
            override val generationState=MutableStateFlow(GenerationState.FAILED)
            override suspend fun listConversations(scope:ConversationScope,page:PageRequest)=ConversationPage(checkpoint.threads,null,authoritative=false)
        }
        val controllerScope=CoroutineScope(coroutineContext + requireNotNull(backgroundScope.coroutineContext[Job]))
        val controller=WorkbenchController(controllerScope,offline,object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId:String,languageCode:String)=AgentCommandCatalog(CatalogVersion("v1"),emptyList())
        },{testScope},persistence=reopened,persistenceDispatcher=dispatcher,replyTimeouts=WorkbenchController.ReplyTimeouts(enabled=false))
        runCurrent()
        val rows=(controller.state.value.timeline as Loadable.Ready).value
        assertFalse(rows.any { it.isStreaming || it.messageStatus==AgentMessageStatus.FAILED })
        assertTrue(rows.any { it.text=="partial output" })
        assertEquals(GenerationState.CANCELLED,controller.state.value.generation)
        assertNull(controller.state.value.notice)
        controller.closeAndAwait()
        // A newer real generation using the same message identities remains authoritative.
        val newest=EncryptedConversationMirror(context,testScope,key,dispatcher)
        newest.applyAndCommit(VerifiedConversationEvent.MessageStatus("evt_new",5,ConversationId("conv_a"),"question",ClientMessageId("client_a"),AgentMessageStatus.FAILED,3,AgentMessageErrorCode.AGENT_UNAVAILABLE,"gen_b"))
        newest.save(checkpoint.copy(messageGenerations=listOf(SavedMessageGeneration("conv_a","question","gen_a",1))))
        assertTrue(newest.load()!!.messageGenerations.all { it.generationId=="gen_b" })
        newest.wipe()
    }

    @Test fun cancellationFactsMessageGenerationAndFolderRecoveryWorkRoundTripWithTheEventCursor() {
        val testScope=scope.copy(profileId="cancel_and_folder_facts")
        val mirror=EncryptedConversationMirror(context,testScope,key)
        val question=TimelineMessage("question","user",listOf(MessagePart.Text("request")),1,"failed",ConversationId("conv_a"),
            errorCode="AGENT_UNAVAILABLE",clientMessageId=ClientMessageId("client_a"))
        val partial=TimelineMessage("partial","assistant",listOf(MessagePart.Text("partial output")),2,"CANCELLED",ConversationId("conv_a"),generationId="gen_a")
        val checkpoint=WorkbenchCheckpoint(listOf(ConversationSummary(ConversationId("conv_a"),"A",1)),"conv_a","A",listOf(question,partial),
            mapOf("question" to 3L),"",0,emptyList(),null,emptySet(),setOf("cancel_event","failed_event"),
            cancellations=listOf(SavedCancellation("conv_a","gen_a")),
            messageGenerations=listOf(SavedMessageGeneration("conv_a","question","gen_a",2),SavedMessageGeneration("conv_a","client_a","gen_a",2)),
            pendingFolderAssignments=listOf(SavedFolderAssignment("conv_a","work")),pendingConversationCreation=SavedConversationCreation("original_client","work"))
        mirror.save(checkpoint)
        mirror.saveTimeline("conv_a",TimelinePage(listOf(question,partial),null))
        val reopened=EncryptedConversationMirror(context,testScope,key)
        val restored=reopened.load()!!
        assertEquals(checkpoint.cancellations,restored.cancellations)
        assertEquals(checkpoint.messageGenerations,restored.messageGenerations)
        assertEquals(checkpoint.eventIds,restored.eventIds)
        assertEquals(checkpoint.pendingFolderAssignments,restored.pendingFolderAssignments)
        assertEquals(checkpoint.pendingConversationCreation,restored.pendingConversationCreation)
        assertEquals("gen_a",restored.messages.single { it.id=="partial" }.generationId)
        assertEquals("gen_a",reopened.timeline("conv_a")!!.messages.single { it.id=="partial" }.generationId)
        assertEquals("failed",restored.messages.single { it.id=="question" }.state)
        val otherAccount=EncryptedConversationMirror(context,testScope.copy(accountId="other"),key)
        assertNull(otherAccount.load())
        reopened.deleteConversation("conv_a")
        reopened.save(checkpoint)
        val deleted=reopened.load()!!
        assertTrue(deleted.cancellations.isEmpty())
        assertTrue(deleted.messageGenerations.isEmpty())
        assertTrue(deleted.pendingFolderAssignments.isEmpty())
        otherAccount.wipe(); reopened.wipe()
    }

    @Test fun organizationArchiveAndCompleteHistoryRemainTogetherAfterReopenAndEmptyListClearsHistory() {
        val testScope=scope.copy(profileId="organization_roundtrip")
        val mirror=EncryptedConversationMirror(context,testScope,key)
        val threads=listOf(ConversationSummary(ConversationId("conv_archived"),"Archived",20,true),ConversationSummary(ConversationId("conv_live"),"Live",10))
        val organization=ConversationOrganization(6,ConversationSortMode.CUSTOM,listOf("conv_live","conv_archived"),
            listOf(ConversationFolder("work","Work")),mapOf("conv_live" to "work"))
        val checkpoint=WorkbenchCheckpoint(threads,"conv_live","Live",emptyList(),emptyMap(),"unsent",1,emptyList(),null,emptySet(),emptySet(),
            organization=organization,threadsSnapshotRevision=12)
        mirror.save(checkpoint)
        val reopened=EncryptedConversationMirror(context,testScope,key)
        assertEquals(organization,reopened.load()?.organization)
        assertEquals(threads,reopened.threads())
        assertEquals(12L,reopened.load()?.threadsSnapshotRevision)
        reopened.saveThreads(emptyList(),13,reopened.beginHistoryRead())
        reopened.save(checkpoint.copy(threads=emptyList(),threadId=null,threadsSnapshotRevision=13))
        val empty=EncryptedConversationMirror(context,testScope,key)
        assertEquals(emptyList<ConversationSummary>(),empty.load()?.threads)
        assertEquals(emptyList<ConversationSummary>(),empty.threads())
        empty.wipe()
    }

    @Test fun deletedConversationCannotReturnThroughAnOldCheckpointLateTimelineOrEvent() {
        val testScope=scope.copy(profileId="deleted_conversation_barrier")
        val mirror=EncryptedConversationMirror(context,testScope,key)
        val thread=ConversationSummary(ConversationId("conv_deleted"),"Deleted",1)
        val row=TimelineMessage("old_message","user",listOf(MessagePart.Text("private deleted body")),1,
            conversationId=thread.id,clientMessageId=ClientMessageId("old_client"),contentRevision=1)
        val checkpoint=WorkbenchCheckpoint(listOf(thread),thread.id.value,thread.title,listOf(row),mapOf(row.id to 1),"keep draft",1,
            emptyList(),SavedSubmission("old",emptyList(),1,thread.id.value,"old_client"),emptySet(),emptySet())
        mirror.save(checkpoint)
        mirror.saveTimeline(thread.id.value,TimelinePage(listOf(row),null,1))
        mirror.saveSendAtoms(thread.id.value,listOf(LocalSendAtom(thread.id,ClientMessageId("old_client"),row.parts,1)))
        mirror.deleteConversation(thread.id.value)
        mirror.save(checkpoint)
        mirror.saveThreads(listOf(thread))
        mirror.saveTimeline(thread.id.value,TimelinePage(listOf(row),null,9))
        mirror.saveSendAtoms(thread.id.value,listOf(LocalSendAtom(thread.id,ClientMessageId("late_client"),row.parts,2)))
        val late=VerifiedConversationEvent.TimelineUpsert("late_event",9,9,row)
        mirror.apply(late)
        assertNull(mirror.projectEvent(late))
        val reopened=EncryptedConversationMirror(context,testScope,key)
        assertNull(reopened.load()?.threadId)
        assertTrue(reopened.load()!!.messages.isEmpty())
        assertTrue(reopened.threads()!!.isEmpty())
        assertNull(reopened.timeline(thread.id.value))
        assertTrue(reopened.loadSendAtoms(thread.id.value).isEmpty())
        assertNull(reopened.load()?.submission)
        reopened.wipe()
    }
    @Test fun acknowledgedArchiveCannotBeUndoneByAnOlderCheckpointOrAnInflightList() {
        val testScope=scope.copy(profileId="archive_metadata_barrier")
        val mirror=EncryptedConversationMirror(context,testScope,key)
        val thread=ConversationSummary(ConversationId("conv_archive"),"Archive",1)
        mirror.saveThreads(listOf(thread),10)
        val epoch=mirror.historyEpoch()
        mirror.archiveConversation(thread.id.value,true)
        mirror.saveThreads(listOf(thread),11,expectedEpoch=epoch)
        mirror.save(WorkbenchCheckpoint(listOf(thread),thread.id.value,thread.title,emptyList(),emptyMap(),"",0,emptyList(),null,
            emptySet(),emptySet(),threadsSnapshotRevision=10))
        assertTrue(mirror.threads()!!.single().archived)
        val reopened=EncryptedConversationMirror(context,testScope,key)
        assertTrue(reopened.load()!!.threads.single().archived)
        reopened.saveThreads(listOf(thread.copy(archived=true)),12,reopened.historyEpoch())
        assertEquals(12L,reopened.threadsSnapshotRevision())
        reopened.archiveConversation(thread.id.value,false)
        assertFalse(reopened.threads()!!.single().archived)
        reopened.wipe()
    }
    @Test fun reopensEncryptedDraftFrozenIdentitiesAllPagesAndGatewayPartitions() {
        val mirror=EncryptedConversationMirror(context,scope,key)
        val threads=listOf(ConversationSummary(ConversationId("conv_one"),"会话",1))
        val messages=(0..120).map { TimelineMessage("msg_$it","user",listOf(MessagePart.Text("private-$it")),it.toLong(),batchId="batch_one") }
        val submission=SavedSubmission("private-draft",emptyList(),8,"conv_one","cm_frozen")
        mirror.save(WorkbenchCheckpoint(threads,"conv_one","会话",messages,mapOf("msg_120" to 3),"private-draft",8,emptyList(),submission,emptySet(),setOf("evt_last"),
            listOf(SavedBatch("cb_frozen","conv_one",listOf(OutgoingMessage(ClientMessageId("cm_member"),"pending"))))))
        mirror.saveThreads(threads);mirror.saveTimeline("conv_one",TimelinePage(messages,null,123))
        val restored=EncryptedConversationMirror(context,scope,key)
        assertEquals("cm_frozen",restored.load()!!.submission!!.clientMessageId);assertEquals("cb_frozen",restored.load()!!.batches.single().batchId)
        assertEquals(121,restored.timeline("conv_one")!!.messages.size);assertEquals("batch_one",restored.timeline("conv_one")!!.messages.first().batchId)
        assertNull(EncryptedConversationMirror(context,scope.copy(gatewayId="https://second.example"),key).load())
        File(context.noBackupFilesDir,"private-documents").walkTopDown().filter{it.isFile}.forEach { assertFalse(it.readBytes().decodeToString().contains("private-draft")) }
        restored.wipe()
        assertEquals("MIRROR_CLOSED", runCatching { mirror.load() }.exceptionOrNull()?.message)
        assertNull(EncryptedConversationMirror(context,scope,key).load())
    }
    @Test fun snapshotRecoveryRemovesObsoleteThreadsAndRetainsTheCompleteBaseline() {
        val mirror=EncryptedConversationMirror(context,scope,key)
        mirror.saveTimeline("conv_obsolete",TimelinePage(listOf(TimelineMessage("old","user",listOf(MessagePart.Text("old")),1)),null))
        val threads=listOf(ConversationSummary(ConversationId("conv_new"),"New",2))
        mirror.installBaseline(threads,mapOf("conv_new" to TimelinePage(listOf(TimelineMessage("new","assistant",listOf(MessagePart.Text("fresh")),2)),null,9)),"evt_baseline")
        val reopened=EncryptedConversationMirror(context,scope,key);reopened.finishBaselineRecovery()
        assertNull(reopened.timeline("conv_obsolete"));assertEquals("new",reopened.timeline("conv_new")!!.messages.single().id);assertEquals(threads,reopened.threads())
        reopened.wipe()
    }
    @Test fun conflictingMessageIdentityIsDroppedInsteadOfCrashing() = runTest {
        val testScope=scope.copy(profileId="conflicting_message_identity")
        val mirror=EncryptedConversationMirror(context,testScope,key)
        val question=TimelineMessage("question","user",listOf(MessagePart.Text("request")),1,"queued",
            ConversationId("conv_a"),clientMessageId=ClientMessageId("client_a"))
        mirror.saveTimeline("conv_a",TimelinePage(listOf(question),null))
        mirror.applyAndCommit(VerifiedConversationEvent.MessageStatus("evt_queued",1,ConversationId("conv_a"),
            "question",ClientMessageId("client_a"),AgentMessageStatus.QUEUED,0,null))
        assertEquals("client_a",mirror.loadSendAtoms("conv_a").single().clientMessageId.value)
        // 同一 messageId 带着另一个 clientMessageId 到达：丢弃该事件，不得把进程打崩。
        mirror.applyAndCommit(VerifiedConversationEvent.MessageStatus("evt_conflict",2,ConversationId("conv_a"),
            "question",ClientMessageId("client_other"),AgentMessageStatus.FAILED,3,AgentMessageErrorCode.AGENT_UNAVAILABLE))
        val untouched=mirror.loadSendAtoms("conv_a").single()
        assertEquals("client_a",untouched.clientMessageId.value)
        assertNull(untouched.errorCode)
        // 身份正确的事件仍能推进状态。
        mirror.applyAndCommit(VerifiedConversationEvent.MessageStatus("evt_completed",3,ConversationId("conv_a"),
            "question",ClientMessageId("client_a"),AgentMessageStatus.COMPLETED,1,null))
        assertEquals(AgentMessageStatus.COMPLETED,mirror.loadSendAtoms("conv_a").single().status)
        mirror.wipe()
    }
    @Test fun divergentServerIdOnAUserUpsertKeepsTheBoundIdentity() = runTest {
        val testScope=scope.copy(profileId="divergent_upsert_identity")
        val mirror=EncryptedConversationMirror(context,testScope,key)
        val question=TimelineMessage("question","user",listOf(MessagePart.Text("request")),1,"queued",
            ConversationId("conv_a"),clientMessageId=ClientMessageId("client_a"))
        mirror.saveTimeline("conv_a",TimelinePage(listOf(question),null))
        mirror.applyAndCommit(VerifiedConversationEvent.MessageStatus("evt_queued",1,ConversationId("conv_a"),
            "question",ClientMessageId("client_a"),AgentMessageStatus.QUEUED,0,null))
        // 同一 clientMessageId 以另一个服务端 id 到达：身份保持、正文更新，不得抛异常。
        mirror.applyAndCommit(VerifiedConversationEvent.TimelineUpsert("evt_upsert",2,3,
            question.copy(id="question_server_side",parts=listOf(MessagePart.Text("edited request")))))
        val atom=mirror.loadSendAtoms("conv_a").single()
        assertEquals("question",atom.messageId)
        assertEquals("edited request",(atom.parts.single() as MessagePart.Text).value)
        mirror.wipe()
    }
}
