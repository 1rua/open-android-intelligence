package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.state.*
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.mobile.conversations.EncryptedConversationMirror
import com.openandroidintelligence.mobile.conversations.MirroredConversationRepository
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LocalConversationFirstFrameTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val key = object : AesGcmKeyProvider {
        override fun getOrCreate() = SecretKeySpec(ByteArray(32) { 29 }, "AES")
        override fun delete() = Unit
    }
    private val account = ConversationScope("profile_first_frame", "https://first-frame.example", "acct_one", "install_one")
    private val user = TimelineMessage("msg_user", "user", listOf(MessagePart.Text("问题")), 10,
        conversationId = ConversationId("conv_a"), clientMessageId = ClientMessageId("cm_one"))
    private val assistant = TimelineMessage("msg_assistant", "assistant", listOf(MessagePart.Text("回答")), 20,
        conversationId = ConversationId("conv_a"))

    @Test fun openingCachedConversationShowsBothSidesBeforeDelayedRemoteCompletes() = runBlocking {
        val mirror = EncryptedConversationMirror(context, account, key, Dispatchers.Unconfined)
        mirror.saveSendAtoms("conv_a", listOf(LocalSendAtom(ConversationId("conv_a"), ClientMessageId("cm_one"), user.parts, 10).accepted(user.id)))
        mirror.saveTimeline("conv_a", TimelinePage(listOf(user, assistant), null, 1))
        mirror.save(WorkbenchCheckpoint(threads(), "conv_b", "另一个会话", emptyList(), emptyMap(), "", 0,
            emptyList(), null, emptySet(), emptySet()))
        val gate = CompletableDeferred<Unit>()
        val host = DelayedHost(gate, listOf(user, assistant))
        val job = SupervisorJob()
        val controller = controller(CoroutineScope(coroutineContext + job), host, mirror)
        try {
            controller.openThread("conv_a")
            awaitTimeline(controller) { rows -> rows.map { it.sender } == listOf("user", "assistant") }
            assertFalse("断言时服务器历史必须仍在等待", gate.isCompleted)
            assertEquals("首个本地可读快照必须同时含用户和助手", listOf("user", "assistant"),
                (controller.state.value.timeline as? Loadable.Ready)?.value.orEmpty().map { it.sender })
        } finally {
            gate.complete(Unit)
            controller.closeAndAwait()
            job.cancel()
        }
    }

    @Test fun staleEmptyHostPageCannotEraseAReplyAlreadyCommittedFromEvents() = runBlocking {
        val mirror = EncryptedConversationMirror(context, account, key, Dispatchers.Unconfined)
        mirror.apply(VerifiedConversationEvent.TimelineUpsert("evt_reply", 20, 5, assistant))
        val host = DelayedHost(CompletableDeferred(Unit), emptyList())
        val page = MirroredConversationRepository(host, mirror).timeline("conv_a", PageRequest())
        assertEquals("没有明确删除的旧空页不能移除刚收到的助手消息", listOf(assistant.id), page.messages.map { it.id })
        assertEquals("再次启动后回复仍须存在", listOf(assistant.id),
            EncryptedConversationMirror(context, account, key, Dispatchers.Unconfined).timeline("conv_a")!!.messages.map { it.id })
    }

    @Test fun aNewerRemoteAssistantEditUpdatesTheVisibleLocalSnapshotWithoutRepeatingEitherSide() = runBlocking {
        val mirror=EncryptedConversationMirror(context,account,key,Dispatchers.Unconfined)
        mirror.saveTimeline("conv_a",TimelinePage(listOf(user,assistant.copy(contentRevision=1)),null,1))
        mirror.save(WorkbenchCheckpoint(threads(),"conv_b","另一个会话",emptyList(),emptyMap(),"",0,
            emptyList(),null,emptySet(),emptySet()))
        val gate=CompletableDeferred<Unit>()
        val changed=assistant.copy(parts=listOf(MessagePart.Text("远端编辑后的回答")),contentRevision=2)
        val job=SupervisorJob()
        val controller=controller(CoroutineScope(coroutineContext+job),DelayedHost(gate,listOf(user,changed)),mirror)
        try {
            controller.openThread("conv_a")
            awaitTimeline(controller) { rows -> rows.singleOrNull { !it.isUser }?.text == "回答" }
            assertEquals("回答",(controller.state.value.timeline as Loadable.Ready).value.single { !it.isUser }.text)
            gate.complete(Unit)
            awaitTimeline(controller) { rows -> rows.singleOrNull { !it.isUser }?.text == "远端编辑后的回答" }
            val rows=(controller.state.value.timeline as Loadable.Ready).value
            assertEquals(2,rows.size)
            assertEquals("远端编辑后的回答",rows.single { !it.isUser }.text)
            assertEquals(changed.parts,EncryptedConversationMirror(context,account,key).timeline("conv_a")!!.messages.single { it.id==assistant.id }.parts)
        } finally { gate.complete(Unit); controller.closeAndAwait(); job.cancel() }
    }

    @Test fun anUnrevisionedOlderHostBodyCannotOverwriteAnAlreadyCompletedEventReply() = runBlocking {
        val mirror=EncryptedConversationMirror(context,account,key,Dispatchers.Unconfined)
        mirror.apply(VerifiedConversationEvent.TimelineUpsert("evt_completed",20,5,assistant))
        val old=assistant.copy(parts=listOf(MessagePart.Text("晚返回的旧回答")))
        val page=MirroredConversationRepository(DelayedHost(CompletableDeferred(Unit),listOf(old)),mirror).timeline("conv_a",PageRequest())
        assertEquals(assistant.parts,page.messages.single().parts)
        assertEquals(assistant.parts,EncryptedConversationMirror(context,account,key).timeline("conv_a")!!.messages.single().parts)
    }

    @Test fun aHigherSnapshotRevisionCannotMakeAnOlderAssistantContentRevisionAuthoritative() = runBlocking {
        val mirror=EncryptedConversationMirror(context,account,key,Dispatchers.Unconfined)
        mirror.apply(VerifiedConversationEvent.TimelineUpsert("evt_new_reply",20,5,assistant))
        val old=assistant.copy(parts=listOf(MessagePart.Text("正文修订3的旧回答")),contentRevision=3)
        val host=object:ConversationRepository by DelayedHost(CompletableDeferred(Unit),listOf(old)) {
            override suspend fun timeline(conversationId:String,page:PageRequest)=TimelinePage(listOf(old),null,100)
        }
        val visible=MirroredConversationRepository(host,mirror).timeline("conv_a",PageRequest())
        assertEquals(100L,visible.snapshotRevision)
        assertEquals(5L,visible.messages.single().contentRevision)
        assertEquals(assistant.parts,visible.messages.single().parts)
        assertEquals(assistant.parts,EncryptedConversationMirror(context,account,key).timeline("conv_a")!!.messages.single().parts)
    }

    @Test fun restartingResumesAnInactiveKnownMediaMessageWithoutScanningThatRemoteConversation() = runBlocking {
        val mirror=EncryptedConversationMirror(context,account,key,Dispatchers.Unconfined)
        mirror.save(WorkbenchCheckpoint(threads(),"conv_a","当前会话",listOf(user),emptyMap(),"",0,
            emptyList(),null,emptySet(),emptySet()))
        val inactive=TimelineMessage("msg_inactive_media","assistant",listOf(MessagePart.Attachment(
            AttachmentDraftId("file_inactive"),"后台回复.png","image/png")),30,conversationId=ConversationId("conv_b"))
        mirror.apply(VerifiedConversationEvent.TimelineUpsert("evt_inactive_media",30,1,inactive))
        val gate=CompletableDeferred<Unit>()
        val host=DelayedHost(gate,emptyList())
        val calls=mutableListOf<Pair<String,String>>()
        val local=linkedMapOf<Pair<String,String>,HistoricalMediaMetadata>()
        val retained=CompletableDeferred<Unit>()
        val media=object:HistoricalMediaPort {
            override suspend fun metadata(conversationId:String,attachmentId:String):HistoricalMediaMetadata {
                calls+=conversationId to attachmentId
                return HistoricalMediaMetadata(conversationId,attachmentId,"后台回复.png","image/png",1,"sha256:"+"0".repeat(64),true,1)
            }
            override suspend fun retain(metadata:HistoricalMediaMetadata) {
                local[metadata.conversationId to metadata.attachmentId]=metadata
                retained.complete(Unit)
            }
            override suspend fun localMetadata(conversationId:String,attachmentId:String)=local[conversationId to attachmentId]
            override fun isRetained(attachmentId:String)=local.keys.any { it.second==attachmentId }
            override fun preview(attachmentId:String):ByteArray?=null
            override fun remove(attachmentId:String) { local.keys.removeAll { it.second==attachmentId } }
        }
        val job=SupervisorJob()
        val reopened=EncryptedConversationMirror(context,account,key,Dispatchers.Unconfined)
        val controller=controller(CoroutineScope(coroutineContext+job),host,reopened,media)
        try {
            withTimeout(5_000) { retained.await() }
            assertEquals(listOf("conv_b" to "file_inactive"),calls)
            assertTrue("本地已知附件恢复不能扫描未打开的服务器会话",host.timelineReads.none { it=="conv_b" })
            assertEquals("conv_a",controller.state.value.activeThreadId)
            reopened.apply(VerifiedConversationEvent.TimelineTombstoned("evt_deleted_inactive",40,inactive.id,2,ConversationId("conv_b")))
            assertTrue("明确删除不得重新成为启动保存意图",reopened.loadKnownMediaMessages().none { it.id==inactive.id })
        } finally { gate.complete(Unit); controller.closeAndAwait(); job.cancel() }
    }

    private suspend fun awaitTimeline(controller: WorkbenchController, predicate: (List<TimelineEntry>) -> Boolean) {
        withTimeout(5_000) {
            controller.state.first { state ->
                state.activeThreadId == "conv_a" && state.historyHydrated &&
                    (state.timeline as? Loadable.Ready)?.value?.let(predicate) == true
            }
        }
    }

    private fun controller(scope: CoroutineScope, host: DelayedHost, mirror: EncryptedConversationMirror,media:HistoricalMediaPort?=null) = WorkbenchController(
        scope, MirroredConversationRepository(host, mirror), object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) = AgentCommandCatalog(CatalogVersion("test"), emptyList())
        }, { account }, persistence = mirror, media=media,mediaDispatcher=Dispatchers.Unconfined,
        replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false),
    )

    private fun threads() = listOf(ConversationSummary(ConversationId("conv_a"), "本地会话", 2),
        ConversationSummary(ConversationId("conv_b"), "另一个会话", 1))

    private class DelayedHost(private val gate: CompletableDeferred<Unit>, private val rows: List<TimelineMessage>) :
        ConversationRepository, GenerationTracker, StreamHealthSource {
        override val generationId = MutableStateFlow<String?>(null)
        override val streamHealth = MutableStateFlow(StreamHealth.LIVE)
        val timelineReads=mutableListOf<String>()
        override suspend fun listConversations(scope: ConversationScope, page: PageRequest) = ConversationPage(emptyList(), null)
        override suspend fun timeline(conversationId: String, page: PageRequest): TimelinePage {
            timelineReads+=conversationId
            if (conversationId == "conv_a") gate.await()
            return TimelinePage(if (conversationId == "conv_a") rows else emptyList(), null, 1)
        }
        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation = error("测试不创建会话")
        override suspend fun submitBatch(batch: MessageBatch): BatchAcceptance = error("测试不发送批次")
        override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance = error("测试不发送消息")
        override fun observeEvents(scope: ConversationScope) = emptyFlow<VerifiedConversationEvent>()
        override suspend fun cancelGeneration(generationId: String, requestId: String) = CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }
}
