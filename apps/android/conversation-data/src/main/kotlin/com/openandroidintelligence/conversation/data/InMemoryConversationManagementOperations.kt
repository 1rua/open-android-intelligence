package com.openandroidintelligence.conversation.data

import com.openandroidintelligence.conversation.ports.ConversationManagementIntent
import com.openandroidintelligence.conversation.ports.ConversationManagementOperationStore
import com.openandroidintelligence.conversation.ports.PendingConversationManagementOperation
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Standalone adapters may use memory; the Android account runtime supplies its encrypted mirror. */
class InMemoryConversationManagementOperations : ConversationManagementOperationStore {
    private val mutex = Mutex()
    private val pending = linkedMapOf<String, PendingConversationManagementOperation>()

    override suspend fun <T> withManagementLock(block: suspend () -> T): T = mutex.withLock { block() }
    override suspend fun pendingManagementOperations(): List<PendingConversationManagementOperation> = pending.values.toList()
    override suspend fun reserveManagementOperation(intent: ConversationManagementIntent): PendingConversationManagementOperation {
        pending[intent.conversationId]?.let {
            check(it.intent == intent) { "CONVERSATION_MANAGEMENT_PENDING" }
            return it
        }
        return PendingConversationManagementOperation("management_${UUID.randomUUID()}", intent).also {
            pending[intent.conversationId] = it
        }
    }
    override suspend fun finishManagementOperation(operation: PendingConversationManagementOperation, confirmed: Boolean) {
        check(pending[operation.intent.conversationId] == operation) { "CONVERSATION_MANAGEMENT_PENDING" }
        pending.remove(operation.intent.conversationId)
    }
}
