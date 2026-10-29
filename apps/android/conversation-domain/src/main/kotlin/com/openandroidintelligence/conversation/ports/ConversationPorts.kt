package com.openandroidintelligence.conversation.ports

import com.openandroidintelligence.conversation.model.*
import java.io.InputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow

data class ConversationScope(
    val profileId: String,
    val gatewayId: String,
    val accountId: String,
    val installId: String,
)

data class PageRequest(val cursor: String? = null, val limit: Int = 50)
data class ConversationPage(val conversations: List<ConversationSummary>, val nextCursor: String?, val snapshotRevision: Long? = null,
    /** Cached history can render a screen, but absence from it is never evidence of deletion. */
    val authoritative: Boolean = true)
data class ConversationSummary(val id: ConversationId, val title: String, val updatedAt: Long, val archived: Boolean = false)
data class Conversation(val id: ConversationId, val title: String, val createdAt: Long)
data class TimelinePage(
    val messages: List<TimelineMessage>, val nextCursor: String?, val snapshotRevision: Long? = null,
    /** 内部删除身份来自明确的宿主行；正文合并后仍需通知已显示的时间线。 */
    val deletedMessageIds: Set<String> = emptySet(),
    /** Local fence for a verified idle-generation receipt; distinct from the Gateway history revision. */
    val idleGenerationRevision: Long? = null,
)
data class TimelineMessage(
    val id: String,
    val sender: String,
    val parts: List<MessagePart>,
    val timestamp: Long,
    val state: String = "CONFIRMED",
    /** The conversation carried by an SSE event, when the Gateway provides it. */
    val conversationId: ConversationId? = null,
    val errorCode: String? = null,
    val batchId:String?=null,
    val clientMessageId: ClientMessageId? = null,
    val localSubmissionFailure: String? = null,
    /** 宿主明确发布的单条正文版本；缺少时沿用既有兼容路径。 */
    val contentRevision: Long? = null,
    /** Gateway generation identity, when supplied by a live event. */
    val generationId: String? = null,
    val executionSteps: List<NativeExecutionStep> = emptyList(),
    /** 仅端侧临时流式行设为 true；不能从真实 ID 的字符串形式猜测。 */
    val localPlaceholder: Boolean = false,
) {
    /** 明确删除的消息身份不能因缺少正文而被本地发送副本补回。 */
    val isDeleted: Boolean get() = state.equals("DELETED", ignoreCase = true) || state.equals("TOMBSTONED", ignoreCase = true)
}

data class MessageBatch(
    val batchId: String,
    val messages: List<OutgoingMessage>,
    val clientConversationId: String? = null,
)

data class OutgoingMessage(
    val clientMessageId: ClientMessageId,
    val text: String,
    val attachmentIds: List<String> = emptyList(),
    val command: MessagePart.Command? = null,
    val delivery: MessageDeliveryMode = MessageDeliveryMode.NORMAL,
)

/** How a follow-up relates to an already-running Agent turn. */
enum class MessageDeliveryMode(val wireValue: String) {
    /** The host's ordinary message path. */
    NORMAL("normal"),

    /** Hold on this phone until the current turn reaches a terminal state. */
    QUEUE("queue"),

    /** Inject into the exact running generation; never create a second turn. */
    STEER("steer"),
}

enum class PendingDeliveryState(val wireValue: String) {
    QUEUED("queued"),
    SENDING("sending"),
    FAILED("failed"),
    OUTCOME_UNKNOWN("outcome_unknown"),
}

data class PendingDelivery(
    val clientMessageId: ClientMessageId,
    val conversationId: ConversationId,
    val text: String,
    val attachmentCount: Int,
    val state: PendingDeliveryState,
    val errorCode: String? = null,
    val generationId: String? = null,
)

/**
 * The Gateway's answer to a batch submission.
 *
 * [memberIds] is the authoritative client→server message id mapping. A member
 * mirrored under its local client id would appear a second time as soon as the
 * same message arrives from the Gateway under the id the Gateway issued, so
 * callers must key the mirror by [memberIds] rather than by the local id.
 */
data class BatchAcceptance(
    val batchId: String,
    val memberIds: Map<String, String> = emptyMap(),
    val generationId: String? = null,
) {
    /**
     * The ids the Gateway issued for the accepted members.
     *
     * Derived from [memberIds] rather than stored beside it: a second list
     * would be a state that can disagree with the mapping it mirrors.
     */
    val acceptedMessageIds: List<String> get() = memberIds.values.toList()
}
data class MessageAcceptance(
    val messageId: String,
    val correlationId: String,
    val generationId: String? = null,
    val delivery: MessageDeliveryMode = MessageDeliveryMode.NORMAL,
    /** false 表示本地已记录/启动提交，尚无服务端身份回执。 */
    val authoritative: Boolean = true,
)

enum class CancelGenerationOutcome {
    CANCELLED,
    ALREADY_COMPLETED,
    UNSUPPORTED,
    OUTCOME_UNKNOWN,
}

data class CancelGenerationResult(val outcome: CancelGenerationOutcome, val message: String? = null)

/**
 * The closed set an Agent-side command may answer with (contract §7.1).
 *
 * `OUTCOME_UNKNOWN` is a real answer too: it is what the Gateway says when it
 * cannot tell whether the command landed, so the UI must not paint it as
 * success or as a refusal it can retry blindly.
 */
enum class CommandOutcome {
    CREATED_CONVERSATION,
    REJECTED,
    UNSUPPORTED,
    OUTCOME_UNKNOWN,
}

enum class AgentMessageStatus(val wireValue: String) {
    QUEUED("queued"), DELIVERED("delivered"), COMPLETED("completed"), FAILED("failed"),
}

enum class AgentMessageErrorCode(val wireValue: String) {
    AGENT_UNAVAILABLE("AGENT_UNAVAILABLE"),
    INVALID_MESSAGE("INVALID_MESSAGE"),
    UNSUPPORTED_CAPABILITY("UNSUPPORTED_CAPABILITY"),
    PROTOCOL_ERROR("PROTOCOL_ERROR"),
    AGENT_REQUEST_FAILED("AGENT_REQUEST_FAILED"),
    ATTACHMENT_READ_FAILED("ATTACHMENT_READ_FAILED"),
    AGENT_MEDIA_REJECTED("AGENT_MEDIA_REJECTED"),
    MODEL_REQUEST_REJECTED("MODEL_REQUEST_REJECTED"),
    APPROVAL_UNSUPPORTED("APPROVAL_UNSUPPORTED"),
}
data class CancelSubmissionResult(val success: Boolean)
data class PendingSubmissionIntent(
    val intentId: SubmitIntentId,
    val clientMessageId: ClientMessageId,
    val draftRevision: Long,
    val text: String,
    val attachments: List<AttachmentDraftId> = emptyList(),
)

fun interface AttachmentContentSource {
    /** Returns a new stream on every call. The caller owns and closes the stream. */
    fun openStream(): InputStream

    /** Stream producers such as Bitmap.compress can bypass a complete encoded ByteArray. */
    fun writeTo(output: OutputStream) {
        openStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                if (read == 0) {
                    val single = input.read()
                    if (single == -1) break
                    output.write(single)
                } else {
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    companion object {
        fun fromWriter(writer: (OutputStream) -> Unit): AttachmentContentSource = object : AttachmentContentSource {
            override fun openStream(): InputStream {
                val input = PipedInputStream(64 * 1024)
                val output = PipedOutputStream(input)
                val failure = AtomicReference<Throwable?>(null)
                val producer = Thread({
                    try {
                        writer(output)
                    } catch (cause: Throwable) {
                        failure.set(cause)
                    } finally {
                        // Publish the error before EOF can wake the reader.
                        runCatching { output.close() }
                    }
                }, "attachment-source-writer").apply {
                    isDaemon = true
                    start()
                }
                return object : FilterInputStream(input) {
                    override fun read(): Int = try {
                        super.read().also { if (it == -1) rethrowProducerFailure(failure.get()) }
                    } catch (cause: IOException) {
                        throwProducerFailureOr(failure.get(), cause)
                    }

                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
                        super.read(buffer, offset, length).also { if (it == -1) rethrowProducerFailure(failure.get()) }
                    } catch (cause: IOException) {
                        throwProducerFailureOr(failure.get(), cause)
                    }

                    override fun close() {
                        super.close()
                        producer.interrupt()
                        try {
                            producer.join(1_000L)
                        } catch (cause: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw IOException("ATTACHMENT_SOURCE_CANCELLED", cause)
                        }
                    }
                }
            }
        }

        private fun rethrowProducerFailure(failure: Throwable?) {
            if (failure != null) throw IOException("ATTACHMENT_READ_FAILED", failure)
        }

        private fun throwProducerFailureOr(failure: Throwable?, fallback: IOException): Nothing =
            throw IOException("ATTACHMENT_READ_FAILED", failure ?: fallback)
    }
}

data class LocalAttachmentSelection(
    val filename: String,
    val mediaType: String,
    val contentSource: AttachmentContentSource,
    /** Bounded, optional display preview; never the upload payload. */
    val previewBytes: ByteArray? = null,
    val recoverAfterRestart: Boolean = true,
)

data class StagedAttachmentContent(val id: String, val sizeBytes: Long, val sha256Hex: String)

interface LocalAttachmentStagingStore {
    fun stage(
        selection: LocalAttachmentSelection,
        onBytesStaged: (Long) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): StagedAttachmentContent

    fun openStream(stagedId: String): InputStream
    fun delete(stagedId: String)
    fun cleanupExpired(nowMillis: Long, maxAgeMillis: Long)
    fun cleanup()
}

data class AttachmentDraftState(
    val draftId: AttachmentDraftId,
    val state: AttachmentState,
    val progress: Float = 0f,
    val transferredBytes: Long = 0,
    val totalBytes: Long? = null,
    val errorMessage: String? = null,
)

data class AgentCommand(
    val command: String,
    val description: String,
    val argumentHint: String? = null,
)

data class AgentCommandCatalog(val version: CatalogVersion, val commands: List<AgentCommand>)

sealed interface VerifiedConversationEvent {
    val eventId: String
    val occurredAt: Long

    data class MessageAccepted(
        override val eventId: String,
        override val occurredAt: Long,
        val messageId: String,
        val correlationId: String,
        val conversationId: ConversationId? = null,
    ) : VerifiedConversationEvent

    data class MessageStatus(
        override val eventId: String,
        override val occurredAt: Long,
        val conversationId: ConversationId,
        val messageId: String,
        val clientMessageId: ClientMessageId,
        val status: AgentMessageStatus,
        val revision: Long,
        val errorCode: AgentMessageErrorCode?,
        val generationId: String? = null,
    ) : VerifiedConversationEvent

    data class GenerationCancelled(
        override val eventId: String,
        override val occurredAt: Long,
        val generationId: String,
        val conversationId: ConversationId? = null,
    ) : VerifiedConversationEvent

    data class CommandResult(
        override val eventId: String,
        override val occurredAt: Long,
        val command: String,
        /**
         * The Gateway's closed answer for one command.
         *
         * Only [CommandOutcome.CREATED_CONVERSATION] together with a non-null
         * [conversationId] is permission to switch: the others are facts about
         * what the Agent refused or could not do, and the caller has to say so
         * rather than degrade into a locally invented conversation.
         */
        val outcome: CommandOutcome = CommandOutcome.OUTCOME_UNKNOWN,
        val sourceConversationId: ConversationId? = null,
        val sourceMessageId: String? = null,
        val conversationId: ConversationId? = null,
    ) : VerifiedConversationEvent

    data class TitleUpdated(
        override val eventId: String,
        override val occurredAt: Long,
        val conversationId: ConversationId,
        val newTitle: String,
    ) : VerifiedConversationEvent

    /** Account-wide metadata changed; fetch committed organization and history again. */
    data class ConversationsChanged(
        override val eventId: String,
        override val occurredAt: Long,
        val conversationId: ConversationId? = null,
        val deleted: Boolean = false,
    ) : VerifiedConversationEvent

    data class TimelineUpsert(
        override val eventId: String,
        override val occurredAt: Long,
        val revision: Long,
        val message: TimelineMessage,
    ) : VerifiedConversationEvent

    data class TimelineTombstoned(
        override val eventId: String,
        override val occurredAt: Long,
        val messageId: String,
        val revision: Long,
        val conversationId: ConversationId? = null,
    ) : VerifiedConversationEvent

    data class SnapshotInvalidated(
        override val eventId: String,
        override val occurredAt: Long,
        val snapshotRevision: Long,
        val conversationId: ConversationId? = null,
    ) : VerifiedConversationEvent

    /**
     * The Gateway asked the phone to decide one command execution (§7.2).
     *
     * This is a request, not a message: it never becomes a chat bubble and no
     * user turn is spent on it. [request] carries the tiers the Gateway offers,
     * which is the only set the UI may draw.
     */
    data class ApprovalRequested(
        override val eventId: String,
        override val occurredAt: Long,
        val request: ApprovalRequest,
        val conversationId: ConversationId? = null,
    ) : VerifiedConversationEvent

    /**
     * How one approval ended (§7.2).
     *
     * The Gateway owns every terminal outcome, including `timeout` and
     * `withdrawn`: a card may grey itself out on its own countdown, but only
     * this event is allowed to say what actually happened.
     */
    data class ApprovalResolved(
        override val eventId: String,
        override val occurredAt: Long,
        val approvalId: ApprovalId,
        val outcome: ApprovalOutcome,
        val decidedAt: Long? = null,
        val conversationId: ConversationId? = null,
    ) : VerifiedConversationEvent

    /**
     * Readable fallback for an approval that cannot be drawn as a card.
     *
     * The command is preserved so the user can inspect exactly what is waiting
     * and answer through the host's typed command path. This is a pending
     * question, never an automatic denial.
     */
    data class ApprovalTextFallback(
        override val eventId: String,
        override val occurredAt: Long,
        val conversationId: ConversationId,
        val approvalId: ApprovalId? = null,
        val command: String,
        val reason: String = "",
        val commands: List<String> = emptyList(),
        val diagnosticCode: String? = null,
    ) : VerifiedConversationEvent

    /** A phone-held queue item changed state without becoming a new Agent turn yet. */
    data class PendingDeliveryUpdated(
        override val eventId: String,
        override val occurredAt: Long,
        val delivery: PendingDelivery,
    ) : VerifiedConversationEvent
}

data class MirrorScope(
    val profileId: String,
    val gatewayId: String,
    val accountId: String,
    val installId: String,
)

interface MirrorSession

interface ConversationRepository {
    suspend fun listConversations(scope: ConversationScope, page: PageRequest): ConversationPage
    suspend fun createConversation(scope: ConversationScope, clientConversationId: String): Conversation
    suspend fun timeline(conversationId: String, page: PageRequest): TimelinePage
    /** Only the repository which verified the idle receipt may validate its local fact revision. */
    fun isIdleGenerationReceiptCurrent(conversationId: String, revision: Long): Boolean = false
    suspend fun submitBatch(batch: MessageBatch): BatchAcceptance
    /** Scoped overload used by delayed batches so a thread switch cannot retarget a send. */
    suspend fun submitBatch(conversationId: String, batch: MessageBatch): BatchAcceptance =
        submitBatch(batch.copy(clientConversationId = conversationId))
    suspend fun submitMessage(message: OutgoingMessage): MessageAcceptance
    /** Scoped overload used by in-flight sends; legacy implementations may delegate. */
    suspend fun submitMessage(conversationId: String, message: OutgoingMessage): MessageAcceptance =
        submitMessage(message)
    /** Inject a text correction into one exact running generation. */
    suspend fun steerMessage(conversationId: String, message: OutgoingMessage): MessageAcceptance =
        error("STEERING_UNSUPPORTED")
    suspend fun pendingDeliveries(conversationId: String): List<PendingDelivery> = emptyList()
    suspend fun retryPendingDeliveries(conversationId: String): Unit = Unit
    suspend fun retryAllPendingDeliveries(): Unit = Unit
    fun observeEvents(scope: ConversationScope): Flow<VerifiedConversationEvent>
    suspend fun cancelGeneration(generationId: String, requestId: String): CancelGenerationResult
    /** Scoped cancellation overload; the default keeps old test adapters source-compatible. */
    suspend fun cancelGeneration(
        conversationId: String,
        generationId: String,
        requestId: String,
    ): CancelGenerationResult = cancelGeneration(generationId, requestId)
    suspend fun updateTitle(conversationId: String, title: String): Boolean = false

    suspend fun readOrganization(): ConversationOrganization = error("CONVERSATION_MANAGEMENT_UNSUPPORTED")
    /** The server commits this complete state only if [expectedRevision] is still current. */
    suspend fun updateOrganization(expectedRevision: Long, organization: ConversationOrganization): ConversationOrganization =
        error("CONVERSATION_MANAGEMENT_UNSUPPORTED")
    suspend fun setArchived(conversationId: String, archived: Boolean): Unit = error("CONVERSATION_MANAGEMENT_UNSUPPORTED")
    suspend fun deleteConversation(conversationId: String): Unit = error("CONVERSATION_MANAGEMENT_UNSUPPORTED")

    /**
     * One conversation's authoritative metadata as the Gateway has it.
     *
     * `null` means this repository cannot answer, which is a fact the caller
     * must handle by falling back to the thread list — never by inventing a
     * title or a creation time locally.
     */
    suspend fun readConversation(conversationId: String): Conversation? = null

    /**
     * One approval decision, sent through its own endpoint rather than as text.
     *
     * The default answers `UNSUPPORTED` on purpose: a repository that cannot
     * reach the decision endpoint must say so, because a card that pretends to
     * submit would leave the command blocked while the UI shows it as allowed.
     */
    suspend fun submitApprovalDecision(
        approvalId: ApprovalId,
        choice: ApprovalChoice,
    ): ApprovalSubmissionResult =
        ApprovalSubmissionResult(
            outcome = ApprovalSubmissionOutcome.UNSUPPORTED,
        )

    /** Text approval prompts that remain pending on this host. */
    suspend fun pendingApprovalFallbacks(conversationId: String): List<VerifiedConversationEvent.ApprovalTextFallback> =
        emptyList()
}

/**
 * Optional recovery port for a request whose HTTP result is unknown.
 * Implementations must query the Gateway by the original client message ID
 * before a retry; callers must never infer that a lost response means failure.
 */
interface MessageOutcomeQuery {
    suspend fun queryMessage(conversationId: String, clientMessageId: ClientMessageId): TimelineMessage?
}

/** Expose a complete account history, never a partial first page or a mixed revision. */
suspend fun ConversationRepository.completeConversations(scope: ConversationScope): ConversationPage {
    repeat(3) { attempt ->
        val conversations = linkedMapOf<String, ConversationSummary>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        var revision: Long? = null
        var first = true
        var authoritative = true
        try {
            do {
                val page = listConversations(scope, PageRequest(cursor, 100))
                if (!first && page.snapshotRevision != revision) error("CURSOR_EXPIRED")
                first = false
                revision = page.snapshotRevision
                authoritative = authoritative && page.authoritative
                page.conversations.forEach { conversations[it.id.value] = it }
                cursor = page.nextCursor
                check(cursor == null || cursors.add(cursor)) { "CONVERSATIONS_CURSOR_CYCLE" }
            } while (cursor != null)
            return ConversationPage(conversations.values.toList(), null, revision, authoritative)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (cause: Exception) {
            if (attempt == 2 || !cause.message.orEmpty().contains("CURSOR_EXPIRED")) throw cause
        }
    }
    error("CONVERSATIONS_RESYNC_FAILED")
}

/** Complete a snapshot before exposing it. A changing snapshot is restarted, never mixed. */
suspend fun ConversationRepository.completeTimeline(conversationId: String): TimelinePage {
    repeat(3) { attempt ->
        val messages = linkedMapOf<String, TimelineMessage>()
        val deletedMessageIds = linkedSetOf<String>()
        val cursors = mutableSetOf<String>()
        var cursor: String? = null
        var revision: Long? = null
        var idleGenerationRevision: Long? = null
        try {
            do {
                val page = timeline(conversationId, PageRequest(cursor, 100))
                if (revision != null && page.snapshotRevision != revision) error("CURSOR_EXPIRED")
                revision = page.snapshotRevision
                idleGenerationRevision = page.idleGenerationRevision
                page.messages.forEach { messages[it.id] = it }
                deletedMessageIds.addAll(page.deletedMessageIds)
                page.messages.filter { it.isDeleted }.forEach { deletedMessageIds.add(it.id) }
                cursor = page.nextCursor
                check(cursor == null || cursors.add(cursor)) { "TIMELINE_CURSOR_CYCLE" }
            } while (cursor != null)
            val idleReceiptIsCurrent = idleGenerationRevision?.let {
                isIdleGenerationReceiptCurrent(conversationId, it)
            } == true
            val completeMessages = messages.values.map { message ->
                if (idleReceiptIsCurrent && message.sender == "assistant" && message.state == "STREAMING")
                    message.copy(state = "CONFIRMED") else message
            }
            return TimelinePage(completeMessages, null, revision, deletedMessageIds, idleGenerationRevision)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (cause: Exception) {
            if (attempt == 2 || !cause.message.orEmpty().contains("CURSOR_EXPIRED")) throw cause
        }
    }
    error("TIMELINE_RESYNC_FAILED")
}

interface AgentCommandCatalogRepository {
    suspend fun get(gatewayId: String, languageCode: String): AgentCommandCatalog
}

data class RecoveredAttachmentDraft(val draft: AttachmentDraft, val content: StagedAttachmentContent, val remoteId: String?,
    val clientAttachmentId: String? = null)
interface AttachmentDraftRecoveryStore {
    fun load(): List<RecoveredAttachmentDraft>
    fun save(records: List<RecoveredAttachmentDraft>)
}

interface AttachmentDraftCoordinator {
    fun restoredDrafts(): List<AttachmentDraft> = emptyList()
    suspend fun prepare(selection: LocalAttachmentSelection): AttachmentDraft
    suspend fun armSubmission(draftId: String, revision: Long): PendingSubmissionIntent
    suspend fun cancelSubmission(intentId: String): CancelSubmissionResult
    fun observe(draftId: String): Flow<AttachmentDraftState>
    fun retry(draftId: String)
    suspend fun discard(draftId: String)
    /** 远端受理且历史原件保存成功后，释放上传临时内容。 */
    suspend fun releaseAfterSubmit(draftId: String)
    /** 使用已核验的本地源保存历史原件，成功后才可释放上传暂存。 */
    suspend fun archiveSubmitted(conversationId: String, draftId: String, media: HistoricalMediaPort) {
        error("ATTACHMENT_ARCHIVE_UNAVAILABLE")
    }
    /** 重建本地上传源，不代表重新上传、重新确认或重新发送消息。 */
    suspend fun ensureSubmittedSource(conversationId: String, draftId: String, media: HistoricalMediaPort) {
        error("ATTACHMENT_RECOVERY_UNAVAILABLE")
    }
    /** 仅供用户明确重试；等待本次远端核验/上传结束，不发送消息。 */
    suspend fun retrySubmittedSource(conversationId: String, draftId: String, media: HistoricalMediaPort): AttachmentDraft =
        error("ATTACHMENT_RECOVERY_UNAVAILABLE")
    fun remoteAttachmentId(draftId: String): String?
}

interface ConversationMirrorStore {
    suspend fun open(scope: MirrorScope): MirrorSession
    suspend fun lock(scope: MirrorScope)
    suspend fun wipeForUnpairing(scope: MirrorScope)
    suspend fun wipeForLocalAccountRemoval(scope: MirrorScope)
}

/**
 * The generation identity the Gateway has actually told us about.
 *
 * Cancellation needs a real `generationId`, and the phone may only use one the
 * server issued. Until an event carries it there is nothing to cancel, and the
 * UI must say so rather than invent an id or pretend the stop succeeded.
 */
interface GenerationTracker {
    val generationId: kotlinx.coroutines.flow.StateFlow<String?>
    val generationState:kotlinx.coroutines.flow.StateFlow<GenerationState>? get()=null
}

data class HistoricalMediaMetadata(val conversationId:String,val attachmentId:String,val filename:String,val mediaType:String,
    val sizeBytes:Long,val sha256:String,val remoteAvailable:Boolean,val estimatedLocalBytes:Long)
/** 正文权威提交后产生的媒体变更；没有单条修订的删除是永久屏障。 */
data class HistoricalMediaTransition(val conversationId: String, val messageId: String, val revision: Long?,
    val attachmentIds: List<String>, val deleted: Boolean) {
    init { require(revision == null || revision >= 0); require(deleted || revision != null) }
}
interface HistoricalMediaPort {
    suspend fun metadata(conversationId:String,attachmentId:String):HistoricalMediaMetadata
    suspend fun retain(metadata:HistoricalMediaMetadata)
    fun isRetained(attachmentId:String):Boolean
    fun preview(attachmentId:String):ByteArray?
    fun remove(attachmentId:String)
    fun remove(conversationId: String, attachmentId: String) = remove(attachmentId)
    suspend fun localMetadata(conversationId: String, attachmentId: String): HistoricalMediaMetadata? = null
    suspend fun ensureLocal(conversationId: String, attachmentId: String): HistoricalMediaMetadata =
        metadata(conversationId, attachmentId).also { retain(it) }
    suspend fun archiveSubmitted(conversationId: String, attachmentId: String, filename: String, mediaType: String,
        content: AttachmentContentSource, sizeBytes: Long, sha256: String, localDraftId: String? = null) {
        error("ATTACHMENT_ARCHIVE_UNAVAILABLE")
    }
    suspend fun submittedMetadata(conversationId: String, localDraftId: String): HistoricalMediaMetadata? = null
    suspend fun submittedDraftIds(conversationId: String, attachmentId: String): List<String> = emptyList()
    suspend fun openOriginal(conversationId: String, attachmentId: String): InputStream = error("LOCAL_MEDIA_UNAVAILABLE")
    fun preview(conversationId: String, attachmentId: String): ByteArray? = preview(attachmentId)
    suspend fun applyTransition(transition: HistoricalMediaTransition) { error("MEDIA_REVISION_UNSUPPORTED") }
    /** 在任何等待或读取之前取得原件代数；删除、恢复和账号清理都会使旧值失效。 */
    fun originalEpoch(conversationId: String, attachmentId: String): String = error("MEDIA_REVISION_UNSUPPORTED")
    /** 与媒体变更共用提交锁，保证最终发布不能跨越已接受的删除或恢复。 */
    fun <T> withOriginalEpoch(conversationId: String, attachmentId: String, epoch: String, commit: () -> T): T =
        error("MEDIA_REVISION_UNSUPPORTED")
}

data class AttachmentDownloadResult(val uri: String, val filename: String, val recoveryWarningCode: String? = null)
interface AttachmentDownloadPort {
    suspend fun download(conversationId: String, attachmentId: String): AttachmentDownloadResult
}
