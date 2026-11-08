package com.openandroidintelligence.mobile.assistant

import com.openandroidintelligence.conversation.model.AttachmentDraft
import com.openandroidintelligence.conversation.ports.AttachmentDraftCoordinator
import com.openandroidintelligence.conversation.ports.LocalAttachmentSelection
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 助手草稿复用会话的上传器，但不会变成主工作台可恢复的草稿。
 *
 * 语义与气泡通知的草稿协调器一致：只暴露本协调器自己准备过的草稿，
 * 关闭时只清理自己名下仍未提交的暂存内容。
 */
internal class AssistantAttachmentDraftCoordinator(
    private val delegate: AttachmentDraftCoordinator,
) : AttachmentDraftCoordinator by delegate {
    private val ownedIds = ConcurrentHashMap.newKeySet<String>()
    private var closed = false
    private var cleanup: Job? = null

    override fun restoredDrafts(): List<AttachmentDraft> =
        delegate.restoredDrafts().filter { it.id.value in ownedIds }

    override suspend fun prepare(selection: LocalAttachmentSelection): AttachmentDraft {
        synchronized(this) { check(!closed) { "ASSISTANT_ATTACHMENTS_CLOSED" } }
        val draft = delegate.prepare(selection.copy(recoverAfterRestart = false))
        val retained = synchronized(this) {
            if (closed) false else { ownedIds += draft.id.value; true }
        }
        if (!retained) {
            withContext(NonCancellable) { delegate.discard(draft.id.value) }
            throw CancellationException("ASSISTANT_ATTACHMENTS_CLOSED")
        }
        return draft
    }

    override suspend fun discard(draftId: String) {
        if (draftId !in ownedIds) return
        delegate.discard(draftId)
        ownedIds -= draftId
    }

    override suspend fun releaseAfterSubmit(draftId: String) {
        if (draftId !in ownedIds) return
        delegate.releaseAfterSubmit(draftId)
        ownedIds -= draftId
    }

    /** 只在控制器真正关闭后运行，且不早于进程内发送结束。 */
    @Synchronized fun close(scope: CoroutineScope): Job {
        cleanup?.let { return it }
        closed = true
        val remaining = ownedIds.toList()
        // 登出可能已经取消了会话协程；回收临时内容仍必须完成，
        // 而 discard 不会删除已经确认的历史媒体副本。
        return scope.launch(NonCancellable) {
            remaining.forEach { id ->
                runCatching { delegate.discard(id) }.onSuccess { ownedIds -= id }
            }
        }.also { cleanup = it }
    }
}
