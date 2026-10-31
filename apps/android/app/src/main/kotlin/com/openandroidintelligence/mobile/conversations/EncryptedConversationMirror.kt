package com.openandroidintelligence.mobile.conversations

import android.content.Context
import com.openandroidintelligence.mobile.plugins.EncryptedDocuments
import com.openandroidintelligence.conversation.model.*
import com.openandroidintelligence.conversation.ports.*
import com.openandroidintelligence.conversation.state.*
import com.openandroidintelligence.gateway.schema.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.openandroidintelligence.encrypted.store.AndroidKeystoreOutboxKeyProvider
import java.io.File
import java.util.UUID

/** 加密范围包含 Gateway 身份；不同宿主的同名账号不会共用文档。 */
class EncryptedConversationMirror(context: Context, private val scope: ConversationScope,
    keyProvider:com.openandroidintelligence.encrypted.store.AesGcmKeyProvider?=null,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO) : AwaitableWorkbenchPersistence, ConversationManagementOperationStore {
    private val documentScope="conversation-mirror-v1:"+Json.canonical(Json.of(listOf(scope.profileId,scope.gatewayId,scope.accountId,scope.installId)))
    private val scopeHash = SqliteConversationRecords.digest(documentScope)
    private val managementLock = managementLockForScope(scopeHash)
    private val legacyKey = keyProvider ?: AndroidKeystoreOutboxKeyProvider("oai_documents_$scopeHash")
    private val legacyDirectory = File(context.noBackupFilesDir, "private-documents/$scopeHash")
    private val documents = SqliteConversationRecords(context, scopeHash,
        keyProvider ?: AndroidKeystoreOutboxKeyProvider("open_android_intelligence_mirror_v1_$scopeHash"),
        EncryptedDocuments(context,documentScope,legacyKey))
    private var lastThreads: List<ConversationSummary> = emptyList()
    private val mediaTransitions = Mutex()
    private companion object {
        private val managementLocks = mutableMapOf<String, java.lang.ref.WeakReference<Mutex>>()
        @Synchronized fun managementLockForScope(scope: String): Mutex {
            managementLocks.entries.removeAll { it.value.get() == null }
            return managementLocks[scope]?.get() ?: Mutex().also {
                managementLocks[scope] = java.lang.ref.WeakReference(it)
            }
        }
    }
    init {
        documents.transaction {
            load()?.let { checkpoint ->
                checkpoint.messages.filter { it.sender == "assistant" }.groupBy { it.conversationId?.value ?: checkpoint.threadId }
                    .forEach { (id,rows) -> id?.let { saveTimeline(it,TimelinePage(rows,null)) } }
            }
        }
        // 已提交的新库是唯一写入权威；旧文档只移入复核目录，失败时不触碰原文件。
        if (legacyDirectory.isDirectory) {
            val retained = File(legacyDirectory.parentFile, "$scopeHash-migrated-${UUID.randomUUID()}")
            check(legacyDirectory.renameTo(retained)) { "MIRROR_MIGRATION_CLEANUP_FAILED" }
            if (keyProvider == null) legacyKey.delete()
        }
    }
    private fun obj(value: JsonValue?) = JsonFields.obj(value) ?: error("MIRROR_CORRUPTED")
    private fun str(o: JsonValue.JObject, key: String) = JsonFields.string(o,key) ?: error("MIRROR_CORRUPTED")
    private fun encode(value: Any?) = Json.canonical(Json.of(value)).toByteArray()
    private fun read(key: String) = documents.read(key)?.let { obj(Json.parse(it.decodeToString())) }
    private fun decodeManagementOperation(value: JsonValue.JObject): PendingConversationManagementOperation {
        val id = str(value,"conversationId")
        val intent = when (str(value,"operation")) {
            "archive" -> ConversationManagementIntent.Archive(id, JsonFields.bool(value,"archived") ?: error("MIRROR_CORRUPTED"))
            "delete" -> ConversationManagementIntent.Delete(id)
            else -> error("MIRROR_CORRUPTED")
        }
        return PendingConversationManagementOperation(str(value,"requestId").also {
            check(com.openandroidintelligence.gateway.http.GatewayHttpClient.CURSOR_ALPHABET.matches(it)) { "MIRROR_CORRUPTED" }
        }, intent)
    }
    private fun pendingManagementOperation(id: String): PendingConversationManagementOperation? =
        read("management-operation:$id")?.let(::decodeManagementOperation)?.also {
            check(it.intent.conversationId == id) { "MIRROR_CORRUPTED" }
        }
    override suspend fun <T> withManagementLock(block: suspend () -> T): T = managementLock.withLock { block() }
    override suspend fun pendingManagementOperations(): List<PendingConversationManagementOperation> = withContext(ioDispatcher) {
        documents.transaction {
            documents.keys("management-operation").sorted().map { key ->
                pendingManagementOperation(key.removePrefix("management-operation:")) ?: error("MIRROR_CORRUPTED")
            }
        }
    }
    override suspend fun reserveManagementOperation(intent: ConversationManagementIntent): PendingConversationManagementOperation = withContext(ioDispatcher) {
        documents.transaction {
            val pending = pendingManagementOperation(intent.conversationId)
            if (pending != null) {
                check(pending.intent == intent) { "CONVERSATION_MANAGEMENT_PENDING" }
                pending
            } else {
                val operation = PendingConversationManagementOperation("management_${UUID.randomUUID()}", intent)
                val archived = (intent as? ConversationManagementIntent.Archive)?.archived
                documents.write("management-operation:${intent.conversationId}", encode(mapOf(
                    "requestId" to operation.requestId, "conversationId" to intent.conversationId,
                    "operation" to if (intent is ConversationManagementIntent.Archive) "archive" else "delete", "archived" to archived)))
                operation
            }
        }.also { documents.flush() }
    }
    override suspend fun finishManagementOperation(operation: PendingConversationManagementOperation, confirmed: Boolean) = withContext(ioDispatcher) {
        synchronized(this@EncryptedConversationMirror) {
            documents.transaction {
                val intent = operation.intent
                check(pendingManagementOperation(intent.conversationId) == operation) { "CONVERSATION_MANAGEMENT_PENDING" }
                if (confirmed) when (intent) {
                    is ConversationManagementIntent.Archive -> archiveConversation(intent.conversationId, intent.archived)
                    is ConversationManagementIntent.Delete -> deleteConversation(intent.conversationId)
                }
                documents.delete("management-operation:${intent.conversationId}")
            }
        }
        documents.flush()
    }
    private fun thread(t: ConversationSummary) = mapOf("id" to t.id.value,"title" to t.title,"updatedAt" to t.updatedAt,"archived" to t.archived)
    private fun decodeThread(o: JsonValue.JObject) = ConversationSummary(ConversationId(str(o,"id")),str(o,"title"),JsonFields.long(o,"updatedAt") ?: 0L,JsonFields.bool(o,"archived") ?: false)
    private fun organization(value: ConversationOrganization) = mapOf("revision" to value.revision,"sortMode" to value.sortMode.wireValue,
        "customOrder" to value.customOrder,"folders" to value.folders.map { mapOf("folderId" to it.folderId,"name" to it.name) },"assignments" to value.assignments)
    private fun decodeOrganization(value: JsonValue.JObject) = ConversationOrganization(
        JsonFields.long(value,"revision") ?: 0L,
        ConversationSortMode.entries.firstOrNull { it.wireValue == JsonFields.string(value,"sortMode") } ?: error("MIRROR_CORRUPTED"),
        JsonFields.strings(value,"customOrder"),JsonFields.objects(value,"folders").map { ConversationFolder(str(it,"folderId"),str(it,"name")) },
        JsonFields.obj(JsonFields.field(value,"assignments"))?.fields.orEmpty().associate { (id,folder) -> id to ((folder as? JsonValue.JString)?.value ?: error("MIRROR_CORRUPTED")) })
    @Synchronized fun organization(): ConversationOrganization = read("organization")?.let(::decodeOrganization) ?: ConversationOrganization()
    @Synchronized fun saveOrganization(value: ConversationOrganization) {
        if (value.revision < organization().revision) return
        documents.write("organization",encode(organization(value.copy(customOrder=value.customOrder.filterNot(::isConversationDeleted),
            assignments=value.assignments.filterKeys { !isConversationDeleted(it) }))))
    }
    @Synchronized fun isConversationDeleted(id: String): Boolean = read("deleted-conversation:$id") != null
    private fun cancellations(o: JsonValue.JObject) = JsonFields.objects(o,"cancellations").map { SavedCancellation(str(it,"conversationId"),str(it,"generationId")) }
    private fun messageGenerations(o: JsonValue.JObject) = JsonFields.objects(o,"messageGenerations").map {
        SavedMessageGeneration(str(it,"conversationId"),str(it,"messageId"),str(it,"generationId"),JsonFields.long(it,"revision") ?: -1L)
    }
    /** SSE commits its cursor before delivery to a controller. Keep these facts in that same transaction. */
    private fun saveGenerationFacts(confirmedCancellations: List<SavedCancellation> = emptyList(), associations: List<SavedMessageGeneration> = emptyList()) {
        val old=read("generation-facts")
        val knownCancellations=old?.let(::cancellations).orEmpty()
        val knownLinks=old?.let(::messageGenerations).orEmpty()
        val cancelled=(knownCancellations+confirmedCancellations).distinct().filterNot { isConversationDeleted(it.conversationId) }
        val links=linkedMapOf<Pair<String,String>,SavedMessageGeneration>()
        (knownLinks+associations).filterNot { isConversationDeleted(it.conversationId) }.forEach { link ->
            val key=link.conversationId to link.messageId
            if (link.revision > (links[key]?.revision ?: Long.MIN_VALUE)) links[key]=link
        }
        if (cancelled == knownCancellations && links.values.toList() == knownLinks) return
        documents.write("generation-facts",encode(mapOf(
            "cancellations" to cancelled.map { mapOf("conversationId" to it.conversationId,"generationId" to it.generationId) },
            "messageGenerations" to links.values.map { mapOf("conversationId" to it.conversationId,"messageId" to it.messageId,"generationId" to it.generationId,"revision" to it.revision) })))
    }
    private fun sanitize(checkpoint: WorkbenchCheckpoint): WorkbenchCheckpoint {
        val removed=checkpoint.threadId?.let(::isConversationDeleted) == true
        return checkpoint.copy(threads=checkpoint.threads.filterNot { isConversationDeleted(it.id.value) },
            threadId=checkpoint.threadId.takeUnless { removed }, title=if (removed) "" else checkpoint.title,
            messages=checkpoint.messages.filterNot { row -> (row.conversationId?.value ?: checkpoint.threadId)?.let(::isConversationDeleted) == true },
            revisions=if (removed) emptyMap() else checkpoint.revisions,
            submission=checkpoint.submission?.takeUnless { it.conversationId?.let(::isConversationDeleted) == true },
            batches=checkpoint.batches.filterNot { isConversationDeleted(it.conversationId) },
            cancellations=checkpoint.cancellations.filterNot { isConversationDeleted(it.conversationId) },
            messageGenerations=checkpoint.messageGenerations.filterNot { isConversationDeleted(it.conversationId) },
            pendingFolderAssignments=checkpoint.pendingFolderAssignments.filterNot { isConversationDeleted(it.conversationId) },
            deletedThreadIds=checkpoint.deletedThreadIds+documents.keys().filter { it.startsWith("deleted-conversation:") }.map { it.removePrefix("deleted-conversation:") })
    }
    private fun parts(value: List<MessagePart>) = value.map { p -> when(p) {
        is MessagePart.Text -> mapOf("type" to "text","text" to p.value)
        is MessagePart.Attachment -> mapOf("type" to "attachment","id" to p.draftId.value,"name" to p.filename,"mediaType" to p.mediaType,
            "sizeBytes" to p.sizeBytes,"sha256" to p.sha256)
        is MessagePart.Command -> mapOf("type" to "command","text" to p.rawText,"catalogVersion" to p.catalogVersion?.value)
    } }
    private fun decodeParts(o: JsonValue.JObject) = JsonFields.objects(o,"parts").map { p -> when(str(p,"type")) {
        "text" -> MessagePart.Text(str(p,"text"))
        "attachment" -> MessagePart.Attachment(AttachmentDraftId(str(p,"id")),str(p,"name"),str(p,"mediaType"),
            JsonFields.long(p,"sizeBytes"),JsonFields.string(p,"sha256"))
        "command" -> MessagePart.Command(str(p,"text"),JsonFields.string(p,"catalogVersion")?.let(::CatalogVersion))
        else -> error("MIRROR_CORRUPTED")
    } }
    private fun message(m: TimelineMessage): Map<String,Any?> = mapOf("id" to m.id,"sender" to m.sender,"parts" to parts(m.parts),
        "timestamp" to m.timestamp,"state" to m.state,"conversationId" to m.conversationId?.value,"errorCode" to m.errorCode,
        "batchId" to m.batchId,"clientMessageId" to m.clientMessageId?.value,"localSubmissionFailure" to m.localSubmissionFailure,
        "contentRevision" to m.contentRevision,"generationId" to m.generationId,
        "executionSteps" to m.executionSteps.map { step -> mapOf("stepId" to step.stepId,"eventId" to step.eventId,
            "toolName" to step.toolName,"arguments" to step.arguments,"output" to step.output,"status" to step.status.name,"revision" to step.revision) })
    private fun decodeMessage(o: JsonValue.JObject) = TimelineMessage(str(o,"id"),str(o,"sender"),decodeParts(o),
        JsonFields.long(o,"timestamp") ?: 0L,str(o,"state"),JsonFields.string(o,"conversationId")?.let(::ConversationId),
        JsonFields.string(o,"errorCode"),JsonFields.string(o,"batchId"),JsonFields.string(o,"clientMessageId")?.let(::ClientMessageId),
        JsonFields.string(o,"localSubmissionFailure"),JsonFields.long(o,"contentRevision"),JsonFields.string(o,"generationId"),
        executionSteps=JsonFields.objects(o,"executionSteps").map { step -> com.openandroidintelligence.conversation.model.NativeExecutionStep(
            str(step,"stepId"),str(step,"eventId"),str(step,"toolName"),str(step,"arguments"),JsonFields.string(step,"output"),
            com.openandroidintelligence.conversation.model.NativeExecutionStatus.valueOf(str(step,"status")),JsonFields.long(step,"revision") ?: 0) })
    private fun attachment(a: AttachmentDraft) = mapOf("id" to a.id.value,"name" to a.filename,"mediaType" to a.mediaType,
        "size" to a.sizeBytes,"sha256" to a.sha256,"state" to a.state.name)
    private fun decodeAttachment(o: JsonValue.JObject) = AttachmentDraft(AttachmentDraftId(str(o,"id")),str(o,"name"),str(o,"mediaType"),
        JsonFields.long(o,"size") ?: 0L,str(o,"sha256"),AttachmentState.valueOf(str(o,"state")))
    /** 只返回有真实 generation 关联或本机未确认提交的身份；普通宿主 user 行不构成受理凭据。 */
    @Synchronized fun nativeRecoveryIdentities(conversationId: String): List<NativeRecoveryIdentity> {
        val links = read("generation-facts")?.let(::messageGenerations).orEmpty()
            .filter { it.conversationId == conversationId }.associateBy { it.messageId }
        return loadSendAtoms(conversationId).filterNot { atom -> atom.isDeleted ||
            atom.delivery == MessageDeliveryMode.STEER || atom.status in setOf(AgentMessageStatus.COMPLETED, AgentMessageStatus.FAILED) }
            .mapNotNull { atom ->
                val generation = (links[atom.clientMessageId.value] ?: links[atom.messageId])?.generationId
                if (generation == null && atom.submissionState != LocalSubmissionState.OUTCOME_UNKNOWN) null
                else NativeRecoveryIdentity(atom.clientMessageId.value, generation)
            }
    }

    @Synchronized fun nativeCreation(requestId: String): NativeCreationReceipt? = read("native-creation:$requestId")?.let { value ->
        NativeCreationReceipt(com.openandroidintelligence.gateway.agents.NativeAgentSession(str(value,"id"),
            JsonFields.string(value,"title"),JsonFields.string(value,"updatedAt"),JsonFields.bool(value,"archived") == true),
            str(value,"eventId"),JsonFields.long(value,"occurredAt") ?: error("MIRROR_CORRUPTED"))
    }

    /** 权威创建与列表增量同事务保存；通知尚未投递时重启仍能用原 requestId 读取。 */
    @Synchronized fun commitNativeCreation(requestId: String, receipt: NativeCreationReceipt) {
        documents.transaction {
            val session = receipt.session
            val existing = nativeCreation(requestId)
            check(existing == null || existing.session.id == session.id) { "NATIVE_CREATE_IDENTITY_CONFLICT" }
            documents.write("native-creation:$requestId", encode(mapOf("id" to session.id,"title" to session.title,
                "updatedAt" to session.updatedAt,"archived" to session.archived,
                "eventId" to receipt.eventId,"occurredAt" to receipt.occurredAt)))
            val current = threads().orEmpty()
            if (current.none { it.id.value == session.id } && !isConversationDeleted(session.id)) {
                saveThreads(current + ConversationSummary(ConversationId(session.id), session.title?.takeIf(String::isNotBlank) ?: "新对话",
                    receipt.occurredAt, session.archived))
            }
        }
        documents.flush()
    }

    @Synchronized fun commitNativeEvents(events: List<VerifiedConversationEvent>) {
        documents.transaction { events.forEach(::apply) }
        documents.flush()
    }

    @Synchronized fun nativeApprovals(conversationId: String): List<com.openandroidintelligence.gateway.agents.NativeEventApproval> =
        read("native-approvals:$conversationId")?.let { saved ->
            JsonFields.objects(saved, "items").map { item ->
                com.openandroidintelligence.gateway.agents.NativeEventApproval(str(item, "type"), obj(JsonFields.field(item, "approval")))
            }
        }.orEmpty()

    @Synchronized fun commitNativeApprovals(conversationId: String,
        approvals: List<com.openandroidintelligence.gateway.agents.NativeEventApproval>) {
        documents.transaction { saveNativeApprovals(conversationId, approvals) }
        documents.flush()
    }

    private fun saveNativeApprovals(conversationId: String,
        approvals: List<com.openandroidintelligence.gateway.agents.NativeEventApproval>) {
        val retained = nativeApprovals(conversationId).toMutableList()
        for (approval in approvals) {
            val id = str(approval.approval, "approvalId")
            check(str(approval.approval, "conversationId") == conversationId) { "NATIVE_APPROVAL_SCOPE_MISMATCH" }
            val resolved = retained.any { str(it.approval, "approvalId") == id && it.type == "approval_resolved" }
            if (approval.type == "approval_requested" && resolved) continue
            retained.removeAll { str(it.approval, "approvalId") == id && it.type == approval.type }
            retained += approval
        }
        documents.write("native-approvals:$conversationId", encode(mapOf("items" to retained.map {
            mapOf("type" to it.type, "approval" to it.approval)
        })))
    }

    private fun nativeSyncKey(observer: String, conversationId: String) =
        "native-event-sync:" + SqliteConversationRecords.digest(Json.canonical(Json.of(listOf(observer, conversationId))))

    @Synchronized fun nativePendingAck(observer: String, conversationId: String): String? =
        read(nativeSyncKey(observer, conversationId))?.let { JsonFields.string(it, "pending") }
    @Synchronized fun nativeEventCursor(observer: String, conversationId: String): String? =
        read(nativeSyncKey(observer, conversationId))?.let { JsonFields.string(it, "cursor") }

    @Synchronized fun commitNativePage(observer: String, conversationId: String, events: List<VerifiedConversationEvent>,
        approvals: List<com.openandroidintelligence.gateway.agents.NativeEventApproval>, cursor: String) {
        documents.transaction {
            events.forEach(::apply)
            saveNativeApprovals(conversationId, approvals)
            documents.write(nativeSyncKey(observer, conversationId), encode(mapOf(
                "cursor" to nativeEventCursor(observer, conversationId), "pending" to cursor)))
        }
        documents.flush()
    }

    @Synchronized fun confirmNativeAck(observer: String, conversationId: String, cursor: String) {
        documents.transaction {
            check(nativePendingAck(observer, conversationId) == cursor) { "NATIVE_EVENT_OWNER_CHANGED" }
            documents.write(nativeSyncKey(observer, conversationId), encode(mapOf("cursor" to cursor)))
        }
        documents.flush()
    }

    /** 旧 native 工作台以 checkpoint 为列表来源；新权威读取接管后不再允许旧 checkpoint 覆盖。 */
    @Synchronized fun saveNativeCheckpoint(checkpoint: WorkbenchCheckpoint): Unit = documents.transaction {
        if (checkpoint.threadsHydrated && read("native-threads-authority") == null) {
            saveThreads(checkpoint.threads, checkpoint.threadsSnapshotRevision)
        }
        save(checkpoint)
    }

    suspend fun saveNativeConversations(page: ConversationPage) = withContext(ioDispatcher) {
        check(page.authoritative && page.nextCursor == null) { "NATIVE_HISTORY_INCOMPLETE" }
        synchronized(this@EncryptedConversationMirror) {
            documents.transaction {
                saveThreads(page.conversations, page.snapshotRevision)
                documents.write("native-threads-authority", encode(mapOf("version" to 1)))
            }
        }
        documents.flush()
    }

    @Synchronized override fun save(checkpoint: WorkbenchCheckpoint): Unit = documents.transaction {
        val hadGlobalHistory=threads() != null
        checkpoint.deletedThreadIds.filterNot(::isConversationDeleted).forEach(::deleteConversation)
        val checkpoint=sanitize(checkpoint)
        saveGenerationFacts(checkpoint.cancellations,checkpoint.messageGenerations)
        // A workbench may still display an older account snapshot. Only seed a
        // missing mirror here; complete remote reads own all subsequent lists.
        if (checkpoint.threadsHydrated && !hadGlobalHistory) saveThreads(checkpoint.threads,checkpoint.threadsSnapshotRevision)
        lastThreads=threads() ?: lastThreads
        saveOrganization(checkpoint.organization)
        val s = checkpoint.submission?.takeUnless { submission -> submission.conversationId?.let { id ->
            loadSendAtoms(id).any { it.clientMessageId.value == submission.clientMessageId && it.isDeleted }
        } == true }
        documents.write("workbench",encode(mapOf("version" to 3,"threads" to lastThreads.map(::thread),"threadId" to checkpoint.threadId,
            "organization" to organization(organization()),"threadsSnapshotRevision" to threadsSnapshotRevision(),"deletedThreadIds" to checkpoint.deletedThreadIds.toList(),
            "cancellations" to checkpoint.cancellations.map { mapOf("conversationId" to it.conversationId,"generationId" to it.generationId) },
            "messageGenerations" to checkpoint.messageGenerations.map { mapOf("conversationId" to it.conversationId,"messageId" to it.messageId,"generationId" to it.generationId,"revision" to it.revision) },
            "pendingFolderAssignments" to checkpoint.pendingFolderAssignments.map { mapOf("conversationId" to it.conversationId,"folderId" to it.folderId) },
            "pendingConversationCreation" to checkpoint.pendingConversationCreation?.let { mapOf("clientId" to it.clientId,"folderId" to it.folderId) },
            "title" to checkpoint.title,"messages" to checkpoint.messages.filterNot { row ->
                (row.conversationId?.value ?: checkpoint.threadId)?.let { isTombstoned(it,row.id) } == true
            }.map(::message),"revisions" to checkpoint.revisions,"draft" to checkpoint.draft,
            "draftRevision" to checkpoint.draftRevision,"attachments" to checkpoint.attachments.map(::attachment),"renamed" to checkpoint.renamedThreads.toList(),
            "events" to checkpoint.eventIds.toList(),"batches" to checkpoint.batches.map { b -> mapOf("batchId" to b.batchId,"conversationId" to b.conversationId,"sealed" to b.sealed,"members" to b.messages.map { cleanOutgoing(b.conversationId,it) }.map { m -> mapOf("id" to m.clientMessageId.value,"text" to m.text,"attachmentIds" to m.attachmentIds,"command" to m.command?.let { parts(listOf(it)).single() }) }) },"submission" to s?.let { mapOf("text" to it.text,"ids" to it.attachmentIds,"revision" to it.revision,
                "conversationId" to it.conversationId,"clientMessageId" to it.clientMessageId) })))
    }
    @Synchronized override fun load(): WorkbenchCheckpoint? = documents.transaction {
        val facts=read("generation-facts")
        val o = read("workbench") ?: return@transaction facts?.let {
            WorkbenchCheckpoint(threads().orEmpty(),null,"",emptyList(),emptyMap(),"",0,emptyList(),null,emptySet(),emptySet(),
                cancellations=cancellations(it).filterNot { fact -> isConversationDeleted(fact.conversationId) },
                messageGenerations=messageGenerations(it).filterNot { link -> isConversationDeleted(link.conversationId) })
        }
        val version=JsonFields.int(o,"version")
        check(version == 1 || version == 2 || version == 3) { "MIRROR_VERSION_UNSUPPORTED" }
        val currentThreads=threads()
        lastThreads = currentThreads ?: JsonFields.objects(o,"threads").map(::decodeThread).filterNot { isConversationDeleted(it.id.value) }
        val savedOrganization=JsonFields.obj(JsonFields.field(o,"organization"))?.let(::decodeOrganization) ?: ConversationOrganization()
        val organization=organization().takeIf { it.revision >= savedOrganization.revision } ?: savedOrganization
        val r = JsonFields.obj(JsonFields.field(o,"revisions"))?.fields.orEmpty().associate { (k,v) -> k to ((v as? JsonValue.JNumber)?.raw?.toLongOrNull() ?: error("MIRROR_CORRUPTED")) }
        val s = JsonFields.obj(JsonFields.field(o,"submission"))?.let { SavedSubmission(str(it,"text"),JsonFields.strings(it,"ids"),JsonFields.long(it,"revision") ?: 0L,
            JsonFields.string(it,"conversationId"),str(it,"clientMessageId")) }
        val checkpoint=WorkbenchCheckpoint(lastThreads,JsonFields.string(o,"threadId"),str(o,"title"),JsonFields.objects(o,"messages").map(::decodeMessage),r,str(o,"draft"),
            JsonFields.long(o,"draftRevision") ?: 0L,JsonFields.objects(o,"attachments").map(::decodeAttachment),s,JsonFields.strings(o,"renamed").toSet(),JsonFields.strings(o,"events").toSet(),JsonFields.objects(o,"batches").map { b -> SavedBatch(str(b,"batchId"),str(b,"conversationId"),JsonFields.objects(b,"members").map { m ->
                val command=JsonFields.obj(JsonFields.field(m,"command"))?.let { MessagePart.Command(str(it,"text"),JsonFields.string(it,"catalogVersion")?.let(::CatalogVersion)) }
                OutgoingMessage(ClientMessageId(str(m,"id")),str(m,"text"),JsonFields.strings(m,"attachmentIds"),command)
            },JsonFields.bool(b,"sealed")!=false) },organization=organization,
            threadsSnapshotRevision=if (currentThreads != null) threadsSnapshotRevision() else JsonFields.long(o,"threadsSnapshotRevision"),
            deletedThreadIds=JsonFields.strings(o,"deletedThreadIds").toSet(),
            cancellations=(cancellations(o)+facts?.let(::cancellations).orEmpty()).distinct(),
            messageGenerations=(facts?.let(::messageGenerations).orEmpty()+messageGenerations(o))
                .groupBy { it.conversationId to it.messageId }.values.map { links -> links.maxBy { it.revision } },
            pendingFolderAssignments=JsonFields.objects(o,"pendingFolderAssignments").map { SavedFolderAssignment(str(it,"conversationId"),str(it,"folderId")) },
            pendingConversationCreation=JsonFields.obj(JsonFields.field(o,"pendingConversationCreation"))?.let { SavedConversationCreation(str(it,"clientId"),JsonFields.string(it,"folderId")) })
        checkpoint.messages.groupBy { it.conversationId?.value ?: checkpoint.threadId }.forEach { (id,rows) ->
            id?.let { rememberSnapshotDeletions(it,TimelinePage(rows,null)) }
        }
        if (version == 1) migrateVersionOne(checkpoint)
        val latestRows = checkpoint.threadId?.let { timeline(it)?.messages }.orEmpty()
        val rows = checkpoint.messages.associateByTo(linkedMapOf()) { it.id }
        latestRows.forEach { row -> rows[row.id] = row }
        val revisions = checkpoint.revisions.toMutableMap()
        latestRows.forEach { row -> row.contentRevision?.let { revision -> revisions[row.id]=maxOf(revisions[row.id] ?: -1L,revision) } }
        sanitize(checkpoint.copy(messages=rows.values.filterNot { row ->
            row.isDeleted || (row.conversationId?.value ?: checkpoint.threadId)?.let { isTombstoned(it,row.id) } == true
        },revisions=revisions,submission=checkpoint.submission?.takeUnless { submission -> submission.conversationId?.let { id ->
            loadSendAtoms(id).any { it.clientMessageId.value == submission.clientMessageId && it.isDeleted }
        } == true },batches=checkpoint.batches.map { b -> b.copy(messages=b.messages.map { cleanOutgoing(b.conversationId,it) }) }))
    }

    private fun cleanOutgoing(conversationId: String,message: OutgoingMessage): OutgoingMessage =
        if (loadSendAtoms(conversationId).any { it.clientMessageId == message.clientMessageId && it.isDeleted })
            message.copy(text="",attachmentIds=emptyList(),command=null) else message

    private fun sendAtom(a: LocalSendAtom): Map<String,Any?> = mapOf("conversationId" to a.conversationId.value,
        "clientMessageId" to a.clientMessageId.value,"parts" to parts(a.parts),"timestamp" to a.timestamp,"messageId" to a.messageId,
        "batchId" to a.batchId,"submissionState" to a.submissionState.name,"status" to a.status?.wireValue,
        "statusRevision" to a.statusRevision,"errorCode" to a.errorCode?.wireValue,"submissionErrorCode" to a.submissionErrorCode,
        "contentRevision" to a.contentRevision,"tombstoneRevision" to a.tombstoneRevision,"snapshotDeleted" to a.snapshotDeleted,
        "delivery" to a.delivery.wireValue)

    private fun decodeSendAtom(o: JsonValue.JObject) = LocalSendAtom(ConversationId(str(o,"conversationId")),
        ClientMessageId(str(o,"clientMessageId")),decodeParts(o),JsonFields.long(o,"timestamp") ?: 0L,
        JsonFields.string(o,"messageId"),JsonFields.string(o,"batchId"),LocalSubmissionState.valueOf(str(o,"submissionState")),
        decodeStatus(JsonFields.string(o,"status")),JsonFields.long(o,"statusRevision") ?: -1L,
        decodeAgentError(JsonFields.string(o,"errorCode")),JsonFields.string(o,"submissionErrorCode"),
        JsonFields.long(o,"contentRevision") ?: -1L,JsonFields.long(o,"tombstoneRevision"),JsonFields.bool(o,"snapshotDeleted") == true,
        JsonFields.string(o,"delivery")?.let { wire ->
            MessageDeliveryMode.entries.firstOrNull { it.wireValue == wire } ?: error("MIRROR_CORRUPTED")
        } ?: MessageDeliveryMode.NORMAL)

    private fun decodeStatus(value: String?) = value?.let { text -> AgentMessageStatus.entries.firstOrNull { it.wireValue == text }
        ?: error("MIRROR_CORRUPTED") }
    private fun decodeAgentError(value: String?) = value?.let { text -> AgentMessageErrorCode.entries.firstOrNull { it.wireValue == text }
        ?: error("MIRROR_CORRUPTED") }

    @Synchronized override fun loadSendAtoms(conversationId: String): List<LocalSendAtom> =
        if (isConversationDeleted(conversationId)) emptyList() else read("send-atoms:$conversationId")?.let { doc ->
            check(JsonFields.int(doc,"version") == 1) { "MIRROR_VERSION_UNSUPPORTED" }
            JsonFields.objects(doc,"atoms").map(::decodeSendAtom).also { atoms ->
                check(atoms.all { it.conversationId.value == conversationId } && atoms.map { it.clientMessageId }.distinct().size == atoms.size) { "MIRROR_CORRUPTED" }
            }.map { withDeletionBarrier(conversationId,it) }
        }.orEmpty()

    /** 磁盘上先到的事件修订优先，较晚的 HTTP 回执不能倒退 Agent 状态或撤销删除。 */
    @Synchronized override fun saveSendAtoms(conversationId: String, atoms: List<LocalSendAtom>): Unit = documents.transaction {
        if (isConversationDeleted(conversationId)) return@transaction
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
        val snapshotDeleted=existing.snapshotDeleted || incoming.snapshotDeleted
        val submission=if (existing.submissionState == LocalSubmissionState.ACCEPTED) LocalSubmissionState.ACCEPTED else incoming.submissionState
        return incoming.copy(
            messageId=incoming.messageId ?: existing.messageId,batchId=incoming.batchId ?: existing.batchId,
            timestamp=existing.timestamp.takeIf { it > 0L } ?: incoming.timestamp,
            parts=if (snapshotDeleted || tombstone != null) emptyList() else if (content.contentRevision >= 0L) content.parts else content.parts.ifEmpty { existing.parts },
            contentRevision=contentRevision,tombstoneRevision=tombstone,snapshotDeleted=snapshotDeleted,status=status.status,statusRevision=status.statusRevision,
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

    private data class ContentRevision(val revision: Long,val tombstoned: Boolean,val deletedAttachmentIds: List<String> = emptyList(),val snapshotDeleted: Boolean = false)
    private fun contentRevision(id: String,messageId: String): ContentRevision = read("event-revision:$id:$messageId")?.let {
        ContentRevision(JsonFields.long(it,"revision") ?: -1L,JsonFields.bool(it,"tombstoned") == true,JsonFields.strings(it,"deletedAttachmentIds"),JsonFields.bool(it,"snapshotDeleted") == true)
    } ?: ContentRevision(-1L,false)
    private fun isTombstoned(id: String,messageId: String) = contentRevision(id,messageId).let { it.tombstoned || it.snapshotDeleted }
    @Synchronized fun deletedMediaIds(conversationId: String,messageId: String): List<String> =
        contentRevision(conversationId,messageId).let { if (it.tombstoned || it.snapshotDeleted) it.deletedAttachmentIds else emptyList() }
    /** 向界面投影明确的快照删除屏障，不让迟到正文事件重新进入显示缓存。 */
    private fun eventConversationId(event: VerifiedConversationEvent): String? = when(event) {
        is VerifiedConversationEvent.TimelineUpsert -> event.message.conversationId?.value
        is VerifiedConversationEvent.TimelineTombstoned -> event.conversationId?.value
        is VerifiedConversationEvent.MessageStatus -> event.conversationId.value
        is VerifiedConversationEvent.TitleUpdated -> event.conversationId.value
        is VerifiedConversationEvent.MessageAccepted -> event.conversationId?.value
        is VerifiedConversationEvent.GenerationCancelled -> event.conversationId?.value
        is VerifiedConversationEvent.ConversationsChanged -> event.conversationId?.value
        is VerifiedConversationEvent.ApprovalTextFallback -> event.conversationId.value
        is VerifiedConversationEvent.PendingDeliveryUpdated -> event.delivery.conversationId.value
        else -> null
    }
    @Synchronized fun projectEvent(event: VerifiedConversationEvent): VerifiedConversationEvent? {
        if (event !is VerifiedConversationEvent.ConversationsChanged && eventConversationId(event)?.let(::isConversationDeleted) == true) return null
        return when(event) {
        is VerifiedConversationEvent.TimelineUpsert -> event.message.conversationId?.value?.let { id ->
            val known=contentRevision(id,event.message.id)
            when {
                known.snapshotDeleted -> event.copy(message=event.message.copy(parts=emptyList(),state="DELETED"))
                event.revision < known.revision -> null
                event.revision == known.revision && known.tombstoned -> event.copy(message=event.message.copy(parts=emptyList(),state="DELETED"))
                else -> event
            }
        } ?: if (event.message.conversationId == null) event else null
        is VerifiedConversationEvent.TimelineTombstoned -> event.conversationId?.value?.let { id ->
            event.takeIf { it.revision >= contentRevision(id,event.messageId).revision }
        } ?: if (event.conversationId == null) event else null
        is VerifiedConversationEvent.MessageStatus -> event.takeIf {
            it.revision >= (statusRevision(it.conversationId.value,it.messageId)?.revision ?: -1L)
        }
        else -> event
        }
    }
    private fun withDeletionBarrier(conversationId: String,atom: LocalSendAtom): LocalSendAtom {
        val messageId=atom.messageId ?: return atom
        val content=contentRevision(conversationId,messageId)
        return if (content.snapshotDeleted || atom.snapshotDeleted) atom.copy(parts=emptyList(),snapshotDeleted=true)
        else if (content.tombstoned && content.revision >= atom.contentRevision) atom.copy(parts=emptyList(),
            contentRevision=content.revision,tombstoneRevision=content.revision) else atom
    }
    private fun saveContentRevision(id: String,messageId: String,revision: Long,tombstoned: Boolean,deletedAttachmentIds: List<String> = emptyList(),snapshotDeleted: Boolean = false) =
        documents.write("event-revision:$id:$messageId",encode(mapOf("revision" to revision,"tombstoned" to tombstoned,
            "deletedAttachmentIds" to deletedAttachmentIds,"snapshotDeleted" to (snapshotDeleted || contentRevision(id,messageId).snapshotDeleted))))

    /** 与正文、删除事实和游标共用事务；完成媒体提交后才退休这条保存意图。 */
    private fun enqueueMediaTransition(conversationId: String, messageId: String, revision: Long?, attachmentIds: List<String>, deleted: Boolean) {
        if (attachmentIds.isEmpty()) return
        val sequence = Math.addExact(read("media-transition-sequence")?.let { JsonFields.long(it, "value") } ?: 0L, 1L)
        documents.write("media-transition-sequence", encode(mapOf("value" to sequence)))
        documents.write("media-transition:" + sequence.toString().padStart(20, '0'), encode(mapOf("conversationId" to conversationId,
            "messageId" to messageId, "revision" to revision, "attachmentIds" to attachmentIds.distinct(), "deleted" to deleted)))
    }
    private fun attachmentIds(parts: List<MessagePart>) = parts.filterIsInstance<MessagePart.Attachment>().map { it.draftId.value }.distinct()
    /** 必须在恢复下载与本地已知附件之前等待完成；异常保留原记录供下次重试。 */
    suspend fun applyPendingMediaTransitions(media: HistoricalMediaPort) = withContext(ioDispatcher) {
        mediaTransitions.withLock {
            try {
                while (true) {
                    val keys = synchronized(this@EncryptedConversationMirror) { documents.keys("media-transition").sorted() }
                    if (keys.isEmpty()) break
                    // 一批只认证待办资源一次，批后再接续新提交的序号，避免逐条扫描变成平方成本。
                    for (key in keys) {
                        val pending = synchronized(this@EncryptedConversationMirror) {
                            val value = read(key) ?: error("MEDIA_TRANSITION_INVALID")
                            HistoricalMediaTransition(str(value, "conversationId"), str(value, "messageId"),
                                JsonFields.long(value, "revision"), JsonFields.strings(value, "attachmentIds"),
                                JsonFields.bool(value, "deleted") ?: error("MEDIA_TRANSITION_INVALID"))
                        }
                        media.applyTransition(pending)
                        synchronized(this@EncryptedConversationMirror) { documents.transaction { documents.delete(key) } }
                        documents.flush()
                    }
                }
                (media as? EncryptedHistoryMediaCache)?.setPendingTransitionsBlocked(false)
            } catch (failure: Throwable) {
                // 正文提交仍可读取；媒体保持封锁，直到同一持久待办被真实补做并确认。
                (media as? EncryptedHistoryMediaCache)?.setPendingTransitionsBlocked(true)
                throw failure
            }
        }
    }

    private fun withoutMessage(document: JsonValue.JObject,conversationId: String,messageId: String,fallbackConversationId: String?): JsonValue.JObject {
        val deletedClients=loadSendAtoms(conversationId).filter { it.messageId == messageId }.map { it.clientMessageId.value }.toSet()
        return document.copy(fields=document.fields.map { (key,value) ->
            key to when {
                key == "messages" && value is JsonValue.JArray -> JsonValue.JArray(value.items.filterNot { item ->
                    val row=obj(item)
                    str(row,"id") == messageId && (JsonFields.string(row,"conversationId") ?: fallbackConversationId) == conversationId
                })
                key == "submission" && value is JsonValue.JObject &&
                    (JsonFields.string(value,"conversationId") ?: fallbackConversationId) == conversationId &&
                    JsonFields.string(value,"clientMessageId") in deletedClients -> Json.of(null)
                key == "batches" && value is JsonValue.JArray -> JsonValue.JArray(value.items.map { item ->
                    val batch=obj(item)
                    if (JsonFields.string(batch,"conversationId") != conversationId) batch else batch.copy(fields=batch.fields.map { (field,members) ->
                        field to if (field == "members" && members is JsonValue.JArray) JsonValue.JArray(members.items.map { member ->
                            val message=obj(member)
                            if (JsonFields.string(message,"id") !in deletedClients) message else message.copy(fields=message.fields.map { (name,body) ->
                                name to when(name) { "text" -> Json.of(""); "attachmentIds" -> Json.of(emptyList<String>()); "command" -> Json.of(null); else -> body }
                            })
                        }) else members
                    })
                })
                else -> value
            }
        })
    }

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
    private fun withDurableStatus(id: String,row: TimelineMessage): TimelineMessage = if (row.isDeleted) row else statusRevision(id,row.id)?.let {
        row.copy(state=it.status.wireValue,errorCode=it.errorCode?.wireValue)
    } ?: row

    /** 快照没有单条删除修订，按身份持久保存删除事实，不能借用分页摘要排序。 */
    private fun rememberSnapshotDeletions(conversationId: String,page: TimelinePage) {
        val deletedRows=page.messages.filter { it.isDeleted }.associateBy { it.id }
        (page.deletedMessageIds + deletedRows.keys).forEach { messageId ->
            val row=deletedRows[messageId]
            check(row?.conversationId?.value?.let { it == conversationId } != false) { "MESSAGE_CONVERSATION_CONFLICT" }
            val atoms=loadSendAtoms(conversationId)
            val existing=atoms.firstOrNull { it.messageId == messageId || (row?.clientMessageId != null && it.clientMessageId == row.clientMessageId) }
            check(existing == null || row?.clientMessageId == null || existing.clientMessageId == row.clientMessageId) { "MESSAGE_ID_CONFLICT" }
            val baseline=timeline(conversationId)
            val legacy=legacySent(conversationId)
            val content=contentRevision(conversationId,messageId)
            val mediaIds=(row?.parts.orEmpty() + baseline?.messages.orEmpty().filter { it.id == messageId }.flatMap { it.parts } +
                legacy.filter { it.id == messageId }.flatMap { it.parts } + existing?.parts.orEmpty())
                .filterIsInstance<MessagePart.Attachment>().map { it.draftId.value }
            // 屏障先落盘；后续正文清理失败时，同一快照仍可重试完成清理。
            saveContentRevision(conversationId,messageId,content.revision,content.tombstoned,
                (content.deletedAttachmentIds + mediaIds).distinct(),snapshotDeleted=true)
            enqueueMediaTransition(conversationId,messageId,null,content.deletedAttachmentIds + mediaIds,true)
            val clientId=existing?.clientMessageId ?: row?.clientMessageId
            if ((existing != null || row?.sender == "user") && clientId != null) {
                val deleted=(existing ?: LocalSendAtom(ConversationId(conversationId),clientId,emptyList(),row?.timestamp ?: 0L))
                    .identified(messageId,row?.batchId ?: existing?.batchId).copy(parts=emptyList(),snapshotDeleted=true)
                saveSendAtoms(conversationId,atoms.filterNot { it.clientMessageId == clientId } + deleted)
            }
            if (legacy.any { it.id == messageId }) saveLegacySent(conversationId,legacy.filterNot { it.id == messageId })
            baseline?.let { writeTimeline(conversationId,it.copy(messages=it.messages.filterNot { message -> message.id == messageId },
                deletedMessageIds=it.deletedMessageIds + messageId)) }
            removeRetainedHistory(conversationId,messageId)
        }
    }

    /** 纯远端基线和本地发送事实只在读取时合并，空快照不删除发送事实。 */
    @Synchronized fun mergeTimeline(conversationId: String,page: TimelinePage): TimelinePage = documents.transaction {
        if (isConversationDeleted(conversationId)) return@transaction TimelinePage(emptyList(),null)
        val canonical=reconcileSnapshotContent(conversationId,page)
        rememberSnapshotDeletions(conversationId,canonical)
        val rows=legacySent(conversationId).associateByTo(linkedMapOf()) { it.id }
        canonical.messages.forEach { rows[it.id]=it }
        val visible=rows.values.filterNot { isTombstoned(conversationId,it.id) }.map { withDurableStatus(conversationId,it) }
        canonical.copy(messages=mergeLocalSendAtoms(visible,loadSendAtoms(conversationId).map { withDeletionBarrier(conversationId,it) }),
            deletedMessageIds=canonical.deletedMessageIds + canonical.messages.filter { it.isDeleted || contentRevision(conversationId,it.id).snapshotDeleted }.map { it.id })
    }

    /** 只比较明确单条正文版本；普通空页和全页摘要不能改写原稿。 */
    private fun reconcileSnapshotContent(id: String,page: TimelinePage): TimelinePage {
        val baseline=timeline(id)?.messages.orEmpty().associateBy { it.id }
        return page.copy(messages=page.messages.map { row ->
            val revision=row.contentRevision ?: return@map if (row.isDeleted) row else baseline[row.id]?.takeIf {
                (it.contentRevision ?: -1L) >= 0L && it.state != "STREAMING"
            } ?: row
            if (row.isDeleted || revision < 0L) return@map row
            check(row.conversationId?.value?.let { it == id } != false) { "MESSAGE_CONVERSATION_CONFLICT" }
            val content=contentRevision(id,row.id)
            if (content.snapshotDeleted) return@map row
            val atoms=loadSendAtoms(id)
            val atom=atoms.firstOrNull { row.sender == "user" && (it.messageId == row.id || (row.clientMessageId != null && it.clientMessageId == row.clientMessageId)) }
            val known=maxOf(content.revision,atom?.contentRevision ?: -1L)
            if (revision < known || (revision == known && content.tombstoned)) {
                return@map baseline[row.id] ?: atom?.let { row.copy(parts=it.parts,contentRevision=it.contentRevision) } ?: row
            }
            if (revision > known) {
                if (atom != null) {
                    val changed=atom.identified(row.id,row.batchId ?: atom.batchId).copy(parts=row.parts,
                        contentRevision=revision,tombstoneRevision=null)
                    saveSendAtoms(id,atoms.map { if (it.clientMessageId == atom.clientMessageId) changed else it })
                }
                saveContentRevision(id,row.id,revision,false)
                val current = attachmentIds(row.parts)
                enqueueMediaTransition(id,row.id,revision,(attachmentIds(baseline[row.id]?.parts.orEmpty()) + content.deletedAttachmentIds).distinct() - current.toSet(),true)
                enqueueMediaTransition(id,row.id,revision,current,false)
            }
            row
        })
    }

    @Synchronized fun reconcileQuery(conversationId: String,clientMessageId: ClientMessageId,row: TimelineMessage): TimelineMessage = documents.transaction {
        row.conversationId?.let { check(it.value == conversationId) { "MESSAGE_CONVERSATION_CONFLICT" } }
        check(row.clientMessageId == null || row.clientMessageId == clientMessageId) { "MESSAGE_ID_CONFLICT" }
        if (row.isDeleted) rememberSnapshotDeletions(conversationId,TimelinePage(listOf(row.copy(clientMessageId=clientMessageId)),null))
        val canonical=reconcileSnapshotContent(conversationId,TimelinePage(listOf(row.copy(
            conversationId=ConversationId(conversationId),clientMessageId=clientMessageId)),null)).messages.single()
        val atoms=loadSendAtoms(conversationId)
        val original=atoms.firstOrNull { it.clientMessageId == clientMessageId }
        val identified=canonical.copy(conversationId=ConversationId(conversationId),clientMessageId=clientMessageId)
        if (original == null) return@transaction identified
        val accepted=original.identified(row.id,row.batchId ?: original.batchId).copy(
            parts=if (original.contentRevision >= 0L || row.parts.isEmpty()) original.parts else row.parts,
            timestamp=original.timestamp.takeIf { it > 0L } ?: row.timestamp,
        )
        saveSendAtoms(conversationId,atoms.map { if (it.clientMessageId == clientMessageId) accepted else it })
        val merged=mergeLocalSendAtoms(listOf(identified.copy(parts=accepted.parts)),loadSendAtoms(conversationId).filter { it.clientMessageId == clientMessageId })
        merged.firstOrNull() ?: identified.copy(parts=emptyList(),state="TOMBSTONED")
    }
    fun wipe() {
        documents.wipe()
        if (legacyDirectory.exists()) {
            check(legacyDirectory.renameTo(File(legacyDirectory.parentFile,"$scopeHash-removed-${UUID.randomUUID()}"))) { "MIRROR_ERASE_FAILED" }
            legacyKey.delete()
        }
    }
    override suspend fun loadTimeline(conversationId: String): TimelinePage? = withContext(ioDispatcher) {
        val page = timeline(conversationId)
        if (page == null && loadSendAtoms(conversationId).isEmpty()) null
        else mergeTimeline(conversationId,page ?: TimelinePage(emptyList(),null))
    }
    /** 已保存的附件引用就是可恢复的保存意图，不扫描尚未读过的服务器会话。 */
    override suspend fun loadKnownMediaMessages(): List<TimelineMessage> = withContext(ioDispatcher) {
        val ids=documents.keys().mapNotNull { key -> when {
            key.startsWith("timeline:") -> key.removePrefix("timeline:")
            key.startsWith("send-atoms:") -> key.removePrefix("send-atoms:")
            key.startsWith("legacy-sent:") -> key.removePrefix("legacy-sent:")
            else -> null
        } }.distinct()
        ids.flatMap { id -> mergeTimeline(id,timeline(id) ?: TimelinePage(emptyList(),null)).messages.mapNotNull { row ->
            check(row.conversationId?.value?.let { it == id } != false) { "MESSAGE_CONVERSATION_CONFLICT" }
            val attachments=row.parts.filterIsInstance<MessagePart.Attachment>()
            if (row.isDeleted || attachments.isEmpty()) null else row.copy(conversationId=ConversationId(id),parts=attachments)
        } }
    }
    override suspend fun saveCheckpoint(checkpoint: WorkbenchCheckpoint) = withContext(ioDispatcher) { save(checkpoint) }
    override suspend fun saveSendAtomsDurably(conversationId: String, atoms: List<LocalSendAtom>) =
        withContext(ioDispatcher) { saveSendAtoms(conversationId,atoms) }
    override suspend fun flush() = withContext(ioDispatcher) { documents.flush() }
    override suspend fun closeStore() = withContext(ioDispatcher) { closeNativeStore() }
    internal fun closeNativeStore() = documents.close()
    suspend fun saveThreadsDurably(threads: List<ConversationSummary>,revision: Long? = null,expectedEpoch: Long? = null) = withContext(ioDispatcher) { saveThreads(threads,revision,expectedEpoch) }
    suspend fun saveOrganizationDurably(value: ConversationOrganization) = withContext(ioDispatcher) { saveOrganization(value) }
    suspend fun deleteConversationDurably(id: String) = withContext(ioDispatcher) { deleteConversation(id); documents.flush() }
    suspend fun archiveConversationDurably(id: String,archived: Boolean) = withContext(ioDispatcher) { archiveConversation(id,archived); documents.flush() }
    suspend fun commitTimeline(conversationId: String,page: TimelinePage,media: HistoricalMediaPort?): TimelinePage = withContext(ioDispatcher) {
        saveTimeline(conversationId,page)
        val result = mergeTimeline(conversationId,timeline(conversationId) ?: page)
        if (media != null) applyPendingMediaTransitions(media)
        result
    }
    suspend fun removeDeletedMedia(conversationId: String,messageId: String,media: HistoricalMediaPort?) = withContext(ioDispatcher) {
        documents.transaction {
            val content = contentRevision(conversationId,messageId)
            if (content.tombstoned || content.snapshotDeleted) enqueueMediaTransition(conversationId,messageId,
                content.revision.takeUnless { content.snapshotDeleted },content.deletedAttachmentIds,true)
        }
        if (media != null) applyPendingMediaTransitions(media)
    }
    fun loadCursor(accountId: String): String? {
        require(accountId == scope.accountId) { "MIRROR_SCOPE_MISMATCH" }
        return read("cursor")?.let { JsonFields.string(it,"value") }
    }
    fun saveCursor(accountId: String,cursor: String) {
        require(accountId == scope.accountId) { "MIRROR_SCOPE_MISMATCH" }
        require(com.openandroidintelligence.gateway.http.GatewayHttpClient.CURSOR_ALPHABET.matches(cursor)) { "SNAPSHOT_BASELINE_INVALID" }
        documents.transaction { documents.write("cursor",encode(mapOf("value" to cursor))) }
    }
    fun clearCursor(accountId: String) {
        require(accountId == scope.accountId) { "MIRROR_SCOPE_MISMATCH" }
        documents.delete("cursor")
    }
    @Synchronized fun historyEpoch(): Long = read("history-epoch")?.let { JsonFields.long(it,"value") } ?: 0L
    private fun advanceHistoryEpoch() = documents.write("history-epoch",encode(mapOf("value" to Math.addExact(historyEpoch(),1L))))
    /** SQLite reserves a unique read fence even across separate mirror instances. */
    @Synchronized fun beginHistoryRead(): Long = documents.transaction {
        advanceHistoryEpoch()
        historyEpoch()
    }
    @Synchronized fun archiveConversation(id: String,archived: Boolean): Unit = documents.transaction {
        if (isConversationDeleted(id)) return@transaction
        advanceHistoryEpoch()
        documents.write("archive-state:$id",encode(mapOf("archived" to archived,"epoch" to historyEpoch())))
        threads()?.let { current -> saveThreads(current.map { if (it.id.value == id) it.copy(archived=archived) else it }) }
    }
    @Synchronized fun saveThreads(threads: List<ConversationSummary>,revision: Long? = null,expectedEpoch: Long? = null): Boolean = documents.transaction {
        if (expectedEpoch != null && historyEpoch() != expectedEpoch) return@transaction false
        if (expectedEpoch == null) advanceHistoryEpoch()
        lastThreads=threads.filterNot { isConversationDeleted(it.id.value) }.map { summary ->
            val receipt=read("archive-state:${summary.id.value}") ?: return@map summary
            val barrier=JsonFields.long(receipt,"epoch")
            // Only a complete authoritative read begun after the receipt can
            // replace it. Legacy receipts are cleared by that same fresh read.
            if (expectedEpoch != null && (barrier == null || expectedEpoch > barrier)) {
                documents.delete("archive-state:${summary.id.value}")
                summary
            } else summary.copy(archived=JsonFields.bool(receipt,"archived") ?: summary.archived)
        }
        val token=if (expectedEpoch != null) revision else revision ?: threadsSnapshotRevision()
        val acceptedReadEpoch=expectedEpoch ?: read("threads")?.let { JsonFields.long(it,"acceptedReadEpoch") }
        documents.write("threads",encode(mapOf("threads" to lastThreads.map(::thread),"snapshotRevision" to token,
            "acceptedReadEpoch" to acceptedReadEpoch)))
        true
    }
    @Synchronized fun threadsSnapshotRevision(): Long? = read("threads")?.let { JsonFields.long(it,"snapshotRevision") }
    @Synchronized fun threads(): List<ConversationSummary>? = read("threads")?.let { JsonFields.objects(it,"threads").map(::decodeThread).filterNot { thread -> isConversationDeleted(thread.id.value) } }
    /** A durable barrier prevents an in-flight read, old checkpoint or replay from reopening a deleted identity. */
    @Synchronized fun deleteConversation(id: String): Unit = documents.transaction {
        if (isConversationDeleted(id)) return@transaction
        val messages=mergeTimeline(id,timeline(id) ?: TimelinePage(emptyList(),null)).messages
        messages.forEach { row -> enqueueMediaTransition(id,row.id,null,attachmentIds(row.parts),true) }
        advanceHistoryEpoch()
        documents.write("deleted-conversation:$id",encode(mapOf("deleted" to true)))
        documents.keys().filter { key -> key in setOf("timeline:$id","send-atoms:$id","legacy-sent:$id","archive-state:$id") ||
            key.startsWith("event-revision:$id:") || key.startsWith("message-status:$id:") }.forEach(documents::delete)
        saveThreads((threads() ?: lastThreads).filterNot { it.id.value == id })
        val organization=organization()
        saveOrganization(organization.copy(customOrder=organization.customOrder.filterNot { it == id },assignments=organization.assignments-id))
        load()?.let(::save)
    }
    private fun writeTimeline(id: String,page: TimelinePage) = documents.write("timeline:$id",encode(mapOf("revision" to page.snapshotRevision,
        "deletedMessageIds" to page.deletedMessageIds.toList(),"messages" to page.messages.map(::message))))
    @Synchronized fun saveTimeline(id: String,page: TimelinePage): Unit = documents.transaction {
        if (isConversationDeleted(id)) return@transaction
        val previous = timeline(id)
        val canonical=reconcileSnapshotContent(id,page.copy(messages=page.messages.map { message ->
            check(message.conversationId?.value?.let { it == id } != false) { "MESSAGE_CONVERSATION_CONFLICT" }
            message.copy(conversationId=ConversationId(id))
        }))
        rememberSnapshotDeletions(id,canonical)
        val rows = previous?.messages.orEmpty().associateByTo(linkedMapOf()) { it.id }
        canonical.messages.forEach { rows[it.id] = it }
        writeTimeline(id,canonical.copy(messages=rows.values.filterNot { it.isDeleted || isTombstoned(id,it.id) }.sortedBy { it.timestamp },
            deletedMessageIds=canonical.deletedMessageIds + previous?.deletedMessageIds.orEmpty() + canonical.messages.filter { it.isDeleted }.map { it.id }))
    }
    @Synchronized fun timeline(id: String): TimelinePage? = if (isConversationDeleted(id)) null else read("timeline:$id")?.let { TimelinePage(JsonFields.objects(it,"messages").map(::decodeMessage),null,
        JsonFields.long(it,"revision"),JsonFields.strings(it,"deletedMessageIds").toSet()) }
    @Synchronized fun apply(event: VerifiedConversationEvent): Unit = documents.transaction {
        if (eventConversationId(event)?.let(::isConversationDeleted) == true && event !is VerifiedConversationEvent.ConversationsChanged) return@transaction
        when(event) {
            is VerifiedConversationEvent.ConversationsChanged -> if (event.deleted) event.conversationId?.value?.let(::deleteConversation) else Unit
            is VerifiedConversationEvent.TimelineUpsert -> event.message.conversationId?.value?.let { id ->
                val revision=contentRevision(id,event.message.id)
                if (revision.snapshotDeleted) return@let
                if (event.revision < revision.revision || (event.revision == revision.revision && revision.tombstoned)) return@let
                val old = timeline(id) ?: TimelinePage(emptyList(),null)
                val atoms=loadSendAtoms(id)
                val existing=atoms.firstOrNull { it.messageId == event.message.id || it.clientMessageId == event.message.clientMessageId }
                // 身份映射分叉时丢弃这条事件：它不属于本地这个原子，带正确 clientMessageId 的
                // 后续事件仍会把状态推进；把可恢复的镜像做成硬崩溃没有任何收益。
                if (existing != null && event.message.clientMessageId != null && existing.clientMessageId != event.message.clientMessageId) return@let
                if (event.revision < (existing?.contentRevision ?: -1L) ||
                    (event.revision == existing?.tombstoneRevision)) return@let
                val clientId=existing?.clientMessageId ?: event.message.clientMessageId
                if (event.message.sender == "user" && clientId != null) {
                    val status=statusRevision(id,event.message.id)
                    val atom=(existing ?: LocalSendAtom(ConversationId(id),clientId,event.message.parts,event.message.timestamp))
                        .identified(event.message.id,event.message.batchId ?: existing?.batchId).copy(
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
                val previousMedia = (attachmentIds(old.messages.filter { it.id == event.message.id }.flatMap { it.parts }) +
                    attachmentIds(existing?.parts.orEmpty()) + attachmentIds(legacy.filter { it.id == event.message.id }.flatMap { it.parts }) +
                    revision.deletedAttachmentIds).distinct()
                val currentMedia = if (event.message.isDeleted) emptyList() else attachmentIds(event.message.parts)
                enqueueMediaTransition(id,event.message.id,event.revision,previousMedia - currentMedia.toSet(),true)
                enqueueMediaTransition(id,event.message.id,event.revision,currentMedia,false)
                writeTimeline(id,old.copy(messages = (old.messages.filterNot { it.id == event.message.id } +
                    withDurableStatus(id,event.message.copy(contentRevision=event.revision))).sortedBy { it.timestamp }))
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
                enqueueMediaTransition(id,event.messageId,event.revision,content.deletedAttachmentIds + mediaIds,true)
                if (atoms.any { it.messageId == event.messageId }) saveSendAtoms(id,atoms.map {
                    if (it.messageId == event.messageId) it.copy(parts=emptyList(),contentRevision=event.revision,tombstoneRevision=event.revision) else it
                })
                if (legacy.any { it.id == event.messageId }) saveLegacySent(id,legacy.filterNot { it.id == event.messageId })
                baseline?.let { page -> saveTimeline(id,page.copy(messages=page.messages.filterNot { it.id == event.messageId })) }
                removeRetainedHistory(id,event.messageId)
            }
            is VerifiedConversationEvent.MessageStatus -> {
                val id=event.conversationId.value
                if (event.revision < (statusRevision(id,event.messageId)?.revision ?: -1L)) return@transaction
                val atoms=loadSendAtoms(id)
                val existing=atoms.firstOrNull { it.clientMessageId == event.clientMessageId || it.messageId == event.messageId }
                // 身份映射分叉时丢弃这条事件：它不属于本地这个原子，带正确 clientMessageId 的
                // 后续事件仍会把状态推进；把可恢复的镜像做成硬崩溃没有任何收益。
                if (existing != null && existing.clientMessageId != event.clientMessageId) return@transaction
                if (event.revision < (existing?.statusRevision ?: -1L)) return@transaction
                event.generationId?.let { generation -> saveGenerationFacts(associations=listOf(event.messageId,event.clientMessageId.value).map {
                    SavedMessageGeneration(id,it,generation,event.revision)
                }) }
                val baseline=timeline(id)
                val knownRow=baseline?.messages?.firstOrNull { it.id == event.messageId && it.sender == "user" }
                if (existing != null || knownRow != null) {
                    val atom=(existing ?: LocalSendAtom(ConversationId(id),event.clientMessageId,knownRow!!.parts,knownRow.timestamp))
                        .identified(event.messageId).copy(status=event.status,statusRevision=event.revision,errorCode=event.errorCode)
                    saveSendAtoms(id,atoms.filterNot { it.clientMessageId == atom.clientMessageId } + atom)
                }
                baseline?.let { page -> saveTimeline(id,page.copy(messages=page.messages.map {
                    if (it.id == event.messageId) it.copy(state=event.status.wireValue,errorCode=event.errorCode?.wireValue,
                        clientMessageId=event.clientMessageId,generationId=event.generationId ?: it.generationId) else it
                })) }
                documents.write("message-status:$id:${event.messageId}",encode(mapOf("status" to event.status.wireValue,
                    "revision" to event.revision,"errorCode" to event.errorCode?.wireValue)))
            }
            is VerifiedConversationEvent.TitleUpdated -> threads()?.let { list -> saveThreads(list.map { if (it.id == event.conversationId) it.copy(title=event.newTitle) else it }) }
            is VerifiedConversationEvent.GenerationCancelled -> event.conversationId?.value?.let { id ->
                saveGenerationFacts(confirmedCancellations=listOf(SavedCancellation(id,event.generationId)))
            }
            else -> Unit
        }
    }
    suspend fun applyAndCommit(event: VerifiedConversationEvent) = withContext(ioDispatcher) {
        synchronized(this@EncryptedConversationMirror) {
            documents.transaction {
                apply(event)
                if (event.eventId.isNotBlank()) saveCursor(scope.accountId,event.eventId)
            }
        }
        documents.flush()
    }
    /** 原子切换一个加密快照；重建失败时，前一代快照仍然可读。 */
    @Synchronized fun installBaseline(threads: List<ConversationSummary>, pages: Map<String,TimelinePage>, cursor: String,
        expectedHistoryEpoch: Long? = null, threadsSnapshotRevision: Long? = null): Boolean = documents.transaction {
        val epoch=expectedHistoryEpoch ?: beginHistoryRead()
        if (historyEpoch() != epoch) return@transaction false
        pages.forEach { (id,page) -> rememberSnapshotDeletions(id,page) }
        val cleanPages=pages.mapValues { (id,page) -> page.copy(messages=page.messages.filterNot { it.isDeleted || isTombstoned(id,it.id) },
            deletedMessageIds=page.deletedMessageIds + page.messages.filter { it.isDeleted }.map { it.id }) }
        check(saveThreads(threads,threadsSnapshotRevision,epoch)) { "SNAPSHOT_STALE" }
        // 所有线程、消息和基线游标同事务提交，进程中断不能产生半代快照。
        cleanPages.forEach { (id,page) -> saveTimeline(id,page) }
        documents.keys().filter { it.startsWith("timeline:") && it.removePrefix("timeline:") !in cleanPages }.forEach(documents::delete)
        // 删除屏障与状态修订属于发送事实，不能随空的历史快照丢弃。
        documents.keys().filter { it.startsWith("snapshot:") }.forEach(documents::delete)
        documents.delete("baseline")
        saveCursor(scope.accountId,cursor)
        true
    }
    suspend fun installBaselineAndCommit(threads: List<ConversationSummary>,pages: Map<String,TimelinePage>,cursor: String,
        expectedHistoryEpoch: Long? = null, threadsSnapshotRevision: Long? = null) = withContext(ioDispatcher) {
        installBaseline(threads,pages,cursor,expectedHistoryEpoch,threadsSnapshotRevision).also { installed ->
            if (installed) documents.flush()
        }
    }
    @Synchronized fun finishBaselineRecovery(): Unit = documents.transaction {
        val key = read("baseline")?.let { JsonFields.string(it,"generation") } ?: return@transaction
        // Legacy staged snapshots have no request fence. Once a fresh complete
        // read has committed, they cannot replace its rows, timelines or cursor.
        if (read("threads")?.let { JsonFields.long(it,"acceptedReadEpoch") } != null) {
            documents.delete("baseline")
            return@transaction
        }
        val snap = read(key) ?: error("MIRROR_CORRUPTED")
        saveThreads(JsonFields.objects(snap,"threads").map(::decodeThread))
        val pages=JsonFields.objects(snap,"timelines")
        pages.forEach { p -> saveTimeline(str(p,"id"),TimelinePage(JsonFields.objects(p,"messages").map(::decodeMessage),null,
            JsonFields.long(p,"revision"),JsonFields.strings(p,"deletedMessageIds").toSet())) }
        val ids=pages.map { str(it,"id") }.toSet()
        documents.keys().filter { it.startsWith("timeline:") && it.removePrefix("timeline:") !in ids }.forEach(documents::delete)
        JsonFields.string(snap,"cursor")?.let { saveCursor(scope.accountId,it) }
        documents.delete("baseline")
    }
}

class MirroredConversationRepository(private val delegate: ConversationRepository, private val mirror: EncryptedConversationMirror,private val media:HistoricalMediaPort?=null,
    /** The delegate's shared operation store already commits these receipts while holding its lock. */
    private val managementCommittedByDelegate: Boolean = false,
    /** 中间流式快照的落盘合并间隔；终态与非流式事件不受影响。 */
    private val streamingPersistIntervalMillis: Long = 500L,
    /** 可注入时钟：测试用假时钟验证合并不必真的等待。 */
    private val clock: () -> Long = { System.nanoTime() },
) : ConversationRepository by delegate, GenerationTracker, com.openandroidintelligence.conversation.model.StreamHealthSource, MessageOutcomeQuery {
    private var lastStreamingPersistAtNanos: Long? = null
    override val generationId get()=(delegate as GenerationTracker).generationId
    override val generationState get()=(delegate as GenerationTracker).generationState
    override val streamHealth get()=(delegate as com.openandroidintelligence.conversation.model.StreamHealthSource).streamHealth
    override suspend fun queryMessage(conversationId:String,clientMessageId:ClientMessageId):TimelineMessage? =
        (delegate as? MessageOutcomeQuery)?.queryMessage(conversationId,clientMessageId)?.let {
            mirror.reconcileQuery(conversationId,clientMessageId,it).also { if (media != null) mirror.applyPendingMediaTransitions(media) }
        }
    override suspend fun listConversations(scope: ConversationScope, page: PageRequest): ConversationPage = try {
        if (page.cursor == null) refreshCompleteHistory(scope)
        else delegate.listConversations(scope,page)
    } catch (c: CancellationException) { throw c } catch (e: java.io.IOException) {
        if (page.cursor == null) withContext(Dispatchers.IO) { mirror.threads()?.let { ConversationPage(it,null,mirror.threadsSnapshotRevision(),authoritative=false) } } ?: throw e else throw e
    }
    private suspend fun refreshCompleteHistory(scope: ConversationScope): ConversationPage {
        repeat(2) { attempt ->
            currentCoroutineContext().ensureActive()
            val epoch=withContext(Dispatchers.IO) { mirror.beginHistoryRead() }
            val page=delegate.completeConversations(scope)
            if (!page.authoritative || page.nextCursor != null) return page
            if (mirror.saveThreadsDurably(page.conversations,page.snapshotRevision,epoch) || attempt == 1) return page
            // Pending native operation recovery can advance the mirror after
            // this read reserved its fence; refetch once from the new baseline.
        }
        error("CONVERSATIONS_RESYNC_FAILED")
    }
    override suspend fun readOrganization(): ConversationOrganization = try {
        delegate.readOrganization().also { mirror.saveOrganizationDurably(it) }
    } catch (c: CancellationException) { throw c } catch (e: java.io.IOException) { withContext(Dispatchers.IO) { mirror.organization() } }
    override suspend fun updateOrganization(expectedRevision: Long,organization: ConversationOrganization): ConversationOrganization =
        delegate.updateOrganization(expectedRevision,organization).also { mirror.saveOrganizationDurably(it) }
    override suspend fun setArchived(conversationId: String,archived: Boolean) {
        delegate.setArchived(conversationId,archived)
        if (!managementCommittedByDelegate) mirror.archiveConversationDurably(conversationId,archived)
    }
    override suspend fun deleteConversation(conversationId: String) {
        delegate.deleteConversation(conversationId)
        if (!managementCommittedByDelegate) mirror.deleteConversationDurably(conversationId)
        if (media != null) mirror.applyPendingMediaTransitions(media)
    }
    override suspend fun timeline(conversationId: String, page: PageRequest): TimelinePage = try {
        if (page.cursor == null) {
            val baseline=delegate.completeTimeline(conversationId)
            mirror.commitTimeline(conversationId,baseline,media)
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
    override fun observeEvents(scope: ConversationScope) = delegate.observeEvents(scope).onStart {
        if (media != null) mirror.applyPendingMediaTransitions(media)
    }.mapNotNull { event ->
        val streaming = event is VerifiedConversationEvent.TimelineUpsert &&
            event.message.sender == "assistant" && event.message.state == "STREAMING"
        val now = clock()
        val intervalNanos = streamingPersistIntervalMillis * 1_000_000L
        // 中间流式快照不是最终事实：按间隔合并落盘，避免每帧一次加密事务把上游读流压成背压。
        // 终态/用户消息/状态事件始终立即落盘，崩溃最多丢一个间隔内的中间文本。
        val persist = !streaming || lastStreamingPersistAtNanos.let { last -> last == null || now - last >= intervalNanos }
        if (persist) {
            if (streaming) lastStreamingPersistAtNanos = now
            mirror.applyAndCommit(event)
            if (media != null) mirror.applyPendingMediaTransitions(media)
        }
        mirror.projectEvent(event)
    }
}
