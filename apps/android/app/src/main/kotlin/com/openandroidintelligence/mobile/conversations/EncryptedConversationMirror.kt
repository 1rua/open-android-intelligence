package com.openandroidintelligence.mobile.conversations

import android.content.Context
import com.openandroidintelligence.mobile.plugins.EncryptedDocuments
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.state.*
import com.openandroidintelligence.gateway.schema.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map

/** A scope includes the Gateway identity; identical account IDs on two hosts never share keys. */
class EncryptedConversationMirror(context: Context, private val scope: ConversationScope,keyProvider:com.openandroidintelligence.encrypted.store.AesGcmKeyProvider?=null) : WorkbenchPersistence {
    private val documentScope="conversation-mirror-v1:"+Json.canonical(Json.of(listOf(scope.profileId,scope.gatewayId,scope.accountId,scope.installId)))
    private val documents = if (keyProvider==null) EncryptedDocuments(context,documentScope) else EncryptedDocuments(context,documentScope,keyProvider)
    private var lastThreads: List<ConversationSummary> = emptyList()
    private fun obj(value: JsonValue?) = JsonFields.obj(value) ?: error("MIRROR_CORRUPTED")
    private fun str(o: JsonValue.JObject, key: String) = JsonFields.string(o,key) ?: error("MIRROR_CORRUPTED")
    private fun encode(value: Any?) = Json.canonical(Json.of(value)).toByteArray()
    private fun read(key: String) = documents.read(key)?.let { obj(Json.parse(it.decodeToString())) }
    private fun thread(t: ConversationSummary) = mapOf("id" to t.id.value,"title" to t.title,"updatedAt" to t.updatedAt)
    private fun decodeThread(o: JsonValue.JObject) = ConversationSummary(ConversationId(str(o,"id")),str(o,"title"),JsonFields.long(o,"updatedAt") ?: 0L)
    private fun message(m: TimelineMessage): Map<String,Any?> = mapOf("id" to m.id,"sender" to m.sender,"parts" to m.parts.map { p -> when(p) {
        is MessagePart.Text -> mapOf("type" to "text","text" to p.value)
        is MessagePart.Attachment -> mapOf("type" to "attachment","id" to p.draftId.value,"name" to p.filename,"mediaType" to p.mediaType)
        is MessagePart.Command -> mapOf("type" to "command","text" to p.rawText)
    } },"timestamp" to m.timestamp,"state" to m.state,"conversationId" to m.conversationId?.value,"errorCode" to m.errorCode,"batchId" to m.batchId)
    private fun decodeMessage(o: JsonValue.JObject) = TimelineMessage(str(o,"id"),str(o,"sender"),JsonFields.objects(o,"parts").map { p -> when(str(p,"type")) {
        "text" -> MessagePart.Text(str(p,"text"))
        "attachment" -> MessagePart.Attachment(AttachmentDraftId(str(p,"id")),str(p,"name"),str(p,"mediaType"))
        "command" -> MessagePart.Command(str(p,"text"))
        else -> error("MIRROR_CORRUPTED")
    } },JsonFields.long(o,"timestamp") ?: 0L, str(o,"state"),JsonFields.string(o,"conversationId")?.let(::ConversationId),JsonFields.string(o,"errorCode"),JsonFields.string(o,"batchId"))
    private fun attachment(a: AttachmentDraft) = mapOf("id" to a.id.value,"name" to a.filename,"mediaType" to a.mediaType,
        "size" to a.sizeBytes,"sha256" to a.sha256,"state" to a.state.name)
    private fun decodeAttachment(o: JsonValue.JObject) = AttachmentDraft(AttachmentDraftId(str(o,"id")),str(o,"name"),str(o,"mediaType"),
        JsonFields.long(o,"size") ?: 0L,str(o,"sha256"),AttachmentState.valueOf(str(o,"state")))
    @Synchronized override fun save(checkpoint: WorkbenchCheckpoint) {
        if (checkpoint.threads.isNotEmpty()) lastThreads = checkpoint.threads
        val s = checkpoint.submission
        documents.write("workbench",encode(mapOf("version" to 1,"threads" to lastThreads.map(::thread),"threadId" to checkpoint.threadId,
            "title" to checkpoint.title,"messages" to checkpoint.messages.map(::message),"revisions" to checkpoint.revisions,"draft" to checkpoint.draft,
            "draftRevision" to checkpoint.draftRevision,"attachments" to checkpoint.attachments.map(::attachment),"renamed" to checkpoint.renamedThreads.toList(),
            "events" to checkpoint.eventIds.toList(),"batches" to checkpoint.batches.map { b -> mapOf("batchId" to b.batchId,"conversationId" to b.conversationId,"sealed" to b.sealed,"members" to b.messages.map { m -> mapOf("id" to m.clientMessageId.value,"text" to m.text) }) },"submission" to s?.let { mapOf("text" to it.text,"ids" to it.attachmentIds,"revision" to it.revision,
                "conversationId" to it.conversationId,"clientMessageId" to it.clientMessageId) })))
    }
    @Synchronized override fun load(): WorkbenchCheckpoint? {
        val o = read("workbench") ?: return null
        check(JsonFields.int(o,"version") == 1) { "MIRROR_VERSION_UNSUPPORTED" }
        lastThreads = JsonFields.objects(o,"threads").map(::decodeThread)
        val r = JsonFields.obj(JsonFields.field(o,"revisions"))?.fields.orEmpty().associate { (k,v) -> k to ((v as? JsonValue.JNumber)?.raw?.toLongOrNull() ?: error("MIRROR_CORRUPTED")) }
        val s = JsonFields.obj(JsonFields.field(o,"submission"))?.let { SavedSubmission(str(it,"text"),JsonFields.strings(it,"ids"),JsonFields.long(it,"revision") ?: 0L,
            JsonFields.string(it,"conversationId"),str(it,"clientMessageId")) }
        return WorkbenchCheckpoint(lastThreads,JsonFields.string(o,"threadId"),str(o,"title"),JsonFields.objects(o,"messages").map(::decodeMessage),r,str(o,"draft"),
            JsonFields.long(o,"draftRevision") ?: 0L,JsonFields.objects(o,"attachments").map(::decodeAttachment),s,JsonFields.strings(o,"renamed").toSet(),JsonFields.strings(o,"events").toSet(),JsonFields.objects(o,"batches").map { b -> SavedBatch(str(b,"batchId"),str(b,"conversationId"),JsonFields.objects(b,"members").map { m -> OutgoingMessage(ClientMessageId(str(m,"id")),str(m,"text")) },JsonFields.bool(b,"sealed")!=false) })
    }
    fun wipe() = documents.erase()
    @Synchronized fun saveThreads(threads: List<ConversationSummary>) { lastThreads = threads; documents.write("threads",encode(mapOf("threads" to threads.map(::thread)))) }
    @Synchronized fun threads(): List<ConversationSummary>? = read("threads")?.let { JsonFields.objects(it,"threads").map(::decodeThread) }
    @Synchronized fun saveTimeline(id: String, page: TimelinePage) = documents.write("timeline:$id",encode(mapOf("revision" to page.snapshotRevision,"messages" to page.messages.map(::message))))
    @Synchronized fun timeline(id: String): TimelinePage? = read("timeline:$id")?.let { TimelinePage(JsonFields.objects(it,"messages").map(::decodeMessage),null,JsonFields.long(it,"revision")) }
    @Synchronized fun apply(event: VerifiedConversationEvent) {
        when(event) {
            is VerifiedConversationEvent.TimelineUpsert -> event.message.conversationId?.value?.let { id ->
                val old = timeline(id) ?: TimelinePage(emptyList(),null)
                val revKey = "event-revision:$id:${event.message.id}"
                val rev = read(revKey)?.let { JsonFields.long(it,"revision") } ?: -1L
                if (event.revision >= rev) {
                    saveTimeline(id,old.copy(messages = (old.messages.filterNot { it.id == event.message.id } + event.message).sortedBy { it.timestamp }))
                    documents.write(revKey,encode(mapOf("revision" to event.revision)))
                }
            }
            is VerifiedConversationEvent.TimelineTombstoned -> event.conversationId?.value?.let { id ->
                timeline(id)?.let { page -> saveTimeline(id,page.copy(messages=page.messages.filterNot { it.id == event.messageId })) }
                documents.write("event-revision:$id:${event.messageId}",encode(mapOf("revision" to event.revision)))
            }
            is VerifiedConversationEvent.TitleUpdated -> threads()?.let { list -> saveThreads(list.map { if (it.id == event.conversationId) it.copy(title=event.newTitle) else it }) }
            else -> Unit
        }
    }
    /** Swap one encrypted generation atomically; a failed rebuild keeps the previous generation usable. */
    @Synchronized fun installBaseline(threads: List<ConversationSummary>, pages: Map<String,TimelinePage>, cursor: String) {
        val generation = "snapshot:$cursor"
        documents.write(generation,encode(mapOf("threads" to threads.map(::thread),"timelines" to pages.map { (id,page) ->
            mapOf("id" to id,"revision" to page.snapshotRevision,"messages" to page.messages.map(::message)) },"cursor" to cursor)))
        documents.write("baseline",encode(mapOf("generation" to generation)))
        saveThreads(threads)
        // The generation remains authoritative if the process dies during materialization.
        pages.forEach { (id,page) -> saveTimeline(id,page) }
        documents.keys().filter { it.startsWith("timeline:") && it.removePrefix("timeline:") !in pages }.forEach(documents::delete)
        documents.keys().filter { it.startsWith("event-revision:") || (it.startsWith("snapshot:") && it != generation) }.forEach(documents::delete)
    }
    @Synchronized fun finishBaselineRecovery() {
        val key = read("baseline")?.let { JsonFields.string(it,"generation") } ?: return
        val snap = read(key) ?: error("MIRROR_CORRUPTED")
        saveThreads(JsonFields.objects(snap,"threads").map(::decodeThread))
        val pages=JsonFields.objects(snap,"timelines")
        pages.forEach { p -> saveTimeline(str(p,"id"),TimelinePage(JsonFields.objects(p,"messages").map(::decodeMessage),null,JsonFields.long(p,"revision"))) }
        val ids=pages.map { str(it,"id") }.toSet()
        documents.keys().filter { it.startsWith("timeline:") && it.removePrefix("timeline:") !in ids }.forEach(documents::delete)
        documents.delete("baseline")
    }
}

class MirroredConversationRepository(private val delegate: ConversationRepository, private val mirror: EncryptedConversationMirror,private val media:HistoricalMediaPort?=null) : ConversationRepository by delegate, GenerationTracker, com.openandroidintelligence.conversation.model.StreamHealthSource, MessageOutcomeQuery {
    override val generationId get()=(delegate as GenerationTracker).generationId
    override val generationState get()=(delegate as GenerationTracker).generationState
    override val streamHealth get()=(delegate as com.openandroidintelligence.conversation.model.StreamHealthSource).streamHealth
    override suspend fun queryMessage(conversationId:String,clientMessageId:ClientMessageId):TimelineMessage?=(delegate as? MessageOutcomeQuery)?.queryMessage(conversationId,clientMessageId)
    override suspend fun listConversations(scope: ConversationScope, page: PageRequest): ConversationPage = try {
        delegate.listConversations(scope,page).also { if (it.nextCursor == null && page.cursor == null) mirror.saveThreads(it.conversations) }
    } catch (c: CancellationException) { throw c } catch (e: java.io.IOException) { mirror.threads()?.let { ConversationPage(it,null) } ?: throw e }
    override suspend fun timeline(conversationId: String, page: PageRequest): TimelinePage = try {
        if (page.cursor == null) delegate.completeTimeline(conversationId).also { mirror.saveTimeline(conversationId,it) }
        else delegate.timeline(conversationId,page)
    } catch (c: CancellationException) { throw c } catch (e: java.io.IOException) { if (page.cursor == null) mirror.timeline(conversationId) ?: throw e else throw e }
    override fun observeEvents(scope: ConversationScope) = delegate.observeEvents(scope).map { event ->
        if (event is VerifiedConversationEvent.TimelineTombstoned) event.conversationId?.value?.let { id ->
            mirror.timeline(id)?.messages?.firstOrNull { it.id==event.messageId }?.parts?.filterIsInstance<MessagePart.Attachment>()?.forEach { media?.remove(it.draftId.value) }
        }
        mirror.apply(event); event
    }
}
