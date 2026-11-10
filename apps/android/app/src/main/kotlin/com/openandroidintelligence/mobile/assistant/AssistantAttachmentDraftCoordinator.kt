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

    /**
     * 交接已成功的附件：移出本协调器的所有权，但**不**丢弃底层草稿与上传任务。
     *
     * 交接成功后附件归主工作台所有：[close] 再按 `ownedIds` 递归丢弃时，
     * 就会把主 App 刚接管的附件连同暂存文件一起清掉。这里只摘除认领，
     * 底层 delegate 的 `discard` / `releaseAfterSubmit` 由新的所有者负责。
     *
     * 未登记的 id（不属于本协调器）是空操作，与 `discard` / `releaseAfterSubmit`
     * 的既有语义一致。
     */
    @Synchronized fun disown(draftId: String): Boolean =
        if (draftId in ownedIds) {
            ownedIds -= draftId
            true
        } else {
            false
        }

    /**
     * 把通用协调器里的草稿收进本协调器的所有权。
     *
     * 交接要求两侧拿到**同一个**包装器：主控制器的 `restoredDrafts()` 只放行自己的
     * `ownedIds`，若主工作台用的是另一个包装器实例，按 draftId 交过去的附件会变成
     * 永不推进的引用。这里在交接时显式建立所有权，而不是靠两边实例恰好相同。
     *
     * 关闭后调用是空操作并返回 false（迟到的交接不能复活已回收的协调器）。
     */
    @Synchronized fun adopt(draftId: String): Boolean {
        if (closed || draftId.isBlank()) return false
        return ownedIds.add(draftId)
    }

    /**
     * 本协调器当前持有的草稿所有权（交接诊断与跨实例移交用）。
     *
     * 返回快照：调用方在另一条线程上遍历时不会被并发 prepare/close 影响。
     */
    @Synchronized fun ownedDraftIds(): Set<String> = ownedIds.toSet()
}
