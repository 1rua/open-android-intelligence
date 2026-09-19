package com.openandroidintelligence.conversation.batch

import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.ports.OutgoingMessage
import kotlinx.coroutines.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class DebouncePolicy(
    val delay: Duration = 1500.milliseconds,
    val maximumWait: Duration = 30.seconds,
    val maximumMembers: Int = 20,
)

class DebounceBatcher(
    private val scope: CoroutineScope,
    private val policy: DebouncePolicy = DebouncePolicy(),
    private val onFlush: suspend (ConversationScope, String, List<OutgoingMessage>) -> Unit,
) : AutoCloseable {
    private val activeBatches = mutableMapOf<String, MutableList<OutgoingMessage>>()
    private val activeScopes = mutableMapOf<String, ConversationScope>()
    private val activeJobs = mutableMapOf<String, Job>()

    /**
     * Collects one message into its conversation's batch.
     *
     * Batches are keyed by conversation and not by the gateway scope: one scope
     * covers every thread on a gateway, so keying by scope merged two threads
     * typed into within the same window into a single batch and delivered it to
     * whichever conversation happened to be flushed first.
     */
    fun offer(targetScope: ConversationScope, conversationId: String, message: OutgoingMessage) {
        activeScopes[conversationId] = targetScope
        val list = activeBatches.getOrPut(conversationId) { mutableListOf() }
        list.add(message)

        if (list.size >= policy.maximumMembers) {
            flush(conversationId)
            return
        }

        activeJobs[conversationId]?.cancel()
        activeJobs[conversationId] = scope.launch {
            delay(policy.delay)
            flush(conversationId)
        }
    }

    fun flush(conversationId: String) {
        activeJobs.remove(conversationId)?.cancel()
        val messages = activeBatches.remove(conversationId) ?: return
        val targetScope = activeScopes.remove(conversationId) ?: return
        if (messages.isNotEmpty()) {
            scope.launch {
                onFlush(targetScope, conversationId, messages)
            }
        }
    }

    override fun close() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        activeBatches.clear()
        activeScopes.clear()
    }
}
