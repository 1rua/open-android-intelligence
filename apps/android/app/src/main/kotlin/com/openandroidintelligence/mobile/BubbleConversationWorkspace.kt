package com.openandroidintelligence.mobile

import com.openandroidintelligence.conversation.batch.DebouncePolicy
import com.openandroidintelligence.conversation.data.GatewayCommandCatalogRepository
import com.openandroidintelligence.conversation.data.GatewayConversationRepository
import com.openandroidintelligence.conversation.ports.AttachmentDownloadPort
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.ports.HistoricalMediaPort
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.gateway.approvals.ApprovalClient
import com.openandroidintelligence.gateway.commands.CommandCatalogClient
import com.openandroidintelligence.gateway.conversations.ConversationClient
import com.openandroidintelligence.gateway.events.EventStreamStatusSink
import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.SignedGatewayRequest
import com.openandroidintelligence.gateway.negotiation.GenerationCancelCapability
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicReference

/** Owns bubble controllers independently of the main window's selection and drafts. */
internal class BubbleConversationWorkspace(
    private val profileId: String,
    private val accountId: String,
    private val create: (String) -> WorkbenchController?,
) : AutoCloseable {
    private var closed = false
    private val controllers = mutableSetOf<WorkbenchController>()
    private val detached = mutableSetOf<WorkbenchController>()

    @Synchronized fun open(target: ReplyNotificationTarget): WorkbenchController? {
        if (closed || !accepts(target)) return null
        return create(target.conversationId)?.also { controllers += it }
    }

    fun accepts(target: ReplyNotificationTarget): Boolean =
        target.profileId == profileId && target.accountId == accountId && target.conversationId.isNotBlank()

    /** UI disposal cannot cancel a submission that this session still owns. */
    @Synchronized fun release(controller: WorkbenchController): Boolean {
        if (controller !in controllers) return true
        if (controller.hasActiveWork.value) {
            detached += controller
            return false
        }
        detached -= controller
        controllers.remove(controller)
        controller.close()
        return true
    }

    @Synchronized fun releaseIfIdle(controller: WorkbenchController): Boolean {
        if (controller !in detached || controller.hasActiveWork.value) return false
        return release(controller)
    }

    @Synchronized override fun close() {
        closed = true
        controllers.forEach(WorkbenchController::close)
        controllers.clear()
        detached.clear()
    }
}

/** A dedicated event client/cursor and repository are mandatory: the HTTP stream has one consumer. */
internal fun createBubbleWorkbench(
    scope: CoroutineScope,
    conversationScope: ConversationScope,
    conversationId: String,
    http: GatewayHttpClient,
    status: EventStreamStatusSink,
    approvals: ApprovalClient?,
    generationCancel: GenerationCancelCapability,
    capabilities: Set<String>,
    debouncePolicy: DebouncePolicy,
    media: HistoricalMediaPort? = null,
    downloads: AttachmentDownloadPort? = null,
): WorkbenchController {
    val active = AtomicReference<String?>(null)
    val client = ConversationClient(http)
    val repository = GatewayConversationRepository(client, streamStatus = status, approvals = approvals,
        activeConversationId = active::get)
    val gated = CapabilityGatedConversationRepository(repository, client, generationCancel, active::get)
    val controller = WorkbenchController(
        scope = scope,
        repository = gated,
        catalogRepository = GatewayCommandCatalogRepository(CommandCatalogClient(http)),
        scopeFactory = { conversationScope },
        supportsMessageBatches = setOf("message-batches-v1", "newline-v1").all { it in capabilities },
        supportsAgentCommandNew = "agent-command-new-v1" in capabilities,
        supportsApprovalCards = approvals != null,
        debouncePolicy = debouncePolicy,
        onActiveThreadChanged = { active.set(it); repository.activeConversationChanged() },
        streamHealthSource = gated,
        // Bubble drafts and selection deliberately have no main-workbench persistence.
        media = media,
        downloads = downloads,
        languageCode = java.util.Locale.getDefault().toLanguageTag(),
    )
    http.setCursorRecovery {
        val response = http.execute(SignedGatewayRequest("GET", "/open-android-intelligence/v2/sync/snapshot"))
        check(response.status == 200) { "SNAPSHOT_FAILED" }
        val data = JsonFields.obj(JsonFields.field(JsonFields.obj(Json.parse(response.body.decodeToString())), "data"))
            ?: error("SNAPSHOT_INVALID")
        val baseline = JsonFields.string(data, "baselineCursor") ?: error("SNAPSHOT_INVALID")
        // The bubble keeps no durable timeline; refetch its authoritative view after a gap.
        controller.retryTimeline()
        baseline
    }
    controller.openThread(conversationId)
    return controller
}
