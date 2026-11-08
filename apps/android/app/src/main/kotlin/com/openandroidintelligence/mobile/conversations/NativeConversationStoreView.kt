package com.openandroidintelligence.mobile.conversations

import com.openandroidintelligence.conversation.ports.ConversationPage
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.gateway.agents.NativeEventApproval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 同一份原生镜像上的第二个事件读取者。
 *
 * 原生事件读取游标按“读取者 + 会话”各记一份，确认时还要求游标仍属于当前读取者。
 * 气泡与主界面同时观察同一个会话时，如果共用同一个读取者身份，两边会互相覆盖
 * 待确认游标并报 `NATIVE_EVENT_OWNER_CHANGED`。这里复用同一个镜像实例（内容写入
 * 仍走同一把锁），只把读取与确认的位置换成气泡自己的身份。
 */
internal class NativeConversationStoreView(
    private val mirror: NativeConversationMirror,
    private val observer: String,
) : NativeConversationStore {
    override suspend fun creation(requestId: String) = mirror.creation(requestId)
    override suspend fun commitCreation(requestId: String, receipt: NativeCreationReceipt) =
        mirror.commitCreation(requestId, receipt)
    override suspend fun saveConversations(page: ConversationPage) = mirror.saveConversations(page)
    override suspend fun localTimeline(conversationId: String) = mirror.localTimeline(conversationId)
    override suspend fun beginListRead(): Long? = mirror.beginListRead()
    override suspend fun saveConversations(page: ConversationPage, expectedEpoch: Long?): Boolean =
        mirror.saveConversations(page, expectedEpoch)
    override suspend fun recoveryIdentities(conversationId: String) = mirror.recoveryIdentities(conversationId)
    override suspend fun commitEvents(events: List<VerifiedConversationEvent>) = mirror.commitEvents(events)
    override suspend fun commitApprovals(conversationId: String, approvals: List<NativeEventApproval>) =
        mirror.commitApprovals(conversationId, approvals)
    override suspend fun loadApprovals(conversationId: String) = mirror.loadApprovals(conversationId)

    // 位置类操作必须落在同一个镜像实例上，并且只写这个读取者自己的键。
    private val store get() = mirror.offlineMirror()
    override suspend fun pendingAck(conversationId: String) = withContext(Dispatchers.IO) {
        store.nativePendingAck(observer, conversationId)
    }
    override suspend fun eventCursor(conversationId: String) = withContext(Dispatchers.IO) {
        store.nativeEventCursor(observer, conversationId)
    }
    override suspend fun commitPage(
        conversationId: String,
        events: List<VerifiedConversationEvent>,
        approvals: List<NativeEventApproval>,
        cursor: String,
    ) = withContext(Dispatchers.IO) {
        store.commitNativePage(observer, conversationId, events, approvals, cursor)
    }
    override suspend fun confirmAck(conversationId: String, cursor: String) = withContext(Dispatchers.IO) {
        store.confirmNativeAck(observer, conversationId, cursor)
    }
}
