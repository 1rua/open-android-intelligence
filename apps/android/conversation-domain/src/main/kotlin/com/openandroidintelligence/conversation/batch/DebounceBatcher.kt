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
    private val onFlush: suspend (ConversationScope, List<OutgoingMessage>) -> Unit,
) : AutoCloseable {
    private val activeBatches = mutableMapOf<ConversationScope, MutableList<OutgoingMessage>>()
    private val activeJobs = mutableMapOf<ConversationScope, Job>()

    fun offer(targetScope: ConversationScope, message: OutgoingMessage) {
        val list = activeBatches.getOrPut(targetScope) { mutableListOf() }
        list.add(message)

        if (list.size >= policy.maximumMembers) {
            flush(targetScope)
            return
        }

        activeJobs[targetScope]?.cancel()
        activeJobs[targetScope] = scope.launch {
            delay(policy.delay)
            flush(targetScope)
        }
    }

    fun flush(targetScope: ConversationScope) {
        activeJobs[targetScope]?.cancel()
        activeJobs.remove(targetScope)
        val messages = activeBatches.remove(targetScope) ?: return
        if (messages.isNotEmpty()) {
            scope.launch {
                onFlush(targetScope, messages)
            }
        }
    }

    override fun close() {
        activeJobs.values.forEach { it.cancel() }
        activeJobs.clear()
        activeBatches.clear()
    }
}
