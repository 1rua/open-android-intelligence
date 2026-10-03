package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.ports.ConversationSummary
import com.openandroidintelligence.conversation.ports.TimelineMessage
import com.openandroidintelligence.conversation.model.AttachmentDraft

data class SavedBatch(val batchId:String,val conversationId:String,val messages:List<com.openandroidintelligence.conversation.ports.OutgoingMessage>,val sealed:Boolean=true)

data class SavedSubmission(val text: String, val attachmentIds: List<String>, val revision: Long,
    val conversationId: String?, val clientMessageId: String)

/** Durable state never contains preview/screenshot bytes and never implies permission to send. */
data class WorkbenchCheckpoint(
    val threads: List<ConversationSummary>, val threadId: String?, val title: String,
    val messages: List<TimelineMessage>, val revisions: Map<String, Long>,
    val draft: String, val draftRevision: Long, val attachments: List<AttachmentDraft>,
    val submission: SavedSubmission?, val renamedThreads: Set<String>, val eventIds: Set<String>,
    val batches:List<SavedBatch> = emptyList(),
)

interface WorkbenchPersistence {
    fun load(): WorkbenchCheckpoint?
    /** Must be atomic and durable before a request is sent or an event cursor is committed. */
    fun save(checkpoint: WorkbenchCheckpoint)
}
