package com.openandroidintelligence.mobile.conversations

import com.openandroidintelligence.conversation.model.MessagePart
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.gateway.events.GatewayEvent
import com.openandroidintelligence.gateway.schema.Json
import java.time.Instant

/**
 * 本地补齐出来的事件前缀。
 *
 * 这些事件由手机自己合成（历史快照、回执、终态回读），不是这一轮新发生的事实。
 * 它们仍然要进本机镜像供界面读取，但不能拿去做“任务已完成”提醒——否则每次
 * 冷启动重放旧历史都会重新弹一遍。
 */
private val SYNTHETIC_EVENT_PREFIXES = listOf(
    "native-history:", "native-read:", "native-receipt:", "native-status:", "native-cancel:", "history:",
)

/** 只有宿主真实发出的帧才允许触发提醒。 */
internal fun VerifiedConversationEvent.isLiveNativeEvent(): Boolean =
    SYNTHETIC_EVENT_PREFIXES.none { eventId.startsWith(it) }

/**
 * 把原生 `/agents` 会话已经校验过的事件翻译成提醒层认识的平台帧。
 *
 * 原生会话的正文与状态只存在于宿主自己的事件账本里，Core 的平台事件库中
 * 没有它们；而完成提醒、气泡快捷方式和账号围栏全部只认平台帧。因此这里把
 * 同一份事实按平台帧的形状送进 [com.openandroidintelligence.mobile.NativeConversationNotifications]，
 * 而不是在通知层另写一套完成判定——一套判定、一套持久围栏，才不会出现两条
 * 链路各自弹一次，也才能让原生会话与旧链路共享同一份去重记录。
 *
 * `agentId` 进入 payload：同一个账号下，不同 Agent 的会话 ID 各自独立，
 * 通知与气泡的目标必须带上 Agent 才不会互相串台。
 * 与提醒无关的事实（审批、执行步骤等）直接返回 null。
 */
internal fun nativeNotificationFrame(
    accountId: String,
    agentId: String,
    event: VerifiedConversationEvent,
): GatewayEvent? {
    if (!event.isLiveNativeEvent()) return null
    val name: String
    val payload: Map<String, Any?>
    when (event) {
        is VerifiedConversationEvent.TimelineUpsert -> {
            val message = event.message
            val conversationId = message.conversationId?.value ?: return null
            // 只有助手正文能成为提醒预览；用户行、工具行与系统行都不产生提醒。
            if (message.sender != "assistant") return null
            name = when (message.state.uppercase()) {
                "STREAMING" -> "conversation.message.delta"
                "CONFIRMED", "COMPLETED" -> "conversation.message.completed"
                // 取消与未知终态由状态事件表达，正文帧不能代表完成。
                else -> return null
            }
            payload = mapOf(
                "agentId" to agentId,
                "conversationId" to conversationId,
                "messageId" to message.id,
                "sender" to message.sender,
                "parts" to message.parts.map { part ->
                    when (part) {
                        is MessagePart.Text -> mapOf("type" to "text", "text" to part.value)
                        is MessagePart.Attachment -> mapOf("type" to "attachment", "attachmentId" to part.draftId.value)
                        // 本地命令占位不是 Agent 说过的正文。
                        is MessagePart.Command -> mapOf("type" to "text", "text" to part.rawText)
                    }
                },
                "text" to message.parts.filterIsInstance<MessagePart.Text>().joinToString("\n") { it.value },
                "timestamp" to message.timestamp,
                "revision" to event.revision,
                "generationId" to message.generationId,
            )
        }
        is VerifiedConversationEvent.MessageStatus -> {
            name = "conversation.message.status"
            payload = mapOf(
                "agentId" to agentId,
                "conversationId" to event.conversationId.value,
                "messageId" to event.messageId,
                "clientMessageId" to event.clientMessageId.value,
                "status" to event.status.wireValue,
                "revision" to event.revision,
                "errorCode" to event.errorCode?.wireValue,
                "generationId" to event.generationId,
            )
        }
        is VerifiedConversationEvent.GenerationCancelled -> {
            val conversationId = event.conversationId?.value ?: return null
            name = "conversation.generation.cancelled"
            payload = mapOf("agentId" to agentId, "conversationId" to conversationId,
                "generationId" to event.generationId)
        }
        is VerifiedConversationEvent.TitleUpdated -> {
            name = "conversation.title.updated"
            payload = mapOf(
                "agentId" to agentId,
                "conversationId" to event.conversationId.value,
                "title" to event.newTitle,
                "newTitle" to event.newTitle,
            )
        }
        // 删除事实让通知与气泡一起退休；创建与普通改动不需要提醒层处理。
        is VerifiedConversationEvent.ConversationsChanged -> {
            if (!event.deleted) return null
            val conversationId = event.conversationId?.value ?: return null
            name = "conversation.deleted"
            payload = mapOf("agentId" to agentId, "conversationId" to conversationId, "deleted" to true)
        }
        else -> return null
    }
    val envelope = mapOf(
        "accountId" to accountId,
        // 用事件自己的时间，而不是手机读到它的时间：重放历史时提醒层的时间围栏
        // 才能按原始发生时间判断；这一轮长耗时也不能把终态推迟到窗口之外。
        "occurredAt" to Instant.ofEpochMilli(event.occurredAt).toString(),
        "payload" to payload,
    )
    return GatewayEvent(
        id = event.eventId.takeIf { it.isNotBlank() },
        event = name,
        data = Json.canonical(Json.of(envelope)),
    )
}
