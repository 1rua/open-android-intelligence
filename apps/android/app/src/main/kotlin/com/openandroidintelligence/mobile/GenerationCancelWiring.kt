package com.openandroidintelligence.mobile

import com.openandroidintelligence.conversation.data.GatewayConversationRepository
import com.openandroidintelligence.conversation.ports.CancelGenerationOutcome
import com.openandroidintelligence.conversation.ports.CancelGenerationResult
import com.openandroidintelligence.conversation.ports.ConversationRepository
import com.openandroidintelligence.conversation.ports.GenerationTracker
import com.openandroidintelligence.conversation.model.StreamHealthSource
import com.openandroidintelligence.gateway.conversations.ConversationClient
import com.openandroidintelligence.gateway.negotiation.GenerationCancelCapability

/** Applies the negotiated cancel capability while preserving the caller's thread and generation. */
class CapabilityGatedConversationRepository(
    private val upstream: GatewayConversationRepository,
    private val client: ConversationClient,
    private val capability: GenerationCancelCapability,
    /** 与上游仓储一致的取消作用域：当前打开的线程。 */
    private val activeConversationId: () -> String?,
) : ConversationRepository by upstream,
    GenerationTracker by upstream,
    StreamHealthSource by upstream,
    com.openandroidintelligence.conversation.ports.MessageOutcomeQuery by upstream {
    override suspend fun timeline(conversationId:String,page:com.openandroidintelligence.conversation.ports.PageRequest):com.openandroidintelligence.conversation.ports.TimelinePage {
        // Include changes received while the history page itself was in flight.
        val revision=upstream.generationRevision(conversationId)
        val result=upstream.timeline(conversationId,page)
        if (capability.agreed) {
            val current=client.currentGeneration(conversationId)
            val installed=upstream.installCurrentGeneration(conversationId,current,expectedRevision=revision)
            // Every page needs its own unchanged receipt before retiring old progress.
            if (installed && current == null) return result.copy(
                messages=result.messages.map { message ->
                    if (message.sender == "assistant" && message.state == "STREAMING") message.copy(state="CONFIRMED") else message
                },
                // A successful install increments the captured revision exactly once.
                // Reading the revision again here could accidentally capture a newer event.
                idleGenerationRevision=revision + 1,
            )
        }
        return result
    }

    override fun isIdleGenerationReceiptCurrent(conversationId: String, revision: Long): Boolean =
        capability.agreed && upstream.generationRevision(conversationId) == revision

    override suspend fun cancelGeneration(
        generationId: String,
        requestId: String,
    ): CancelGenerationResult {
        val conversationId = activeConversationId()
            ?: return CancelGenerationResult(CancelGenerationOutcome.UNSUPPORTED, "NO_ACTIVE_CONVERSATION")
        return cancelGeneration(conversationId, generationId, requestId)
    }

    override suspend fun cancelGeneration(
        conversationId: String,
        generationId: String,
        requestId: String,
    ): CancelGenerationResult {
        val outcome = client.cancelGeneration(conversationId, generationId, requestId, capability)
        if (outcome=="CANCELLED" || outcome=="ALREADY_COMPLETED") upstream.finishGeneration(conversationId,generationId)
        return CancelGenerationResult(
            outcome = when (outcome) {
                "CANCELLED" -> CancelGenerationOutcome.CANCELLED
                "ALREADY_COMPLETED" -> CancelGenerationOutcome.ALREADY_COMPLETED
                "UNSUPPORTED" -> CancelGenerationOutcome.UNSUPPORTED
                else -> CancelGenerationOutcome.OUTCOME_UNKNOWN
            },
            message = outcome,
        )
    }
}
