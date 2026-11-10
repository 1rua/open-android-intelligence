package com.openandroidintelligence.mobile.assistant

import com.openandroidintelligence.conversation.model.AttachmentDraft
import com.openandroidintelligence.conversation.model.AttachmentDraftId
import com.openandroidintelligence.conversation.model.AttachmentState
import com.openandroidintelligence.conversation.ports.AttachmentDraftCoordinator
import com.openandroidintelligence.conversation.ports.AttachmentDraftState
import com.openandroidintelligence.conversation.ports.CancelSubmissionResult
import com.openandroidintelligence.conversation.ports.LocalAttachmentSelection
import com.openandroidintelligence.conversation.ports.PendingSubmissionIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 交接时的附件所有权转移。
 *
 * 交接要求「先迁移成功再关闭浮层」：主 App 接管附件后，浮层关闭不能把这份附件连同
 * 暂存文件一起丢弃。`disown` 只摘除认领，底层 delegate 的暂存/上传任务仍归主 App；
 * `adopt` 用来把通用协调器里的同一草稿收进包装器，避免两侧各持一个实例导致
 * 按 draftId 交接得到永不推进的引用。
 */
class AssistantAttachmentDraftCoordinatorHandoffTest {

    @Test
    fun aDisownedDraftSurvivesClosingTheAssistantCoordinator() = runBlocking {
        val delegate = Uploads()
        val assistant = AssistantAttachmentDraftCoordinator(delegate)
        val draft = assistant.prepare(selection("shot.png"))

        assertTrue("已迁移给主 App 的草稿应当可以摘除认领", assistant.disown(draft.id.value))
        assistant.close(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)).join()

        assertTrue("摘除认领后关闭不得丢弃附件", delegate.discarded.isEmpty())
        assertTrue("底层草稿仍归新的所有者", delegate.drafts.containsKey(draft.id.value))
    }

    @Test
    fun draftsThatWereNotHandedOffAreStillReclaimedOnClose() = runBlocking {
        val delegate = Uploads()
        val assistant = AssistantAttachmentDraftCoordinator(delegate)
        val handedOff = assistant.prepare(selection("shot.png"))
        val remaining = assistant.prepare(selection("voice.m4a"))
        assistant.disown(handedOff.id.value)

        assistant.close(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)).join()

        assertEquals("未迁移的附件必须被回收", listOf(remaining.id.value), delegate.discarded)
    }

    @Test
    fun disowningAnUnknownDraftIsANoOp() = runBlocking {
        val assistant = AssistantAttachmentDraftCoordinator(Uploads())

        assertFalse(assistant.disown("draft_unknown"))
    }

    @Test
    fun adoptingRestoresOwnershipSoRestoredDraftsCanBePublished() = runBlocking {
        val delegate = Uploads()
        val main = delegate.prepare(selection("shared.png"))
        val assistant = AssistantAttachmentDraftCoordinator(delegate)

        // 通用协调器里已有同一草稿：交接后助手包装器必须能按 id 观察它。
        assertTrue(assistant.adopt(main.id.value))
        assertEquals(AttachmentState.UPLOADING, assistant.observe(main.id.value).first().state)
        assertEquals(listOf(main.id), assistant.restoredDrafts().map { it.id })
    }

    @Test
    fun adoptingAfterCloseIsRejected() = runBlocking {
        val assistant = AssistantAttachmentDraftCoordinator(Uploads())
        assistant.close(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)).join()

        assertFalse("迟到的交接不能复活已回收的协调器", assistant.adopt("draft_late"))
    }

    private fun selection(name: String) = LocalAttachmentSelection(
        filename = name,
        mediaType = "image/png",
        contentSource = com.openandroidintelligence.conversation.ports.AttachmentContentSource {
            java.io.ByteArrayInputStream(byteArrayOf(1, 2, 3))
        },
    )

    /** 最小协调器：只记录所有权相关动作，不模拟真实上传。 */
    private class Uploads : AttachmentDraftCoordinator {
        val drafts = linkedMapOf<String, AttachmentDraft>()
        private val states = mutableMapOf<String, MutableStateFlow<AttachmentDraftState>>()
        val discarded = mutableListOf<String>()
        private var nextId = 0

        override fun restoredDrafts() = drafts.values.toList()

        override suspend fun prepare(selection: LocalAttachmentSelection): AttachmentDraft {
            val id = "draft_${++nextId}"
            val draft = AttachmentDraft(
                AttachmentDraftId(id), selection.filename, selection.mediaType, 3,
                "0".repeat(64), AttachmentState.UPLOADING,
            )
            drafts[id] = draft
            states[id] = MutableStateFlow(AttachmentDraftState(draft.id, draft.state))
            return draft
        }

        override fun observe(draftId: String): Flow<AttachmentDraftState> = states.getValue(draftId)
        override fun retry(draftId: String) = Unit
        override suspend fun discard(draftId: String) {
            discarded += draftId
            drafts.remove(draftId)
        }
        override suspend fun releaseAfterSubmit(draftId: String) = Unit
        override suspend fun armSubmission(draftId: String, revision: Long): PendingSubmissionIntent =
            error("test does not submit")
        override suspend fun cancelSubmission(intentId: String) = CancelSubmissionResult(true)
        override fun remoteAttachmentId(draftId: String): String? = null
    }
}
