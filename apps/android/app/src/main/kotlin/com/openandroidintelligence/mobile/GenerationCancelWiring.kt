package com.openandroidintelligence.mobile

import com.openandroidintelligence.conversation.data.GatewayConversationRepository
import com.openandroidintelligence.conversation.ports.CancelGenerationOutcome
import com.openandroidintelligence.conversation.ports.CancelGenerationResult
import com.openandroidintelligence.conversation.ports.ConversationRepository
import com.openandroidintelligence.conversation.ports.GenerationTracker
import com.openandroidintelligence.conversation.model.StreamHealthSource
import com.openandroidintelligence.gateway.conversations.ConversationClient
import com.openandroidintelligence.gateway.negotiation.GenerationCancelCapability

/**
 * generation-cancel-v1 的宿主接线层：把协商派生的取消门禁接进工作台链路。
 *
 * 背景：A2 给 `ConversationClient.cancelGeneration` 追加了 fail-closed 门禁
 * （缺省 [GenerationCancelCapability.NotNegotiated] ⇒ 不发请求、如实返回
 * UNSUPPORTED）。但 `GatewayConversationRepository` 与 `WorkbenchController`
 * 都不感知协商结果，工作台的「停止生成」因此从「永远可用」变成了「永远
 * UNSUPPORTED」的临时回归。
 *
 * 两个模块都不可修改，所以宿主在装配处包一层：除取消外的所有端口调用都
 * 原样委托给上游仓储，仅 `cancelGeneration` 在这里带上协商门禁转发给
 * 真实的 [ConversationClient]。门禁由
 * [GenerationCancelCapability.fromNegotiation] 从协商结果派生——没有协商
 * 结果时装配缺省值，门依然是关着的。
 */
class CapabilityGatedConversationRepository(
    private val upstream: GatewayConversationRepository,
    private val client: ConversationClient,
    private val capability: GenerationCancelCapability,
    /** 与上游仓储一致的取消作用域：当前打开的线程。 */
    private val activeConversationId: () -> String?,
) : ConversationRepository by upstream,
    GenerationTracker by upstream,
    StreamHealthSource by upstream {

    override suspend fun cancelGeneration(
        generationId: String,
        requestId: String,
    ): CancelGenerationResult {
        val conversationId = activeConversationId()
            ?: return CancelGenerationResult(
                outcome = CancelGenerationOutcome.UNSUPPORTED,
                message = "NO_ACTIVE_CONVERSATION",
            )
        val outcome = client.cancelGeneration(conversationId, generationId, requestId, capability)
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
