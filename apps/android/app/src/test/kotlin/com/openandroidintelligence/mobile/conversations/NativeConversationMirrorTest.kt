package com.openandroidintelligence.mobile.conversations

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.model.ClientMessageId
import com.openandroidintelligence.conversation.model.LocalSendAtom
import com.openandroidintelligence.conversation.model.LocalSubmissionState
import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.model.NativeExecutionStep
import com.openandroidintelligence.conversation.model.NativeExecutionStatus
import com.openandroidintelligence.gateway.agents.NativeEventApproval
import com.openandroidintelligence.gateway.agents.AgentCapabilities
import com.openandroidintelligence.gateway.agents.AgentSessionClient
import com.openandroidintelligence.gateway.agents.CoreAgent
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.*
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.state.WorkbenchCheckpoint
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],application=Application::class)
class NativeConversationMirrorTest {
    @Test fun checkpointFailureRecoversAndReopensTheRealSqliteDraft() = runBlocking {
        val account=scope("checkpoint-recovery-"+UUID.randomUUID())
        val mirror=NativeConversationMirror(context,account,{"dsh"},key)
        var failNext=false
        var injectedFailures=0
        val persistence=object:com.openandroidintelligence.conversation.state.AwaitableWorkbenchPersistence by mirror {
            override suspend fun saveCheckpoint(checkpoint:WorkbenchCheckpoint) {
                if(failNext) {
                    failNext=false;injectedFailures++
                    throw java.io.IOException("SYNTHETIC_CHECKPOINT_WRITE_FAILED")
                }
                mirror.saveCheckpoint(checkpoint)
            }
        }
        val repository=object:ConversationRepository {
            override suspend fun listConversations(scope:ConversationScope,page:PageRequest)=
                ConversationPage(listOf(ConversationSummary(ConversationId("chat"),"合成会话",1)),null)
            override suspend fun timeline(conversationId:String,page:PageRequest)=TimelinePage(emptyList(),null)
            override fun observeEvents(scope:ConversationScope)=emptyFlow<VerifiedConversationEvent>()
            override suspend fun createConversation(scope:ConversationScope,clientConversationId:String):Conversation=error("合成测试不创建会话")
            override suspend fun submitMessage(message:OutgoingMessage):MessageAcceptance=error("合成测试不发送消息")
            override suspend fun submitBatch(batch:MessageBatch):BatchAcceptance=error("合成测试不发送消息")
            override suspend fun cancelGeneration(generationId:String,requestId:String)=CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
        }
        val owner=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+Dispatchers.Unconfined)
        val controller=com.openandroidintelligence.conversation.state.WorkbenchController(owner,repository,
            object:AgentCommandCatalogRepository {
                override suspend fun get(gatewayId:String,languageCode:String)=
                    AgentCommandCatalog(com.openandroidintelligence.conversation.model.CatalogVersion("synthetic"),emptyList())
            },{account},persistence=persistence,
            replyTimeouts=com.openandroidintelligence.conversation.state.WorkbenchController.ReplyTimeouts(enabled=false))
        try {
            controller.openThread("chat")
            withTimeout(5_000) { controller.state.first { it.historyHydrated } }
            controller.flushPendingPersistence()
            failNext=true
            controller.editDraft("合成失败草稿")
            withTimeout(5_000) { controller.state.first { it.notice?.startsWith("LOCAL_SAVE_FAILED:")==true } }
            assertEquals(1,injectedFailures)
            assertNotEquals("失败写入不能被当成持久成功","合成失败草稿",mirror.load()!!.draft)
            controller.editDraft("合成恢复后草稿")
            controller.flushPendingPersistence()
            assertEquals("合成恢复后草稿",mirror.load()!!.draft)
            assertNull("恢复成功应清理 checkpoint 独占的错误",controller.state.value.notice)
        } finally { controller.closeAndAwait();owner.cancel();mirror.closeStore() }
        val reopened=NativeConversationMirror(context,account,{"dsh"},key)
        try {
            val persisted=reopened.load()!!
            assertEquals("合成恢复后草稿",persisted.draft)
            assertEquals("合成恢复后草稿",persisted.composerDrafts.single { it.threadId=="chat" }.text)
        } finally { reopened.closeStore() }
    }

    @Test fun workbenchReopensCancelledPlaceholderAndReadsNativeHistoryWithoutPostingAgain() = runBlocking {
        val account=scope("workbench-cancelled-${UUID.randomUUID()}")
        val clientId=ClientMessageId("stopped-before-identity")
        val seed=NativeConversationMirror(context,account,{"dsh"},key)
        seed.saveSendAtomsDurably("chat",listOf(LocalSendAtom(ConversationId("chat"),clientId,
            listOf(MessagePart.Text("停止前输入")),1000,messageId=clientId.value,
            submissionState=LocalSubmissionState.ACCEPTED,status=AgentMessageStatus.FAILED,statusRevision=30)))
        seed.closeStore()
        val reopened=NativeConversationMirror(context,account,{"dsh"},key)
        val requests=java.util.concurrent.CopyOnWriteArrayList<WireRequest>()
        val transport=object:GatewayByteTransport {
            override suspend fun execute(request:WireRequest):WireResponse {
                requests+=request
                val body=when {
                    request.target.endsWith("/sessions") -> """{"sessions":[{"id":"chat","title":"测试会话","updatedAt":"1970-01-01T00:00:01Z","archived":false}]}"""
                    request.target.endsWith("/history") -> """{"messages":[{"id":"native-stopped-user","role":"user","text":"停止前输入","createdAt":"1970-01-01T00:00:01Z","clientMessageId":"stopped-before-identity","generationId":"native-cancelled-generation","state":"cancelled","contentRevision":25}]}"""
                    else -> error("非预期请求")
                }
                return WireResponse(200,listOf(RawHeader("Content-Type","application/json")),body.toByteArray())
            }
            override fun eventStream(request:WireRequest)=emptyFlow<ByteArray>()
        }
        val owner=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+Dispatchers.Unconfined)
        val agent=CoreAgent("dsh","测试DSH","dsh",true,"deployment",AgentCapabilities(list=true,history=true))
        val http=GatewayHttpClient(GatewayProfile("profile","device","session","https://synthetic.example",accessToken="synthetic"),
            transport,{ByteArray(64)},InMemoryEventCursorStore(),ioDispatcher=Dispatchers.IO)
        val repository=CoreAgentConversationRepository(AgentSessionClient(http),{agent},{"chat"},owner,
            Dispatchers.IO,storeForAgent={reopened})
        val controller=com.openandroidintelligence.conversation.state.WorkbenchController(owner,repository,
            object:AgentCommandCatalogRepository {
                override suspend fun get(gatewayId:String,languageCode:String)=AgentCommandCatalog(
                    com.openandroidintelligence.conversation.model.CatalogVersion("test"),emptyList())
            },{account},persistence=reopened,authoritativeMessageIds=true,
            replyTimeouts=com.openandroidintelligence.conversation.state.WorkbenchController.ReplyTimeouts(enabled=false))
        try {
            controller.openThread("chat")
            val state=withTimeout(5_000) { controller.state.first { state ->
                (state.timeline as? com.openandroidintelligence.conversation.state.Loadable.Ready)?.value
                    ?.any { it.key=="native-stopped-user" }==true || state.notice?.startsWith("HISTORY_SYNC_FAILED:")==true
            } }
            assertFalse("合法原生历史不能被镜像身份合并拒绝",state.notice?.startsWith("HISTORY_SYNC_FAILED:")==true)
            val rows=(state.timeline as com.openandroidintelligence.conversation.state.Loadable.Ready).value
            assertEquals(listOf("native-stopped-user"),rows.filter { it.isUser }.map { it.key })
            assertEquals("native-stopped-user",reopened.loadSendAtoms("chat").single().messageId)
            assertTrue(requests.any { it.target.endsWith("/history") })
            assertTrue(requests.none { it.method=="POST" })
        } finally { controller.closeAndAwait();repository.close();owner.cancel();reopened.closeStore() }
    }

    @Test fun cancelledClientPlaceholderAdoptsNativeHistoryAfterReopenWithoutLosingTerminalFacts() = runBlocking {
        val account=scope("cancelled-placeholder-${UUID.randomUUID()}")
        val clientId=ClientMessageId("cancelled-client")
        val original=LocalSendAtom(ConversationId("chat"),clientId,listOf(MessagePart.Text("停止前输入")),1000,
            messageId=clientId.value,submissionState=LocalSubmissionState.ACCEPTED,
            status=AgentMessageStatus.FAILED,statusRevision=30,errorCode=AgentMessageErrorCode.AGENT_REQUEST_FAILED)
        val first=NativeConversationMirror(context,account,{"dsh"},key)
        first.saveSendAtomsDurably("chat",listOf(original))
        first.closeStore()
        val restored=NativeConversationMirror(context,account,{"dsh"},key)
        val history=TimelineMessage("native-cancelled-user","user",original.parts,1000,"cancelled",
            conversationId=ConversationId("chat"),clientMessageId=clientId,generationId="native-generation",contentRevision=25)
        val event=VerifiedConversationEvent.TimelineUpsert("cancelled-history",1000,25,history)
        try {
            restored.commitEvents(listOf(event))
            restored.commitEvents(listOf(event))
            assertEquals("native-cancelled-user",restored.loadSendAtoms("chat").single().messageId)
        } finally { restored.closeStore() }
        val reopened=NativeConversationMirror(context,account,{"dsh"},key)
        try {
            val atom=reopened.loadSendAtoms("chat").single()
            assertEquals("native-cancelled-user",atom.messageId)
            assertEquals(AgentMessageStatus.FAILED,atom.status)
            assertEquals(30L,atom.statusRevision)
            assertEquals(AgentMessageErrorCode.AGENT_REQUEST_FAILED,atom.errorCode)
            val rows=com.openandroidintelligence.conversation.model.mergeLocalSendAtoms(
                reopened.loadTimeline("chat")!!.messages,listOf(atom))
            assertEquals(listOf("native-cancelled-user"),rows.map { it.id })
            assertFalse(rows.single().localPlaceholder)
            assertEquals(original.parts,rows.single().parts)
        } finally { reopened.closeStore() }
    }

    @Test fun acceptedIdentityUpgradeAndStalePlaceholderWritePreserveNativeIdentityAndNewerFacts() = runBlocking {
        val mirror=NativeConversationMirror(context,scope("accept-placeholder-${UUID.randomUUID()}"),{"dsh"},key)
        val clientId=ClientMessageId("accepted-client")
        val placeholder=LocalSendAtom(ConversationId("chat"),clientId,listOf(MessagePart.Text("原输入")),1000,
            messageId=clientId.value)
        try {
            mirror.saveSendAtomsDurably("chat",listOf(placeholder))
            val accepted=placeholder.identified("native-user").copy(parts=listOf(MessagePart.Text("已修订输入")),
                contentRevision=5,status=AgentMessageStatus.COMPLETED,statusRevision=8)
            mirror.saveSendAtomsDurably("chat",listOf(accepted))
            mirror.saveSendAtomsDurably("chat",listOf(placeholder))
            val saved=mirror.loadSendAtoms("chat").single()
            assertEquals("native-user",saved.messageId)
            assertEquals(accepted.parts,saved.parts)
            assertEquals(5L,saved.contentRevision)
            assertEquals(AgentMessageStatus.COMPLETED,saved.status)
            assertEquals(8L,saved.statusRevision)
            assertEquals(LocalSubmissionState.ACCEPTED,saved.submissionState)
            mirror.saveSendAtomsDurably("chat",listOf(saved.copy(parts=emptyList(),contentRevision=9,tombstoneRevision=9)))
            mirror.saveSendAtomsDurably("chat",listOf(placeholder))
            val deleted=mirror.loadSendAtoms("chat").single()
            assertEquals("native-user",deleted.messageId)
            assertEquals(9L,deleted.tombstoneRevision)
            assertTrue(deleted.parts.isEmpty())
        } finally { mirror.closeStore() }
    }

    @Test fun historySnapshotCanUpgradePlaceholderWhileRealIdentityConflictsStillRollback() = runBlocking {
        val mirror=NativeConversationMirror(context,scope("snapshot-placeholder-${UUID.randomUUID()}"),{"dsh"},key)
        val clientId=ClientMessageId("snapshot-client")
        val placeholder=LocalSendAtom(ConversationId("chat"),clientId,listOf(MessagePart.Text("原输入")),1000,
            messageId=clientId.value)
        try {
            mirror.saveSendAtomsDurably("chat",listOf(placeholder))
            val row=TimelineMessage("native-user","user",placeholder.parts,1000,
                conversationId=ConversationId("chat"),clientMessageId=clientId,contentRevision=5)
            mirror.offlineMirror().saveTimeline("chat",TimelinePage(listOf(row),null))
            assertEquals("native-user",mirror.loadSendAtoms("chat").single().messageId)
            val original=mirror.loadSendAtoms("chat").single()
            val nativeConflict=runCatching {
                mirror.saveSendAtomsDurably("chat",listOf(original.copy(messageId="another-native-user")))
            }.exceptionOrNull()
            assertEquals("MESSAGE_ID_CONFLICT",nativeConflict?.message)
            val sharedServerId=runCatching {
                mirror.saveSendAtomsDurably("chat",listOf(original.copy(clientMessageId=ClientMessageId("other-client"))))
            }.exceptionOrNull()
            assertEquals("MESSAGE_ID_CONFLICT",sharedServerId?.message)
            val foreignConversation=runCatching {
                mirror.saveSendAtomsDurably("chat",listOf(original.copy(conversationId=ConversationId("other-chat"))))
            }.exceptionOrNull()
            assertEquals("MESSAGE_CONVERSATION_CONFLICT",foreignConversation?.message)
            assertEquals(listOf(original),mirror.loadSendAtoms("chat"))
        } finally { mirror.closeStore() }
    }

    @Test fun provisionalAssistantIdentitySurvivesCheckpointAndTimelineReopen() = runBlocking {
        val account=scope("provisional-${UUID.randomUUID()}")
        val provisional=TimelineMessage("stream_local", "assistant", listOf(MessagePart.Text("尚待真实身份的回复")),
            1000, "CONFIRMED", conversationId=ConversationId("chat"), generationId="g", localPlaceholder=true)
        val real=provisional.copy(id="stream_real_host_id",localPlaceholder=false)
        val mirror=NativeConversationMirror(context,account,{"hermes"},key)
        mirror.saveCheckpoint(checkpoint(listOf("chat")).copy(threadId="chat",messages=listOf(provisional,real)))
        mirror.closeStore()
        val reopened=NativeConversationMirror(context,account,{"hermes"},key)
        try {
            val restored=reopened.load()!!.messages
            assertTrue(restored.single { it.id==provisional.id }.localPlaceholder)
            assertFalse("真实宿主 ID 即使有 stream 前缀也不能被冒认为临时行",
                restored.single { it.id==real.id }.localPlaceholder)
            assertTrue(reopened.loadTimeline("chat")!!.messages.single { it.id==provisional.id }.localPlaceholder)
        } finally { reopened.closeStore() }
    }

    @Test fun reopenedPlaceholderMergesWithRealHistoryWithoutMergingDistinctRealReplies() = runBlocking {
        val account=scope("reconcile-placeholder-${UUID.randomUUID()}")
        val provisional=TimelineMessage("stream_local", "assistant", listOf(MessagePart.Text("同一正文")),
            1000, "CONFIRMED", conversationId=ConversationId("chat"), generationId="g", localPlaceholder=true)
        val first=provisional.copy(id="native-first",localPlaceholder=false)
        val second=first.copy(id="native-second",timestamp=1001)
        val mirror=NativeConversationMirror(context,account,{"hermes"},key)
        mirror.saveCheckpoint(checkpoint(listOf("chat")).copy(threadId="chat",messages=listOf(provisional)))
        mirror.closeStore()
        val reopened=NativeConversationMirror(context,account,{"hermes"},key)
        val owner=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()+Dispatchers.Unconfined)
        val repository=object:ConversationRepository {
            override suspend fun listConversations(scope:ConversationScope,page:PageRequest)=
                ConversationPage(listOf(ConversationSummary(ConversationId("chat"),"chat",1000)),null)
            override suspend fun createConversation(scope:ConversationScope,clientConversationId:String):Conversation=error("unused")
            override suspend fun timeline(conversationId:String,page:PageRequest)=TimelinePage(listOf(first,second),null)
            override suspend fun submitMessage(message:OutgoingMessage):MessageAcceptance=error("unused")
            override suspend fun submitBatch(batch:MessageBatch):BatchAcceptance=error("unused")
            override fun observeEvents(scope:ConversationScope)=kotlinx.coroutines.flow.emptyFlow<VerifiedConversationEvent>()
            override suspend fun cancelGeneration(generationId:String,requestId:String)=CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
        }
        val controller=com.openandroidintelligence.conversation.state.WorkbenchController(owner,repository,
            object:AgentCommandCatalogRepository {
                override suspend fun get(gatewayId:String,languageCode:String)=
                    AgentCommandCatalog(com.openandroidintelligence.conversation.model.CatalogVersion("test"),emptyList())
            },{account},persistence=reopened,authoritativeMessageIds=true,
            replyTimeouts=com.openandroidintelligence.conversation.state.WorkbenchController.ReplyTimeouts(enabled=false))
        try {
            controller.openThread("chat")
            val ready=withTimeout(5_000) { controller.state.first { state ->
                (state.timeline as? com.openandroidintelligence.conversation.state.Loadable.Ready)?.value?.any { it.key==second.id }==true
            } }
            val rows=(ready.timeline as com.openandroidintelligence.conversation.state.Loadable.Ready).value
            assertEquals(listOf(first.id,second.id),rows.map { it.key })
        } finally { controller.closeAndAwait(); owner.cancel() }
    }

    @Test fun legacySteeringIdentityUpgradesDurablyWithoutRecreatingTheUserMessage() = runBlocking {
        val testScope = scope("legacy-steering-id")
        val mirror = NativeConversationMirror(context, testScope, { "hermes" }, key)
        val clientId = com.openandroidintelligence.conversation.model.ClientMessageId("steering-client")
        val atom = com.openandroidintelligence.conversation.model.LocalSendAtom(ConversationId("chat"), clientId,
            listOf(MessagePart.Text("原引导")), 1000, messageId = "steer_steering-client",
            submissionState = com.openandroidintelligence.conversation.model.LocalSubmissionState.ACCEPTED,
            delivery = MessageDeliveryMode.STEER)
        mirror.saveSendAtoms("chat", listOf(atom))
        mirror.commitEvents(listOf(VerifiedConversationEvent.TimelineUpsert("real-user", 1000, 1,
            TimelineMessage("native-steering-user", "user", atom.parts, 1000, conversationId = ConversationId("chat"),
                clientMessageId = clientId, generationId = "g"))))
        mirror.closeStore()
        val reopened = NativeConversationMirror(context, testScope, { "hermes" }, key)
        try {
            assertEquals("native-steering-user", reopened.loadSendAtoms("chat").single().messageId)
            val rows = com.openandroidintelligence.conversation.model.mergeLocalSendAtoms(
                reopened.loadTimeline("chat")!!.messages, reopened.loadSendAtoms("chat"))
            assertEquals(1, rows.size)
            assertEquals("user", rows.single().sender)
            assertEquals("native-steering-user", rows.single().id)
        } finally { reopened.closeStore() }
    }

    @Test fun remoteOnlyConfirmedHistorySurvivesMirrorReopenWithoutBecomingQueued() = runBlocking {
        val testScope = scope("remote-only-confirmed-${UUID.randomUUID()}")
        val clientId = com.openandroidintelligence.conversation.model.ClientMessageId("external-client")
        val message = TimelineMessage(
            id = "msg_external",
            sender = "user",
            parts = listOf(MessagePart.Text("由 Core 客户端提交")),
            timestamp = 1_000L,
            state = "CONFIRMED",
            conversationId = ConversationId("chat"),
            clientMessageId = clientId,
            generationId = null,
            contentRevision = 1L,
        )
        val mirror = NativeConversationMirror(context, testScope, { "hermes" }, key)
        mirror.commitEvents(listOf(VerifiedConversationEvent.TimelineUpsert(
            eventId = "external-history",
            occurredAt = 1_000L,
            revision = 1L,
            message = message,
        )))
        mirror.closeStore()
        val reopened = NativeConversationMirror(context, testScope, { "hermes" }, key)
        try {
            val stored = reopened.offlineMirror().timeline("chat")!!.messages.single()
            val merged = com.openandroidintelligence.conversation.model.mergeLocalSendAtoms(
                listOf(stored),
                reopened.loadSendAtoms("chat"),
            ).single()

            assertEquals("CONFIRMED", merged.state)
        } finally {
            reopened.closeStore()
        }
    }

    @Test fun toolSourcePositionSurvivesEncryptedMirrorReopen() = runBlocking {
        val testScope = scope("tool-source-position")
        val mirror = NativeConversationMirror(context, testScope, { "hermes" }, key, observerId = "phone:1")
        val step = NativeExecutionStep("call", "event", "terminal", "pwd", "/project", NativeExecutionStatus.COMPLETED, 3,
            sourceMessageId = "tool-after-steering", occurredAt = 3000L)
        mirror.commitEvents(listOf(VerifiedConversationEvent.TimelineUpsert("source-history", 3000, 1,
            TimelineMessage("tool-after-steering", "assistant", emptyList(), 3000,
                conversationId = ConversationId("chat"), generationId = "g", executionSteps = listOf(step)))))
        mirror.closeStore()
        val reopened = NativeConversationMirror(context, testScope, { "hermes" }, key, observerId = "phone:1")
        try {
            assertEquals(step, reopened.loadTimeline("chat")!!.messages.single().executionSteps.single())
        } finally { reopened.closeStore() }
    }

    @Test fun aNewNativeThreadMustSurviveTheNextOfflineLoad() = runBlocking {
        val context=ApplicationProvider.getApplicationContext<Application>()
        val key=object:AesGcmKeyProvider {
            override fun getOrCreate()=SecretKeySpec(ByteArray(32){19},"AES")
            override fun delete()=Unit
        }
        val scope=ConversationScope("bug08_native_threads","https://synthetic.example","account","install")
        val mirror=NativeConversationMirror(context,scope,{"hermes"},key)
        fun checkpoint(ids:List<String>)=WorkbenchCheckpoint(ids.map { ConversationSummary(ConversationId(it),it,1) },
            "old","old",emptyList(),emptyMap(),"",0,emptyList(),null,emptySet(),emptySet(),threadsHydrated=true)
        mirror.saveCheckpoint(checkpoint(listOf("old")))
        mirror.saveCheckpoint(checkpoint(listOf("old","new")))
        mirror.flush()
        val reopened=NativeConversationMirror(context,scope,{"hermes"},key).load()!!.threads.map { it.id.value }
        println("BUG08_NATIVE_MIRROR saved=[old,new] reopened=$reopened")
        assertEquals("native 直接仓储没有单独刷新 mirror 列表；最新工作台快照不能保留旧列表",listOf("old","new"),reopened)
    }
    private val context get()=ApplicationProvider.getApplicationContext<Application>()
    private val key=object:AesGcmKeyProvider {
        override fun getOrCreate()=SecretKeySpec(ByteArray(32){29},"AES")
        override fun delete()=Unit
    }
    private fun scope(account:String="account")=ConversationScope("native_event_mirror","https://synthetic.example",account,"install")
    private fun checkpoint(ids:List<String>)=WorkbenchCheckpoint(ids.map { ConversationSummary(ConversationId(it),it,1) },
        "old","old",emptyList(),emptyMap(),"",0,emptyList(),null,emptySet(),emptySet(),threadsHydrated=true)

    @Test fun authoritativeCreationAndNotificationIdentitySurviveOwnerRecreationWithoutListRefresh()=runBlocking {
        val mirror=NativeConversationMirror(context,scope(),{"hermes"},key)
        val receipt=NativeCreationReceipt(com.openandroidintelligence.gateway.agents.NativeAgentSession("created", "real title", null, false),"creation-event",17)
        mirror.commitCreation("original-create",receipt)
        mirror.closeStore()
        val reopened=NativeConversationMirror(context,scope(),{"hermes"},key)
        val otherAgent=NativeConversationMirror(context,scope(),{"other"},key)
        val otherAccount=NativeConversationMirror(context,scope("other"),{"hermes"},key)
        try {
            assertEquals(receipt,reopened.creation("original-create"))
            assertEquals("created",reopened.offlineMirror().threads()!!.single().id.value)
            assertNull(otherAgent.creation("original-create"))
            assertNull(otherAccount.creation("original-create"))
        } finally { reopened.closeStore(); otherAgent.closeStore(); otherAccount.closeStore() }
    }

    @Test fun anAuthoritativeNativeListRejectsALaterOldWorkbenchCheckpoint()=runBlocking {
        val mirror=NativeConversationMirror(context,scope(),{"hermes"},key)
        try {
            mirror.saveCheckpoint(checkpoint(listOf("old")))
            mirror.saveConversations(ConversationPage(listOf("old","new").map { ConversationSummary(ConversationId(it),it,2) },null))
            mirror.saveCheckpoint(checkpoint(listOf("old")))
            assertEquals(listOf("old","new"),mirror.load()!!.threads.map {it.id.value})
        } finally {mirror.closeStore()}
    }

    @Test fun aLateNativeListCannotReplaceANewerCommittedList() = runBlocking {
        val store = NativeConversationMirror(context, scope(), { "hermes" }, key)
        try {
            val olderRead = store.beginListRead()
            val newerRead = store.beginListRead()
            val fresh = ConversationPage(listOf(ConversationSummary(ConversationId("fresh"), "fresh", 2)), null)
            assertTrue(store.saveConversations(fresh, newerRead))
            assertFalse("迟到的空页不能覆盖更新列表", store.saveConversations(ConversationPage(emptyList(), null), olderRead))
            assertEquals(listOf("fresh"), store.offlineMirror().threads()!!.map { it.id.value })
        } finally { store.closeStore() }
    }

    @Test fun explicitDraftsAndFrozenDeliverySurviveReopeningTheMirror() = runBlocking {
        val mirror=NativeConversationMirror(context,scope(),{"hermes"},key)
        val expected=checkpoint(listOf("a","b")).copy(draftExplicit=true,
            composerDrafts=listOf(com.openandroidintelligence.conversation.state.SavedComposerDraft("a","A 草稿",3,emptyList()),
                com.openandroidintelligence.conversation.state.SavedComposerDraft("b","",4,emptyList())),
            pendingSubmissions=listOf(com.openandroidintelligence.conversation.state.SavedSubmission("已冻结",listOf("draft"),5,"a","client",
                MessageDeliveryMode.QUEUE,"generation")))
        mirror.saveCheckpoint(expected); mirror.closeStore()
        val reopened=NativeConversationMirror(context,scope(),{"hermes"},key)
        try {
            val actual=requireNotNull(reopened.load())
            assertTrue(actual.draftExplicit)
            assertEquals(expected.composerDrafts,actual.composerDrafts)
            assertEquals(expected.pendingSubmissions,actual.pendingSubmissions)
        } finally { reopened.closeStore() }
    }

    @Test fun nativeListAndAckUseTheSameLockOrder()=runBlocking {
        val testScope=scope("lock-order-${UUID.randomUUID()}")
        val mirror=NativeConversationMirror(context,testScope,{"hermes"},key,observerId="phone:1")
        val delegate=mirror.offlineMirror()
        val records=EncryptedConversationMirror::class.java.getDeclaredField("documents").let { field ->
            field.isAccessible=true
            checkNotNull(field.get(delegate))
        }
        val receipt=NativeCreationReceipt(com.openandroidintelligence.gateway.agents.NativeAgentSession("created", "title", null, false),"event",17)
        val page=ConversationPage(listOf("old","created").map { ConversationSummary(ConversationId(it),it,1) },null)
        try {
            mirror.commitCreation("original-create",receipt)
            mirror.commitPage("chat",emptyList(),emptyList(),"cursor_one")

            // Block each real monitor separately so both production paths can finish even on the old lock order.
            val listWrite=async(Dispatchers.IO,start=CoroutineStart.LAZY) { mirror.saveConversations(page) }
            val recordsProbe=monitorProbe(records)
            val recordsAvailable=synchronized(delegate) {
                listWrite.start()
                check(waitForBlockedCall("saveNativeConversations")) { "NATIVE_LIST_WAIT_NOT_OBSERVED" }
                recordsProbe.start()
                recordsProbe.canAcquire()
            }
            withTimeout(5_000) { listWrite.await() }
            recordsProbe.thread.join(5_000)
            assertFalse(recordsProbe.thread.isAlive)

            val ack=async(Dispatchers.IO,start=CoroutineStart.LAZY) { mirror.confirmAck("chat","cursor_one") }
            val mirrorProbe=monitorProbe(delegate)
            val mirrorAvailable=synchronized(records) {
                ack.start()
                check(waitForBlockedCall("confirmNativeAck")) { "NATIVE_ACK_WAIT_NOT_OBSERVED" }
                mirrorProbe.start()
                mirrorProbe.canAcquire()
            }
            withTimeout(5_000) { ack.await() }
            mirrorProbe.thread.join(5_000)
            assertFalse(mirrorProbe.thread.isAlive)

            assertFalse("ACK 确认应在持有镜像锁时等待真实数据库锁",mirrorAvailable)
            assertTrue("会话列表保存不能持有数据库锁等待镜像锁，否则与 ACK 确认死锁",recordsAvailable)
            mirror.closeStore()
            val reopened=NativeConversationMirror(context,testScope,{"hermes"},key,observerId="phone:1")
            try {
                assertEquals(receipt,reopened.creation("original-create"))
                assertEquals("cursor_one",reopened.eventCursor("chat"))
                assertNull(reopened.pendingAck("chat"))
                assertEquals(listOf("old","created"),reopened.offlineMirror().threads()!!.map { it.id.value })
            } finally { reopened.closeStore() }
        } finally { mirror.closeStore() }
    }

    private class MonitorProbe(monitor:Any) {
        val started=CountDownLatch(1)
        val acquired=CountDownLatch(1)
        val thread=Thread({
            started.countDown()
            synchronized(monitor) { acquired.countDown() }
        },"native-mirror-lock-probe").apply { isDaemon=true }
        fun start()=thread.start()
        fun canAcquire():Boolean {
            check(started.await(5,TimeUnit.SECONDS)) { "MONITOR_PROBE_NOT_STARTED" }
            val deadline=System.nanoTime()+5_000_000_000L
            while (System.nanoTime()<deadline) {
                if (acquired.count==0L) return true
                if (thread.state==Thread.State.BLOCKED) return false
                check(thread.isAlive) { "MONITOR_PROBE_STOPPED" }
                Thread.sleep(5)
            }
            error("MONITOR_PROBE_TIMED_OUT")
        }
    }

    private fun monitorProbe(monitor:Any)=MonitorProbe(monitor)

    private fun waitForBlockedCall(method:String):Boolean {
        val deadline=System.nanoTime()+5_000_000_000L
        while (System.nanoTime()<deadline) {
            if (Thread.getAllStackTraces().any { (thread,stack) ->
                thread.state==Thread.State.BLOCKED && stack.any { frame ->
                    frame.className.startsWith(EncryptedConversationMirror::class.java.name) &&
                        (frame.methodName==method || frame.className.contains("\$$method\$"))
                }
            }) return true
            Thread.sleep(5)
        }
        return false
    }

    @Test fun ownerIsFixedBeforeTheIoWriteAndOtherAgentsStaySeparate()=runBlocking {
        var selected="agent-a"
        val old=NativeConversationMirror(context,scope(),{selected},key)
        selected="agent-b"
        val next=NativeConversationMirror(context,scope(),{selected},key)
        try {
            old.saveCheckpoint(checkpoint(listOf("only-a")))
            assertNull(next.load())
            assertEquals(listOf("only-a"),old.load()!!.threads.map {it.id.value})
        } finally {old.closeStore();next.closeStore()}
    }

    @Test fun pendingAckApprovalsAndToolFactsSurviveReopenAndPairingChangesCannotBorrowTheCursor()=runBlocking {
        val mirror=NativeConversationMirror(context,scope(),{"hermes"},key,observerId="phone:1")
        val resolved=NativeEventApproval("approval_resolved",JsonFields.obj(Json.parse(
            """{"approvalId":"approval","conversationId":"chat","decision":"once","decidedAt":2}"""))!!)
        val requested=NativeEventApproval("approval_requested",JsonFields.obj(Json.parse(
            """{"approvalId":"approval","conversationId":"chat","command":"synthetic","requestedAt":1,"options":[{"choice":"once","label":"once"}]}"""))!!)
        val event=VerifiedConversationEvent.TimelineUpsert("tool-history",1,1,TimelineMessage("answer","assistant",
            listOf(MessagePart.Text("final")),1,conversationId=ConversationId("chat"),generationId="generation",
            executionSteps=listOf(NativeExecutionStep("step","step-event","synthetic","{}",status=NativeExecutionStatus.COMPLETED,revision=4))))
        mirror.commitPage("chat",listOf(event),listOf(resolved),"cursor_one")
        mirror.closeStore()
        val reopened=NativeConversationMirror(context,scope(),{"hermes"},key,observerId="phone:1")
        val otherPairing=NativeConversationMirror(context,scope(),{"hermes"},key,observerId="phone:2")
        val otherAccount=NativeConversationMirror(context,scope("other"),{"hermes"},key,observerId="phone:1")
        try {
            assertEquals("cursor_one",reopened.pendingAck("chat"))
            assertNull(otherPairing.pendingAck("chat"))
            assertNull(otherAccount.pendingAck("chat"))
            assertNull(reopened.pendingAck("other-chat"))
            reopened.commitApprovals("chat",listOf(requested))
            assertEquals(listOf("approval_resolved"),reopened.loadApprovals("chat").map {it.type})
            assertEquals(4L,reopened.loadTimeline("chat")!!.messages.single().executionSteps.single().revision)
            reopened.confirmAck("chat","cursor_one")
            assertEquals("cursor_one",reopened.eventCursor("chat"))
            assertNull(reopened.pendingAck("chat"))
        } finally {reopened.closeStore();otherPairing.closeStore();otherAccount.closeStore()}
    }

    @Test fun onlyDurableUnknownSubmissionsAndGenerationAssociationsAreRecoveryCandidates()=runBlocking {
        val mirror=NativeConversationMirror(context,scope(),{"hermes"},key,observerId="phone:1")
        try {
            val parts=listOf(MessagePart.Text("synthetic"))
            mirror.saveSendAtomsDurably("chat",listOf(
                com.openandroidintelligence.conversation.model.LocalSendAtom(ConversationId("chat"),
                    com.openandroidintelligence.conversation.model.ClientMessageId("local-unknown"),parts,1,
                    submissionState=com.openandroidintelligence.conversation.model.LocalSubmissionState.OUTCOME_UNKNOWN),
                com.openandroidintelligence.conversation.model.LocalSendAtom(ConversationId("chat"),
                    com.openandroidintelligence.conversation.model.ClientMessageId("steering"),parts,1,
                    submissionState=com.openandroidintelligence.conversation.model.LocalSubmissionState.OUTCOME_UNKNOWN,
                    delivery=MessageDeliveryMode.STEER),
                com.openandroidintelligence.conversation.model.LocalSendAtom(ConversationId("chat"),
                    com.openandroidintelligence.conversation.model.ClientMessageId("imported"),parts,1,messageId="native-user",
                    submissionState=com.openandroidintelligence.conversation.model.LocalSubmissionState.ACCEPTED)
            ))
            assertEquals(listOf(NativeRecoveryIdentity("local-unknown")),mirror.recoveryIdentities("chat"))
        } finally {mirror.closeStore()}
    }

}
