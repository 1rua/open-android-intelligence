package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.ports.ConversationSummary
import com.openandroidintelligence.conversation.ports.TimelineMessage
import com.openandroidintelligence.conversation.model.AttachmentDraft
import com.openandroidintelligence.conversation.model.LocalSendAtom

data class SavedBatch(val batchId:String,val conversationId:String,val messages:List<com.openandroidintelligence.conversation.ports.OutgoingMessage>,val sealed:Boolean=true)

data class SavedSubmission(val text: String, val attachmentIds: List<String>, val revision: Long,
    val conversationId: String?, val clientMessageId: String,
    val delivery: com.openandroidintelligence.conversation.ports.MessageDeliveryMode = com.openandroidintelligence.conversation.ports.MessageDeliveryMode.NORMAL,
    val generationId: String? = null)

data class SavedComposerDraft(val threadId: String?, val text: String, val revision: Long,
    val attachments: List<AttachmentDraft>, val explicit: Boolean = true)

data class SavedCancellation(val conversationId: String, val generationId: String)
data class SavedMessageGeneration(val conversationId: String, val messageId: String, val generationId: String, val revision: Long)
data class SavedFolderAssignment(val conversationId: String, val folderId: String)
data class SavedConversationCreation(val clientId: String, val folderId: String?,
    /** 旧 checkpoint 缺少发送事实时保守恢复；只有明确保存 false 才允许首次发送。 */
    val mayHaveBeenSent: Boolean = true)

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
    /** true 时空草稿也是用户已保存的事实，不用旧发送文字补齐。 */
    val draftExplicit: Boolean = false,
    val composerDrafts: List<SavedComposerDraft> = emptyList(),
    val pendingSubmissions: List<SavedSubmission> = emptyList(),
    val draftVersion: Long = draftRevision,
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
    /** null 为尚无列表镜像；非 null 的空页仍是已保存的真实快照。 */
    suspend fun loadConversations(): com.openandroidintelligence.conversation.ports.ConversationPage? = null
    suspend fun loadTimeline(conversationId: String): com.openandroidintelligence.conversation.ports.TimelinePage?
    suspend fun loadKnownMediaMessages(): List<TimelineMessage>
    suspend fun saveCheckpoint(checkpoint: WorkbenchCheckpoint)
    suspend fun saveSendAtomsDurably(conversationId: String, atoms: List<LocalSendAtom>)
    suspend fun flush()
    suspend fun closeStore()
}
