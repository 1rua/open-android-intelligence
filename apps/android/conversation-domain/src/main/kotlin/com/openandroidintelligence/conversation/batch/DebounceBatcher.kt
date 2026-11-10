package com.openandroidintelligence.conversation.batch

import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.ports.OutgoingMessage
import kotlinx.coroutines.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * 客户端本地合并连续消息的策略。
 *
 * 合并完全由手机端决定：默认 [delay] 为零，等同于不合并、点击即提交；只有用户
 * 在设置里主动选择大于零的时长，才会先在本地暂存再一次性提交。
 */
data class DebouncePolicy(
    val delay: Duration = Duration.ZERO,
    /** 从首条消息起的硬上限：窗口不断被新消息延长时，也必须在此刻发出。 */
    val maximumWait: Duration = 30.seconds,
    val maximumMembers: Int = 20,
    val maximumBytes: Int = 64 * 1024,
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

    /** 当前策略是否要求合并：零时长时调用方应直接发送，而不是先落到等待队列。 */
    val merges: Boolean get() = !policy.delay.isNegative() && policy.delay > Duration.ZERO

    fun updatePolicy(next:DebouncePolicy) {
        require(!next.delay.isNegative() && next.maximumWait>=next.delay && next.maximumMembers>0 && next.maximumBytes>0)
        policy=next
    }

    fun offer(targetScope: ConversationScope, conversationId: String, message: OutgoingMessage) {
        var policy = batchPolicies.getOrPut(conversationId) { this.policy }
        val bytes = message.text.toByteArray(Charsets.UTF_8).size
        val prior = activeBatches[conversationId].orEmpty()
        // newline-v1 contributes one UTF-8 byte between each pair of members.
        val priorBytes = prior.sumOf { it.text.toByteArray(Charsets.UTF_8).size } + prior.size
        // 超过字节上限或成员数上限都要先封存已有成员，再让这一条自己开始新批次。
        if (prior.isNotEmpty() &&
            (priorBytes + bytes > policy.maximumBytes || prior.size >= policy.maximumMembers)) {
            flush(conversationId); policy=this.policy; batchPolicies[conversationId]=policy
        }
        activeScopes[conversationId] = targetScope
        val list = activeBatches.getOrPut(conversationId) { mutableListOf() }
        if (list.isEmpty()) {
            deadlineJobs[conversationId] = scope.launch {
                delay(policy.maximumWait)
                flush(conversationId)
            }
        }
        list.add(message)

        // 零时长意味着用户要求立即发送：不进等待队列，本次提交就是唯一一条成员。
        if (policy.delay == Duration.ZERO || list.size >= policy.maximumMembers || bytes >= policy.maximumBytes) {
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
