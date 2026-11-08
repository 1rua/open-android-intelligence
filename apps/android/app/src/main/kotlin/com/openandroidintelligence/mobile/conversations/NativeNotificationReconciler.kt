package com.openandroidintelligence.mobile.conversations

import com.openandroidintelligence.gateway.agents.NativeAgentMessage
import com.openandroidintelligence.gateway.conversations.GatewayTimelineMessage
import com.openandroidintelligence.gateway.conversations.MessagePart
import com.openandroidintelligence.mobile.NativeConversationNotifications
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** 原生会话的当前轮次：生成 ID 与宿主报告的原始状态。 */
internal data class NativeCurrentTurn(val generationId: String, val state: String)

/**
 * 原生会话的终态核对。
 *
 * 平台事件链路可以用 `generations/current` 加时间线修复丢掉的终态帧，原生
 * `/agents` 会话没有这两个旧路由：它自己的 `generation` 与 `history` 才是权威。
 * 这里只做和平台链路同样的事——确认当前是否还有生成、按真实历史重放正文——
 * 空闲只证明“这一轮结束了”，绝不推断成功，也不为历史轮次补发提醒。
 *
 * 两个读取入口都做成可注入的缝：判定本身与 HTTP 客户端无关，测试可以直接
 * 提供真实事实，而不是伪造一个网络层。
 */
internal class NativeNotificationReconciler(
    private val notifications: NativeConversationNotifications,
    private val currentTurn: suspend (agentId: String, conversationId: String) -> NativeCurrentTurn?,
    private val history: suspend (agentId: String, conversationId: String) -> List<NativeAgentMessage>,
) {
    fun hasPendingReconciliation(): Boolean = notifications.hasPendingReconciliation()

    suspend fun reconcileActive(force: Boolean = false) {
        if (force) notifications.requestReconciliation()
        for (request in notifications.reconciliationRequests()) {
            val target = request.session.target
            val agentId = target.agentId ?: continue
            try {
                withTimeoutOrNull(15_000L) {
                    // 读前后各确认一次当前轮次；中途换轮说明这份快照不能作为终态证据。
                    val before = currentTurn(agentId, target.conversationId)
                    val messages = history(agentId, target.conversationId)
                        .map { it.toTimelineMessage(target.conversationId) }
                    val after = currentTurn(agentId, target.conversationId)
                    if (before?.generationId != after?.generationId) return@withTimeoutOrNull
                    notifications.reconcile(request, after?.let { it.generationId to it.state }, messages)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 传输或协议失败不改变任务状态，保留重试资格。
            }
        }
    }
}

/** 原生历史 ⇄ 通知核对使用的平台时间线形状；只搬运字段，不做任何推断。 */
internal fun NativeAgentMessage.toTimelineMessage(conversationId: String): GatewayTimelineMessage =
    GatewayTimelineMessage(
        messageId = id,
        sender = role.lowercase(),
        parts = buildList {
            if (text.isNotEmpty()) add(MessagePart.Text(text))
            attachments.forEach { attachment ->
                add(MessagePart.AttachmentRef(
                    attachmentId = attachment.id,
                    filename = attachment.filename,
                    mediaType = attachment.mediaType,
                    sizeBytes = attachment.sizeBytes,
                    sha256 = attachment.sha256?.takeIf { it.startsWith("sha256:") },
                ))
            }
        },
        timestamp = occurredAtOf(createdAt),
        state = state ?: "CONFIRMED",
        clientMessageId = clientMessageId,
        contentRevision = contentRevision,
    )

private fun occurredAtOf(value: String?): Long? {
    val text = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    text.toBigDecimalOrNull()?.let { number ->
        return runCatching {
            (if (number.abs() < java.math.BigDecimal("100000000000")) number * java.math.BigDecimal(1000) else number)
                .setScale(0, java.math.RoundingMode.DOWN).longValueExact()
        }.getOrNull()
    }
    return runCatching { java.time.Instant.parse(text).toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
        ?: runCatching { java.time.LocalDateTime.parse(text).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }.getOrNull()
}
