package com.openandroidintelligence.mobile.conversations

import android.content.Context
import com.openandroidintelligence.mobile.plugins.EncryptedDocuments
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.state.*
import com.openandroidintelligence.gateway.schema.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map

/** 加密范围包含 Gateway 身份；不同宿主的同名账号不会共用文档。 */
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
    private fun parts(value: List<MessagePart>) = value.map { p -> when(p) {
        is MessagePart.Text -> mapOf("type" to "text","text" to p.value)
        is MessagePart.Attachment -> mapOf("type" to "attachment","id" to p.draftId.value,"name" to p.filename,"mediaType" to p.mediaType)
        is MessagePart.Command -> mapOf("type" to "command","text" to p.rawText,"catalogVersion" to p.catalogVersion?.value)
    } }
    private fun decodeParts(o: JsonValue.JObject) = JsonFields.objects(o,"parts").map { p -> when(str(p,"type")) {
        "text" -> MessagePart.Text(str(p,"text"))
        "attachment" -> MessagePart.Attachment(AttachmentDraftId(str(p,"id")),str(p,"name"),str(p,"mediaType"))
        "command" -> MessagePart.Command(str(p,"text"),JsonFields.string(p,"catalogVersion")?.let(::CatalogVersion))
        else -> error("MIRROR_CORRUPTED")
    } }
    private fun message(m: TimelineMessage): Map<String,Any?> = mapOf("id" to m.id,"sender" to m.sender,"parts" to parts(m.parts),
        "timestamp" to m.timestamp,"state" to m.state,"conversationId" to m.conversationId?.value,"errorCode" to m.errorCode,
        "batchId" to m.batchId,"clientMessageId" to m.clientMessageId?.value,"localSubmissionFailure" to m.localSubmissionFailure)
    private fun decodeMessage(o: JsonValue.JObject) = TimelineMessage(str(o,"id"),str(o,"sender"),decodeParts(o),
        JsonFields.long(o,"timestamp") ?: 0L,str(o,"state"),JsonFields.string(o,"conversationId")?.let(::ConversationId),
        JsonFields.string(o,"errorCode"),JsonFields.string(o,"batchId"),JsonFields.string(o,"clientMessageId")?.let(::ClientMessageId),
        JsonFields.string(o,"localSubmissionFailure"))
    private fun attachment(a: AttachmentDraft) = mapOf("id" to a.id.value,"name" to a.filename,"mediaType" to a.mediaType,
        "size" to a.sizeBytes,"sha256" to a.sha256,"state" to a.state.name)
    private fun decodeAttachment(o: JsonValue.JObject) = AttachmentDraft(AttachmentDraftId(str(o,"id")),str(o,"name"),str(o,"mediaType"),
        JsonFields.long(o,"size") ?: 0L,str(o,"sha256"),AttachmentState.valueOf(str(o,"state")))
    @Synchronized override fun save(checkpoint: WorkbenchCheckpoint) {
        if (checkpoint.threads.isNotEmpty()) lastThreads = checkpoint.threads
        val s = checkpoint.submission
        documents.write("workbench",encode(mapOf("version" to 2,"threads" to lastThreads.map(::thread),"threadId" to checkpoint.threadId,
            "title" to checkpoint.title,"messages" to checkpoint.messages.filterNot { row ->
                (row.conversationId?.value ?: checkpoint.threadId)?.let { isTombstoned(it,row.id) } == true
            }.map(::message),"revisions" to checkpoint.revisions,"draft" to checkpoint.draft,
            "draftRevision" to checkpoint.draftRevision,"attachments" to checkpoint.attachments.map(::attachment),"renamed" to checkpoint.renamedThreads.toList(),
            "events" to checkpoint.eventIds.toList(),"batches" to checkpoint.batches.map { b -> mapOf("batchId" to b.batchId,"conversationId" to b.conversationId,"sealed" to b.sealed,"members" to b.messages.map { m -> mapOf("id" to m.clientMessageId.value,"text" to m.text,"attachmentIds" to m.attachmentIds,"command" to m.command?.let { parts(listOf(it)).single() }) }) },"submission" to s?.let { mapOf("text" to it.text,"ids" to it.attachmentIds,"revision" to it.revision,
                "conversationId" to it.conversationId,"clientMessageId" to it.clientMessageId) })))
    }
    @Synchronized override fun load(): WorkbenchCheckpoint? {
        val o = read("workbench") ?: return null
        val version=JsonFields.int(o,"version")
        check(version == 1 || version == 2) { "MIRROR_VERSION_UNSUPPORTED" }
        lastThreads = JsonFields.objects(o,"threads").map(::decodeThread)
        val r = JsonFields.obj(JsonFields.field(o,"revisions"))?.fields.orEmpty().associate { (k,v) -> k to ((v as? JsonValue.JNumber)?.raw?.toLongOrNull() ?: error("MIRROR_CORRUPTED")) }
        val s = JsonFields.obj(JsonFields.field(o,"submission"))?.let { SavedSubmission(str(it,"text"),JsonFields.strings(it,"ids"),JsonFields.long(it,"revision") ?: 0L,
            JsonFields.string(it,"conversationId"),str(it,"clientMessageId")) }
        val checkpoint=WorkbenchCheckpoint(lastThreads,JsonFields.string(o,"threadId"),str(o,"title"),JsonFields.objects(o,"messages").map(::decodeMessage),r,str(o,"draft"),
            JsonFields.long(o,"draftRevision") ?: 0L,JsonFields.objects(o,"attachments").map(::decodeAttachment),s,JsonFields.strings(o,"renamed").toSet(),JsonFields.strings(o,"events").toSet(),JsonFields.objects(o,"batches").map { b -> SavedBatch(str(b,"batchId"),str(b,"conversationId"),JsonFields.objects(b,"members").map { m ->
                val command=JsonFields.obj(JsonFields.field(m,"command"))?.let { MessagePart.Command(str(it,"text"),JsonFields.string(it,"catalogVersion")?.let(::CatalogVersion)) }
                OutgoingMessage(ClientMessageId(str(m,"id")),str(m,"text"),JsonFields.strings(m,"attachmentIds"),command)
            },JsonFields.bool(b,"sealed")!=false) })
        if (version == 1) migrateVersionOne(checkpoint)
        return checkpoint
    }

    private fun sendAtom(a: LocalSendAtom): Map<String,Any?> = mapOf("conversationId" to a.conversationId.value,
        "clientMessageId" to a.clientMessageId.value,"parts" to parts(a.parts),"timestamp" to a.timestamp,"messageId" to a.messageId,
        "batchId" to a.batchId,"submissionState" to a.submissionState.name,"status" to a.status?.wireValue,
        "statusRevision" to a.statusRevision,"errorCode" to a.errorCode?.wireValue,"submissionErrorCode" to a.submissionErrorCode,
        "contentRevision" to a.contentRevision,"tombstoneRevision" to a.tombstoneRevision)

    private fun decodeSendAtom(o: JsonValue.JObject) = LocalSendAtom(ConversationId(str(o,"conversationId")),
        ClientMessageId(str(o,"clientMessageId")),decodeParts(o),JsonFields.long(o,"timestamp") ?: 0L,
        JsonFields.string(o,"messageId"),JsonFields.string(o,"batchId"),LocalSubmissionState.valueOf(str(o,"submissionState")),
        decodeStatus(JsonFields.string(o,"status")),JsonFields.long(o,"statusRevision") ?: -1L,
        decodeAgentError(JsonFields.string(o,"errorCode")),JsonFields.string(o,"submissionErrorCode"),
        JsonFields.long(o,"contentRevision") ?: -1L,JsonFields.long(o,"tombstoneRevision"))

    private fun decodeStatus(value: String?) = value?.let { text -> AgentMessageStatus.entries.firstOrNull { it.wireValue == text }
        ?: error("MIRROR_CORRUPTED") }
    private fun decodeAgentError(value: String?) = value?.let { text -> AgentMessageErrorCode.entries.firstOrNull { it.wireValue == text }
        ?: error("MIRROR_CORRUPTED") }

    @Synchronized override fun loadSendAtoms(conversationId: String): List<LocalSendAtom> =
        read("send-atoms:$conversationId")?.let { doc ->
            check(JsonFields.int(doc,"version") == 1) { "MIRROR_VERSION_UNSUPPORTED" }
            JsonFields.objects(doc,"atoms").map(::decodeSendAtom).also { atoms ->
                check(atoms.all { it.conversationId.value == conversationId } && atoms.map { it.clientMessageId }.distinct().size == atoms.size) { "MIRROR_CORRUPTED" }
            }
        }.orEmpty()

    /** 磁盘上先到的事件修订优先，较晚的 HTTP 回执不能倒退 Agent 状态或撤销删除。 */
    @Synchronized override fun saveSendAtoms(conversationId: String, atoms: List<LocalSendAtom>) {
        require(atoms.all { it.conversationId.value == conversationId }) { "MESSAGE_CONVERSATION_CONFLICT" }
        require(atoms.map { it.clientMessageId }.distinct().size == atoms.size) { "MESSAGE_ID_CONFLICT" }
        val merged=loadSendAtoms(conversationId).associateByTo(linkedMapOf()) { it.clientMessageId }
        atoms.forEach { incoming -> merged[incoming.clientMessageId]=merged[incoming.clientMessageId]?.let { mergeAtom(it,incoming) } ?: incoming }
        merged.replaceAll { _,atom -> withDeletionBarrier(conversationId,atom) }
        val serverIds=merged.values.mapNotNull { it.messageId }
        check(serverIds.all { it.isNotBlank() } && serverIds.distinct().size == serverIds.size) { "MESSAGE_ID_CONFLICT" }
        documents.write("send-atoms:$conversationId",encode(mapOf("version" to 1,"atoms" to merged.values.map(::sendAtom))))
    }

    private fun mergeAtom(existing: LocalSendAtom,incoming: LocalSendAtom): LocalSendAtom {
        check(existing.messageId == null || incoming.messageId == null || existing.messageId == incoming.messageId) { "MESSAGE_ID_CONFLICT" }
        val content=if (existing.contentRevision >= 0L && existing.contentRevision >= incoming.contentRevision) existing else incoming
        val status=if (existing.status != null && existing.statusRevision >= incoming.statusRevision) existing else incoming
        val contentRevision=maxOf(existing.contentRevision,incoming.contentRevision)
        val tombstone=listOfNotNull(existing.tombstoneRevision,incoming.tombstoneRevision).maxOrNull()?.takeIf { it >= contentRevision }
        val submission=if (existing.submissionState == LocalSubmissionState.ACCEPTED) LocalSubmissionState.ACCEPTED else incoming.submissionState
        return incoming.copy(
            messageId=incoming.messageId ?: existing.messageId,batchId=incoming.batchId ?: existing.batchId,
            timestamp=existing.timestamp.takeIf { it > 0L } ?: incoming.timestamp,
            parts=if (tombstone != null) emptyList() else if (content.contentRevision >= 0L) content.parts else content.parts.ifEmpty { existing.parts },
            contentRevision=contentRevision,tombstoneRevision=tombstone,status=status.status,statusRevision=status.statusRevision,
            errorCode=status.errorCode,submissionState=submission,
            submissionErrorCode=if (submission == LocalSubmissionState.FAILED) incoming.submissionErrorCode else null,
        )
    }

    private fun legacySent(conversationId: String): List<TimelineMessage> =
        read("legacy-sent:$conversationId")?.let { JsonFields.objects(it,"messages").map(::decodeMessage) }.orEmpty()

    private fun saveLegacySent(conversationId: String,messages: List<TimelineMessage>) =
        documents.write("legacy-sent:$conversationId",encode(mapOf("messages" to messages.map(::message))))

    /** 旧版只在最后写入 v2 检查点；中途失败时再次读取仍会按明确身份继续迁移。 */
    private fun migrateVersionOne(checkpoint: WorkbenchCheckpoint) {
        val migrated=linkedMapOf<String,LinkedHashMap<ClientMessageId,LocalSendAtom>>()
        val legacy=linkedMapOf<String,LinkedHashMap<String,TimelineMessage>>()
        fun remember(atom: LocalSendAtom) { migrated.getOrPut(atom.conversationId.value) { linkedMapOf() }.putIfAbsent(atom.clientMessageId,atom) }
        checkpoint.messages.filter { it.sender == "user" }.forEach { row ->
            val conversationId=row.conversationId?.value ?: checkpoint.threadId ?: return@forEach
            val clientId=row.clientMessageId
            if (clientId == null) {
                legacy.getOrPut(conversationId) { linkedMapOf() }[row.id]=row.copy(conversationId=ConversationId(conversationId))
            } else {
                val localOnly=row.id == "local_${clientId.value}"
                remember(LocalSendAtom(ConversationId(conversationId),clientId,row.parts,row.timestamp,
                    row.id.takeUnless { localOnly },row.batchId,
                    if (localOnly) LocalSubmissionState.OUTCOME_UNKNOWN else LocalSubmissionState.ACCEPTED,
                    AgentMessageStatus.entries.firstOrNull { it.wireValue == row.state },
                    errorCode=AgentMessageErrorCode.entries.firstOrNull { it.wireValue == row.errorCode },
                    contentRevision=checkpoint.revisions[row.id] ?: -1L))
            }
        }
        checkpoint.submission?.let { submission ->
            submission.conversationId?.let { conversationId ->
                val attachmentById=checkpoint.attachments.associateBy { it.id.value }
                val submissionParts=buildList<MessagePart> {
                    if (submission.text.isNotEmpty()) add(MessagePart.Text(submission.text))
                    submission.attachmentIds.forEach { id ->
                        val attachment=attachmentById[id]
                        add(MessagePart.Attachment(AttachmentDraftId(id),attachment?.filename.orEmpty(),attachment?.mediaType.orEmpty()))
                    }
                }
                remember(LocalSendAtom(ConversationId(conversationId),ClientMessageId(submission.clientMessageId),submissionParts,0L,
                    submissionState=LocalSubmissionState.OUTCOME_UNKNOWN))
            }
        }
        checkpoint.batches.forEach { batch -> batch.messages.forEach { member ->
            remember(LocalSendAtom(ConversationId(batch.conversationId),member.clientMessageId,
                listOfNotNull(member.text.takeIf { it.isNotEmpty() }?.let { MessagePart.Text(it) },member.command),0L,
                batchId=batch.batchId,submissionState=LocalSubmissionState.OUTCOME_UNKNOWN))
        } }
        migrated.forEach { (id,atoms) ->
            val existing=loadSendAtoms(id).associateByTo(linkedMapOf()) { it.clientMessageId }
            atoms.forEach { (clientId,old) -> existing.putIfAbsent(clientId,old) }
            saveSendAtoms(id,existing.values.toList())
        }
        legacy.forEach { (id,rows) ->
            val existing=legacySent(id).associateByTo(linkedMapOf()) { it.id }
            rows.forEach { (messageId,row) -> if (!isTombstoned(id,messageId)) existing.putIfAbsent(messageId,row) }
            saveLegacySent(id,existing.values.toList())
        }
        save(checkpoint)
    }

    private data class ContentRevision(val revision: Long,val tombstoned: Boolean,val deletedAttachmentIds: List<String> = emptyList())
    private fun contentRevision(id: String,messageId: String): ContentRevision = read("event-revision:$id:$messageId")?.let {
        ContentRevision(JsonFields.long(it,"revision") ?: -1L,JsonFields.bool(it,"tombstoned") == true,JsonFields.strings(it,"deletedAttachmentIds"))
    } ?: ContentRevision(-1L,false)
    private fun isTombstoned(id: String,messageId: String) = contentRevision(id,messageId).tombstoned
    @Synchronized fun deletedMediaIds(conversationId: String,messageId: String): List<String> =
        contentRevision(conversationId,messageId).let { if (it.tombstoned) it.deletedAttachmentIds else emptyList() }
    private fun withDeletionBarrier(conversationId: String,atom: LocalSendAtom): LocalSendAtom {
        val messageId=atom.messageId ?: return atom
        val content=contentRevision(conversationId,messageId)
        return if (content.tombstoned && content.revision >= atom.contentRevision) atom.copy(parts=emptyList(),
            contentRevision=content.revision,tombstoneRevision=content.revision) else atom
    }
    private fun saveContentRevision(id: String,messageId: String,revision: Long,tombstoned: Boolean,deletedAttachmentIds: List<String> = emptyList()) =
        documents.write("event-revision:$id:$messageId",encode(mapOf("revision" to revision,"tombstoned" to tombstoned,
            "deletedAttachmentIds" to deletedAttachmentIds)))

    private fun withoutMessage(document: JsonValue.JObject,conversationId: String,messageId: String,fallbackConversationId: String?): JsonValue.JObject =
        document.copy(fields=document.fields.map { (key,value) ->
            key to if (key == "messages" && value is JsonValue.JArray) JsonValue.JArray(value.items.filterNot { item ->
                val row=obj(item)
                str(row,"id") == messageId && (JsonFields.string(row,"conversationId") ?: fallbackConversationId) == conversationId
            }) else value
        })

    /** 删除事件按明确消息身份清除历史副本，不能把正文藏在上一次检查点或恢复快照内。 */
    private fun removeRetainedHistory(conversationId: String,messageId: String) {
        read("workbench")?.let { checkpoint ->
            val cleaned=withoutMessage(checkpoint,conversationId,messageId,JsonFields.string(checkpoint,"threadId"))
            if (cleaned != checkpoint) documents.write("workbench",encode(cleaned))
        }
        documents.keys().filter { it.startsWith("snapshot:") }.forEach { key ->
            val snapshot=read(key) ?: error("MIRROR_CORRUPTED")
            val cleaned=snapshot.copy(fields=snapshot.fields.map { (field,value) ->
                field to if (field == "timelines" && value is JsonValue.JArray) JsonValue.JArray(value.items.map { item ->
                    val page=obj(item)
                    withoutMessage(page,conversationId,messageId,str(page,"id"))
                }) else value
            })
            if (cleaned != snapshot) documents.write(key,encode(cleaned))
        }
    }

    private data class StatusRevision(val status: AgentMessageStatus,val revision: Long,val errorCode: AgentMessageErrorCode?)
    private fun statusRevision(id: String,messageId: String): StatusRevision? = read("message-status:$id:$messageId")?.let {
        StatusRevision(decodeStatus(str(it,"status"))!!,JsonFields.long(it,"revision") ?: -1L,decodeAgentError(JsonFields.string(it,"errorCode")))
    }
    private fun withDurableStatus(id: String,row: TimelineMessage): TimelineMessage = statusRevision(id,row.id)?.let {
        row.copy(state=it.status.wireValue,errorCode=it.errorCode?.wireValue)
    } ?: row

    /** 纯远端基线和本地发送事实只在读取时合并，空快照不删除发送事实。 */
    @Synchronized fun mergeTimeline(conversationId: String,page: TimelinePage): TimelinePage {
        val rows=legacySent(conversationId).associateByTo(linkedMapOf()) { it.id }
        page.messages.forEach { rows[it.id]=it }
        val visible=rows.values.filterNot { isTombstoned(conversationId,it.id) }.map { withDurableStatus(conversationId,it) }
        return page.copy(messages=mergeLocalSendAtoms(visible,loadSendAtoms(conversationId).map { withDeletionBarrier(conversationId,it) }))
    }

    @Synchronized fun reconcileQuery(conversationId: String,clientMessageId: ClientMessageId,row: TimelineMessage): TimelineMessage {
        row.conversationId?.let { check(it.value == conversationId) { "MESSAGE_CONVERSATION_CONFLICT" } }
        check(row.clientMessageId == null || row.clientMessageId == clientMessageId) { "MESSAGE_ID_CONFLICT" }
        val atoms=loadSendAtoms(conversationId)
        val original=atoms.firstOrNull { it.clientMessageId == clientMessageId }
        val identified=row.copy(conversationId=ConversationId(conversationId),clientMessageId=clientMessageId)
        if (original == null) return identified
        val accepted=original.accepted(row.id,row.batchId ?: original.batchId).copy(
            parts=if (original.contentRevision >= 0L || row.parts.isEmpty()) original.parts else row.parts,
            timestamp=original.timestamp.takeIf { it > 0L } ?: row.timestamp,
        )
        saveSendAtoms(conversationId,atoms.map { if (it.clientMessageId == clientMessageId) accepted else it })
        val merged=mergeLocalSendAtoms(listOf(identified.copy(parts=accepted.parts)),loadSendAtoms(conversationId).filter { it.clientMessageId == clientMessageId })
        return merged.firstOrNull() ?: identified.copy(parts=emptyList(),state="TOMBSTONED")
    }
    fun wipe() = documents.erase()
    @Synchronized fun saveThreads(threads: List<ConversationSummary>) { lastThreads = threads; documents.write("threads",encode(mapOf("threads" to threads.map(::thread)))) }
    @Synchronized fun threads(): List<ConversationSummary>? = read("threads")?.let { JsonFields.objects(it,"threads").map(::decodeThread) }
    private fun writeTimeline(id: String,page: TimelinePage) = documents.write("timeline:$id",encode(mapOf("revision" to page.snapshotRevision,"messages" to page.messages.map(::message))))
    @Synchronized fun saveTimeline(id: String,page: TimelinePage) = writeTimeline(id,
        page.copy(messages=page.messages.filterNot { isTombstoned(id,it.id) }))
    @Synchronized fun timeline(id: String): TimelinePage? = read("timeline:$id")?.let { TimelinePage(JsonFields.objects(it,"messages").map(::decodeMessage),null,JsonFields.long(it,"revision")) }
    @Synchronized fun apply(event: VerifiedConversationEvent) {
        when(event) {
            is VerifiedConversationEvent.TimelineUpsert -> event.message.conversationId?.value?.let { id ->
                val revision=contentRevision(id,event.message.id)
                if (event.revision < revision.revision || (event.revision == revision.revision && revision.tombstoned)) return@let
                val old = timeline(id) ?: TimelinePage(emptyList(),null)
                val atoms=loadSendAtoms(id)
                val existing=atoms.firstOrNull { it.messageId == event.message.id || it.clientMessageId == event.message.clientMessageId }
                check(existing == null || event.message.clientMessageId == null || existing.clientMessageId == event.message.clientMessageId) { "MESSAGE_ID_CONFLICT" }
                if (event.revision < (existing?.contentRevision ?: -1L) ||
                    (event.revision == existing?.tombstoneRevision)) return@let
                val clientId=existing?.clientMessageId ?: event.message.clientMessageId
                if (event.message.sender == "user" && clientId != null) {
                    val status=statusRevision(id,event.message.id)
                    val atom=(existing ?: LocalSendAtom(ConversationId(id),clientId,event.message.parts,event.message.timestamp))
                        .accepted(event.message.id,event.message.batchId ?: existing?.batchId).copy(
                            parts=event.message.parts,contentRevision=event.revision,tombstoneRevision=null,
                            status=status?.status ?: existing?.status,statusRevision=status?.revision ?: existing?.statusRevision ?: -1L,
                            errorCode=if (status != null) status.errorCode else existing?.errorCode,
                        )
                    saveSendAtoms(id,atoms.filterNot { it.clientMessageId == clientId } + atom)
                }
                val legacy=legacySent(id)
                if (legacy.any { it.id == event.message.id }) saveLegacySent(id,legacy.map {
                    if (it.id == event.message.id) withDurableStatus(id,event.message) else it
                })
                writeTimeline(id,old.copy(messages = (old.messages.filterNot { it.id == event.message.id } + withDurableStatus(id,event.message)).sortedBy { it.timestamp }))
                saveContentRevision(id,event.message.id,event.revision,false)
            }
            is VerifiedConversationEvent.TimelineTombstoned -> event.conversationId?.value?.let { id ->
                val content=contentRevision(id,event.messageId)
                if (event.revision < content.revision) return@let
                val atoms=loadSendAtoms(id)
                if (event.revision < (atoms.firstOrNull { it.messageId == event.messageId }?.contentRevision ?: -1L)) return@let
                val baseline=timeline(id)
                val legacy=legacySent(id)
                val mediaIds=(baseline?.messages.orEmpty().filter { it.id == event.messageId }.flatMap { it.parts } +
                    legacy.filter { it.id == event.messageId }.flatMap { it.parts } +
                    atoms.filter { it.messageId == event.messageId }.flatMap { it.parts })
                    .filterIsInstance<MessagePart.Attachment>().map { it.draftId.value }
                // 删除屏障与附件身份先落盘，后续正文清理失败时仍可按原事件重放。
                saveContentRevision(id,event.messageId,event.revision,true,(content.deletedAttachmentIds + mediaIds).distinct())
                if (atoms.any { it.messageId == event.messageId }) saveSendAtoms(id,atoms.map {
                    if (it.messageId == event.messageId) it.copy(parts=emptyList(),contentRevision=event.revision,tombstoneRevision=event.revision) else it
                })
                if (legacy.any { it.id == event.messageId }) saveLegacySent(id,legacy.filterNot { it.id == event.messageId })
                baseline?.let { page -> saveTimeline(id,page.copy(messages=page.messages.filterNot { it.id == event.messageId })) }
                removeRetainedHistory(id,event.messageId)
            }
            is VerifiedConversationEvent.MessageStatus -> {
                val id=event.conversationId.value
                if (event.revision < (statusRevision(id,event.messageId)?.revision ?: -1L)) return
                val atoms=loadSendAtoms(id)
                val existing=atoms.firstOrNull { it.clientMessageId == event.clientMessageId || it.messageId == event.messageId }
                check(existing == null || existing.clientMessageId == event.clientMessageId) { "MESSAGE_ID_CONFLICT" }
                if (event.revision < (existing?.statusRevision ?: -1L)) return
                val baseline=timeline(id)
                val knownRow=baseline?.messages?.firstOrNull { it.id == event.messageId && it.sender == "user" }
                if (existing != null || knownRow != null) {
                    val atom=(existing ?: LocalSendAtom(ConversationId(id),event.clientMessageId,knownRow!!.parts,knownRow.timestamp))
                        .accepted(event.messageId).copy(status=event.status,statusRevision=event.revision,errorCode=event.errorCode)
                    saveSendAtoms(id,atoms.filterNot { it.clientMessageId == atom.clientMessageId } + atom)
                }
                baseline?.let { page -> saveTimeline(id,page.copy(messages=page.messages.map {
                    if (it.id == event.messageId) it.copy(state=event.status.wireValue,errorCode=event.errorCode?.wireValue,
                        clientMessageId=event.clientMessageId) else it
                })) }
                documents.write("message-status:$id:${event.messageId}",encode(mapOf("status" to event.status.wireValue,
                    "revision" to event.revision,"errorCode" to event.errorCode?.wireValue)))
            }
            is VerifiedConversationEvent.TitleUpdated -> threads()?.let { list -> saveThreads(list.map { if (it.id == event.conversationId) it.copy(title=event.newTitle) else it }) }
            else -> Unit
        }
    }
    /** 原子切换一个加密快照；重建失败时，前一代快照仍然可读。 */
    @Synchronized fun installBaseline(threads: List<ConversationSummary>, pages: Map<String,TimelinePage>, cursor: String) {
        val generation = "snapshot:$cursor"
        val cleanPages=pages.mapValues { (id,page) -> page.copy(messages=page.messages.filterNot { isTombstoned(id,it.id) }) }
        documents.write(generation,encode(mapOf("threads" to threads.map(::thread),"timelines" to cleanPages.map { (id,page) ->
            mapOf("id" to id,"revision" to page.snapshotRevision,"messages" to page.messages.map(::message)) },"cursor" to cursor)))
        documents.write("baseline",encode(mapOf("generation" to generation)))
        saveThreads(threads)
        // 写入展开副本时若进程终止，整代快照仍然是恢复依据。
        cleanPages.forEach { (id,page) -> saveTimeline(id,page) }
        documents.keys().filter { it.startsWith("timeline:") && it.removePrefix("timeline:") !in cleanPages }.forEach(documents::delete)
        // 删除屏障与状态修订属于发送事实，不能随空的历史快照丢弃。
        documents.keys().filter { it.startsWith("snapshot:") && it != generation }.forEach(documents::delete)
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
    override suspend fun queryMessage(conversationId:String,clientMessageId:ClientMessageId):TimelineMessage? =
        (delegate as? MessageOutcomeQuery)?.queryMessage(conversationId,clientMessageId)?.let { mirror.reconcileQuery(conversationId,clientMessageId,it) }
    override suspend fun listConversations(scope: ConversationScope, page: PageRequest): ConversationPage = try {
        delegate.listConversations(scope,page).also { if (it.nextCursor == null && page.cursor == null) mirror.saveThreads(it.conversations) }
    } catch (c: CancellationException) { throw c } catch (e: java.io.IOException) { mirror.threads()?.let { ConversationPage(it,null) } ?: throw e }
    override suspend fun timeline(conversationId: String, page: PageRequest): TimelinePage = try {
        if (page.cursor == null) {
            val baseline=delegate.completeTimeline(conversationId)
            mirror.saveTimeline(conversationId,baseline)
            mirror.mergeTimeline(conversationId,baseline)
        }
        else delegate.timeline(conversationId,page)
    } catch (c: CancellationException) { throw c } catch (e: java.io.IOException) {
        if (page.cursor == null) {
            val baseline=mirror.timeline(conversationId)
            val restored=mirror.mergeTimeline(conversationId,baseline ?: TimelinePage(emptyList(),null))
            if (baseline != null || restored.messages.isNotEmpty() || mirror.loadSendAtoms(conversationId).isNotEmpty()) restored
            else throw e
        } else throw e
    }
    override fun observeEvents(scope: ConversationScope) = delegate.observeEvents(scope).map { event ->
        mirror.apply(event)
        if (event is VerifiedConversationEvent.TimelineTombstoned) event.conversationId?.value?.let { id ->
            mirror.deletedMediaIds(id,event.messageId).forEach { media?.remove(it) }
        }
        event
    }
}
