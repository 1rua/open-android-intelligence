package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import java.io.ByteArrayInputStream
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WorkbenchLocalMediaRegressionTest {
    private val mediaMessage = TimelineMessage("msg_media", "assistant", listOf(MessagePart.Attachment(
        AttachmentDraftId("file_one"), "回答.png", "image/png")), 10, conversationId=ConversationId("conv_a"))

    @Test fun attachmentOnlyAssistantHistoryIsSavedAutomaticallyAndDuplicateEventsUseTheLocalOriginal() = runTest {
        val host=Host().apply { rows=listOf(mediaMessage) }
        val media=Media()
        val controller=createController(host,media)
        try {
            controller.openThread("conv_a"); runCurrent()
            val attachment=rows(controller).single().attachments.single()
            assertEquals("纯媒体消息保留附件卡片", "", rows(controller).single().text)
            assertEquals(AttachmentLocalState.SAVED,attachment.localState)
            assertArrayEquals(byteArrayOf(1,2,3),attachment.previewBytes)
            host.events.emit(VerifiedConversationEvent.TimelineUpsert("evt_media",10,1,mediaMessage)); runCurrent()
            controller.onForegrounded(); runCurrent()
            assertEquals("事件重放和历史同步不能重新下载已保存的原件",1,media.fetches)
        } finally { controller.close() }
    }

    @Test fun unavailableOriginalKeepsTheAttachmentIdentityAndDoesNotReplaceTheMessageWithAnEmptyTextError() = runTest {
        val host=Host().apply { rows=listOf(mediaMessage) }
        val media=Media().apply { failure=IOException("REMOTE_UNAVAILABLE") }
        val controller=createController(host,media)
        try {
            controller.openThread("conv_a"); runCurrent()
            val row=rows(controller).single()
            assertEquals("file_one",row.attachments.single().draftId)
            assertEquals("回答.png",row.attachments.single().filename)
            assertEquals(AttachmentLocalState.UNAVAILABLE,row.attachments.single().localState)
            assertEquals("",row.text)
        } finally { controller.close() }
    }

    @Test fun anUnownedMediaEventCannotBorrowTheVisibleConversationOrStartSavingAnOriginal() = runTest {
        val host=Host()
        val media=Media()
        val controller=createController(host,media)
        try {
            controller.openThread("conv_a"); runCurrent()
            host.events.emit(VerifiedConversationEvent.TimelineUpsert("evt_unowned",10,1,mediaMessage.copy(conversationId=null)))
            runCurrent()
            assertTrue("未绑定线程的消息不能进入当前会话",rows(controller).isEmpty())
            assertEquals("未绑定线程的原件不能启动归档",0,media.fetches)
            assertEquals("conv_a",controller.state.value.activeThreadId)
        } finally { controller.close() }
    }

    @Test fun deletionDuringAutomaticSavingCancelsTheTaskAndCannotRestoreTheMediaRow() = runTest {
        val host=Host().apply { rows=listOf(mediaMessage) }
        val gate=CompletableDeferred<Unit>()
        val media=Media().apply { fetchingGate=gate }
        val controller=createController(host,media)
        try {
            controller.openThread("conv_a"); runCurrent()
            host.rows=emptyList()
            host.events.emit(VerifiedConversationEvent.TimelineTombstoned("evt_delete",20,"msg_media",2,ConversationId("conv_a")))
            runCurrent(); gate.complete(Unit); runCurrent()
            assertTrue("迟到原件不能让已删除消息重新出现",rows(controller).isEmpty())
            assertEquals(1,media.cancelledFetches)
        } finally { gate.complete(Unit); controller.close() }
    }

    @Test fun aHigherContentRevisionStartsANewMediaAttemptAfterAnOrdinaryTombstone() = runTest {
        val host=Host().apply { rows=listOf(mediaMessage.copy(contentRevision=1)) }
        val media=Media()
        val controller=createController(host,media)
        try {
            controller.openThread("conv_a"); runCurrent()
            assertEquals(AttachmentLocalState.SAVED,rows(controller).single().attachments.single().localState)
            host.rows=emptyList()
            host.events.emit(VerifiedConversationEvent.TimelineTombstoned("evt_delete",20,"msg_media",2,ConversationId("conv_a")))
            runCurrent()
            assertTrue(rows(controller).isEmpty())
            media.local.clear()
            host.events.emit(VerifiedConversationEvent.TimelineUpsert("evt_restore",30,3,mediaMessage.copy(contentRevision=3)))
            runCurrent()
            assertEquals("明确的更高正文修订必须重新启动原件保存",2,media.fetches)
            assertEquals(AttachmentLocalState.SAVED,rows(controller).single().attachments.single().localState)
        } finally { controller.close() }
    }

    @Test fun anOldDownloadIgnoringCancellationCannotOverwriteTheRestoredGenerationsSuccess() = runTest {
        val host=Host().apply { rows=listOf(mediaMessage.copy(contentRevision=1)) }
        val gate=CompletableDeferred<Unit>()
        var attempts=0
        val downloads=object:AttachmentDownloadPort {
            override suspend fun download(conversationId:String,attachmentId:String):AttachmentDownloadResult {
                if (attempts++==0) {
                    withContext(NonCancellable) { gate.await() }
                    return AttachmentDownloadResult("content://downloads/old","旧任务.png")
                }
                return AttachmentDownloadResult("content://downloads/new","新任务.png")
            }
        }
        val controller=createController(host,Media(),downloads=downloads)
        try {
            controller.openThread("conv_a"); runCurrent()
            controller.downloadAttachment("file_one"); runCurrent()
            host.rows=emptyList()
            host.events.emit(VerifiedConversationEvent.TimelineTombstoned("evt_delete",20,"msg_media",2,ConversationId("conv_a")))
            runCurrent()
            host.events.emit(VerifiedConversationEvent.TimelineUpsert("evt_restore",30,3,mediaMessage.copy(contentRevision=3)))
            runCurrent()
            controller.downloadAttachment("file_one"); runCurrent()
            assertTrue(controller.state.value.notice?.contains("新任务.png")==true)
            gate.complete(Unit); runCurrent()
            assertEquals(2,attempts)
            assertEquals(AttachmentDownloadState.SAVED,rows(controller).single().attachments.single().downloadState)
            assertTrue("旧任务即使晚到也不能回写成功提示",controller.state.value.notice?.contains("新任务.png")==true)
        } finally { gate.complete(Unit); controller.close() }
    }

    @Test fun archiveFailureBeforeMessageHttpPreservesDraftIntentAndRetryStaging() = runTest {
        val host=Host()
        val coordinator=Coordinator().apply { archiveFailure=IOException("DISK_FULL") }
        val controller=createController(host,Media(),coordinator)
        try {
            controller.openThread("conv_a"); runCurrent()
            controller.addAttachment(LocalAttachmentSelection("照片.png","image/png",AttachmentContentSource { ByteArrayInputStream(byteArrayOf(7)) }))
            runCurrent(); controller.editDraft("必须保留的原消息"); controller.sendDraft(); runCurrent()
            assertEquals("归档失败必须发生在消息HTTP之前",0,host.submissions)
            assertEquals("本地归档失败不得释放唯一暂存原件",0,coordinator.releases)
            assertTrue(controller.state.value.notice?.contains("LOCAL_SAVE_FAILED") == true)
            assertEquals("必须保留的原消息",controller.state.value.draft)
            assertEquals("draft_one",controller.state.value.attachments.single().id.value)
            assertTrue("原提交身份必须仍可恢复",controller.state.value.hasRecoverableSubmissions)
            controller.retryPendingSubmissions(); runCurrent()
            assertEquals("恢复查询不应绕过归档门控",0,host.submissions)
        } finally { controller.close() }
    }

    @Test fun successfulArchivePrecedesStagingReleaseAndDownloadKeepsItsOriginalConversation() = runTest {
        val host=Host()
        val coordinator=Coordinator()
        val gate=CompletableDeferred<Unit>()
        val calls=mutableListOf<Pair<String,String>>()
        val downloads=object:AttachmentDownloadPort {
            override suspend fun download(conversationId:String,attachmentId:String):AttachmentDownloadResult {
                calls+=conversationId to attachmentId
                gate.await()
                return AttachmentDownloadResult("content://downloads/one","照片.png")
            }
        }
        val controller=createController(host,Media(),coordinator,downloads)
        try {
            controller.openThread("conv_a"); runCurrent()
            controller.addAttachment(LocalAttachmentSelection("照片.png","image/png",AttachmentContentSource { ByteArrayInputStream(byteArrayOf(7)) }))
            runCurrent(); controller.sendDraft(); runCurrent()
            assertEquals(listOf("archive","release"),coordinator.order)
            controller.downloadAttachment("file_one"); runCurrent()
            assertEquals(AttachmentDownloadState.DOWNLOADING,rows(controller).single().attachments.single().downloadState)
            controller.openThread("conv_b"); runCurrent()
            gate.complete(Unit); runCurrent()
            assertEquals(listOf("conv_a" to "file_one"),calls)
            assertTrue(controller.state.value.notice?.startsWith("已保存到下载/OpenAndroidIntelligence") == true)
        } finally { gate.complete(Unit); controller.close() }
    }

    @Test fun recoveringAnUnknownSendCanArchiveItsOriginalButCannotReleaseItsRetryStaging() = runTest {
        val draft=AttachmentDraft(AttachmentDraftId("draft_one"),"待确认.png","image/png",1,"sha256:"+"0".repeat(64),AttachmentState.VERIFIED)
        val coordinator=Coordinator().apply { restored=listOf(draft) }
        val atom=LocalSendAtom(ConversationId("conv_a"),ClientMessageId("cm_unknown"),listOf(MessagePart.Attachment(
            AttachmentDraftId("file_one"),draft.filename,draft.mediaType)),10,submissionState=LocalSubmissionState.OUTCOME_UNKNOWN)
        val journal=MemoryJournal(atom)
        val controller=createController(Host(),Media(),coordinator,persistence=journal)
        try {
            runCurrent()
            assertEquals("未知结果只保存副本，不释放发送重试的暂存",listOf("archive"),coordinator.order)
            assertEquals(0,coordinator.releases)
            assertEquals(LocalSubmissionState.OUTCOME_UNKNOWN,journal.loadSendAtoms("conv_a").single().submissionState)
        } finally { controller.close() }
    }

    @Test fun resendingTheSameDraftQueriesBeforeRetryAndWaitsForThisAttemptDespiteTheOldVerifiedState() = runTest {
        val host=Host().apply { submitFailure=IOException("ACK_LOST") }
        val coordinator=Coordinator()
        val journal=MemoryJournal()
        val controller=createController(host,Media(),coordinator,persistence=journal)
        val retryGate=CompletableDeferred<Unit>()
        try {
            controller.openThread("conv_a"); runCurrent()
            controller.addAttachment(LocalAttachmentSelection("照片.png","image/png",AttachmentContentSource { ByteArrayInputStream(byteArrayOf(7)) }))
            runCurrent(); controller.editDraft("保留同一条消息"); controller.sendDraft(); runCurrent()
            val originalId=host.sent.single().clientMessageId
            assertEquals(LocalSubmissionState.OUTCOME_UNKNOWN,journal.loadSendAtoms("conv_a").single().submissionState)
            assertEquals(AttachmentState.VERIFIED,controller.state.value.attachments.single().state)
            host.submitFailure=null
            coordinator.retryGate=retryGate
            coordinator.beforeRetry={ assertEquals("必须先查询原客户端消息身份",listOf(originalId),host.queried) }
            controller.sendDraft(); runCurrent()
            assertEquals("旧 VERIFIED 不能替代本次附件恢复完成",1,host.submissions)
            assertEquals(1,coordinator.retries)
            assertEquals(0,coordinator.releases)
            retryGate.complete(Unit); runCurrent()
            assertEquals(2,host.submissions)
            assertEquals(originalId,host.sent.last().clientMessageId)
            assertEquals(listOf("file_retried"),host.sent.last().attachmentIds)
            val restored=journal.loadSendAtoms("conv_a").single()
            assertEquals(originalId,restored.clientMessageId)
            assertEquals("file_retried",restored.parts.filterIsInstance<MessagePart.Attachment>().single().draftId.value)
            assertEquals(LocalSubmissionState.ACCEPTED,restored.submissionState)
            assertEquals(1,coordinator.releases)
        } finally { retryGate.complete(Unit); controller.close() }
    }

    @Test fun recoveringUnknownAttachmentWaitsForTheNewAttemptAndKeepsItsDurableClientIdentity() = runTest {
        val atom=unknownAttachmentAtom()
        val host=Host()
        val coordinator=Coordinator().apply { restored=listOf(verifiedDraft()); retryGate=CompletableDeferred() }
        val journal=MemoryJournal(atom)
        val controller=createController(host,Media(),coordinator,persistence=journal)
        try {
            runCurrent()
            coordinator.beforeRetry={ assertEquals(listOf(atom.clientMessageId),host.queried) }
            controller.retryPendingSubmissions(); runCurrent()
            assertEquals(0,host.submissions)
            assertEquals(1,coordinator.retries)
            assertEquals(0,coordinator.releases)
            coordinator.retryGate!!.complete(Unit); runCurrent()
            assertEquals(atom.clientMessageId,host.sent.single().clientMessageId)
            assertEquals(listOf("file_retried"),host.sent.single().attachmentIds)
            val saved=journal.loadSendAtoms("conv_a").single()
            assertEquals(atom.clientMessageId,saved.clientMessageId)
            assertEquals("file_retried",saved.parts.filterIsInstance<MessagePart.Attachment>().single().draftId.value)
            assertEquals(LocalSubmissionState.ACCEPTED,saved.submissionState)
        } finally { coordinator.retryGate?.complete(Unit); controller.close() }
    }

    @Test fun anAlreadyAcceptedAttachmentQueryDoesNotRetryUploadOrResubmitItsMessage() = runTest {
        val atom=unknownAttachmentAtom()
        val host=Host().apply { queryResult=TimelineMessage("accepted_original","user",atom.parts,10,
            conversationId=atom.conversationId,clientMessageId=atom.clientMessageId) }
        val coordinator=Coordinator().apply { restored=listOf(verifiedDraft()) }
        val journal=MemoryJournal(atom)
        val controller=createController(host,Media(),coordinator,persistence=journal)
        try {
            runCurrent(); controller.retryPendingSubmissions(); runCurrent()
            assertEquals(listOf(atom.clientMessageId),host.queried)
            assertEquals(0,coordinator.retries)
            assertEquals(0,host.submissions)
            assertEquals("accepted_original",journal.loadSendAtoms("conv_a").single().messageId)
            assertEquals(LocalSubmissionState.ACCEPTED,journal.loadSendAtoms("conv_a").single().submissionState)
        } finally { controller.close() }
    }

    @Test fun successfulDownloadRetainsTheWarningAboutAnOlderInterruptedDownload() = runTest {
        val host=Host().apply { rows=listOf(mediaMessage) }
        val downloads=object:AttachmentDownloadPort {
            override suspend fun download(conversationId:String,attachmentId:String)=AttachmentDownloadResult(
                "content://downloads/new","新文件.png","DOWNLOAD_RECOVERY_FAILED")
        }
        val controller=createController(host,Media(),downloads=downloads)
        try {
            controller.openThread("conv_a"); runCurrent()
            controller.downloadAttachment("file_one"); runCurrent()
            assertEquals(AttachmentDownloadState.SAVED,rows(controller).single().attachments.single().downloadState)
            assertTrue(controller.state.value.notice?.contains("已保存到下载/OpenAndroidIntelligence：新文件.png") == true)
            assertTrue("新下载成功不能覆盖旧下载恢复失败提醒",controller.state.value.notice?.contains("上次中断的下载尚未恢复") == true)
        } finally { controller.close() }
    }

    private fun verifiedDraft()=AttachmentDraft(AttachmentDraftId("draft_one"),"照片.png","image/png",1,
        "sha256:"+"0".repeat(64),AttachmentState.VERIFIED)
    private fun unknownAttachmentAtom()=LocalSendAtom(ConversationId("conv_a"),ClientMessageId("cm_unknown"),
        listOf(MessagePart.Text("恢复原消息"),MessagePart.Attachment(AttachmentDraftId("file_one"),"照片.png","image/png",1,"sha256:"+"0".repeat(64))),
        10,submissionState=LocalSubmissionState.OUTCOME_UNKNOWN)

    @Test fun closingWaitsForCancelledIoAndItsLateResultCannotPublishAfterPrivateCleanup() = runTest {
        val host=Host().apply { rows=listOf(mediaMessage) }
        val gate=CompletableDeferred<Unit>()
        val media=Media().apply { fetchingGate=gate; finishDespiteCancellation=true }
        val controller=createController(host,media)
        controller.openThread("conv_a"); runCurrent()
        val closing=launch { controller.closeAndAwait() }
        runCurrent()
        assertFalse("磁盘 IO 未收尾时不能开始清理私有目录",closing.isCompleted)
        assertFalse(controller.state.value.isOnline)
        gate.complete(Unit); runCurrent()
        assertTrue(closing.isCompleted)
        assertEquals(1,media.completedFetches)
        media.local.clear()
        controller.openThread("conv_b"); runCurrent()
        assertTrue(media.local.isEmpty())
        assertEquals("关闭后不接受新的线程绑定","conv_a",controller.state.value.activeThreadId)
        assertEquals("迟到 IO 不得把附件发布为已保存",AttachmentLocalState.PENDING,rows(controller).single().attachments.single().localState)
    }

    private fun TestScope.createController(host:Host,media:Media,coordinator:Coordinator?=null,downloads:AttachmentDownloadPort?=null,
        persistence:WorkbenchPersistence?=null)=WorkbenchController(
        this,host,object:AgentCommandCatalogRepository {
            override suspend fun get(gatewayId:String,languageCode:String)=AgentCommandCatalog(CatalogVersion("test"),emptyList())
        },{ConversationScope("profile","gateway","account","install")},attachmentCoordinator=coordinator,
        media=media,downloads=downloads,persistence=persistence,mediaDispatcher=UnconfinedTestDispatcher(testScheduler),
        replyTimeouts=WorkbenchController.ReplyTimeouts(enabled=false))
    private fun rows(controller:WorkbenchController)=(controller.state.value.timeline as? Loadable.Ready)?.value.orEmpty()

    private class Host:ConversationRepository,MessageOutcomeQuery {
        var rows=emptyList<TimelineMessage>()
        var submissions=0
        var submitFailure:Exception?=null
        var queryResult:TimelineMessage?=null
        val sent=mutableListOf<OutgoingMessage>()
        val queried=mutableListOf<ClientMessageId>()
        val events=MutableSharedFlow<VerifiedConversationEvent>(extraBufferCapacity=16)
        override suspend fun listConversations(scope:ConversationScope,page:PageRequest)=ConversationPage(emptyList(),null)
        override suspend fun timeline(conversationId:String,page:PageRequest)=TimelinePage(if(conversationId=="conv_a") rows else emptyList(),null)
        override suspend fun submitMessage(message:OutgoingMessage)=submitMessage("conv_a",message)
        override suspend fun submitMessage(conversationId:String,message:OutgoingMessage):MessageAcceptance {
            submissions++
            sent+=message
            submitFailure?.let { throw it }
            rows=listOf(TimelineMessage("msg_upload","user",listOf(MessagePart.Attachment(AttachmentDraftId("file_one"),"照片.png","image/png")),10,
                conversationId=ConversationId(conversationId),clientMessageId=message.clientMessageId))
            return MessageAcceptance("msg_upload",message.clientMessageId.value)
        }
        override suspend fun queryMessage(conversationId:String,clientMessageId:ClientMessageId):TimelineMessage? {
            queried+=clientMessageId
            return queryResult
        }
        override fun observeEvents(scope:ConversationScope)=events
        override suspend fun submitBatch(batch:MessageBatch):BatchAcceptance=error("纯附件不能提交合并批次")
        override suspend fun createConversation(scope:ConversationScope,clientConversationId:String):Conversation=error("测试不创建会话")
        override suspend fun cancelGeneration(generationId:String,requestId:String)=CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }
    private class Media:HistoricalMediaPort {
        val local=linkedMapOf<Pair<String,String>,HistoricalMediaMetadata>()
        private val revisions=linkedMapOf<Pair<String,String>,Pair<Long?,Boolean>>()
        var fetchingGate:CompletableDeferred<Unit>?=null
        var failure:Exception?=null
        var fetches=0
        var cancelledFetches=0
        var completedFetches=0
        var finishDespiteCancellation=false
        override suspend fun metadata(conversationId:String,attachmentId:String)=HistoricalMediaMetadata(conversationId,attachmentId,"回答.png","image/png",3,"sha256:"+"0".repeat(64),true,3)
        override suspend fun retain(metadata:HistoricalMediaMetadata) { local[metadata.conversationId to metadata.attachmentId]=metadata }
        override suspend fun localMetadata(conversationId:String,attachmentId:String)=local[conversationId to attachmentId]
        override suspend fun ensureLocal(conversationId:String,attachmentId:String):HistoricalMediaMetadata {
            fetches++
            try {
                if (finishDespiteCancellation) withContext(NonCancellable) { fetchingGate?.await() } else fetchingGate?.await()
            } catch(cancelled:CancellationException) { cancelledFetches++; throw cancelled }
            failure?.let { throw it }
            return metadata(conversationId,attachmentId).also { retain(it); completedFetches++ }
        }
        override fun isRetained(attachmentId:String)=local.keys.any { it.second==attachmentId }
        override fun preview(attachmentId:String)=byteArrayOf(1,2,3)
        override fun remove(attachmentId:String) { local.keys.removeAll { it.second==attachmentId } }
        override suspend fun applyTransition(transition:HistoricalMediaTransition) {
            val revision=transition.revision
            transition.attachmentIds.forEach { id ->
                val key=transition.conversationId to id
                val old=revisions[key]
                if (old != null && (old.first == null || (revision != null && revision < old.first!!))) return@forEach
                revisions[key]=revision to transition.deleted
                if (transition.deleted) local.remove(key)
            }
        }
    }
    private class Coordinator:AttachmentDraftCoordinator {
        var archiveFailure:Exception?=null
        var releases=0
        var retries=0
        var retryGate:CompletableDeferred<Unit>?=null
        var beforeRetry:(()->Unit)?=null
        private var remoteId="file_one"
        val order=mutableListOf<String>()
        var restored=emptyList<AttachmentDraft>()
        override fun restoredDrafts()=restored
        override suspend fun prepare(selection:LocalAttachmentSelection)=AttachmentDraft(AttachmentDraftId("draft_one"),selection.filename,selection.mediaType,1,
            "sha256:"+"0".repeat(64),AttachmentState.VERIFIED)
        override fun remoteAttachmentId(draftId:String)=remoteId
        override fun observe(draftId:String)=flowOf(AttachmentDraftState(AttachmentDraftId(draftId),AttachmentState.VERIFIED,1f))
        override suspend fun archiveSubmitted(conversationId:String,draftId:String,media:HistoricalMediaPort) {
            archiveFailure?.let { throw it }; order+="archive"
            media.retain(HistoricalMediaMetadata(conversationId,remoteId,"照片.png","image/png",1,"sha256:"+"0".repeat(64),true,1))
        }
        override suspend fun retrySubmittedSource(conversationId:String,draftId:String,media:HistoricalMediaPort):AttachmentDraft {
            beforeRetry?.invoke()
            retries++
            retryGate?.await()
            remoteId="file_retried"
            return AttachmentDraft(AttachmentDraftId(draftId),"照片.png","image/png",1,"sha256:"+"0".repeat(64),AttachmentState.VERIFIED)
        }
        override suspend fun releaseAfterSubmit(draftId:String) { releases++; order+="release"; restored=restored.filterNot { it.id.value==draftId } }
        override suspend fun armSubmission(draftId:String,revision:Long):PendingSubmissionIntent=error("测试不使用独立提交")
        override suspend fun cancelSubmission(intentId:String)=CancelSubmissionResult(true)
        override fun retry(draftId:String)=error("附件已经验证")
        override suspend fun discard(draftId:String) { order+="discard" }
    }

    private class MemoryJournal(atom:LocalSendAtom?=null):AwaitableWorkbenchPersistence {
        private var atoms=listOfNotNull(atom)
        private var checkpoint=WorkbenchCheckpoint(emptyList(),"conv_a","待确认会话",emptyList(),emptyMap(),"",0,emptyList(),null,emptySet(),emptySet())
        private var commits=0
        private var closed=false
        override fun load()=checkpoint
        override fun save(checkpoint:WorkbenchCheckpoint) { check(!closed); this.checkpoint=checkpoint; commits++ }
        override fun loadSendAtoms(conversationId:String)=if(conversationId=="conv_a") atoms else emptyList()
        override fun saveSendAtoms(conversationId:String,atoms:List<LocalSendAtom>) { check(!closed); if(conversationId=="conv_a") this.atoms=atoms; commits++ }
        override suspend fun loadTimeline(conversationId:String)=TimelinePage(mergeLocalSendAtoms(emptyList(),loadSendAtoms(conversationId)),null)
        override suspend fun loadKnownMediaMessages()=mergeLocalSendAtoms(emptyList(),atoms)
        override suspend fun saveCheckpoint(checkpoint:WorkbenchCheckpoint)=save(checkpoint)
        override suspend fun saveSendAtomsDurably(conversationId:String,atoms:List<LocalSendAtom>)=saveSendAtoms(conversationId,atoms)
        override suspend fun flush() { check(!closed && commits>0) }
        override suspend fun closeStore() { closed=true }
    }
}
