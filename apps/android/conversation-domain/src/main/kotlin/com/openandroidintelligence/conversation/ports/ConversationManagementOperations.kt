package com.openandroidintelligence.conversation.ports

/** The complete native mutation must survive an uncertain response unchanged. */
sealed interface ConversationManagementIntent {
    val conversationId: String

    data class Archive(override val conversationId: String, val archived: Boolean) : ConversationManagementIntent
    data class Delete(override val conversationId: String) : ConversationManagementIntent
}

data class PendingConversationManagementOperation(
    val requestId: String,
    val intent: ConversationManagementIntent,
)

/** Account/Gateway scoped, and shared by every repository for that scope. */
interface ConversationManagementOperationStore {
    /** Includes the remote attempt, so sibling workbenches cannot replace an in-flight intent. */
    suspend fun <T> withManagementLock(block: suspend () -> T): T
    suspend fun pendingManagementOperations(): List<PendingConversationManagementOperation>
    /** Commits the identity and exact intent before the first network write. */
    suspend fun reserveManagementOperation(intent: ConversationManagementIntent): PendingConversationManagementOperation
    /** A confirmed mutation and its local mirror changes commit together. */
    suspend fun finishManagementOperation(operation: PendingConversationManagementOperation, confirmed: Boolean)
}
