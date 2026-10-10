package com.openandroidintelligence.mobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.openandroidintelligence.conversation.batch.DebouncePolicy
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.state.*
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import com.openandroidintelligence.gateway.schema.*
import com.openandroidintelligence.mobile.conversations.EncryptedConversationMirror
import com.openandroidintelligence.mobile.conversations.MirroredConversationRepository
import com.openandroidintelligence.mobile.plugins.EncryptedDocuments
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EncryptedSendAtomRecoveryTest {
    private val context get() = ApplicationProvider.getApplicationContext<Application>()
    private val key = object : AesGcmKeyProvider {
        override fun getOrCreate() = SecretKeySpec(ByteArray(32) { 11 }, "AES")
        override fun delete() = Unit
    }
    private val accountScope = ConversationScope("profile_atoms", "https://gateway.example", "acct_atoms", "install_atoms")

    @Test fun explicitEmptyRemoteEditDoesNotRestoreThePreviousLocalText() {
        val mirror = EncryptedConversationMirror(context, accountScope, key)
        val original = atom("cm_one", "必须被远端编辑清空的正文").accepted("msg_cm_one")
        mirror.saveSendAtoms("conv_a", listOf(original))
        mirror.apply(VerifiedConversationEvent.TimelineUpsert("evt_empty_edit", 2, 2,
            row("msg_cm_one", "", "cm_one").copy(parts = emptyList())))
        mirror.saveSendAtoms("conv_a", listOf(original))
        val reopened = EncryptedConversationMirror(context, accountScope, key)
        assertTrue("明确的空内容编辑不能回退旧正文", reopened.loadSendAtoms("conv_a").single().parts.isEmpty())
        assertTrue(reopened.mergeTimeline("conv_a", TimelinePage(emptyList(), null)).messages.single().parts.isEmpty())
    }

    @Test fun olderNonEmptyHostSnapshotCannotRestoreTextAfterConfirmedEmptyEdit() = runBlocking {
        val mirror = EncryptedConversationMirror(context, accountScope, key)
        mirror.saveSendAtoms("conv_a", listOf(atom("cm_one", "编辑前的正文").accepted("msg_cm_one")))
        mirror.apply(VerifiedConversationEvent.TimelineUpsert("evt_confirmed_empty", 4, 4,
            row("msg_cm_one", "", "cm_one").copy(parts = emptyList())))

        val reopened = EncryptedConversationMirror(context, accountScope, key)
        val host = Host().apply { rows = listOf(row("msg_cm_one", "过期快照仍含旧正文", "cm_one")) }
        val visible = MirroredConversationRepository(host, reopened).timeline("conv_a", PageRequest()).messages.single()

        assertTrue("明确编辑为空之后，较旧的非空远端快照不能重新显示旧正文", visible.parts.isEmpty())
        assertEquals(4L, reopened.loadSendAtoms("conv_a").single().contentRevision)
    }

    @Test fun olderNonEmptyHostSnapshotCannotOverwriteConfirmedEditedText() = runBlocking {
        val mirror = EncryptedConversationMirror(context, accountScope, key)
        mirror.saveSendAtoms("conv_a", listOf(atom("cm_one", "编辑前的正文").accepted("msg_cm_one")))
        mirror.apply(VerifiedConversationEvent.TimelineUpsert("evt_confirmed_edit", 4, 4,
            row("msg_cm_one", "远端已确认的新正文", "cm_one")))

        val reopened = EncryptedConversationMirror(context, accountScope, key)
        val host = Host().apply { rows = listOf(row("msg_cm_one", "过期快照仍含旧正文", "cm_one")) }
        val visible = MirroredConversationRepository(host, reopened).timeline("conv_a", PageRequest()).messages.single()

        assertEquals("远端已确认的新正文", (visible.parts.single() as MessagePart.Text).value)
        assertEquals(4L, reopened.loadSendAtoms("conv_a").single().contentRevision)
    }

    @Test fun tombstoneRevisionWriteFailureCanReplayWithoutLosingDeletedAttachmentIds() = runBlocking {
        val mirror = EncryptedConversationMirror(context, accountScope, key)
        val localParts = listOf(MessagePart.Text("删除前的本地正文"),
            MessagePart.Attachment(AttachmentDraftId("att_local"), "本地附件.txt", "text/plain"))
        val remoteParts = listOf(MessagePart.Text("删除前的远端正文"),
            MessagePart.Attachment(AttachmentDraftId("att_remote"), "远端附件.txt", "text/plain"))
        mirror.saveSendAtoms("conv_a", listOf(atom("cm_one", "").accepted("msg_cm_one").copy(parts = localParts)))
        mirror.saveTimeline("conv_a", TimelinePage(listOf(row("msg_cm_one", "", "cm_one").copy(parts = remoteParts)), null))
        val documentRoot = File(context.noBackupFilesDir, "private-documents/${digest(documentScope())}")
        val blockedRevisionWrite = File(documentRoot, digest("event-revision:conv_a:msg_cm_one") + ".tmp")
        assertTrue(blockedRevisionWrite.mkdir())
        val event = VerifiedConversationEvent.TimelineTombstoned("evt_deleted_with_media", 1000,
            "msg_cm_one", 9, ConversationId("conv_a"))
        val host = Host(listOf(event))
        val firstAttempt = MirroredConversationRepository(host, mirror)

        assertTrue(runCatching { firstAttempt.observeEvents(accountScope).toList() }.exceptionOrNull() is IOException)
        assertEquals("evt_before", host.cursor)
        assertTrue(blockedRevisionWrite.renameTo(File(context.cacheDir, "tombstone-revision-write-failure-backup")))

        val reopened = EncryptedConversationMirror(context, accountScope, key)
        MirroredConversationRepository(host, reopened).observeEvents(accountScope).toList()
        assertEquals("evt_deleted_with_media", host.cursor)
        assertEquals("删除事件写入中断后，重放仍须保留全部待清理附件身份",
            setOf("att_local", "att_remote"), reopened.deletedMediaIds("conv_a", "msg_cm_one").toSet())
        assertTrue(reopened.loadSendAtoms("conv_a").single().parts.isEmpty())
        assertTrue(reopened.timeline("conv_a")!!.messages.isEmpty())
    }

    @Test fun controllerKeepsBothSendsAcrossThreadSwitchAndEncryptedMirrorReopenWithEmptyHostHistory() = runBlocking {
        val mirror = EncryptedConversationMirror(context, accountScope, key)
        val host = Host()
        val job = SupervisorJob()
        val controller = controller(CoroutineScope(job + Dispatchers.Unconfined), host, mirror)
        try {
            controller.openThread("conv_a")
            yield()
            listOf("等待回复的第一条", "等待回复的第二条").forEach { text ->
                controller.editDraft(text)
                controller.sendDraft()
                yield()
            }
            assertEquals(2, host.submissionCount)
            assertEquals(listOf("等待回复的第一条", "等待回复的第二条"), userTexts(controller))
            controller.openThread("conv_b")
            yield()
            assertTrue(userTexts(controller).isEmpty())
            controller.openThread("conv_a")
            yield()
            assertEquals(listOf("等待回复的第一条", "等待回复的第二条"), userTexts(controller))
        } finally {
            controller.close()
            job.cancel()
        }

        val reopened = EncryptedConversationMirror(context, accountScope, key)
        val restartJob = SupervisorJob()
        val restarted = controller(CoroutineScope(restartJob + Dispatchers.Unconfined), host, reopened)
        try {
            yield()
            restarted.openThread("conv_a")
            yield()
            assertEquals(listOf("等待回复的第一条", "等待回复的第二条"), userTexts(restarted))
            assertEquals(2, reopened.loadSendAtoms("conv_a").size)
            assertTrue("远端基线不能写入本地发送副本", reopened.timeline("conv_a")!!.messages.isEmpty())
            assertTrue(EncryptedConversationMirror(context, accountScope.copy(accountId = "other_account"), key).loadSendAtoms("conv_a").isEmpty())
            File(context.noBackupFilesDir, "private-documents").walkTopDown().filter { it.isFile }.forEach {
                assertFalse(it.readBytes().decodeToString().contains("等待回复的第一条"))
            }
        } finally {
            restarted.close()
            restartJob.cancel()
        }
    }

    @Test fun statusBeforeReceiptAndIndependentContentRevisionSurviveReopenAndOlderWrites() = runBlocking {
        val mirror = EncryptedConversationMirror(context, accountScope, key)
        val original = atom("cm_one", "原正文")
        mirror.saveSendAtoms("conv_a", listOf(original))
        val events = listOf(
            status("evt_complete", "cm_one", AgentMessageStatus.COMPLETED, 8),
            VerifiedConversationEvent.TimelineUpsert("evt_edit", 2, 2, row("msg_cm_one", "远端编辑后的正文", "cm_one")),
            status("evt_old", "cm_one", AgentMessageStatus.QUEUED, 7),
        )
        val host = Host(events)
        MirroredConversationRepository(host, mirror).observeEvents(accountScope).toList()
        assertEquals("evt_old", host.cursor)
        mirror.saveSendAtoms("conv_a", listOf(original.accepted("msg_cm_one")))

        val reopened = EncryptedConversationMirror(context, accountScope, key)
        val durable = reopened.loadSendAtoms("conv_a").single()
        assertEquals(AgentMessageStatus.COMPLETED, durable.status)
        assertEquals(8L, durable.statusRevision)
        assertEquals(2L, durable.contentRevision)
        assertEquals("远端编辑后的正文", (durable.parts.single() as MessagePart.Text).value)
        assertEquals(1000L, durable.timestamp)
        val visible = MirroredConversationRepository(Host(), reopened).timeline("conv_a", PageRequest()).messages.single()
        assertEquals("completed", visible.state)
        assertEquals("远端编辑后的正文", (visible.parts.single() as MessagePart.Text).value)
    }

    @Test fun tombstoneSuppressesLocalAndLegacyRowsAcrossOldUpsertsAndEmptySnapshotRecovery() = runBlocking {
        writeVersionOne(
            messages = listOf(row("msg_legacy", "旧版已发送消息")),
            submission = mapOf("text" to "待确认消息", "ids" to emptyList<String>(), "revision" to 3,
                "conversationId" to "conv_a", "clientMessageId" to "cm_one"),
        )
        val mirror = EncryptedConversationMirror(context, accountScope, key)
        mirror.load()
        mirror.saveSendAtoms("conv_a", mirror.loadSendAtoms("conv_a").map { it.accepted("msg_cm_one") })
        val events = listOf(
            VerifiedConversationEvent.TimelineTombstoned("evt_delete_atom", 3, "msg_cm_one", 3, ConversationId("conv_a")),
            VerifiedConversationEvent.TimelineTombstoned("evt_delete_legacy", 4, "msg_legacy", 4, ConversationId("conv_a")),
            VerifiedConversationEvent.TimelineUpsert("evt_stale_atom", 2, 2, row("msg_cm_one", "不应复活", "cm_one")),
            VerifiedConversationEvent.TimelineUpsert("evt_stale_legacy", 2, 2, row("msg_legacy", "不应复活")),
        )
        MirroredConversationRepository(Host(events), mirror).observeEvents(accountScope).toList()
        mirror.installBaseline(listOf(ConversationSummary(ConversationId("conv_a"), "会话", 1)),
            mapOf("conv_a" to TimelinePage(emptyList(), null, 5)), "evt_snapshot")
        val reopened = EncryptedConversationMirror(context, accountScope, key)
        reopened.finishBaselineRecovery()
        val durable = reopened.loadSendAtoms("conv_a").single()
        assertEquals(3L, durable.tombstoneRevision)
        assertTrue("远端删除不能保留隐藏旧正文", durable.parts.isEmpty())
        val staleHost = Host().apply { rows = listOf(row("msg_legacy", "旧快照正文"), row("msg_cm_one", "旧快照正文", "cm_one")) }
        assertTrue(MirroredConversationRepository(staleHost, reopened).timeline("conv_a", PageRequest()).messages.isEmpty())
        assertTrue(reopened.timeline("conv_a")!!.messages.isEmpty())
        assertTrue(JsonFields.objects(rawWorkbench(), "messages").isEmpty())
    }

    @Test fun versionOneMigrationRetriesAfterFinalCheckpointFailureAndPreservesNewerLedgerFacts() {
        val legacyRows = listOf(row("msg_legacy", "旧消息的明确服务端身份"))
        val attachments = listOf(mapOf("id" to "att_one", "name" to "证据.txt", "mediaType" to "text/plain",
            "size" to 9, "sha256" to "hash", "state" to AttachmentState.VERIFIED.name))
        val batches = listOf(mapOf("batchId" to "cb_old", "conversationId" to "conv_b", "sealed" to true,
            "members" to listOf(mapOf("id" to "cm_batch", "text" to "冻结批次正文"))))
        writeVersionOne(legacyRows, mapOf("text" to "未知结果的正文", "ids" to listOf("att_one"), "revision" to 7,
            "conversationId" to "conv_a", "clientMessageId" to "cm_one"), attachments, batches)
        val documentRoot = File(context.noBackupFilesDir, "private-documents/${digest(documentScope())}")
        val blockedCheckpoint = File(documentRoot, digest("workbench") + ".tmp")
        assertTrue(blockedCheckpoint.mkdir())
        assertNotNull(runCatching { EncryptedConversationMirror(context, accountScope, key).load() }.exceptionOrNull())
        assertEquals(1, JsonFields.int(rawWorkbench(), "version"))
        assertTrue(blockedCheckpoint.renameTo(File(documentRoot, "migration-failure-marker")))

        val partiallyMigrated = EncryptedConversationMirror(context, accountScope, key)
        val confirmed = partiallyMigrated.loadSendAtoms("conv_a").single().accepted("msg_cm_one").copy(
            status = AgentMessageStatus.COMPLETED, statusRevision = 6,
        )
        partiallyMigrated.saveSendAtoms("conv_a", listOf(confirmed))
        val reopened = EncryptedConversationMirror(context, accountScope, key)
        val saved = reopened.load()!!
        assertEquals(2, JsonFields.int(rawWorkbench(), "version"))
        assertEquals("保留的草稿", saved.draft)
        assertEquals("cm_one", saved.submission!!.clientMessageId)
        assertEquals("证据.txt", saved.attachments.single().filename)
        assertEquals("cb_old", saved.batches.single().batchId)
        assertEquals(AgentMessageStatus.COMPLETED, reopened.loadSendAtoms("conv_a").single().status)
        assertEquals(LocalSubmissionState.OUTCOME_UNKNOWN, reopened.loadSendAtoms("conv_b").single().submissionState)
        assertEquals(0L, reopened.loadSendAtoms("conv_b").single().timestamp)
        assertEquals(1, reopened.loadSendAtoms("conv_a").size)
        val query = MirroredConversationRepository(Host(), reopened)
        val visible = runBlocking { query.timeline("conv_a", PageRequest()).messages }
        assertEquals(setOf("msg_legacy", "msg_cm_one"), visible.map { it.id }.toSet())
    }

    @Test fun deletionBeforeHttpAcceptanceStillSuppressesTheLateReceipt() = runBlocking {
        val mirror = EncryptedConversationMirror(context, accountScope, key)
        val prepared = atom("cm_one", "删除前尚未取得回执的正文")
        mirror.saveSendAtoms("conv_a", listOf(prepared))
        mirror.apply(VerifiedConversationEvent.TimelineTombstoned("evt_deleted", 1000, "msg_cm_one", 5, ConversationId("conv_a")))
        mirror.saveSendAtoms("conv_a", listOf(prepared.accepted("msg_cm_one")))
        val reopened = EncryptedConversationMirror(context, accountScope, key)
        assertEquals(5L, reopened.loadSendAtoms("conv_a").single().tombstoneRevision)
        assertTrue(reopened.loadSendAtoms("conv_a").single().parts.isEmpty())
        assertTrue(MirroredConversationRepository(Host(), reopened).timeline("conv_a", PageRequest()).messages.isEmpty())
    }

    @Test fun emptyOutcomeQueryBindsOriginalIdentityWithoutLosingBodyOrSubmittingAgain() = runBlocking {
        val mirror = EncryptedConversationMirror(context, accountScope, key)
        mirror.saveSendAtoms("conv_a", listOf(atom("cm_one", "不能丢失的正文").copy(submissionState = LocalSubmissionState.OUTCOME_UNKNOWN)))
        val host = Host().apply { query = row("msg_cm_one", "").copy(parts = emptyList(), timestamp = 0, state = "accepted") }
        val repository = MirroredConversationRepository(host, mirror)
        val recovered = repository.queryMessage("conv_a", ClientMessageId("cm_one"))!!
        assertEquals(ClientMessageId("cm_one"), recovered.clientMessageId)
        assertEquals(ConversationId("conv_a"), recovered.conversationId)
        assertEquals("不能丢失的正文", (recovered.parts.single() as MessagePart.Text).value)
        assertEquals(1000L, recovered.timestamp)
        assertEquals("msg_cm_one", EncryptedConversationMirror(context, accountScope, key).loadSendAtoms("conv_a").single().messageId)
        assertEquals(0, host.submissionCount)
    }

    @Test fun eventPersistenceFailureLeavesCursorReplayableAndRetryDurablyAppliesIt() = runBlocking {
        val mirror = EncryptedConversationMirror(context, accountScope, key)
        mirror.saveSendAtoms("conv_a", listOf(atom("cm_one", "原正文")))
        val documentRoot = File(context.noBackupFilesDir, "private-documents/${digest(documentScope())}")
        val blockedLedgerWrite = File(documentRoot, digest("send-atoms:conv_a") + ".tmp")
        assertTrue(blockedLedgerWrite.mkdir())
        val host = Host(listOf(status("evt_complete", "cm_one", AgentMessageStatus.COMPLETED, 1)))
        val repository = MirroredConversationRepository(host, mirror)
        assertTrue(runCatching { repository.observeEvents(accountScope).toList() }.exceptionOrNull() is IOException)
        assertEquals("evt_before", host.cursor)
        assertEquals(LocalSubmissionState.PREPARED, mirror.loadSendAtoms("conv_a").single().submissionState)
        assertTrue(blockedLedgerWrite.renameTo(File(documentRoot, "event-write-failure-marker")))
        repository.observeEvents(accountScope).toList()
        assertEquals("evt_complete", host.cursor)
        assertEquals(AgentMessageStatus.COMPLETED, EncryptedConversationMirror(context, accountScope, key).loadSendAtoms("conv_a").single().status)
    }

    private fun controller(scope: CoroutineScope, host: Host, mirror: EncryptedConversationMirror) = WorkbenchController(
        scope, MirroredConversationRepository(host, mirror), object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) = AgentCommandCatalog(CatalogVersion("test"), emptyList())
        }, { accountScope }, debouncePolicy = DebouncePolicy(delay = Duration.ZERO), supportsMessageBatches = true,
        replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false), clock = { 1000 }, persistence = mirror,
    )

    private fun userTexts(controller: WorkbenchController) =
        (controller.state.value.timeline as? Loadable.Ready)?.value.orEmpty().filter { it.isUser }.map { it.text }

    private fun atom(id: String, text: String) = LocalSendAtom(ConversationId("conv_a"), ClientMessageId(id), listOf(MessagePart.Text(text)), 1000)
    private fun row(id: String, text: String, clientId: String? = null) = TimelineMessage(id, "user", listOf(MessagePart.Text(text)), 1000,
        conversationId = ConversationId("conv_a"), clientMessageId = clientId?.let(::ClientMessageId))
    private fun status(eventId: String, clientId: String, state: AgentMessageStatus, revision: Long) =
        VerifiedConversationEvent.MessageStatus(eventId, 1000, ConversationId("conv_a"), "msg_$clientId", ClientMessageId(clientId), state, revision, null)

    private fun documentScope() = "conversation-mirror-v1:" + Json.canonical(Json.of(
        listOf(accountScope.profileId, accountScope.gatewayId, accountScope.accountId, accountScope.installId)))
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun rawWorkbench() = JsonFields.obj(Json.parse(EncryptedDocuments(context, documentScope(), key).read("workbench")!!.decodeToString()))!!
    private fun writeVersionOne(
        messages: List<TimelineMessage>, submission: Map<String, Any?>?,
        attachments: List<Map<String, Any?>> = emptyList(), batches: List<Map<String, Any?>> = emptyList(),
    ) {
        val payload = mapOf("version" to 1, "threads" to listOf(mapOf("id" to "conv_a", "title" to "会话", "updatedAt" to 1)),
            "threadId" to "conv_a", "title" to "会话", "messages" to messages.map {
                mapOf("id" to it.id, "sender" to it.sender, "parts" to it.parts.map { p -> mapOf("type" to "text", "text" to (p as MessagePart.Text).value) },
                    "timestamp" to it.timestamp, "state" to it.state, "conversationId" to it.conversationId?.value)
            }, "revisions" to emptyMap<String, Long>(), "draft" to "保留的草稿", "draftRevision" to 7,
            "attachments" to attachments, "submission" to submission, "renamed" to emptyList<String>(), "events" to emptyList<String>(), "batches" to batches)
        EncryptedDocuments(context, documentScope(), key).write("workbench", Json.canonical(Json.of(payload)).toByteArray())
    }

    private class Host(private val events: List<VerifiedConversationEvent> = emptyList()) : ConversationRepository, GenerationTracker, StreamHealthSource, MessageOutcomeQuery {
        override val generationId = MutableStateFlow<String?>(null)
        override val streamHealth = MutableStateFlow(StreamHealth.LIVE)
        var rows = emptyList<TimelineMessage>()
        var query: TimelineMessage? = null
        var cursor = "evt_before"
        var submissionCount = 0
        override suspend fun listConversations(scope: ConversationScope, page: PageRequest) = ConversationPage(
            listOf("conv_a", "conv_b").mapIndexed { i, id -> ConversationSummary(ConversationId(id), id, i.toLong()) }, null)
        override suspend fun timeline(conversationId: String, page: PageRequest) = TimelinePage(rows, null, 0)
        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) = Conversation(ConversationId("conv_a"), "conv_a", 0)
        override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance {
            submissionCount++
            return MessageAcceptance("msg_${message.clientMessageId.value}", message.clientMessageId.value)
        }
        override suspend fun submitBatch(batch: MessageBatch): BatchAcceptance {
            submissionCount++
            return BatchAcceptance("server_${batch.batchId}", batch.messages.associate { it.clientMessageId.value to "msg_${it.clientMessageId.value}" })
        }
        override suspend fun queryMessage(conversationId: String, clientMessageId: ClientMessageId) = query
        override fun observeEvents(scope: ConversationScope): Flow<VerifiedConversationEvent> = flow {
            events.forEach { emit(it); cursor = it.eventId }
        }
        override suspend fun cancelGeneration(generationId: String, requestId: String) = CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }
}
