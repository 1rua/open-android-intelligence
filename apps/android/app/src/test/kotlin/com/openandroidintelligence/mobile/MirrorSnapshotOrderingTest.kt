package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.state.WorkbenchCheckpoint
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.mobile.conversations.EncryptedConversationMirror
import com.openandroidintelligence.mobile.conversations.MirroredConversationRepository
import com.openandroidintelligence.mobile.conversations.SqliteConversationRecords
import com.openandroidintelligence.mobile.plugins.EncryptedDocuments
import com.openandroidintelligence.gateway.schema.Json
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class MirrorSnapshotOrderingTest {
    private val context get()=ApplicationProvider.getApplicationContext<Application>()
    private val key=object:AesGcmKeyProvider {
        override fun getOrCreate()=SecretKeySpec(ByteArray(32) { 41 },"AES")
        override fun delete()=Unit
    }
    private fun scope(name:String)=ConversationScope("snapshot_$name","https://gateway.example","account","install")
    private fun mirror(scope:ConversationScope)=EncryptedConversationMirror(context,scope,key,Dispatchers.Unconfined)
    private fun row(id:String,title:String=id,archived:Boolean=false)=ConversationSummary(ConversationId(id),title,1,archived)
    private fun checkpoint(rows:List<ConversationSummary>,token:Long?,draft:String="")=WorkbenchCheckpoint(
        rows,rows.firstOrNull()?.id?.value,rows.firstOrNull()?.title.orEmpty(),emptyList(),emptyMap(),draft,1,
        emptyList(),null,emptySet(),emptySet(),threadsSnapshotRevision=token)

    @Test fun aLowerAuthoritativeDigestUpdatesTheDurableListAndSurvivesReopen()=runBlocking {
        val account=scope("descending")
        val store=mirror(account)
        store.saveThreads(listOf(row("removed")),900)
        val fresh=listOf(row("fresh","New title",true))
        val repository=MirroredConversationRepository(host { ConversationPage(fresh,null,20) },store)
        assertEquals(fresh,repository.listConversations(account,PageRequest()).conversations)
        assertEquals(fresh,store.threads())
        assertEquals(20L,store.threadsSnapshotRevision())
        store.closeStore()
        val reopened=mirror(account)
        assertEquals(fresh,reopened.threads())
        assertEquals(20L,reopened.threadsSnapshotRevision())
        reopened.wipe()
    }

    @Test fun distinctMirrorsReserveDistinctReadFencesAndALateResponseRefetches()=runBlocking {
        val account=scope("parallel")
        val first=mirror(account)
        val second=mirror(account)
        val old=listOf(row("old"))
        val fresh=listOf(row("fresh","Newest title"))
        first.saveThreads(old,900)
        assertEquals(old,second.threads())
        val entered=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>()
        var olderReads=0
        val older=MirroredConversationRepository(host {
            olderReads++
            if (olderReads==1) {
                entered.complete(Unit)
                release.await()
                ConversationPage(old,null,900)
            } else ConversationPage(fresh,null,20)
        },first)
        val late=async { older.listConversations(account,PageRequest()) }
        entered.await()
        val newer=MirroredConversationRepository(host { ConversationPage(fresh,null,20) },second)
        newer.listConversations(account,PageRequest())
        assertEquals(fresh,first.threads())
        release.complete(Unit)
        assertEquals(fresh,late.await().conversations)
        assertEquals(2,olderReads)
        assertEquals(fresh,first.threads())
        assertEquals(fresh,second.threads())
        assertEquals(20L,first.threadsSnapshotRevision())
        first.closeStore()
        second.closeStore()
        val reopened=mirror(account)
        assertEquals(fresh,reopened.threads())
        reopened.wipe()
    }

    @Test fun archiveReceiptRejectsInflightHistoryAndOnlyAFreshReadClearsIt()=runBlocking {
        val account=scope("archive_receipt")
        val store=mirror(account)
        val active=listOf(row("a"))
        store.save(checkpoint(active,900))
        val inflight=store.beginHistoryRead()
        store.archiveConversation("a",true)
        assertFalse(store.saveThreads(active,800,inflight))
        val beforeAnotherArchive=store.beginHistoryRead()
        store.archiveConversation("a",true)
        assertFalse(store.saveThreads(active,700,beforeAnotherArchive))
        store.save(checkpoint(active,900))
        assertTrue(store.threads()!!.single().archived)
        store.closeStore()
        val reopened=mirror(account)
        assertTrue(reopened.load()!!.threads.single().archived)
        // A later native unarchive has a lower digest. It is newer because this
        // complete read began after the receipt, independently of token value.
        assertTrue(reopened.saveThreads(active,20,reopened.beginHistoryRead()))
        assertFalse(reopened.threads()!!.single().archived)
        reopened.save(checkpoint(active.map { it.copy(archived=true) },900))
        assertFalse(reopened.load()!!.threads.single().archived)
        reopened.wipe()
    }

    @Test fun firstPagePendingRecoveryGetsOneFreshReadAfterAdvancingTheMirror()=runBlocking {
        val account=scope("pending_recovery")
        val store=mirror(account)
        store.saveThreads(listOf(row("a")),900)
        val pending=store.reserveManagementOperation(ConversationManagementIntent.Archive("a",true))
        val fresh=listOf(row("a",archived=true),row("new_shared"))
        var reads=0
        val repository=MirroredConversationRepository(host {
            reads++
            if (reads==1) store.finishManagementOperation(pending,confirmed=true)
            ConversationPage(fresh,null,20)
        },store)
        repository.listConversations(account,PageRequest())
        assertEquals(2,reads)
        assertTrue(store.pendingManagementOperations().isEmpty())
        assertEquals(fresh,store.threads())
        assertEquals(20L,store.threadsSnapshotRevision())
        store.wipe()
    }

    @Test fun cachedHistoryCannotReplaceTheAuthoritativeListOrClearAnArchiveReceipt()=runBlocking {
        val account=scope("cached")
        val store=mirror(account)
        val active=listOf(row("a"))
        store.saveThreads(active,900)
        store.archiveConversation("a",true)
        val repository=MirroredConversationRepository(host { ConversationPage(active,null,20,authoritative=false) },store)
        assertFalse(repository.listConversations(account,PageRequest()).authoritative)
        assertTrue(store.threads()!!.single().archived)
        assertEquals(900L,store.threadsSnapshotRevision())
        // Local unfenced writes still respect the receipt left by the cached read.
        store.saveThreads(active)
        assertTrue(store.threads()!!.single().archived)
        store.wipe()
    }

    @Test fun staleCheckpointAfterRefreshAndReopenKeepsCurrentGlobalRowsButSavesTheDraft()=runBlocking {
        val account=scope("checkpoint")
        val store=mirror(account)
        val stale=checkpoint(listOf(row("obsolete")),900)
        store.save(stale)
        val fresh=listOf(row("current","Current title"))
        MirroredConversationRepository(host { ConversationPage(fresh,null,20) },store)
            .listConversations(account,PageRequest())
        store.closeStore()
        val reopened=mirror(account)
        reopened.save(stale.copy(draft="unsent work"))
        assertEquals(fresh,reopened.threads())
        assertEquals(fresh,reopened.load()!!.threads)
        assertEquals(20L,reopened.load()!!.threadsSnapshotRevision)
        assertEquals("unsent work",reopened.load()!!.draft)
        reopened.closeStore()
        val restored=mirror(account)
        assertEquals(fresh,restored.load()!!.threads)
        assertEquals(20L,restored.load()!!.threadsSnapshotRevision)
        restored.wipe()
    }

    @Test fun legacyNullTokensSeedAndSuccessiveAuthoritativeNullSnapshotsStillUpdate()=runBlocking {
        val account=scope("legacy_null")
        val store=mirror(account)
        val legacy=checkpoint(listOf(row("legacy")),null)
        store.save(legacy)
        assertEquals(legacy.threads,store.load()!!.threads)
        var response=ConversationPage(listOf(row("digest")),null,900)
        val repository=MirroredConversationRepository(host { response },store)
        repository.listConversations(account,PageRequest())
        response=ConversationPage(listOf(row("first_null")),null)
        repository.listConversations(account,PageRequest())
        response=ConversationPage(listOf(row("second_null")),null)
        repository.listConversations(account,PageRequest())
        store.save(legacy.copy(draft="legacy draft survives"))
        assertEquals(response.conversations,store.threads())
        assertNull(store.threadsSnapshotRevision())
        store.closeStore()
        val reopened=mirror(account)
        assertEquals(response.conversations,reopened.load()!!.threads)
        assertNull(reopened.load()!!.threadsSnapshotRevision)
        assertEquals("legacy draft survives",reopened.load()!!.draft)
        reopened.wipe()
    }

    @Test fun aStaleBaselineCannotAlterThreadsTimelineDeletionReceiptsOrCursor()=runBlocking {
        val account=scope("baseline_fence")
        val store=mirror(account)
        val current=listOf(row("a","Current"))
        val message=TimelineMessage("message","assistant",listOf(MessagePart.Attachment(
            AttachmentDraftId("file"),"image.png","image/png")),1,conversationId=ConversationId("a"),contentRevision=1)
        store.saveThreads(current,900)
        store.saveTimeline("a",TimelinePage(listOf(message),null,1))
        store.saveCursor(account.accountId,"evt_current")
        val staleEpoch=store.beginHistoryRead()
        assertTrue(store.saveThreads(current,20,store.beginHistoryRead()))
        val stalePages=mapOf("a" to TimelinePage(emptyList(),null,900,deletedMessageIds=setOf(message.id)))
        assertFalse(store.installBaselineAndCommit(listOf(row("obsolete")),stalePages,"evt_stale",staleEpoch,900))
        assertEquals(current,store.threads())
        assertEquals(20L,store.threadsSnapshotRevision())
        assertEquals(listOf(message),store.timeline("a")!!.messages)
        assertTrue(store.deletedMediaIds("a",message.id).isEmpty())
        assertEquals("evt_current",store.loadCursor(account.accountId))
        assertTrue(store.installBaselineAndCommit(current,mapOf("a" to TimelinePage(listOf(message),null,1)),
            "evt_new",store.beginHistoryRead(),10))
        assertEquals(10L,store.threadsSnapshotRevision())
        assertEquals("evt_new",store.loadCursor(account.accountId))
        store.wipe()
    }

    @Test fun aLegacyStagedBaselineStillRecoversBeforeAnyFreshAuthoritativeList()=runBlocking {
        val account=scope("legacy_stage")
        val store=mirror(account)
        store.save(checkpoint(listOf(row("before")),null))
        store.saveTimeline("before",TimelinePage(emptyList(),null))
        val staged=listOf(row("legacy_recovered"))
        stageLegacyBaseline(account,staged,"evt_legacy")
        store.closeStore()
        val reopened=mirror(account)
        reopened.finishBaselineRecovery()
        assertEquals(staged,reopened.threads())
        assertNull(reopened.timeline("before"))
        assertEquals("evt_legacy",reopened.loadCursor(account.accountId))
        reopened.wipe()
    }

    @Test fun aLegacyStagedBaselineCannotReplaceAFreshListOrRewindItsCursorAfterReopen()=runBlocking {
        val account=scope("obsolete_stage")
        val store=mirror(account)
        store.save(checkpoint(listOf(row("before")),900))
        stageLegacyBaseline(account,listOf(row("obsolete")),"evt_legacy")
        val current=listOf(row("current"))
        assertTrue(store.saveThreads(current,20,store.beginHistoryRead()))
        store.saveTimeline("current",TimelinePage(emptyList(),null))
        store.saveCursor(account.accountId,"evt_current")
        store.closeStore()
        val reopened=mirror(account)
        reopened.finishBaselineRecovery()
        assertEquals(current,reopened.threads())
        assertEquals(20L,reopened.threadsSnapshotRevision())
        assertNotNull(reopened.timeline("current"))
        assertEquals("evt_current",reopened.loadCursor(account.accountId))
        reopened.wipe()
    }

    @Test fun aCommittedFreshBaselineRetiresAnOlderStageInTheSameTransaction()=runBlocking {
        val account=scope("replace_stage")
        val store=mirror(account)
        store.save(checkpoint(listOf(row("before")),null))
        stageLegacyBaseline(account,listOf(row("obsolete")),"evt_legacy")
        val current=listOf(row("current"))
        assertTrue(store.installBaselineAndCommit(current,emptyMap(),"evt_current",store.beginHistoryRead(),20))
        store.closeStore()
        val reopened=mirror(account)
        // The referenced legacy stage was pruned by installation; its marker
        // must be gone too, so restart cannot replay or dereference that stage.
        reopened.finishBaselineRecovery()
        assertEquals(current,reopened.threads())
        assertEquals("evt_current",reopened.loadCursor(account.accountId))
        reopened.wipe()
    }

    private fun stageLegacyBaseline(scope:ConversationScope,rows:List<ConversationSummary>,cursor:String) {
        val documentScope="conversation-mirror-v1:"+Json.canonical(Json.of(listOf(scope.profileId,scope.gatewayId,scope.accountId,scope.installId)))
        val records=SqliteConversationRecords(context,SqliteConversationRecords.digest(documentScope),key,
            EncryptedDocuments(context,documentScope,key))
        try {
            records.transaction {
                records.write("snapshot:legacy",Json.canonical(Json.of(mapOf("threads" to rows.map { thread ->
                    mapOf("id" to thread.id.value,"title" to thread.title,"updatedAt" to thread.updatedAt,"archived" to thread.archived)
                },"timelines" to emptyList<Any>(),"cursor" to cursor))).toByteArray())
                records.write("baseline",Json.canonical(Json.of(mapOf("generation" to "snapshot:legacy"))).toByteArray())
            }
        } finally { records.close() }
    }

    private fun host(list:suspend (PageRequest)->ConversationPage)=object:ConversationRepository {
        override suspend fun listConversations(scope:ConversationScope,page:PageRequest)=list(page)
        override suspend fun timeline(conversationId:String,page:PageRequest)=TimelinePage(emptyList(),null)
        override fun observeEvents(scope:ConversationScope)=emptyFlow<VerifiedConversationEvent>()
        override suspend fun createConversation(scope:ConversationScope,clientConversationId:String):Conversation=error("UNUSED")
        override suspend fun submitMessage(message:OutgoingMessage):MessageAcceptance=error("UNUSED")
        override suspend fun submitBatch(batch:MessageBatch):BatchAcceptance=error("UNUSED")
        override suspend fun cancelGeneration(generationId:String,requestId:String)=CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }
}
