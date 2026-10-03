package com.openandroidintelligence.conversation.batch

import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.ports.OutgoingMessage
import kotlinx.coroutines.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

enum class DebounceMode { FIXED_WINDOW, EXTEND_WINDOW }

data class DebouncePolicy(
    val delay: Duration = 1500.milliseconds,
    val maximumWait: Duration = 30.seconds,
    val maximumMembers: Int = 20,
    val maximumBytes: Int = 64 * 1024,
    val mode: DebounceMode = DebounceMode.EXTEND_WINDOW,
)

class DebounceBatcher(
    private val scope: CoroutineScope,
    policy: DebouncePolicy = DebouncePolicy(),
    private val onFlush: suspend (ConversationScope, String, List<OutgoingMessage>) -> Unit,
) : AutoCloseable {
    private var policy = policy
    private val batchPolicies = mutableMapOf<String,DebouncePolicy>()
    private val activeBatches = mutableMapOf<String, MutableList<OutgoingMessage>>()
    private val activeScopes = mutableMapOf<String, ConversationScope>()
    private val activeJobs = mutableMapOf<String, Job>()
    private val deadlineJobs = mutableMapOf<String, Job>()

    init {
        require(!policy.delay.isNegative() && policy.maximumWait >= policy.delay)
        require(policy.maximumMembers > 0 && policy.maximumBytes > 0)
    }

    /**
     * Collects one message into its conversation's batch.
     *
     * Batches are keyed by conversation and not by the gateway scope: one scope
     * covers every thread on a gateway, so keying by scope merged two threads
     * typed into within the same window into a single batch and delivered it to
     * whichever conversation happened to be flushed first.
     */
    fun hasPending(conversationId:String)=activeBatches.containsKey(conversationId)

    fun updatePolicy(next:DebouncePolicy) {
        require(!next.delay.isNegative() && next.maximumWait>=next.delay && next.maximumMembers>0 && next.maximumBytes>0)
        policy=next
    }

    fun offer(targetScope: ConversationScope, conversationId: String, message: OutgoingMessage) {
        var policy = batchPolicies.getOrPut(conversationId) { this.policy }
        val bytes = message.text.toByteArray(Charsets.UTF_8).size
        val priorBytes = activeBatches[conversationId].orEmpty().sumOf { it.text.toByteArray(Charsets.UTF_8).size }
        if (priorBytes + bytes > policy.maximumBytes) { flush(conversationId); policy=this.policy; batchPolicies[conversationId]=policy }
        activeScopes[conversationId] = targetScope
        val list = activeBatches.getOrPut(conversationId) { mutableListOf() }
        if (list.isEmpty()) {
            deadlineJobs[conversationId] = scope.launch {
                delay(policy.maximumWait)
                flush(conversationId)
            }
        }
        list.add(message)

        if (policy.delay == Duration.ZERO || list.size >= policy.maximumMembers || bytes >= policy.maximumBytes) {
            flush(conversationId)
            return
        }

        if (policy.mode == DebounceMode.FIXED_WINDOW && activeJobs[conversationId]?.isActive == true) return
        activeJobs[conversationId]?.cancel()
        activeJobs[conversationId] = scope.launch {
            delay(policy.delay)
            flush(conversationId)
        }
    }

    fun flush(conversationId: String) {
        batchPolicies.remove(conversationId)
        activeJobs.remove(conversationId)?.cancel()
        deadlineJobs.remove(conversationId)?.cancel()
        val messages = activeBatches.remove(conversationId) ?: return
        val targetScope = activeScopes.remove(conversationId) ?: return
        if (messages.isNotEmpty()) {
            scope.launch {
                onFlush(targetScope, conversationId, messages)
            }
        }
    }

    override fun close() {
        deadlineJobs.values.forEach { it.cancel() }
        deadlineJobs.clear()
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        batchPolicies.clear()
        activeBatches.clear()
        activeScopes.clear()
    }
}
