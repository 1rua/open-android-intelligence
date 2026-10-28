package com.openandroidintelligence.mobile.conversations

import android.content.Context
import com.openandroidintelligence.conversation.model.ConversationId
import com.openandroidintelligence.conversation.ports.AwaitableWorkbenchPersistence
import com.openandroidintelligence.conversation.ports.ConversationPage
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.ports.ConversationSummary
import com.openandroidintelligence.conversation.ports.EncryptedLocalDocumentKeyProvider
import com.openandroidintelligence.conversation.ports.TimelineMessage
import com.openandroidintelligence.conversation.ports.TimelinePage
import com.openandroidintelligence.conversation.ports.WorkbenchCheckpoint
import com.openandroidintelligence.conversation.ports.WorkbenchPersistence
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
    private val localDocumentKeyProvider: EncryptedLocalDocumentKeyProvider,
) : AwaitableWorkbenchPersistence {

    private fun delegateMirror(): EncryptedConversationMirror {
        val agentId = currentAgentId() ?: "default"
        // 范围派生：在 accountId 维度拼接 agentId 确保 SQLite 与私有密文分区完全物理隔离
        val scoped = baseScope.copy(accountId = "${baseScope.accountId}#native#$agentId")
        return EncryptedConversationMirror(context, scoped, localDocumentKeyProvider)
    }

    override suspend fun saveTimeline(conversationId: String, page: TimelinePage) {
        withContext(Dispatchers.IO) {
            delegateMirror().saveTimeline(conversationId, page)
        }
    }

    override suspend fun timeline(conversationId: String): TimelinePage? {
        return withContext(Dispatchers.IO) {
            delegateMirror().timeline(conversationId)
        }
    }

    override suspend fun saveConversations(page: ConversationPage) {
        withContext(Dispatchers.IO) {
            delegateMirror().saveConversations(page)
        }
    }

    override suspend fun conversations(): ConversationPage? {
        return withContext(Dispatchers.IO) {
            delegateMirror().conversations()
        }
    }

    override suspend fun saveCheckpoint(checkpoint: WorkbenchCheckpoint) {
        withContext(Dispatchers.IO) {
            delegateMirror().saveCheckpoint(checkpoint)
        }
    }

    override suspend fun load(): WorkbenchCheckpoint? {
        return withContext(Dispatchers.IO) {
            delegateMirror().load()
        }
    }

    override suspend fun awaitPendingWrites() {
        delegateMirror().awaitPendingWrites()
    }
}
