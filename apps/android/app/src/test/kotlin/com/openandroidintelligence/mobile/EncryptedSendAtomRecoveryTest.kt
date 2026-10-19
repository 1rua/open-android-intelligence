package com.openandroidintelligence.mobile

import android.app.Application
import android.database.sqlite.SQLiteDatabase
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

    @Test fun explicitNewerMessageContentVersionUpdatesTheJournalAndRejectsAnOlderPage() = runBlocking {
        val mirror = EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined)
        mirror.saveSendAtoms("conv_a",listOf(atom("cm_one","旧的可读原文").accepted("msg_cm_one").copy(contentRevision=1)))
        val host=Host().apply { rows=listOf(row("msg_cm_one","宿主明确编辑的新原文","cm_one").copy(contentRevision=2,
            parts=listOf(MessagePart.Text("宿主明确编辑的新原文"),MessagePart.Attachment(AttachmentDraftId("att_edited"),"新原图.png","image/png")))) }
        val repository=MirroredConversationRepository(host,mirror)
        val current=repository.timeline("conv_a",PageRequest()).messages.single()
        assertEquals("宿主明确编辑的新原文",(current.parts.first() as MessagePart.Text).value)
        assertEquals(2L,mirror.loadSendAtoms("conv_a").single().contentRevision)
        host.rows=listOf(row("msg_cm_one","晚到的旧页","cm_one").copy(contentRevision=1))
        assertEquals(current.parts,repository.timeline("conv_a",PageRequest()).messages.single().parts)
        val reopened=EncryptedConversationMirror(context,accountScope,key,Dispatchers.Unconfined)
        assertEquals(current.parts,MirroredConversationRepository(Host(),reopened).timeline("conv_a",PageRequest()).messages.single().parts)
    }

    @Test fun explicitEmptyContentVersionClearsTheBodyWithoutInventingDeletion() = runBlocking {
        val mirror=EncryptedConversationMirror(context,accountScope,key,Dispatchers.Unconfined)
        mirror.saveSendAtoms("conv_a",listOf(atom("cm_one","被明确清空的正文").accepted("msg_cm_one").copy(contentRevision=1)))
        val updated=mirror.reconcileQuery("conv_a",ClientMessageId("cm_one"),row("msg_cm_one","","cm_one")
            .copy(parts=emptyList(),contentRevision=2))
        assertTrue(updated.parts.isEmpty())
        assertFalse(mirror.loadSendAtoms("conv_a").single().isDeleted)
        assertEquals(2L,mirror.loadSendAtoms("conv_a").single().contentRevision)
    }

    @Test fun explicitDeletedHostSnapshotDoesNotRestoreTheLocalMessageOrCompletedStatus() = runBlocking {
        val mirror = EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined)
        val original = atom("cm_one", "宿主明确删除的原始正文").accepted("msg_cm_one").copy(parts = listOf(
            MessagePart.Text("宿主明确删除的原始正文"),
            MessagePart.Attachment(AttachmentDraftId("att_one"), "原始图片.png", "image/png"),
        ))
        mirror.saveSendAtoms("conv_a", listOf(original))
        mirror.apply(status("evt_complete", "cm_one", AgentMessageStatus.COMPLETED, 8))
        val host = Host().apply { rows = listOf(row("msg_cm_one", "", "cm_one").copy(parts = emptyList(), state = "DELETED")) }

        val visible = MirroredConversationRepository(host, mirror).timeline("conv_a", PageRequest())

        assertTrue("明确删除的宿主行不能被本地正文或完成状态复活", visible.messages.isEmpty())
        val reopened = EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined)
        assertTrue("删除后不能在 journal 中保留隐藏正文与附件", reopened.loadSendAtoms("conv_a").single().parts.isEmpty())
        assertEquals(setOf("att_one"), reopened.deletedMediaIds("conv_a", "msg_cm_one").toSet())
        assertEquals("分页摘要不能伪装成内容修订", -1L, reopened.loadSendAtoms("conv_a").single().contentRevision)
        assertTrue(MirroredConversationRepository(Host(), reopened).timeline("conv_a", PageRequest()).messages.isEmpty())
    }

    @Test fun displayedUserAndAssistantDeletionClearsTheWorkbenchAndSurvivesReopen() = runBlocking {
        val mirror = EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined)
        val original = atom("cm_one", "已显示的用户消息").accepted("msg_cm_one")
        mirror.saveSendAtoms("conv_a", listOf(original))
        val host = Host().apply { rows = listOf(row("msg_cm_one", "已显示的用户消息", "cm_one"),
            row("assistant_one", "已显示的助手回复").copy(sender = "assistant")) }
        val job = SupervisorJob()
        val controller = controller(CoroutineScope(coroutineContext + job), host, mirror)
        try {
            awaitLaunchedWork(job) { controller.openThread("conv_a") }
            awaitReadyThread(controller, "conv_a")
            assertEquals(2, (controller.state.value.timeline as Loadable.Ready).value.size)
            controller.editDraft("删除历史时保留当前草稿")
            host.rows = host.rows.map { it.copy(parts = emptyList(), state = "DELETED") }
            awaitLaunchedWork(job) { controller.onForegrounded() }
            awaitReadyThread(controller, "conv_a")

            assertTrue("正在显示的用户与助手旧缓存必须遵守明确删除", (controller.state.value.timeline as? Loadable.Ready)?.value.orEmpty().isEmpty())
            assertEquals("删除历史时保留当前草稿", controller.state.value.draft)
        } finally {
            controller.closeAndAwait(); job.cancel()
        }

        val reopened = EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined)
        val restartJob = SupervisorJob()
        val restarted = controller(CoroutineScope(coroutineContext + restartJob), Host(), reopened)
        try {
            awaitLaunchedWork(restartJob) { restarted.openThread("conv_a") }
            awaitReadyThread(restarted, "conv_a")
            assertTrue((restarted.state.value.timeline as? Loadable.Ready)?.value.orEmpty().isEmpty())
            assertEquals("删除历史时保留当前草稿", restarted.state.value.draft)
        } finally {
            restarted.closeAndAwait(); restartJob.cancel()
        }
    }

    @Test fun deletedMemberInFrozenBatchIsNotResubmittedOrShownAsRecoverable() = runBlocking {
        val mirror = EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined)
        val frozen = SavedBatch("cb_frozen", "conv_a", listOf(OutgoingMessage(ClientMessageId("cm_one"), "已删除的冻结提交正文")))
        mirror.saveSendAtoms("conv_a", listOf(atom("cm_one", "已删除的冻结提交正文").copy(
            batchId = "cb_frozen", submissionState = LocalSubmissionState.OUTCOME_UNKNOWN,
        )))
        mirror.save(WorkbenchCheckpoint(emptyList(), "conv_a", "会话", emptyList(), emptyMap(), "独立的新草稿", 3,
            emptyList(), null, emptySet(), emptySet(), listOf(frozen)))
        mirror.saveTimeline("conv_a", TimelinePage(listOf(row("msg_cm_one", "", "cm_one").copy(parts = emptyList(), state = "DELETED")), null))
        val host = Host()
        val job = SupervisorJob()
        val controller = controller(CoroutineScope(coroutineContext + job), host, mirror)
        try {
            awaitReadyThread(controller, "conv_a")
            awaitLaunchedWork(job) { controller.retryPendingSubmissions() }

            assertEquals("已删除的批次成员不能因查询空结果而重复提交", 0, host.submissionCount)
            assertFalse(controller.state.value.hasRecoverableSubmissions)
            assertTrue(userTexts(controller).isEmpty())
            assertEquals("独立的新草稿", controller.state.value.draft)
        } finally {
            controller.closeAndAwait(); job.cancel()
        }
    }

    @Test fun snapshotDeletionSurvivesLateUpsertsStatusReceiptEmptyBaselineAndAssistantReopen() = runBlocking {
        val mirror = EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined)
        val original = atom("cm_one", "迟到回执不得补回原文")
        mirror.saveSendAtoms("conv_a", listOf(original))
        mirror.saveTimeline("conv_a", TimelinePage(listOf(
            row("msg_cm_one", "", "cm_one").copy(parts = emptyList(), state = "DELETED"),
            row("assistant_one", "").copy(sender = "assistant", parts = emptyList(), state = "DELETED"),
        ), null, Long.MAX_VALUE))
        val late = listOf(
            VerifiedConversationEvent.TimelineUpsert("evt_late_user", 900, 900, row("msg_cm_one", "迟到的用户旧内容", "cm_one")),
            VerifiedConversationEvent.TimelineUpsert("evt_late_assistant", 901, 901, row("assistant_one", "迟到的助手旧内容").copy(sender = "assistant")),
            status("evt_late_status", "cm_one", AgentMessageStatus.COMPLETED, 999),
        )
        val projected = MirroredConversationRepository(Host(late), mirror).observeEvents(accountScope).toList()
        assertTrue(projected.filterIsInstance<VerifiedConversationEvent.TimelineUpsert>().all { it.message.isDeleted && it.message.parts.isEmpty() })
        mirror.saveSendAtoms("conv_a", listOf(original.accepted("msg_cm_one")))
        mirror.installBaseline(emptyList(), mapOf("conv_a" to TimelinePage(emptyList(), null, 1)), "evt_empty_baseline")
        val reopened = EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined)
        reopened.finishBaselineRecovery()
        val durable = reopened.loadSendAtoms("conv_a").single()
        assertTrue(durable.snapshotDeleted)
        assertTrue(durable.parts.isEmpty())
        assertEquals(-1L, durable.contentRevision)
        assertEquals(AgentMessageStatus.COMPLETED, durable.status)
        val job = SupervisorJob()
        val controller = controller(CoroutineScope(coroutineContext + job), Host(late), reopened)
        try {
            awaitLaunchedWork(job) { controller.openThread("conv_a") }
            awaitReadyThread(controller, "conv_a")
            assertTrue((controller.state.value.timeline as? Loadable.Ready)?.value.orEmpty().isEmpty())
            awaitLaunchedWork(job) { controller.retryPendingSubmissions() }
            assertFalse(controller.state.value.hasRecoverableSubmissions)
        } finally {
            controller.closeAndAwait(); job.cancel()
        }
    }

    @Test fun mixedDeletedFrozenBatchKeepsAllIdentitiesAndOnlyQueriesRemainingUnknownMembers() = runBlocking {
        val mirror = EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined)
        val members = listOf(OutgoingMessage(ClientMessageId("cm_deleted"), "已删除成员的原稿"),
            OutgoingMessage(ClientMessageId("cm_unknown"), "仍未知成员的原稿"))
        mirror.saveSendAtoms("conv_a", members.map { atom(it.clientMessageId.value, it.text).copy(
            batchId = "cb_original", submissionState = LocalSubmissionState.OUTCOME_UNKNOWN,
        ) })
        mirror.save(WorkbenchCheckpoint(emptyList(), "conv_a", "会话", emptyList(), emptyMap(), "独立草稿", 7,
            emptyList(), null, emptySet(), emptySet(), listOf(SavedBatch("cb_original", "conv_a", members))))
        mirror.saveTimeline("conv_a", TimelinePage(listOf(row("msg_cm_deleted", "", "cm_deleted").copy(parts = emptyList(), state = "DELETED")), null))
        val host = Host()
        val job = SupervisorJob()
        val controller = controller(CoroutineScope(coroutineContext + job), host, mirror)
        try {
            awaitReadyThread(controller, "conv_a")
            awaitLaunchedWork(job) { controller.retryPendingSubmissions() }
            controller.flushPendingPersistence()
            assertEquals(0, host.submissionCount)
            assertEquals(listOf(ClientMessageId("cm_unknown")), host.queryIds)
            assertTrue(controller.state.value.hasRecoverableSubmissions)
            assertTrue(controller.state.value.notice?.contains("禁止重发") == true)
            assertEquals(listOf("仍未知成员的原稿"), userTexts(controller))
            assertEquals("独立草稿", controller.state.value.draft)
            val restored = EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined)
            val frozen = restored.load()!!.batches.single()
            assertEquals("cb_original", frozen.batchId)
            assertEquals(members.map { it.clientMessageId }, frozen.messages.map { it.clientMessageId })
            assertEquals("", frozen.messages.first().text)
            assertEquals("仍未知成员的原稿", frozen.messages.last().text)
            assertEquals(LocalSubmissionState.OUTCOME_UNKNOWN, restored.loadSendAtoms("conv_a").single { it.clientMessageId.value == "cm_unknown" }.submissionState)
        } finally {
            controller.closeAndAwait(); job.cancel()
        }
    }

    @Test fun higherContentRevisionStillRestoresAnOrdinaryRevisionedTombstone() = runBlocking {
        val mirror = EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined)
        mirror.saveSendAtoms("conv_a", listOf(atom("cm_one", "删除之前").accepted("msg_cm_one")))
        val events = listOf(
            VerifiedConversationEvent.TimelineTombstoned("evt_revision_delete", 7, "msg_cm_one", 7, ConversationId("conv_a")),
            VerifiedConversationEvent.TimelineUpsert("evt_revision_restore", 8, 8, row("msg_cm_one", "更高修订的远端正文", "cm_one")),
        )
        MirroredConversationRepository(Host(events), mirror).observeEvents(accountScope).toList()
        val reopened = EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined)
        val atom = reopened.loadSendAtoms("conv_a").single()
        assertFalse(atom.snapshotDeleted)
        assertNull(atom.tombstoneRevision)
        assertEquals(8L, atom.contentRevision)
        assertEquals("更高修订的远端正文", (MirroredConversationRepository(Host(), reopened).timeline("conv_a", PageRequest()).messages.single().parts.single() as MessagePart.Text).value)
    }

    @Test fun versionOneDeletedRowMigratesWithoutLosingReadableHistoryOrDraft() = runBlocking {
        val deleted = row("msg_cm_one", "已删除的旧版正文").copy(state = "DELETED")
        writeVersionOne(listOf(deleted, row("msg_legacy", "仍可读的旧版消息")), null)
        val mirror = EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined)
        mirror.saveSendAtoms("conv_a", listOf(atom("cm_one", "已删除的旧版正文").accepted("msg_cm_one")))
        val loaded = mirror.load()!!
        assertEquals("保留的草稿", loaded.draft)
        assertEquals(listOf("msg_legacy"), loaded.messages.map { it.id })
        val reopened = EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined)
        assertTrue(reopened.loadSendAtoms("conv_a").single().snapshotDeleted)
        assertTrue(reopened.loadSendAtoms("conv_a").single().parts.isEmpty())
        assertEquals("仍可读的旧版消息", (MirroredConversationRepository(Host(), reopened).timeline("conv_a", PageRequest()).messages.single().parts.single() as MessagePart.Text).value)
    }

    @Test fun completedPaginationKeepsExplicitDeletionIdentitiesAndDiscardsAnExpiredAttempt() = runBlocking {
        val deleted = row("msg_deleted", "").copy(parts = emptyList(), state = "DELETED")
        val completed = Host().apply { pages = mutableListOf(TimelinePage(listOf(deleted), "next", 10),
            TimelinePage(listOf(row("assistant_one", "正常回复").copy(sender = "assistant")), null, 10)) }.completeTimeline("conv_a")
        assertEquals(setOf("msg_deleted"), completed.deletedMessageIds)
        assertEquals(10L, completed.snapshotRevision)
        assertEquals(2, completed.messages.size)

        val restarted = Host().apply { pages = mutableListOf(
            TimelinePage(listOf(deleted), "next", 10), TimelinePage(emptyList(), null, 11),
            TimelinePage(listOf(row("msg_current", "最新快照")), "next", 12), TimelinePage(emptyList(), null, 12),
        ) }.completeTimeline("conv_a")
        assertTrue("过期分页中的删除事实不能混入尚未接受的另一代快照", restarted.deletedMessageIds.isEmpty())
        assertEquals(listOf("msg_current"), restarted.messages.map { it.id })
        assertEquals(12L, restarted.snapshotRevision)
    }

    @Test fun explicitEmptyRemoteEditDoesNotRestoreThePreviousLocalText() {
        val mirror = EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined)
        val original = atom("cm_one", "必须被远端编辑清空的正文").accepted("msg_cm_one")
        mirror.saveSendAtoms("conv_a", listOf(original))
        mirror.apply(VerifiedConversationEvent.TimelineUpsert("evt_empty_edit", 2, 2,
            row("msg_cm_one", "", "cm_one").copy(parts = emptyList())))
        mirror.saveSendAtoms("conv_a", listOf(original))
        val reopened = EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined)
        assertTrue("明确的空内容编辑不能回退旧正文", reopened.loadSendAtoms("conv_a").single().parts.isEmpty())
        assertTrue(reopened.mergeTimeline("conv_a", TimelinePage(emptyList(), null)).messages.single().parts.isEmpty())
    }

    @Test fun olderNonEmptyHostSnapshotCannotRestoreTextAfterConfirmedEmptyEdit() = runBlocking {
        val mirror = EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined)
        mirror.saveSendAtoms("conv_a", listOf(atom("cm_one", "编辑前的正文").accepted("msg_cm_one")))
        mirror.apply(VerifiedConversationEvent.TimelineUpsert("evt_confirmed_empty", 4, 4,
            row("msg_cm_one", "", "cm_one").copy(parts = emptyList())))

        val reopened = EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined)
        val host = Host().apply { rows = listOf(row("msg_cm_one", "过期快照仍含旧正文", "cm_one")) }
        val visible = MirroredConversationRepository(host, reopened).timeline("conv_a", PageRequest()).messages.single()

        assertTrue("明确编辑为空之后，较旧的非空远端快照不能重新显示旧正文", visible.parts.isEmpty())
        assertEquals(4L, reopened.loadSendAtoms("conv_a").single().contentRevision)
    }

    @Test fun olderNonEmptyHostSnapshotCannotOverwriteConfirmedEditedText() = runBlocking {
        val mirror = EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined)
        mirror.saveSendAtoms("conv_a", listOf(atom("cm_one", "编辑前的正文").accepted("msg_cm_one")))
        mirror.apply(VerifiedConversationEvent.TimelineUpsert("evt_confirmed_edit", 4, 4,
            row("msg_cm_one", "远端已确认的新正文", "cm_one")))

        val reopened = EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined)
        val host = Host().apply { rows = listOf(row("msg_cm_one", "过期快照仍含旧正文", "cm_one")) }
        val visible = MirroredConversationRepository(host, reopened).timeline("conv_a", PageRequest()).messages.single()

        assertEquals("远端已确认的新正文", (visible.parts.single() as MessagePart.Text).value)
        assertEquals(4L, reopened.loadSendAtoms("conv_a").single().contentRevision)
    }

    @Test fun tombstoneRevisionWriteFailureCanReplayWithoutLosingDeletedAttachmentIds() = runBlocking {
        val mirror = EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined)
        val localParts = listOf(MessagePart.Text("删除前的本地正文"),
            MessagePart.Attachment(AttachmentDraftId("att_local"), "本地附件.txt", "text/plain"))
        val remoteParts = listOf(MessagePart.Text("删除前的远端正文"),
            MessagePart.Attachment(AttachmentDraftId("att_remote"), "远端附件.txt", "text/plain"))
        mirror.saveSendAtoms("conv_a", listOf(atom("cm_one", "").accepted("msg_cm_one").copy(parts = localParts)))
        mirror.saveTimeline("conv_a", TimelinePage(listOf(row("msg_cm_one", "", "cm_one").copy(parts = remoteParts)), null))
        failRecordWrites("event-revision")
        val event = VerifiedConversationEvent.TimelineTombstoned("evt_deleted_with_media", 1000,
            "msg_cm_one", 9, ConversationId("conv_a"))
        val host = Host(listOf(event))
        val firstAttempt = MirroredConversationRepository(host, mirror)

        assertTrue(runCatching { firstAttempt.observeEvents(accountScope).toList() }.exceptionOrNull() is IOException)
        assertEquals("evt_before", host.cursor)
        allowRecordWrites()

        val reopened = EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined)
        MirroredConversationRepository(host, reopened).observeEvents(accountScope).toList()
        assertEquals("evt_deleted_with_media", host.cursor)
        assertEquals("删除事件写入中断后，重放仍须保留全部待清理附件身份",
            setOf("att_local", "att_remote"), reopened.deletedMediaIds("conv_a", "msg_cm_one").toSet())
        assertTrue(reopened.loadSendAtoms("conv_a").single().parts.isEmpty())
        assertTrue(reopened.timeline("conv_a")!!.messages.isEmpty())
    }

    @Test fun controllerKeepsBothSendsAcrossThreadSwitchAndEncryptedMirrorReopenWithEmptyHostHistory() = runBlocking {
        val mirror = EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined)
        val host = Host()
        val job = SupervisorJob()
        val controller = controller(CoroutineScope(coroutineContext + job), host, mirror)
        try {
            controller.openThread("conv_a")
            awaitReadyThread(controller, "conv_a")
            listOf("等待回复的第一条", "等待回复的第二条").forEach { text ->
                controller.editDraft(text)
                assertTrue(controller.state.value.canSend)
                controller.sendDraft()
                // The repository also uses Dispatchers.IO: yield does not wait for a durable send.
                withTimeout(5_000) {
                    controller.state.first { state ->
                        state.draft.isEmpty() && state.composer == ComposerState.EDITING &&
                            state.pendingBatch.isEmpty() &&
                            (state.timeline as? Loadable.Ready)?.value?.none { it.pendingAcceptance } == true
                    }
                }
            }
            assertEquals(2, host.submissionCount)
            assertEquals(listOf("等待回复的第一条", "等待回复的第二条"), userTexts(controller))
            controller.openThread("conv_b")
            awaitReadyThread(controller, "conv_b")
            assertTrue(userTexts(controller).isEmpty())
            controller.openThread("conv_a")
            awaitReadyThread(controller, "conv_a")
            assertEquals(listOf("等待回复的第一条", "等待回复的第二条"), userTexts(controller))
        } finally {
            controller.closeAndAwait()
            job.cancel()
        }

        val reopened = EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined)
        val restartJob = SupervisorJob()
        val restarted = controller(CoroutineScope(coroutineContext + restartJob), host, reopened)
        try {
            restarted.openThread("conv_a")
            awaitReadyThread(restarted, "conv_a")
            assertEquals(listOf("等待回复的第一条", "等待回复的第二条"), userTexts(restarted))
            assertEquals(2, reopened.loadSendAtoms("conv_a").size)
            assertTrue("远端基线不能写入本地发送副本", reopened.timeline("conv_a")!!.messages.isEmpty())
            assertTrue(EncryptedConversationMirror(context, accountScope.copy(accountId = "other_account"), key).loadSendAtoms("conv_a").isEmpty())
            File(context.noBackupFilesDir, "private-documents").walkTopDown().filter { it.isFile }.forEach {
                assertFalse(it.readBytes().decodeToString().contains("等待回复的第一条"))
            }
        } finally {
            restarted.closeAndAwait()
            restartJob.cancel()
        }
    }

    @Test fun statusBeforeReceiptAndIndependentContentRevisionSurviveReopenAndOlderWrites() = runBlocking {
        val mirror = EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined)
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

        val reopened = EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined)
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
        val mirror = EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined)
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
        val reopened = EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined)
        reopened.finishBaselineRecovery()
        val durable = reopened.loadSendAtoms("conv_a").single()
        assertEquals(3L, durable.tombstoneRevision)
        assertTrue("远端删除不能保留隐藏旧正文", durable.parts.isEmpty())
        val staleHost = Host().apply { rows = listOf(row("msg_legacy", "旧快照正文"), row("msg_cm_one", "旧快照正文", "cm_one")) }
        assertTrue(MirroredConversationRepository(staleHost, reopened).timeline("conv_a", PageRequest()).messages.isEmpty())
        assertTrue(reopened.timeline("conv_a")!!.messages.isEmpty())
        assertTrue(EncryptedConversationMirror(context,accountScope,key,Dispatchers.Unconfined).load()!!.messages.isEmpty())
    }

    @Test fun versionOneMigrationRetriesAfterFinalCheckpointFailureAndPreservesNewerLedgerFacts() {
        val legacyRows = listOf(row("msg_legacy", "旧消息的明确服务端身份"))
        val attachments = listOf(mapOf("id" to "att_one", "name" to "证据.txt", "mediaType" to "text/plain",
            "size" to 9, "sha256" to "hash", "state" to AttachmentState.VERIFIED.name))
        val batches = listOf(mapOf("batchId" to "cb_old", "conversationId" to "conv_b", "sealed" to true,
            "members" to listOf(mapOf("id" to "cm_batch", "text" to "冻结批次正文"))))
        writeVersionOne(legacyRows, mapOf("text" to "未知结果的正文", "ids" to listOf("att_one"), "revision" to 7,
            "conversationId" to "conv_a", "clientMessageId" to "cm_one"), attachments, batches)
        failRecordWrites("workbench")
        assertNotNull(runCatching { EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined).load() }.exceptionOrNull())
        assertEquals(1, JsonFields.int(rawWorkbench(), "version"))
        allowRecordWrites()

        val partiallyMigrated = EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined)
        val confirmed = partiallyMigrated.loadSendAtoms("conv_a").single().accepted("msg_cm_one").copy(
            status = AgentMessageStatus.COMPLETED, statusRevision = 6,
        )
        partiallyMigrated.saveSendAtoms("conv_a", listOf(confirmed))
        val reopened = EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined)
        val saved = reopened.load()!!
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
        val mirror = EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined)
        val prepared = atom("cm_one", "删除前尚未取得回执的正文")
        mirror.saveSendAtoms("conv_a", listOf(prepared))
        mirror.apply(VerifiedConversationEvent.TimelineTombstoned("evt_deleted", 1000, "msg_cm_one", 5, ConversationId("conv_a")))
        mirror.saveSendAtoms("conv_a", listOf(prepared.accepted("msg_cm_one")))
        val reopened = EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined)
        assertEquals(5L, reopened.loadSendAtoms("conv_a").single().tombstoneRevision)
        assertTrue(reopened.loadSendAtoms("conv_a").single().parts.isEmpty())
        assertTrue(MirroredConversationRepository(Host(), reopened).timeline("conv_a", PageRequest()).messages.isEmpty())
    }

    @Test fun emptyOutcomeQueryBindsOriginalIdentityWithoutLosingBodyOrSubmittingAgain() = runBlocking {
        val mirror = EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined)
        mirror.saveSendAtoms("conv_a", listOf(atom("cm_one", "不能丢失的正文").copy(submissionState = LocalSubmissionState.OUTCOME_UNKNOWN)))
        val host = Host().apply { query = row("msg_cm_one", "").copy(parts = emptyList(), timestamp = 0, state = "accepted") }
        val repository = MirroredConversationRepository(host, mirror)
        val recovered = repository.queryMessage("conv_a", ClientMessageId("cm_one"))!!
        assertEquals(ClientMessageId("cm_one"), recovered.clientMessageId)
        assertEquals(ConversationId("conv_a"), recovered.conversationId)
        assertEquals("不能丢失的正文", (recovered.parts.single() as MessagePart.Text).value)
        assertEquals(1000L, recovered.timestamp)
        assertEquals("msg_cm_one", EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined).loadSendAtoms("conv_a").single().messageId)
        assertEquals(0, host.submissionCount)
    }

    @Test fun eventPersistenceFailureLeavesCursorReplayableAndRetryDurablyAppliesIt() = runBlocking {
        val mirror = EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined)
        mirror.saveSendAtoms("conv_a", listOf(atom("cm_one", "原正文")))
        failRecordWrites("send-atom-record")
        val host = Host(listOf(status("evt_complete", "cm_one", AgentMessageStatus.COMPLETED, 1)))
        val repository = MirroredConversationRepository(host, mirror)
        assertTrue(runCatching { repository.observeEvents(accountScope).toList() }.exceptionOrNull() is IOException)
        assertEquals("evt_before", host.cursor)
        assertEquals(LocalSubmissionState.PREPARED, mirror.loadSendAtoms("conv_a").single().submissionState)
        allowRecordWrites()
        repository.observeEvents(accountScope).toList()
        assertEquals("evt_complete", host.cursor)
        assertEquals(AgentMessageStatus.COMPLETED, EncryptedConversationMirror(context, accountScope, key, Dispatchers.Unconfined).loadSendAtoms("conv_a").single().status)
    }

    private fun controller(scope: CoroutineScope, host: Host, mirror: EncryptedConversationMirror) = WorkbenchController(
        scope, MirroredConversationRepository(host, mirror), object : AgentCommandCatalogRepository {
            override suspend fun get(gatewayId: String, languageCode: String) = AgentCommandCatalog(CatalogVersion("test"), emptyList())
        }, { accountScope }, debouncePolicy = DebouncePolicy(delay = Duration.ZERO), supportsMessageBatches = true,
        replyTimeouts = WorkbenchController.ReplyTimeouts(enabled = false), clock = { 1000 }, persistence = mirror,
        persistenceDispatcher = Dispatchers.Unconfined,
    )

    private fun userTexts(controller: WorkbenchController) =
        (controller.state.value.timeline as? Loadable.Ready)?.value.orEmpty().filter { it.isUser }.map { it.text }

    private suspend fun awaitReadyThread(controller: WorkbenchController, threadId: String) {
        withTimeout(5_000) {
            controller.state.first { state ->
                state.activeThreadId == threadId && state.historyHydrated && state.threads is Loadable.Ready &&
                    (state.timeline is Loadable.Ready || state.timeline is Loadable.Empty)
            }
        }
    }

    /** A no-op recovery can finish without publishing another StateFlow value. */
    private suspend fun awaitLaunchedWork(parent: Job, action: () -> Unit) {
        fun descendants(job: Job): List<Job> = job.children.flatMap { child ->
            sequenceOf(child) + descendants(child).asSequence()
        }.toList()
        val existing = descendants(parent).toSet()
        action()
        val launched = descendants(parent).filterNot { it in existing }
        withTimeout(5_000) { launched.forEach { it.join() } }
    }

    private fun atom(id: String, text: String) = LocalSendAtom(ConversationId("conv_a"), ClientMessageId(id), listOf(MessagePart.Text(text)), 1000)
    private fun row(id: String, text: String, clientId: String? = null) = TimelineMessage(id, "user", listOf(MessagePart.Text(text)), 1000,
        conversationId = ConversationId("conv_a"), clientMessageId = clientId?.let(::ClientMessageId))
    private fun status(eventId: String, clientId: String, state: AgentMessageStatus, revision: Long) =
        VerifiedConversationEvent.MessageStatus(eventId, 1000, ConversationId("conv_a"), "msg_$clientId", ClientMessageId(clientId), state, revision, null)

    private fun documentScope() = "conversation-mirror-v1:" + Json.canonical(Json.of(
        listOf(accountScope.profileId, accountScope.gatewayId, accountScope.accountId, accountScope.installId)))
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun rawWorkbench() = JsonFields.obj(Json.parse(EncryptedDocuments(context, documentScope(), key).read("workbench")!!.decodeToString()))!!
    private fun testDatabase(): SQLiteDatabase {
        val directory=File(context.noBackupFilesDir,"conversation-mirror/${digest(documentScope())}")
        check(directory.isDirectory || directory.mkdirs())
        return SQLiteDatabase.openOrCreateDatabase(File(directory,"mirror-v1.db"),null).also { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS records(row_id TEXT PRIMARY KEY,resource_type TEXT NOT NULL,lookup_key TEXT NOT NULL UNIQUE,revision INTEGER NOT NULL,sort_key INTEGER NOT NULL,tombstone INTEGER NOT NULL,payload BLOB)")
            db.execSQL("CREATE TABLE IF NOT EXISTS migrations(version INTEGER PRIMARY KEY)")
            db.version=1
        }
    }
    /** 用真实 SQLite 写失败验证回滚与重放，避免依赖旧文件写入路径。 */
    private fun failRecordWrites(resourceType: String) {
        require(resourceType.matches(Regex("[a-z-]+")))
        testDatabase().use { db -> db.execSQL("CREATE TRIGGER fail_test_write BEFORE INSERT ON records WHEN NEW.resource_type='$resourceType' BEGIN SELECT RAISE(ABORT,'TEST_COMMIT_FAILURE'); END") }
    }
    private fun allowRecordWrites() { testDatabase().use { it.execSQL("DROP TRIGGER fail_test_write") } }
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
        var pages: MutableList<TimelinePage>? = null
        var query: TimelineMessage? = null
        val queryIds = mutableListOf<ClientMessageId>()
        var cursor = "evt_before"
        var submissionCount = 0
        override suspend fun listConversations(scope: ConversationScope, page: PageRequest) = ConversationPage(
            listOf("conv_a", "conv_b").mapIndexed { i, id -> ConversationSummary(ConversationId(id), id, i.toLong()) }, null)
        override suspend fun timeline(conversationId: String, page: PageRequest) = pages?.removeAt(0) ?: TimelinePage(rows, null, 0)
        override suspend fun createConversation(scope: ConversationScope, clientConversationId: String) = Conversation(ConversationId("conv_a"), "conv_a", 0)
        override suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance {
            submissionCount++
            return MessageAcceptance("msg_${message.clientMessageId.value}", message.clientMessageId.value)
        }
        override suspend fun submitBatch(batch: MessageBatch): BatchAcceptance {
            submissionCount++
            return BatchAcceptance("server_${batch.batchId}", batch.messages.associate { it.clientMessageId.value to "msg_${it.clientMessageId.value}" })
        }
        override suspend fun queryMessage(conversationId: String, clientMessageId: ClientMessageId): TimelineMessage? {
            queryIds += clientMessageId
            return query
        }
        override fun observeEvents(scope: ConversationScope): Flow<VerifiedConversationEvent> = flow {
            events.forEach { emit(it); cursor = it.eventId }
        }
        override suspend fun cancelGeneration(generationId: String, requestId: String) = CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED)
    }
}
