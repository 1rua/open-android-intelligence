package com.openandroidintelligence.mobile.conversations

import android.content.Context
import com.openandroidintelligence.conversation.model.LocalSendAtom
import com.openandroidintelligence.conversation.ports.ConversationPage
import com.openandroidintelligence.conversation.ports.VerifiedConversationEvent
import com.openandroidintelligence.gateway.agents.NativeEventApproval
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.ports.TimelineMessage
import com.openandroidintelligence.conversation.ports.TimelinePage
import com.openandroidintelligence.conversation.state.AwaitableWorkbenchPersistence
import com.openandroidintelligence.conversation.state.WorkbenchCheckpoint
import com.openandroidintelligence.encrypted.store.AesGcmKeyProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 原生会话加密镜像适配器，按 (ConversationScope, 会话面, agentId) 分区隔离。
 * 复用现有的 EncryptedConversationMirror，解决原生工作台 persistence 为空导致的离线空态与断网重启丢失问题。
 */
class NativeConversationMirror(
    private val context: Context,
    private val baseScope: ConversationScope,
    currentAgentId: () -> String?,
    private val keyProvider: AesGcmKeyProvider? = null,
    private val observerId: String? = null,
) : AwaitableWorkbenchPersistence, NativeConversationStore {

    // 在控制器创建时固定 Agent；迟到的 IO 仍只能写入原分区。
    private val ownerAgentId = currentAgentId()
    private val mirrorDelegate = lazy {
        val agentId = checkNotNull(ownerAgentId) { "NATIVE_AGENT_REQUIRED" }
        require(agentId.isNotBlank()) { "NATIVE_AGENT_REQUIRED" }
        EncryptedConversationMirror(context, baseScope.copy(accountId = "${baseScope.accountId}#native#$agentId"), keyProvider)
    }

    private val mirror get() = mirrorDelegate.value
    private fun delegateMirror() = mirror

    /** 连接的全部子任务完成后关闭仓储持有的句柄，不触发新的存储初始化。 */
    internal fun close() {
        if (mirrorDelegate.isInitialized()) mirror.closeNativeStore()
    }

    override suspend fun saveConversations(page: ConversationPage) = withContext(Dispatchers.IO) {
        mirror.saveNativeConversations(page)
    }

    override suspend fun recoveryIdentities(conversationId: String) = withContext(Dispatchers.IO) {
        mirror.nativeRecoveryIdentities(conversationId)
    }

    override suspend fun commitEvents(events: List<VerifiedConversationEvent>) = withContext(Dispatchers.IO) {
        mirror.commitNativeEvents(events)
    }

    override suspend fun commitApprovals(conversationId: String, approvals: List<NativeEventApproval>) = withContext(Dispatchers.IO) {
        mirror.commitNativeApprovals(conversationId, approvals)
    }

    override suspend fun loadApprovals(conversationId: String) = withContext(Dispatchers.IO) {
        mirror.nativeApprovals(conversationId)
    }

    private fun observer() = checkNotNull(observerId) { "NATIVE_EVENT_OBSERVER_REQUIRED" }
    override suspend fun pendingAck(conversationId: String) = withContext(Dispatchers.IO) {
        mirror.nativePendingAck(observer(), conversationId)
    }
    override suspend fun eventCursor(conversationId: String) = withContext(Dispatchers.IO) {
        mirror.nativeEventCursor(observer(), conversationId)
    }
    override suspend fun commitPage(conversationId: String, events: List<VerifiedConversationEvent>,
        approvals: List<NativeEventApproval>, cursor: String) = withContext(Dispatchers.IO) {
        mirror.commitNativePage(observer(), conversationId, events, approvals, cursor)
    }
    override suspend fun confirmAck(conversationId: String, cursor: String) = withContext(Dispatchers.IO) {
        mirror.confirmNativeAck(observer(), conversationId, cursor)
    }

    override fun load(): WorkbenchCheckpoint? {
        return delegateMirror().load()
    }

    override fun save(checkpoint: WorkbenchCheckpoint) {
        delegateMirror().saveNativeCheckpoint(checkpoint)
    }

    override fun loadSendAtoms(conversationId: String): List<LocalSendAtom> {
        return delegateMirror().loadSendAtoms(conversationId)
    }

    override fun saveSendAtoms(conversationId: String, atoms: List<LocalSendAtom>) {
        delegateMirror().saveSendAtoms(conversationId, atoms)
    }

    override suspend fun loadTimeline(conversationId: String): TimelinePage? {
        return withContext(Dispatchers.IO) {
            delegateMirror().loadTimeline(conversationId)
        }
    }

    override suspend fun loadKnownMediaMessages(): List<TimelineMessage> {
        return withContext(Dispatchers.IO) {
            delegateMirror().loadKnownMediaMessages()
        }
    }

    override suspend fun saveCheckpoint(checkpoint: WorkbenchCheckpoint) {
        withContext(Dispatchers.IO) {
            delegateMirror().saveNativeCheckpoint(checkpoint)
        }
    }

    override suspend fun saveSendAtomsDurably(conversationId: String, atoms: List<LocalSendAtom>) {
        withContext(Dispatchers.IO) {
            delegateMirror().saveSendAtomsDurably(conversationId, atoms)
        }
    }

    override suspend fun flush() {
        withContext(Dispatchers.IO) {
            delegateMirror().flush()
        }
    }

    override suspend fun closeStore() {
        withContext(Dispatchers.IO) {
            delegateMirror().closeStore()
        }
    }
}
