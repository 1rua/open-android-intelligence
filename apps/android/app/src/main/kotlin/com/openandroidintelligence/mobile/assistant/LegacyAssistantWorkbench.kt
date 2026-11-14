package com.openandroidintelligence.mobile.assistant

import com.openandroidintelligence.conversation.batch.DebouncePolicy
import com.openandroidintelligence.conversation.data.GatewayCommandCatalogRepository
import com.openandroidintelligence.conversation.data.GatewayConversationRepository
import com.openandroidintelligence.conversation.data.InMemoryConversationManagementOperations
import com.openandroidintelligence.conversation.ports.AgentCommandCatalogRepository
import com.openandroidintelligence.conversation.ports.AttachmentDownloadPort
import com.openandroidintelligence.conversation.ports.AttachmentDraftCoordinator
import com.openandroidintelligence.conversation.ports.ConversationManagementOperationStore
import com.openandroidintelligence.conversation.ports.ConversationRepository
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
import com.openandroidintelligence.mobile.CapabilityGatedConversationRepository
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicReference

/**
 * 助手会话工厂：把「构建一个控制器」与「工作区身份」固定在一起。
 *
 * 工作区只证明自己属于某个 (profile, account, agent)；真正创建控制器需要连接
 * 所有者提供的 HTTP 客户端、事件游标、附件协调器等，全部封装在 [open] 里，
 * 由宿主在连接建立时安装、在连接退役时 [close]。
 */
internal class AssistantSessionFactory(
    internal val profileId: String,
    internal val accountId: String,
    internal val scope: CoroutineScope,
    private val open: (agentId: String?, sessionToken: String) -> WorkbenchController?,
    private val onSessionReleased: (String) -> Unit = {},
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    fun create(agentId: String?, sessionToken: String): WorkbenchController? = open(agentId, sessionToken)

    /** 会话控制器真正关闭；宿主借此释放该会话专用的存储与事件订阅。 */
    fun release(sessionToken: String) = onSessionReleased(sessionToken)

    override fun close() = onClose()
}

/**
 * 助手会话的控制器工厂（旧版 Gateway 对话面）。
 *
 * A dedicated event client/cursor and repository are mandatory: the HTTP stream has one consumer.
 */
internal fun createAssistantWorkbench(
    scope: CoroutineScope,
    conversationScope: ConversationScope,
    http: GatewayHttpClient,
    status: EventStreamStatusSink,
    approvals: ApprovalClient?,
    generationCancel: GenerationCancelCapability,
    capabilities: Set<String>,
    debouncePolicy: DebouncePolicy,
    media: HistoricalMediaPort? = null,
    downloads: AttachmentDownloadPort? = null,
    attachmentCoordinator: AttachmentDraftCoordinator? = null,
    managementOperations: ConversationManagementOperationStore = InMemoryConversationManagementOperations(),
    externalBusyThreadIds: () -> Set<String> = { emptySet() },
): WorkbenchController {
    val active = AtomicReference<String?>(null)
    val client = ConversationClient(http)
    val repository = GatewayConversationRepository(client, streamStatus = status, approvals = approvals,
        activeConversationId = active::get,
        managementOperations = managementOperations,
        supportsConversationManagement = "conversation-management-v1" in capabilities)
    val gated = CapabilityGatedConversationRepository(repository, client, generationCancel, active::get)
    val controller = assistantController(
        scope = scope,
        repository = gated,
        catalogRepository = GatewayCommandCatalogRepository(CommandCatalogClient(http)),
        conversationScope = conversationScope,
        capabilities = capabilities,
        debouncePolicy = debouncePolicy,
        supportsApprovalCards = approvals != null,
        media = media,
        downloads = downloads,
        attachmentCoordinator = attachmentCoordinator,
        externalBusyThreadIds = externalBusyThreadIds,
        onActiveThreadChanged = { active.set(it); repository.activeConversationChanged() },
        streamHealthSource = gated,
    )
    http.setCursorRecovery {
        val response = http.execute(SignedGatewayRequest("GET", "/open-android-intelligence/v2/sync/snapshot"))
        check(response.status == 200) { "SNAPSHOT_FAILED" }
        val data = JsonFields.obj(JsonFields.field(JsonFields.obj(Json.parse(response.body.decodeToString())), "data"))
            ?: error("SNAPSHOT_INVALID")
        val baseline = JsonFields.string(data, "baselineCursor") ?: error("SNAPSHOT_INVALID")
        // 助手没有持久时间线；事件缺口后重新拉取它的权威视图。
        controller.retryTimeline()
        baseline
    }
    return controller
}

/**
 * 助手会话的控制器工厂（原生 Agent 会话面）。
 *
 * 原生 Agent 的 `/v2/conversations` 并不存在，因此这里直接使用 Agent 会话仓储；
 * 工作区是临时表面，不挂持久化存储，也不复用主工作台的轮次状态。
 */
internal fun createNativeAssistantWorkbench(
    scope: CoroutineScope,
    conversationScope: ConversationScope,
    repository: ConversationRepository,
    catalogRepository: AgentCommandCatalogRepository,
    capabilities: Set<String>,
    debouncePolicy: DebouncePolicy,
    supportsApprovalCards: Boolean,
    onActiveThreadChanged: (String?) -> Unit,
    media: HistoricalMediaPort? = null,
    downloads: AttachmentDownloadPort? = null,
    attachmentCoordinator: AttachmentDraftCoordinator? = null,
    externalBusyThreadIds: () -> Set<String> = { emptySet() },
): WorkbenchController = assistantController(
    scope = scope,
    repository = repository,
    catalogRepository = catalogRepository,
    conversationScope = conversationScope,
    capabilities = capabilities,
    debouncePolicy = debouncePolicy,
    supportsApprovalCards = supportsApprovalCards,
    media = media,
    downloads = downloads,
    attachmentCoordinator = attachmentCoordinator,
    externalBusyThreadIds = externalBusyThreadIds,
    onActiveThreadChanged = onActiveThreadChanged,
    streamHealthSource = repository as? com.openandroidintelligence.conversation.model.StreamHealthSource,
)

private fun assistantController(
    scope: CoroutineScope,
    repository: ConversationRepository,
    catalogRepository: AgentCommandCatalogRepository,
    conversationScope: ConversationScope,
    capabilities: Set<String>,
    debouncePolicy: DebouncePolicy,
    supportsApprovalCards: Boolean,
    media: HistoricalMediaPort?,
    downloads: AttachmentDownloadPort?,
    attachmentCoordinator: AttachmentDraftCoordinator?,
    externalBusyThreadIds: () -> Set<String>,
    onActiveThreadChanged: (String?) -> Unit,
    streamHealthSource: com.openandroidintelligence.conversation.model.StreamHealthSource?,
): WorkbenchController = WorkbenchController(
    scope = scope,
    repository = repository,
    catalogRepository = catalogRepository,
    scopeFactory = { conversationScope },
    // 助手是临时会话面：不恢复主工作台的上次线程，也不写任何持久化。
    restoreLastThread = false,
    supportsMessageBatches = setOf("message-batches-v1", "newline-v1").all { it in capabilities },
    supportsAgentCommandNew = "agent-command-new-v1" in capabilities,
    supportsApprovalCards = supportsApprovalCards,
    supportsConversationManagement = "conversation-management-v1" in capabilities,
    externalBusyThreadIds = externalBusyThreadIds,
    debouncePolicy = debouncePolicy,
    onActiveThreadChanged = onActiveThreadChanged,
    streamHealthSource = streamHealthSource,
    attachmentCoordinator = attachmentCoordinator,
    onClosed = { (attachmentCoordinator as? AssistantAttachmentDraftCoordinator)?.close(scope) },
    // 助手草稿与选择刻意不进入主工作台的恢复集。
    media = media,
    downloads = downloads,
    languageCode = java.util.Locale.getDefault().toLanguageTag(),
)
