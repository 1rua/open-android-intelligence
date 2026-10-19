package com.openandroidintelligence.mobile

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

/** Bubble drafts use the session's uploader without becoming the main workbench's recovered drafts. */
internal class BubbleAttachmentDraftCoordinator(
    private val delegate: AttachmentDraftCoordinator,
) : AttachmentDraftCoordinator by delegate {
    private val ownedIds = ConcurrentHashMap.newKeySet<String>()
    private var closed = false
    private var cleanup: Job? = null

    override fun restoredDrafts(): List<AttachmentDraft> =
        delegate.restoredDrafts().filter { it.id.value in ownedIds }

    override suspend fun prepare(selection: LocalAttachmentSelection): AttachmentDraft {
        synchronized(this) { check(!closed) { "BUBBLE_ATTACHMENTS_CLOSED" } }
        val draft = delegate.prepare(selection.copy(recoverAfterRestart = false))
        val retained = synchronized(this) {
            if (closed) false else { ownedIds += draft.id.value; true }
        }
        if (!retained) {
            withContext(NonCancellable) { delegate.discard(draft.id.value) }
            throw CancellationException("BUBBLE_ATTACHMENTS_CLOSED")
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

    /** Runs only when the controller really closes, after any process-owned send has finished. */
    @Synchronized fun close(scope: CoroutineScope): Job {
        cleanup?.let { return it }
        closed = true
        val remaining = ownedIds.toList()
        // Logout may already have cancelled the session job. Reclaiming temporary content
        // still has to finish; discard never deletes the confirmed historical media copy.
        return scope.launch(NonCancellable) {
            remaining.forEach { id ->
                runCatching { delegate.discard(id) }.onSuccess { ownedIds -= id }
            }
        }.also { cleanup = it }
    }
}
