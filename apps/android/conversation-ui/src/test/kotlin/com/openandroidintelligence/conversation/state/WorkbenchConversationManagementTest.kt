package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchConversationManagementTest {
    @Test fun browserDrainsHistoryAndLoadsCommittedOrganizationBeforePublishing()=runTest {
        val host=Host().apply {
            threads=(0..124).map { summary("conv_$it",it.toLong()) }
            organization=ConversationOrganization(4,ConversationSortMode.CUSTOM,listOf("conv_0"),
                listOf(ConversationFolder("work","Work")),mapOf("conv_0" to "work"))
        }
        val saved=MemoryPersistence()
        val controller=controller(host,saved)
        runCurrent()
        assertEquals(125,rows(controller).size)
        assertEquals("conv_0",rows(controller).first().id.value)
        assertEquals(listOf(null,"100"),host.cursors)
        assertEquals(host.organization,controller.state.value.organization)
        controller.flushPendingPersistence()
        assertEquals(host.organization,saved.checkpoint?.organization)
        assertEquals(125,saved.checkpoint?.threads?.size)
        controller.close()
    }

    @Test fun changesUseLatestServerRevision()=runTest {
        val host=Host()
        val controller=controller(host)
        runCurrent()
        // Another surface changed its folders after this controller loaded.
        host.organization=ConversationOrganization(5,folders=listOf(ConversationFolder("other","Other")))
        controller.createFolder("Personal")
        runCurrent()
        assertEquals(5L,host.expectedRevisions.single())
        assertEquals(listOf("Other","Personal"),controller.state.value.organization.folders.map { it.name })
        assertNull(controller.state.value.notice)
        controller.close()
    }

    @Test fun aConflictReappliesTheOriginalActionAndPreservesConcurrentUnrelatedEdits()=runTest {
        val host=Host().apply {
            organization=ConversationOrganization(5,folders=listOf(ConversationFolder("other","Other")))
        }
        val controller=controller(host)
        runCurrent()
        val gate=CompletableDeferred<Unit>()
        host.organizationUpdateGate=gate
        controller.createFolder("Personal")
        runCurrent()
        assertEquals(listOf(5L),host.expectedRevisions)
        assertEquals(listOf("Other"),controller.state.value.organization.folders.map { it.name })
        // Another surface commits after this action read revision 5 but before its PUT.
        host.organization=host.organization.copy(revision=6,sortMode=ConversationSortMode.CUSTOM,
            customOrder=listOf("b","a"),folders=host.organization.folders+ConversationFolder("remote","Remote"),
            assignments=mapOf("b" to "remote"))
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(5L,6L),host.expectedRevisions)
        assertEquals(listOf("Other","Remote","Personal"),controller.state.value.organization.folders.map { it.name })
        assertEquals(listOf("b","a"),controller.state.value.organization.customOrder)
        assertEquals(mapOf("b" to "remote"),controller.state.value.organization.assignments)
        val submittedIds=host.submittedOrganizations.map { it.folders.single { folder -> folder.name=="Personal" }.folderId }
        assertEquals(1,submittedIds.toSet().size)
        assertEquals(host.organization,controller.state.value.organization)
        assertNull(controller.state.value.notice)
        assertFalse(controller.state.value.managingThreads)
        controller.close()
    }

    @Test fun repeatedConflictsExhaustTheRetryBoundAndRemainVisibleWithoutLocalSuccess()=runTest {
        val host=Host().apply { alwaysConflict=true }
        val controller=controller(host)
        runCurrent()
        controller.createFolder("Uncommitted")
        runCurrent()
        assertEquals(listOf(0L,1L,2L),host.expectedRevisions)
        assertTrue(controller.state.value.notice.orEmpty().contains("ORGANIZATION_CONFLICT"))
        assertTrue(controller.state.value.organization.folders.isEmpty())
        assertEquals(host.organization,controller.state.value.organization)
        assertFalse(controller.state.value.managingThreads)
        controller.close()
    }

    @Test fun anUnconfirmedWriteFailureIsNotRetried()=runTest {
        val host=Host().apply { organizationUpdateFailure="ORGANIZATION_UPDATE_FAILED:503" }
        val controller=controller(host)
        runCurrent()
        controller.createFolder("Unconfirmed")
        runCurrent()
        assertEquals(listOf(0L),host.expectedRevisions)
        assertEquals("CONVERSATION_MANAGEMENT_FAILED:ORGANIZATION_UPDATE_FAILED:503",controller.state.value.notice)
        assertTrue(controller.state.value.organization.folders.isEmpty())
        assertFalse(controller.state.value.managingThreads)
        controller.close()
    }

    @Test fun aScopeChangeDuringAConflictingWritePreventsAnotherAttempt()=runTest {
        val host=Host()
        var scope=ConversationScope("profile","gateway","account","install")
        val controller=controller(host,scope={scope})
        runCurrent()
        val gate=CompletableDeferred<Unit>()
        host.organizationUpdateGate=gate
        controller.createFolder("Old account action")
        runCurrent()
        scope=scope.copy(accountId="new_account")
        host.organization=host.organization.copy(revision=1)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(0L),host.expectedRevisions)
        assertTrue(host.organization.folders.isEmpty())
        assertNull(controller.state.value.notice)
        controller.close()
    }

    @Test fun closingWhileAnUncancellableReadFinishesPreventsTheWrite()=runTest {
        val host=Host()
        val controller=controller(host)
        runCurrent()
        val gate=CompletableDeferred<Unit>()
        host.organizationReadGate=gate
        host.ignoreOrganizationReadCancellation=true
        controller.createFolder("Cancelled action")
        runCurrent()
        controller.close()
        gate.complete(Unit)
        runCurrent()
        assertTrue(host.expectedRevisions.isEmpty())
        assertTrue(host.organization.folders.isEmpty())
    }

    @Test fun aScopeChangeWhileReadingPreventsTheWrite()=runTest {
        val host=Host()
        var scope=ConversationScope("profile","gateway","account","install")
        val controller=controller(host,scope={scope})
        runCurrent()
        val gate=CompletableDeferred<Unit>()
        host.organizationReadGate=gate
        controller.createFolder("Old account action")
        runCurrent()
        scope=scope.copy(accountId="new_account")
        gate.complete(Unit)
        runCurrent()
        assertTrue(host.expectedRevisions.isEmpty())
        assertTrue(host.organization.folders.isEmpty())
        controller.close()
    }

    @Test fun aFreshSnapshotWithADescendingDigestUpdatesTheBrowserAndCheckpoint()=runTest {
        val host=Host().apply { snapshotRevision=900 }
        val saved=MemoryPersistence()
        val controller=controller(host,saved)
        runCurrent()
        host.snapshotRevision=20
        host.threads=listOf(summary("a",3).copy(title="Updated elsewhere",archived=true),summary("new_shared",1))
        controller.refreshThreads()
        runCurrent()
        assertEquals(listOf("a","new_shared"),rows(controller).map { it.id.value })
        assertEquals("Updated elsewhere",controller.state.value.activeThreadTitle)
        assertTrue(rows(controller).first().archived)
        controller.flushPendingPersistence()
        assertEquals(20L,saved.checkpoint?.threadsSnapshotRevision)
        assertEquals(host.threads,saved.checkpoint?.threads)
        controller.close()
    }

    @Test fun aRestoredSnapshotAcceptsAFreshLowerDigest()=runTest {
        val host=Host().apply {
            snapshotRevision=20
            threads=listOf(summary("a",3).copy(title="Fresh title"),summary("from_other_surface",1))
        }
        val cached=listOf(summary("a",2),summary("removed",1))
        val saved=MemoryPersistence().apply {
            checkpoint=WorkbenchCheckpoint(cached,"a","Cached title",emptyList(),emptyMap(),"",0,
                emptyList(),null,emptySet(),emptySet(),threadsSnapshotRevision=900)
        }
        val controller=controller(host,saved)
        assertEquals(cached,rows(controller))
        runCurrent()
        assertEquals(host.threads,rows(controller))
        assertEquals("Fresh title",controller.state.value.activeThreadTitle)
        controller.flushPendingPersistence()
        assertEquals(20L,saved.checkpoint?.threadsSnapshotRevision)
        controller.close()
    }

    @Test fun anOlderRequestCannotOverwriteANewerSnapshotEvenWithALargerDigest()=runTest {
        val host=Host().apply { snapshotRevision=10 }
        val saved=MemoryPersistence()
        val controller=controller(host,saved)
        runCurrent()
        val gate=CompletableDeferred<Unit>()
        host.listGate=gate
        host.snapshotRevision=900
        host.threads=listOf(summary("a",2),summary("stale",1))
        controller.refreshThreads()
        runCurrent()
        host.snapshotRevision=20
        host.threads=listOf(summary("a",3).copy(title="Newest title"),summary("fresh",1))
        controller.refreshThreads()
        runCurrent()
        assertEquals(host.threads,rows(controller))
        gate.complete(Unit)
        runCurrent()
        assertEquals(host.threads,rows(controller))
        assertEquals("Newest title",controller.state.value.activeThreadTitle)
        controller.flushPendingPersistence()
        assertEquals(20L,saved.checkpoint?.threadsSnapshotRevision)
        controller.close()
    }

    @Test fun archiveAndDeleteRespectOtherSurfacesAndPendingSendsAcrossNavigation()=runTest {
        val host=Host()
        var external=setOf("a")
        val controller=controller(host,external={external})
        runCurrent()
        controller.archiveThread("a")
        controller.deleteThread("a")
        runCurrent()
        assertEquals(0,host.archives)
        assertEquals(0,host.deletes)
        external=emptySet()
        controller.editDraft("pending")
        controller.sendDraft()
        controller.openThread("b")
        assertTrue("a" in controller.ownedBusyThreadIds)
        controller.deleteThread("a")
        runCurrent()
        assertEquals(0,host.deletes)
        assertTrue(controller.state.value.notice.orEmpty().startsWith("CONVERSATION_BUSY"))
        controller.close()
    }

    @Test fun archivedHistoryRemainsReadableAcrossRefreshAndUnarchiveIsServerBacked()=runTest {
        val host=Host()
        val controller=controller(host)
        runCurrent()
        controller.archiveThread("a")
        runCurrent()
        assertEquals("a",controller.state.value.activeThreadId)
        assertTrue(rows(controller).first { it.id.value=="a" }.archived)
        controller.openThread("a")
        controller.editDraft("read only")
        assertFalse(controller.state.value.canSend)
        controller.onForegrounded()
        runCurrent()
        assertEquals("a",controller.state.value.activeThreadId)
        assertFalse(controller.state.value.canSend)
        controller.archiveThread("a",false)
        runCurrent()
        assertFalse(rows(controller).first { it.id.value=="a" }.archived)
        assertTrue(controller.state.value.canSend)
        assertEquals(2,host.archives)
        controller.close()
    }

    @Test fun deletedThreadCannotReopenFromReplayedEventsOrAnOldListResponse()=runTest {
        val host=Host()
        val controller=controller(host)
        runCurrent()
        val gate=CompletableDeferred<Unit>()
        host.listGate=gate
        controller.refreshThreads()
        runCurrent()
        controller.deleteThread("a")
        runCurrent()
        assertEquals("b",controller.state.value.activeThreadId)
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("b"),rows(controller).map { it.id.value })
        host.events.emit(VerifiedConversationEvent.TimelineUpsert("late",9,9,
            TimelineMessage("old","assistant",listOf(MessagePart.Text("must stay deleted")),9,
                conversationId=ConversationId("a"))))
        runCurrent()
        controller.openThread("a")
        assertEquals("b",controller.state.value.activeThreadId)
        assertFalse((controller.state.value.timeline as? Loadable.Ready)?.value.orEmpty().any { it.text=="must stay deleted" })
        controller.close()
    }

    @Test fun metadataEventsAndForegroundReturnRefreshSharedHistoryAndOrganization()=runTest {
        val host=Host()
        val controller=controller(host)
        runCurrent()
        host.threads=listOf(summary("a",3),summary("b",2),summary("from_other_surface",1))
        host.organization=ConversationOrganization(9,folders=listOf(ConversationFolder("new_folder","Shared")))
        host.events.emit(VerifiedConversationEvent.ConversationsChanged("metadata",2))
        runCurrent()
        assertEquals(3,rows(controller).size)
        assertEquals(9L,controller.state.value.organization.revision)
        host.threads=host.threads.filterNot { it.id.value=="from_other_surface" }
        host.organization=host.organization.copy(revision=10,folders=emptyList())
        controller.onForegrounded()
        runCurrent()
        assertEquals(2,rows(controller).size)
        assertEquals(10L,controller.state.value.organization.revision)
        controller.close()
    }

    @Test fun unsupportedHostShowsUnsupportedWithoutChangingServerOrLocalStructure()=runTest {
        val host=Host()
        val controller=controller(host,supported=false)
        runCurrent()
        controller.createFolder("Unsupported")
        controller.archiveThread("a")
        controller.deleteThread("a")
        runCurrent()
        assertEquals("CONVERSATION_MANAGEMENT_UNSUPPORTED",controller.state.value.notice)
        assertEquals(0,host.archives)
        assertEquals(0,host.deletes)
        assertTrue(controller.state.value.organization.folders.isEmpty())
        controller.close()
    }

    @Test fun pickerTargetChangesForSendAndAwayThenBackEvenWhenThreadIdentityMatches()=runTest {
        val host=Host()
        val controller=controller(host)
        runCurrent()
        val initial=controller.attachmentTargetRevision
        controller.openThread("b"); controller.openThread("a")
        assertTrue(controller.attachmentTargetRevision > initial)
        val beforeSend=controller.attachmentTargetRevision
        controller.editDraft("freeze")
        controller.sendDraft()
        assertTrue(controller.attachmentTargetRevision > beforeSend)
        controller.close()
    }

    @Test fun anArchivedSelectionSurvivesInitialRefreshAndAComposerWithoutThreadIsNotRetargeted()=runTest {
        val host=Host().apply { threads=threads.map { if(it.id.value=="a") it.copy(archived=true) else it } }
        val archived=controller(host)
        archived.openThread("a")
        runCurrent()
        assertEquals("a",archived.state.value.activeThreadId)
        archived.close()
        val saved=MemoryPersistence().apply { checkpoint=WorkbenchCheckpoint(host.threads,null,"",emptyList(),emptyMap(),"new unsent draft",1,
            emptyList(),null,emptySet(),emptySet()) }
        val restored=controller(host,saved)
        runCurrent()
        restored.onForegrounded()
        runCurrent()
        assertNull(restored.state.value.activeThreadId)
        assertEquals("new unsent draft",restored.state.value.draft)
        restored.close()
    }

    @Test fun controllerCloseRunsOwnedCleanupExactlyOnce()=runTest {
        var closes=0
        val controller=WorkbenchController(workbenchTestScope(),Host(),object:AgentCommandCatalogRepository {
            override suspend fun get(gatewayId:String,languageCode:String)=AgentCommandCatalog(CatalogVersion("v1"),emptyList())
        },{ConversationScope("profile","gateway","account","install")},onClosed={closes++})
        controller.close()
        controller.close()
        assertEquals(1,closes)
    }

    @Test fun cachedMissingHistoryNeverEvictsOrTombstonesASelectedThreadAfterReconnect()=runTest {
        val host=Host().apply { authoritative=false }
        val controller=controller(host)
        controller.openThread("deep_linked")
        runCurrent()
        controller.refreshThreads()
        runCurrent()
        assertEquals("deep_linked",controller.state.value.activeThreadId)
        host.authoritative=true
        host.threads=host.threads+summary("deep_linked",3)
        controller.onForegrounded()
        runCurrent()
        controller.openThread("b")
        controller.openThread("deep_linked")
        assertEquals("deep_linked",controller.state.value.activeThreadId)
        controller.close()
    }

    @Test fun aListRequestedBeforeNavigationCannotEvictTheNewlySelectedThread()=runTest {
        val host=Host()
        val gate=CompletableDeferred<Unit>()
        host.listGate=gate
        val controller=controller(host)
        runCurrent()
        controller.openThread("created_elsewhere")
        gate.complete(Unit)
        runCurrent()
        assertEquals("created_elsewhere",controller.state.value.activeThreadId)
        controller.close()
    }

    private fun TestScope.controller(host:Host,persistence:WorkbenchPersistence?=null,supported:Boolean=true,external:() -> Set<String> = {emptySet()},
        scope:() -> ConversationScope = {ConversationScope("profile","gateway","account","install")})=
        WorkbenchController(workbenchTestScope(),host,object:AgentCommandCatalogRepository {
            override suspend fun get(gatewayId:String,languageCode:String)=AgentCommandCatalog(CatalogVersion("v1"),emptyList())
        },scope,persistence=persistence,
            persistenceDispatcher=StandardTestDispatcher(testScheduler),replyTimeouts=WorkbenchController.ReplyTimeouts(enabled=false),
            supportsConversationManagement=supported,externalBusyThreadIds=external)

    private fun rows(controller:WorkbenchController)=(controller.state.value.threads as Loadable.Ready).value
    private class MemoryPersistence:WorkbenchPersistence {
        var checkpoint:WorkbenchCheckpoint?=null
        private val atoms=mutableMapOf<String,List<LocalSendAtom>>()
        override fun load()=checkpoint
        override fun save(checkpoint:WorkbenchCheckpoint) { this.checkpoint=checkpoint }
        override fun loadSendAtoms(conversationId:String)=atoms[conversationId].orEmpty()
        override fun saveSendAtoms(conversationId:String,atoms:List<LocalSendAtom>) { this.atoms[conversationId]=atoms }
    }
    private class Host:ConversationRepository {
        var threads=listOf(summary("a",2),summary("b",1))
        var organization=ConversationOrganization()
        val cursors=mutableListOf<String?>()
        val expectedRevisions=mutableListOf<Long>()
        val submittedOrganizations=mutableListOf<ConversationOrganization>()
        val events=MutableSharedFlow<VerifiedConversationEvent>(extraBufferCapacity=16)
        val sendGate=CompletableDeferred<Unit>()
        var listGate:CompletableDeferred<Unit>?=null
        var snapshotRevision:Long?=null
        var organizationReadGate:CompletableDeferred<Unit>?=null
        var ignoreOrganizationReadCancellation=false
        var organizationUpdateGate:CompletableDeferred<Unit>?=null
        var alwaysConflict=false
        var organizationUpdateFailure:String?=null
        var archives=0
        var deletes=0
        var authoritative=true
        override suspend fun listConversations(scope:ConversationScope,page:PageRequest):ConversationPage {
            val snapshot=threads
            val revision=snapshotRevision
            cursors+=page.cursor
            listGate?.let { gate -> listGate=null; gate.await() }
            val offset=page.cursor?.toInt() ?: 0
            val tail=(offset+page.limit).takeIf { it < snapshot.size }?.toString()
            return ConversationPage(snapshot.drop(offset).take(page.limit),tail,snapshotRevision=revision,authoritative=authoritative)
        }
        override suspend fun readOrganization():ConversationOrganization {
            val snapshot=organization
            organizationReadGate?.let { gate ->
                organizationReadGate=null
                if (ignoreOrganizationReadCancellation) withContext(NonCancellable) { gate.await() } else gate.await()
            }
            return snapshot
        }
        override suspend fun updateOrganization(expectedRevision:Long,organization:ConversationOrganization):ConversationOrganization {
            expectedRevisions+=expectedRevision
            submittedOrganizations+=organization
            organizationUpdateGate?.let { gate -> organizationUpdateGate=null; gate.await() }
            organizationUpdateFailure?.let { error(it) }
            if(alwaysConflict) {
                this.organization=this.organization.copy(revision=this.organization.revision+1)
                error("ORGANIZATION_UPDATE_FAILED:ORGANIZATION_CONFLICT")
            }
            check(expectedRevision==this.organization.revision) { "ORGANIZATION_UPDATE_FAILED:ORGANIZATION_CONFLICT" }
            this.organization=organization.copy(revision=expectedRevision+1)
            return this.organization
        }
        override suspend fun setArchived(conversationId:String,archived:Boolean) { archives++; threads=threads.map { if(it.id.value==conversationId) it.copy(archived=archived) else it } }
        override suspend fun deleteConversation(conversationId:String) { deletes++; threads=threads.filterNot { it.id.value==conversationId } }
        override fun observeEvents(scope:ConversationScope)=events
        override suspend fun timeline(conversationId:String,page:PageRequest)=TimelinePage(emptyList(),null)
        override suspend fun submitMessage(message:OutgoingMessage):MessageAcceptance=error("thread required")
        override suspend fun submitMessage(conversationId:String,message:OutgoingMessage):MessageAcceptance { sendGate.await(); return MessageAcceptance("server",message.clientMessageId.value) }
        override suspend fun submitBatch(batch:MessageBatch):BatchAcceptance=error("not used")
        override suspend fun createConversation(scope:ConversationScope,clientConversationId:String)=Conversation(ConversationId("created"),"Created",0)
        override suspend fun cancelGeneration(generationId:String,requestId:String)=CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }
    private companion object { fun summary(id:String,updatedAt:Long)=ConversationSummary(ConversationId(id),id,updatedAt) }
}
