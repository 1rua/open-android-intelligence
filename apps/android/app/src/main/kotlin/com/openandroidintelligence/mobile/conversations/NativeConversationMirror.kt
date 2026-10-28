package com.openandroidintelligence.mobile.conversations

import android.content.Context
import com.openandroidintelligence.conversation.model.LocalSendAtom
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
    private val currentAgentId: () -> String?,
    private val keyProvider: AesGcmKeyProvider? = null,
) : AwaitableWorkbenchPersistence {

    private fun delegateMirror(): EncryptedConversationMirror {
        val agentId = currentAgentId() ?: "default"
        // 范围派生：在 accountId 维度拼接 agentId 确保 SQLite 与私有密文分区完全物理隔离
        val scoped = baseScope.copy(accountId = "${baseScope.accountId}#native#$agentId")
        return EncryptedConversationMirror(context, scoped, keyProvider)
    }

    override fun load(): WorkbenchCheckpoint? {
        return delegateMirror().load()
    }

    override fun save(checkpoint: WorkbenchCheckpoint) {
        delegateMirror().save(checkpoint)
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
            delegateMirror().saveCheckpoint(checkpoint)
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
