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
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns assistant controllers independently of the main window's selection and drafts.
 *
 * 一个工作区只属于一个 (profileId, accountId, agentId) 身份；身份之内再用
 * sessionToken 区分每次助理会话。账号或 Agent 切换时宿主调用 [closeAndAwait]，
 * 旧身份的发送任务不会被带进新工作区。
 *
 * [busyThreadIds] 刻意不加锁：工作台在投影状态时会同步读取它，若这里再取
 * 工作区监视器，就会与关闭路径形成锁顺序反转。
 */
internal class AssistantConversationWorkspace(
    internal val profileId: String,
    internal val accountId: String,
    internal val agentId: String?,
    private val scope: CoroutineScope,
    private val create: (String) -> WorkbenchController?,
    /** 会话真正关闭时回调；宿主借此释放该会话专用的仓储与事件订阅。 */
    private val onSessionReleased: (String) -> Unit = {},
) : AutoCloseable {
    @Volatile private var closed = false
    private val controllers = ConcurrentHashMap<String, WorkbenchController>()
    /** 界面已释放但仍有进程内发送的会话；终态到达后才真正关闭。 */
    private val detached = ConcurrentHashMap.newKeySet<String>()
    private val observers = ConcurrentHashMap<String, Job>()
    private val _hasActiveWork = MutableStateFlow(false)

    /** 是否有助手会话仍在等待真实终态；账号/Agent 切换与后台保活都读取它。 */
    val hasActiveWork: StateFlow<Boolean> = _hasActiveWork.asStateFlow()

    /** 判断本工作区是否仍属于当前连接与 Agent，避免旧身份借到新会话。 */
    fun matches(profileId: String, accountId: String, agentId: String?): Boolean =
        this.profileId == profileId && this.accountId == accountId && this.agentId == agentId

    /**
     * Returns the controller owned by [sessionToken], creating it on first use.
     *
     * Repeating `open` for the same token is idempotent: an IPC reconnect must not
     * replace a controller whose send is still owned by this session.
     */
    @Synchronized fun open(sessionToken: String): WorkbenchController {
        check(!closed) { "ASSISTANT_WORKSPACE_CLOSED" }
        check(sessionToken.isNotBlank()) { "ASSISTANT_SESSION_TOKEN_REQUIRED" }
        controllers[sessionToken]?.let { return it }
        val controller = checkNotNull(create(sessionToken)) { "ASSISTANT_SESSION_UNAVAILABLE" }
        controllers[sessionToken] = controller
        observers[sessionToken] = scope.launch {
            controller.hasActiveWork.collect {
                publishActiveWork()
                closeIfDetachedIdle(sessionToken, controller)
            }
        }
        publishActiveWork()
        return controller
    }

    /**
     * Releases the UI's claim on a session.
     *
     * A controller with live work stays open until the Gateway reports its terminal
     * status: closing it here would cancel a POST the process still owns. Releasing
     * an unknown or already closed token is a no-op.
     */
    @Synchronized fun release(sessionToken: String) {
        val controller = controllers[sessionToken] ?: return
        if (controller.hasActiveWork.value) {
            detached += sessionToken
            return
        }
        closeOwned(sessionToken, controller)
    }

    /** Conversations this workspace is currently sending to; hosts exclude them from management. */
    fun busyThreadIds(): Set<String> =
        controllers.values.flatMap { it.ownedBusyThreadIds }.toSet()

    /** 指定会话 token 当前展示的线程；没有该会话时为 null。 */
    fun conversationFor(sessionToken: String): String? =
        controllers[sessionToken]?.state?.value?.activeThreadId

    @Synchronized override fun close() {
        closed = true
        val owned = controllers.toMap()
        val jobs = observers.values.toList()
        controllers.clear()
        detached.clear()
        observers.clear()
        jobs.forEach(Job::cancel)
        owned.values.forEach(WorkbenchController::close)
        owned.keys.forEach { token -> runCatching { onSessionReleased(token) } }
        publishActiveWork()
    }

    /**
     * 账号/Agent 切换时关闭整个工作区。
     *
     * 与 [close] 不同，这里等待每个控制器把本地提交收尾；切换期间调用方被取消
     * 也不能让旧会话的关闭半途而废，因此收尾阶段使用 [NonCancellable]。
     */
    suspend fun closeAndAwait() {
        val (owned, jobs) = synchronized(this) {
            if (closed && controllers.isEmpty()) return
            closed = true
            val snapshot = controllers.toMap() to observers.values.toList()
            controllers.clear()
            detached.clear()
            observers.clear()
            snapshot
        }
        jobs.forEach(Job::cancel)
        withContext(NonCancellable) {
            owned.forEach { (token, controller) ->
                try {
                    controller.closeAndAwait()
                } catch (_: Throwable) {
                    // 收尾失败仍要释放；同步 close 至少停止事件订阅与后台任务。
                    runCatching { controller.close() }
                }
                runCatching { onSessionReleased(token) }
            }
        }
        publishActiveWork()
    }

    @Synchronized private fun closeIfDetachedIdle(sessionToken: String, controller: WorkbenchController) {
        if (closed || sessionToken !in detached || controller.hasActiveWork.value) return
        closeOwned(sessionToken, controller)
    }

    private fun closeOwned(sessionToken: String, controller: WorkbenchController) {
        controllers.remove(sessionToken)
        detached -= sessionToken
        observers.remove(sessionToken)?.cancel()
        controller.close()
        runCatching { onSessionReleased(sessionToken) }
        publishActiveWork()
    }

    private fun publishActiveWork() {
        _hasActiveWork.value = controllers.values.any { it.hasActiveWork.value }
    }
}

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
