package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.ports.ConversationSummary
import com.openandroidintelligence.conversation.ports.TimelineMessage
import com.openandroidintelligence.conversation.model.AttachmentDraft
import com.openandroidintelligence.conversation.model.LocalSendAtom

data class SavedBatch(val batchId:String,val conversationId:String,val messages:List<com.openandroidintelligence.conversation.ports.OutgoingMessage>,val sealed:Boolean=true)

data class SavedSubmission(val text: String, val attachmentIds: List<String>, val revision: Long,
    val conversationId: String?, val clientMessageId: String)

data class SavedCancellation(val conversationId: String, val generationId: String)
data class SavedMessageGeneration(val conversationId: String, val messageId: String, val generationId: String, val revision: Long)
data class SavedFolderAssignment(val conversationId: String, val folderId: String)
data class SavedConversationCreation(val clientId: String, val folderId: String?)

/** Durable state never contains preview/screenshot bytes and never implies permission to send. */
data class WorkbenchCheckpoint(
    val threads: List<ConversationSummary>, val threadId: String?, val title: String,
    val messages: List<TimelineMessage>, val revisions: Map<String, Long>,
    val draft: String, val draftRevision: Long, val attachments: List<AttachmentDraft>,
    val submission: SavedSubmission?, val renamedThreads: Set<String>, val eventIds: Set<String>,
    val batches:List<SavedBatch> = emptyList(),
    val organization: com.openandroidintelligence.conversation.ports.ConversationOrganization = com.openandroidintelligence.conversation.ports.ConversationOrganization(),
    /** An initial/loading screen must not replace a completed account history with an empty list. */
    val threadsHydrated: Boolean = true,
    val threadsSnapshotRevision: Long? = null,
    val deletedThreadIds: Set<String> = emptySet(),
    /** These facts must commit with the event cursor, including legacy statuses without a generation id. */
    val cancellations: List<SavedCancellation> = emptyList(),
    val messageGenerations: List<SavedMessageGeneration> = emptyList(),
    val pendingFolderAssignments: List<SavedFolderAssignment> = emptyList(),
    val pendingConversationCreation: SavedConversationCreation? = null,
)

interface WorkbenchPersistence {
    fun load(): WorkbenchCheckpoint?
    /** Must be atomic and durable before a request is sent or an event cursor is committed. */
    fun save(checkpoint: WorkbenchCheckpoint)
    fun loadSendAtoms(conversationId: String): List<LocalSendAtom>
    /** 按线程原子保存；成功返回后才允许向 Gateway 提交该发送。 */
    fun saveSendAtoms(conversationId: String, atoms: List<LocalSendAtom>)
}

/** 真实磁盘适配器提供可等待的 IO 提交；内存测试仓库继续使用基础端口。 */
interface AwaitableWorkbenchPersistence : WorkbenchPersistence {
    suspend fun loadTimeline(conversationId: String): com.openandroidintelligence.conversation.ports.TimelinePage?
    suspend fun loadKnownMediaMessages(): List<TimelineMessage>
    suspend fun saveCheckpoint(checkpoint: WorkbenchCheckpoint)
    suspend fun saveSendAtomsDurably(conversationId: String, atoms: List<LocalSendAtom>)
    suspend fun flush()
    suspend fun closeStore()
}
