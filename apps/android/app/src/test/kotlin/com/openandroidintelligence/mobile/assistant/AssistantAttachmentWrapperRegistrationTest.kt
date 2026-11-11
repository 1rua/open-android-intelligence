package com.openandroidintelligence.mobile.assistant

import com.openandroidintelligence.conversation.model.AttachmentDraft
import com.openandroidintelligence.conversation.model.AttachmentDraftId
import com.openandroidintelligence.conversation.model.AttachmentState
import com.openandroidintelligence.conversation.ports.AttachmentDraftCoordinator
import com.openandroidintelligence.conversation.ports.AttachmentDraftState
import com.openandroidintelligence.conversation.ports.CancelSubmissionResult
import com.openandroidintelligence.conversation.ports.LocalAttachmentSelection
import com.openandroidintelligence.conversation.ports.PendingSubmissionIntent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 助手会话附件包装器的登记契约。
 *
 * 交接成功后主 App 接管附件，浮层关闭时工作区退役、包装器按 `ownedIds` 回收。若包装器
 * 没被登记进 `assistantAttachmentWrappers`，`prepareAssistantHandoff` 的 `disown` 就永远
 * 不会执行——旧版 Gateway 分支正是这样把刚迁给主 App 的草稿连同暂存文件一起删掉。
 * 因此「创建」与「登记」必须是同一个动作，两种 Gateway 分支都不能各自实现一遍。
 */
class AssistantAttachmentWrapperRegistrationTest {

    @Test fun creatingAWrapperRegistersItUnderTheSessionToken() {
        val wrappers = ConcurrentHashMap<String, AssistantAttachmentDraftCoordinator>()
        val delegate = Uploads()

        val wrapper = assistantAttachmentWrapperFor(wrappers, delegate, "session-1")

        assertNotNull(wrapper)
        assertSame("登记进表的必须就是交给控制器的那一个", wrapper, wrappers["session-1"])
    }

    @Test fun withoutAnOwningCoordinatorNothingIsCreatedOrRegistered() {
        val wrappers = ConcurrentHashMap<String, AssistantAttachmentDraftCoordinator>()

        assertNull(assistantAttachmentWrapperFor(wrappers, null, "session-1"))
        assertEquals(emptyMap<String, AssistantAttachmentDraftCoordinator>(), wrappers)
    }

    @Test fun everySessionGetsItsOwnRegisteredWrapper() {
        val wrappers = ConcurrentHashMap<String, AssistantAttachmentDraftCoordinator>()
        val delegate = Uploads()

        val first = assistantAttachmentWrapperFor(wrappers, delegate, "session-1")
        val second = assistantAttachmentWrapperFor(wrappers, delegate, "session-2")

        assertSame(first, wrappers["session-1"])
        assertSame(second, wrappers["session-2"])
        assertNotNull(first)
        assertNotNull(second)
    }

    /** 最小协调器：只记录所有权相关动作，不模拟真实上传。 */
    private class Uploads : AttachmentDraftCoordinator {
        private val drafts = linkedMapOf<String, AttachmentDraft>()
        private val states = mutableMapOf<String, MutableStateFlow<AttachmentDraftState>>()
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
            drafts.remove(draftId)
        }
        override suspend fun releaseAfterSubmit(draftId: String) = Unit
        override suspend fun armSubmission(draftId: String, revision: Long): PendingSubmissionIntent =
            error("test does not submit")
        override suspend fun cancelSubmission(intentId: String) = CancelSubmissionResult(true)
        override fun remoteAttachmentId(draftId: String): String? = null
    }
}
