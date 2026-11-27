package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.model.AttachmentDraftId
import com.openandroidintelligence.conversation.model.ClientMessageId
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.ports.PageRequest
import com.openandroidintelligence.conversation.ports.ConversationPage
import com.openandroidintelligence.conversation.ports.ConversationRepository
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.ports.ConversationSummary
import com.openandroidintelligence.conversation.ports.Conversation
import com.openandroidintelligence.conversation.ports.CancelGenerationOutcome
import com.openandroidintelligence.conversation.ports.CancelGenerationResult
import com.openandroidintelligence.conversation.ports.MessageAcceptance
import com.openandroidintelligence.conversation.ports.MessageBatch
import com.openandroidintelligence.conversation.ports.BatchAcceptance
import com.openandroidintelligence.conversation.ports.OutgoingMessage
import com.openandroidintelligence.conversation.ports.TimelineMessage
import com.openandroidintelligence.conversation.ports.TimelinePage
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.conversation.title.ConversationTitlePolicy
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.mobile.conversations.EncryptedConversationMirror
import com.openandroidintelligence.mobile.conversations.MirroredConversationRepository
import com.openandroidintelligence.mobile.conversations.OfflineMirrorRepository
import com.openandroidintelligence.mobile.conversations.nativeNotificationInput
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 本地加密镜像恢复、分页历史读取、会话列表摘要与通知栏预览全链路回归测试。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ContextInjectionMirrorAndNotificationTest {

    private val context get() = ApplicationProvider.getApplicationContext<Application>()

    private val key = object : AesGcmKeyProvider {
        override fun getOrCreate() = SecretKeySpec(ByteArray(32) { 37 }, "AES")
        override fun delete() = Unit
    }

    private fun testScope(suffix: String) =
        ConversationScope("bug6_$suffix", "https://gateway.example", "acc-$suffix", "install-$suffix")

    private fun mirror(scope: ConversationScope) =
        EncryptedConversationMirror(context, scope, key, Dispatchers.Unconfined)

    @Test
    fun `本地加密镜像底层完整保留rawParts和isMeta而所有展示投影出口均完成净化`() = runBlocking {
        val scope = testScope("mirror_lossless")
        val initialMirror = mirror(scope)
        val convId = "conv-raw-preserve"
        val rawEnvelopeText = "<system-reminder>secret rule</system-reminder>\n用户真实提问：构建为什么失败"
        val rawMsg = TimelineMessage(
            id = "m-user-raw",
            sender = "user",
            parts = listOf(MessagePart.Text("用户真实提问：构建为什么失败")),
            rawParts = listOf(MessagePart.Text(rawEnvelopeText)),
            timestamp = 100L,
            conversationId = ConversationId(convId),
            contentRevision = 1L,
        )
        val systemMsg = TimelineMessage(
            id = "m-system-1",
            sender = "system",
            parts = listOf(MessagePart.Text("Dynamic system prompt")),
            timestamp = 110L,
            conversationId = ConversationId(convId),
            contentRevision = 2L,
        )
        val missingSenderMsg = TimelineMessage(
            id = "m-missing-sender",
            sender = "",
            parts = listOf(MessagePart.Text("Unknown origin payload")),
            timestamp = 115L,
            conversationId = ConversationId(convId),
            contentRevision = 3L,
        )
        val hiddenVisMsg = TimelineMessage(
            id = "m-hidden-vis",
            sender = "assistant",
            parts = listOf(MessagePart.Text("Model-only note")),
            timestamp = 118L,
            conversationId = ConversationId(convId),
            visibility = "hidden",
            messageType = "context_injection",
            contentRevision = 4L,
        )
        val attachmentMsg = TimelineMessage(
            id = "m-att",
            sender = "assistant",
            parts = listOf(
                MessagePart.Attachment(AttachmentDraftId("att-1"), "trace.log", "text/plain"),
            ),
            rawParts = listOf(
                MessagePart.Text("[Current time: 2026-11-20 14:30 UTC]"),
                MessagePart.Attachment(AttachmentDraftId("att-1"), "trace.log", "text/plain"),
            ),
            timestamp = 120L,
            conversationId = ConversationId(convId),
            contentRevision = 5L,
        )
        val metaMsg = TimelineMessage(
            id = "m-meta",
            sender = "assistant",
            parts = listOf(MessagePart.Text("Meta frame")),
            timestamp = 130L,
            conversationId = ConversationId(convId),
            isMeta = true,
            contentRevision = 6L,
        )

        initialMirror.saveTimeline(
            convId,
            TimelinePage(
                messages = listOf(rawMsg, systemMsg, missingSenderMsg, hiddenVisMsg, attachmentMsg, metaMsg),
                nextCursor = null,
            ),
        )
        initialMirror.saveThreads(
            listOf(
                ConversationSummary(
                    ConversationId(convId),
                    "<available_skills><skill>a</skill></available_skills>\n构建排查会话",
                    200L,
                ),
            ),
        )

        // 模拟冷启动重建镜像实例
        val reopened = mirror(scope)

        // 1. 验证底层只读镜像完整保存了原始数据（rawParts、isMeta、visibility、messageType、raw title）
        val rawStored = reopened.timeline(convId)!!.messages.associateBy { it.id }
        assertEquals(6, rawStored.size)
        assertEquals(listOf(MessagePart.Text(rawEnvelopeText)), rawStored.getValue("m-user-raw").rawParts)
        assertTrue(rawStored.getValue("m-meta").isMeta)
        assertEquals("hidden", rawStored.getValue("m-hidden-vis").visibility)
        assertEquals("context_injection", rawStored.getValue("m-hidden-vis").messageType)

        // 2. 验证冷启动恢复出口 loadTimeline 与 OfflineMirrorRepository 仅输出干净对话消息与附件
        val offlineRepo = OfflineMirrorRepository(reopened)
        val offlinePage = offlineRepo.timeline(convId, PageRequest())
        assertEquals(listOf("m-user-raw", "m-att"), offlinePage.messages.map { it.id })
        assertEquals("用户真实提问：构建为什么失败", (offlinePage.messages[0].parts.single() as MessagePart.Text).value)
        assertEquals(1, offlinePage.messages[1].parts.filterIsInstance<MessagePart.Attachment>().size)

        // 3. 验证离线会话摘要标题已剥离技能注入块
        val offlineThreads = offlineRepo.listConversations(scope, PageRequest())
        assertEquals("构建排查会话", offlineThreads.conversations.single().title)
    }

    @Test
    fun `MirroredConversationRepository分页历史与projectEvent均统一经过净化管线`() = runBlocking {
        val scope = testScope("mirrored_repo")
        val localMirror = mirror(scope)
        val convId = "conv-paged"

        val pagedMessages = listOf(
            TimelineMessage("p-sys", "developer", listOf(MessagePart.Text("Dev rule")), 10L, conversationId = ConversationId(convId)),
            TimelineMessage(
                "p-ok",
                "assistant",
                listOf(MessagePart.Text("[System Context]\nmode=auto\n[/System Context]\n这是分页历史正文")),
                20L,
                conversationId = ConversationId(convId),
            ),
        )
        val liveEvents = flowOf(
            VerifiedConversationEvent.TimelineUpsert(
                eventId = "ev-hidden",
                occurredAt = 30L,
                revision = 1L,
                message = TimelineMessage(
                    id = "m-live-hidden",
                    sender = "context",
                    parts = listOf(MessagePart.Text("Internal context")),
                    timestamp = 30L,
                    conversationId = ConversationId(convId),
                ),
            ),
            VerifiedConversationEvent.TimelineUpsert(
                eventId = "ev-clean",
                occurredAt = 40L,
                revision = 2L,
                message = TimelineMessage(
                    id = "m-live-ok",
                    sender = "assistant",
                    parts = listOf(MessagePart.Text("这是代码块讨论：\n```xml\n<system-reminder>keep</system-reminder>\n```")),
                    timestamp = 40L,
                    conversationId = ConversationId(convId),
                ),
            ),
        )

        val delegate = object : ConversationRepository {
            override suspend fun listConversations(scope: ConversationScope, page: PageRequest) =
                ConversationPage(
                    listOf(ConversationSummary(ConversationId(convId), "Browser time zone for this request: UTC\n干净标题", 10L)),
                    null,
                    snapshotRevision = 1L,
                    authoritative = true,
                )
            override suspend fun timeline(conversationId: String, page: PageRequest): TimelinePage =
                TimelinePage(pagedMessages, nextCursor = null)
            override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) =
                Conversation(ConversationId(convId), "", 0L)
            override suspend fun submitMessage(message: OutgoingMessage) = MessageAcceptance("ok", message.clientMessageId.value)
            override suspend fun submitBatch(batch: MessageBatch) = BatchAcceptance(batch.batchId)
            override fun observeEvents(scope: ConversationScope): Flow<VerifiedConversationEvent> = liveEvents
            override suspend fun cancelGeneration(generationId: String, requestId: String) =
                CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
        }

        val repo = MirroredConversationRepository(delegate, localMirror)
        val cursorPage = repo.timeline(convId, PageRequest(cursor = "cursor-1"))
        assertEquals(listOf("p-ok"), cursorPage.messages.map { it.id })
        assertEquals("这是分页历史正文", (cursorPage.messages.single().parts.single() as MessagePart.Text).value)

        val listPage = repo.listConversations(scope, PageRequest())
        assertEquals("干净标题", listPage.conversations.single().title)

        val pagedList = repo.listConversations(scope, PageRequest(cursor = "page-2"))
        assertEquals("干净标题", pagedList.conversations.single().title)

        val observed = repo.observeEvents(scope).toList()
        // 非对话 context 消息被 projectEvent 转为 Tombstoned / 屏蔽；代码防误杀消息原样放行
        assertTrue(observed[0] is VerifiedConversationEvent.TimelineTombstoned)
        val second = observed[1] as VerifiedConversationEvent.TimelineUpsert
        assertTrue((second.message.parts.single() as MessagePart.Text).value.contains("<system-reminder>keep</system-reminder>"))
    }

    @Test
    fun `通知预览和摘要标题绝不泄露上下文注入信封且不干扰正常通知完成状态`() {
        val appContext = context
        val publications = mutableListOf<Pair<NativeConversationSession, Boolean>>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val prefs = appContext.getSharedPreferences("bug6-notif-sanitize-test", 0)
        prefs.edit().clear().commit()
        try {
            val notifications = NativeConversationNotifications(
                context = appContext,
                scope = scope,
                publish = { session, alert -> publications += session to alert },
                throttleMillis = 0L,
                preferencesFor = { _, _ -> prefs },
            )
            notifications.bind("p1", "acc1")
            val target = ReplyNotificationTarget("p1", "acc1", "conv-notif-1", "hermes")
            val baseTime = System.currentTimeMillis()

            // 1. 纯系统/meta 消息即使进入也不得触发通知或泄露内容
            val metaInput = nativeNotificationInput(
                "p1", "acc1", "hermes",
                VerifiedConversationEvent.TimelineUpsert(
                    eventId = "live-meta-1",
                    occurredAt = baseTime + 20L,
                    revision = 1L,
                    message = TimelineMessage(
                        id = "msg-meta-1",
                        sender = "assistant",
                        parts = listOf(MessagePart.Text("Internal control hint")),
                        timestamp = baseTime + 20L,
                        conversationId = ConversationId("conv-notif-1"),
                        isMeta = true,
                    ),
                ),
            )
            assertNull("isMeta 消息不应产生通知输入", metaInput)

            // 2. 带注入信封的真实流式完成通知 -> latestReply 与 notificationReplyExcerpt 均已剥离
            val deltaInput = nativeNotificationInput(
                "p1", "acc1", "hermes",
                VerifiedConversationEvent.TimelineUpsert(
                    eventId = "live-delta-1",
                    occurredAt = baseTime + 30L,
                    revision = 2L,
                    message = TimelineMessage(
                        id = "msg-ok-1",
                        sender = "assistant",
                        parts = listOf(MessagePart.Text("<system-reminder>内部规约</system-reminder>\n已完成日志定位")),
                        timestamp = baseTime + 30L,
                        state = "STREAMING",
                        conversationId = ConversationId("conv-notif-1"),
                        generationId = "gen-1",
                    ),
                ),
            )
            assertNotNull(deltaInput)
            notifications.accept(deltaInput!!)

            val completeInput = nativeNotificationInput(
                "p1", "acc1", "hermes",
                VerifiedConversationEvent.TimelineUpsert(
                    eventId = "live-done-1",
                    occurredAt = baseTime + 40L,
                    revision = 3L,
                    message = TimelineMessage(
                        id = "msg-ok-1",
                        sender = "assistant",
                        parts = listOf(
                            MessagePart.Text(
                                "<system-reminder>内部规约</system-reminder>\n已完成日志定位\n\n[Current time: 2026-11-20 14:40 UTC]",
                            ),
                        ),
                        timestamp = baseTime + 40L,
                        state = "CONFIRMED",
                        conversationId = ConversationId("conv-notif-1"),
                        generationId = "gen-1",
                    ),
                ),
            )
            notifications.accept(completeInput!!)
            val titleInput = nativeNotificationInput(
                "p1", "acc1", "hermes",
                VerifiedConversationEvent.TitleUpdated(
                    "t-after-created",
                    baseTime + 50L,
                    ConversationId("conv-notif-1"),
                    "<environment_context><cwd>/app</cwd></environment_context>\n排查通知泄露",
                ),
            )
            assertNotNull(titleInput)
            notifications.accept(titleInput!!)

            val session = notifications.sessions.value.single { it.target == target }
            assertEquals("已完成日志定位", session.latestReply)
            assertEquals("排查通知泄露", session.title)

            val excerpt = notificationReplyExcerpt(
                "<system-reminder>leak</system-reminder>\n通知摘录正文",
                isRunning = false,
            )
            assertEquals("通知摘录正文", excerpt)

            val generatedTitle = ConversationTitlePolicy.generateTitle(
                OutgoingMessage(
                    ClientMessageId("c-title"),
                    "Conversation info (untrusted metadata):\n```json\n{\"a\":1}\n```\n\n首条真实问题标题",
                ),
            )
            assertEquals("首条真实问题标题", generatedTitle)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `checkpoint读取与reconcileQuery和完整会话刷新均统一剥离注入信封且不破坏镜像原件`() = runBlocking {
        val scope = testScope("mirror_checkpoint_reconcile")
        val localMirror = mirror(scope)
        val convId = "conv-ckpt-1"
        val clientId = ClientMessageId("cli-reconcile-1")

        // 1. 写入含上下文注入的 checkpoint 消息与标题，验证 load() 单一收口净化
        val rawUser = TimelineMessage(
            id = "ckpt-u-1",
            sender = "user",
            parts = listOf(MessagePart.Text("<permissions instructions>\nrules\n</permissions instructions>\n\n检查网络状态")),
            timestamp = 10L,
            conversationId = ConversationId(convId),
        )
        val rawSystem = TimelineMessage(
            id = "ckpt-sys-1",
            sender = "system",
            parts = listOf(MessagePart.Text("Internal system prompt")),
            timestamp = 11L,
            conversationId = ConversationId(convId),
        )
        localMirror.save(
            com.openandroidintelligence.conversation.state.WorkbenchCheckpoint(
                threads = listOf(ConversationSummary(ConversationId(convId), "<system-reminder>x</system-reminder>\n网络排查", 10L)),
                threadId = convId,
                title = "<environment_context><cwd>/</cwd></environment_context>\n网络排查",
                messages = listOf(rawUser, rawSystem),
                revisions = mapOf("ckpt-u-1" to 1L, "ckpt-sys-1" to 1L),
                draft = "",
                draftRevision = 0L,
                attachments = emptyList(),
                submission = null,
                renamedThreads = emptySet(),
                eventIds = emptySet(),
            ),
        )
        val loaded = localMirror.load()!!
        assertEquals(listOf("ckpt-u-1"), loaded.messages.map { it.id })
        assertEquals("检查网络状态", (loaded.messages.single().parts.single() as MessagePart.Text).value)
        assertEquals("网络排查", loaded.title)
        assertEquals("网络排查", loaded.threads.single().title)

        // 2. reconcileQuery 写入远端确认回显时同步净化，不把注入文本带回原子或返回消息
        localMirror.saveSendAtoms(
            convId,
            listOf(
                com.openandroidintelligence.conversation.model.LocalSendAtom(
                    conversationId = ConversationId(convId),
                    clientMessageId = clientId,
                    parts = listOf(MessagePart.Text("检查网络状态")),
                    timestamp = 15L,
                ),
            ),
        )
        val reconciled = localMirror.reconcileQuery(
            convId,
            clientId,
            TimelineMessage(
                id = "srv-rec-1",
                sender = "user",
                parts = listOf(MessagePart.Text("<system-reminder>injected</system-reminder>\n检查网络状态")),
                timestamp = 20L,
                state = "CONFIRMED",
                conversationId = ConversationId(convId),
                clientMessageId = clientId,
                contentRevision = 2L,
            ),
        )
        assertEquals("检查网络状态", (reconciled.parts.single() as MessagePart.Text).value)
        val storedAtom = localMirror.loadSendAtoms(convId).single()
        assertEquals("检查网络状态", (storedAtom.parts.single() as MessagePart.Text).value)

        // 3. MirroredConversationRepository.listConversations 全量刷新持久化到镜像的标题已净化
        val delegate = object : ConversationRepository {
            override suspend fun listConversations(scope: ConversationScope, page: PageRequest) =
                ConversationPage(
                    listOf(ConversationSummary(ConversationId(convId), "<permissions instructions>p</permissions instructions>\n干净会话名", 30L)),
                    null,
                    snapshotRevision = 5L,
                    authoritative = true,
                )
            override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(emptyList(), null)
            override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) = Conversation(ConversationId(convId), "", 0L)
            override suspend fun submitMessage(message: OutgoingMessage) = MessageAcceptance("ok", message.clientMessageId.value)
            override suspend fun submitBatch(batch: MessageBatch) = BatchAcceptance(batch.batchId)
            override fun observeEvents(scope: ConversationScope): Flow<VerifiedConversationEvent> = flowOf()
            override suspend fun cancelGeneration(generationId: String, requestId: String) =
                CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
        }
        val repo = MirroredConversationRepository(delegate, localMirror)
        val refreshed = repo.listConversations(scope, PageRequest())
        assertEquals("干净会话名", refreshed.conversations.single().title)
        assertEquals("干净会话名", localMirror.threads()!!.single().title)
    }
}
