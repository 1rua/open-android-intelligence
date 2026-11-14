package com.openandroidintelligence.mobile.conversations

import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.mobile.NativeNotificationInput
import com.openandroidintelligence.mobile.NativeNotificationKind
import com.openandroidintelligence.mobile.ReplyNotificationTarget

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
 * 直接交付已校验的领域事实，保留原始发生时间和端点身份。
 * 主工作台、助手与气泡共用通知状态机和持久围栏，禁止先编码为旧协议再解码。
 */
internal fun nativeNotificationInput(
    profileId: String,
    accountId: String,
    endpointId: String?,
    event: VerifiedConversationEvent,
): NativeNotificationInput? {
    if (!event.isLiveNativeEvent()) return null
    val conversationId: String
    val kind: NativeNotificationKind
    when (event) {
        is VerifiedConversationEvent.TimelineUpsert -> {
            val message = event.message
            conversationId = message.conversationId?.value ?: return null
            if (message.sender != "assistant") return null
            kind = when (message.state.uppercase()) {
                "STREAMING" -> NativeNotificationKind.REPLY_DELTA
                "CONFIRMED", "COMPLETED" -> NativeNotificationKind.REPLY_COMPLETED
                else -> return null
            }
        }
        is VerifiedConversationEvent.MessageStatus -> {
            conversationId = event.conversationId.value
            kind = NativeNotificationKind.MESSAGE_STATUS
        }
        is VerifiedConversationEvent.GenerationCancelled -> {
            conversationId = event.conversationId?.value ?: return null
            kind = NativeNotificationKind.GENERATION_CANCELLED
        }
        is VerifiedConversationEvent.TitleUpdated -> {
            conversationId = event.conversationId.value
            kind = NativeNotificationKind.TITLE_UPDATED
        }
        is VerifiedConversationEvent.ConversationsChanged -> {
            if (!event.deleted) return null
            conversationId = event.conversationId?.value ?: return null
            kind = NativeNotificationKind.CONVERSATION_DELETED
        }
        else -> return null
    }
    if (profileId.isBlank() || accountId.isBlank() || conversationId.isBlank()) return null
    return NativeNotificationInput(ReplyNotificationTarget(profileId, accountId, conversationId,
        endpointId?.takeIf { it.isNotBlank() }), kind, event)
}
