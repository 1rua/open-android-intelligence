package com.openandroidintelligence.mobile

import com.openandroidintelligence.gateway.conversations.ConversationClient
import com.openandroidintelligence.gateway.conversations.GatewayTimelineMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** Snapshot recovery is silent and only projects conversations already observed by this process. */
internal class NotificationConversationReconciler(
    private val notifications: NativeConversationNotifications,
    private val client: ConversationClient,
) {
    private val mutex = Mutex()

    fun hasPendingReconciliation(): Boolean = notifications.hasPendingReconciliation()

    suspend fun reconcileActive(force: Boolean = false) = mutex.withLock {
        if (force) notifications.requestReconciliation()
        for (request in notifications.reconciliationRequests()) {
            try {
                withTimeoutOrNull(15_000L) {
                    val conversationId = request.session.target.conversationId
                    val generationBefore = client.currentGeneration(conversationId)
                    val messages = linkedMapOf<String, GatewayTimelineMessage>()
                    val cursors = mutableSetOf<String>()
                    var cursor: String? = null
                    var snapshotRevision: Long? = null
                    var pageCount = 0
                    do {
                        // A partial/looping timeline must never attest that a task ended.
                        check(++pageCount <= 1_000) { "NOTIFICATION_TIMELINE_INCOMPLETE" }
                        val page = client.readTimeline(conversationId, cursor, limit = 200)
                        if (snapshotRevision != null && page.snapshotRevision != null) {
                            check(snapshotRevision == page.snapshotRevision) { "NOTIFICATION_TIMELINE_CHANGED" }
                        }
                        if (snapshotRevision == null) snapshotRevision = page.snapshotRevision
                        page.messages.forEach { message ->
                            val previousRevision = messages[message.messageId]?.contentRevision
                            val contentRevision = message.contentRevision
                            if (previousRevision == null || contentRevision == null || contentRevision >= previousRevision) {
                                messages[message.messageId] = message
                            }
                        }
                        cursor = page.nextCursor
                        cursor?.let { check(cursors.add(it)) { "NOTIFICATION_TIMELINE_CURSOR_LOOP" } }
                    } while (cursor != null)
                    val generationAfter = client.currentGeneration(conversationId)
                    if (generationBefore == generationAfter) {
                        // accept() also advances a per-conversation revision. An
                        // event arriving during any HTTP read invalidates this pass.
                        notifications.reconcile(request, generationAfter, messages.values.toList())
                    }
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (_: Exception) {
                // Transport/protocol failures leave both task state and retry
                // eligibility intact for the next periodic or reconnect pass.
            }
        }
    }
}
