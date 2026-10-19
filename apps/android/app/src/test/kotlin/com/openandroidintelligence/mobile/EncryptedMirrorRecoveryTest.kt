package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.mobile.conversations.EncryptedConversationMirror
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.state.*
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import java.io.File
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class EncryptedMirrorRecoveryTest {
    private val context get()=ApplicationProvider.getApplicationContext<Application>()
    private val key=object:AesGcmKeyProvider {override fun getOrCreate()=SecretKeySpec(ByteArray(32){7},"AES");override fun delete(){}}
    private val scope=ConversationScope("profile_mirror","https://first.example","acct_same","install_one")
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
}
