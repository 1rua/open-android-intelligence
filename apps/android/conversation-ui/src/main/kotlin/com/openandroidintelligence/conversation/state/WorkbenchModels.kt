package com.openandroidintelligence.conversation.state

import com.openandroidintelligence.conversation.model.ClientMessageId
import com.openandroidintelligence.conversation.model.ComposerState
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.ports.AgentCommandCatalog
import com.openandroidintelligence.conversation.ports.ConversationOrganization
import com.openandroidintelligence.conversation.ports.ConversationSummary

/**
 * 供界面渲染的单条时间线条目：既可能是服务端镜像消息，也可能是等待网关受理的本地发送单元。
 *
 * 本地单元保留各自的独立身份，确保防抖合并批次不会丢失任何成员；
 * 受理状态始终以服务端回执为准，不由本地定时器自行判定。
 */
data class TimelineEntry(
    val key: String,
    val sender: String,
    val text: String,
    val isUser: Boolean,
    val timestamp: Long,
    val pendingAcceptance: Boolean,
    val batchGroupId: String?,
    val attachments: List<com.openandroidintelligence.conversation.model.TimelineAttachment> = emptyList(),
    val isStreaming: Boolean = false,
    val messageStatus: com.openandroidintelligence.conversation.ports.AgentMessageStatus? = null,
    val messageStatusErrorCode: com.openandroidintelligence.conversation.ports.AgentMessageErrorCode? = null,
    val submissionFailureCode: String? = null,
    /**
     * 当本行属于系统导航行而非普通消息时，指向其创建的新会话 ID。
     *
     * 发送 `/new` 的原会话会保留一份回执以记录新创建的会话，确保即便用户在结果到达前
     * 已离开原会话，新会话依然可达。回执仅用于本地导航，不属于网关聊天正文。
     */
    val systemThreadId: String? = null,
    /**
     * 当本行用于承载网关发起的审批卡片（而非普通消息）时的卡片状态。
     *
     * 审批是网关发起的请求，既非用户输入也非 Agent 回复，因此作为独立行存在：
     * 消息折叠与去重裁剪不会作用于它，卡片自身独立维护决策状态。
     */
    val approval: ApprovalCardState? = null,
    /** 明确的客户端身份优先于旧历史的正文相似判断。 */
    val clientMessageId: ClientMessageId? = null,
    val generationId: String? = null,
    val executionSteps: List<com.openandroidintelligence.conversation.model.NativeExecutionStep> = emptyList(),
    val localPlaceholder: Boolean = false,
    /** 仅用于展示的执行过程事实；该行在离线镜像历史中不作为助手消息持久化。 */
    val executionFact: Boolean = false,
    /** 该执行过程所属任务的明确状态，独立于消息正文是否仍在流式。 */
    val executionGenerationState: GenerationState? = null,
    /** UI 临时来源；历史装载重新建立快照基线，不进入 checkpoint。 */
    val presentation: StreamPresentationMetadata? = null,
    val reasoning: List<com.openandroidintelligence.conversation.model.MessageReasoning> = emptyList(),
    /** 统一有序异构执行步骤（思考与工具调用按到达顺序/内容块原始索引交错排列）。 */
    val timelineSteps: List<com.openandroidintelligence.conversation.model.ExecutionTimelineStep> = emptyList(),
    /** 该条目首次出现在时间线中的单调顺序号（协议 seq 或本地到达序）。 */
    val orderSequence: Long? = null,
    val isMeta: Boolean = false,
)

data class WorkbenchUiState(
    val threads: Loadable<List<ConversationSummary>> = Loadable.Idle,
    val timeline: Loadable<List<TimelineEntry>> = Loadable.Idle,
    val catalog: Loadable<AgentCommandCatalog> = Loadable.Idle,
    val activeThreadId: String? = null,
    val activeThreadTitle: String = "",
    val generation: GenerationState = GenerationState.IDLE,
    val draft: String = "",
    val draftEpoch: Long = 0L,
    val draftVersion: Long = 0L,
    val timelineOpenedAtNs: Long = 0L,
    val timelineRequestEpoch: Long = 0L,
    val timelineDataSource: String? = null,
    val composer: ComposerState = ComposerState.EDITING,
    val attachments: List<com.openandroidintelligence.conversation.model.AttachmentDraft> = emptyList(),
    /** 防抖窗口期内收集的待合并批次成员（最新项在最后）。 */
    val pendingBatch: List<TimelineEntry> = emptyList(),
    /** 等待当前 Agent 轮次结束后投递的手机端补充修正消息。 */
    val pendingDeliveries: List<com.openandroidintelligence.conversation.ports.PendingDelivery> = emptyList(),
    val notice: String? = null,
    /** 网关创建会话请求正在进行中时为 true。 */
    val creatingThread: Boolean = false,
    /** 发起创建会话请求时的源会话 ID（若存在）。 */
    val creationSourceThreadId: String? = null,
    /** 入站回复流通道是否存活；通道断开时用于向界面解释无响应原因。 */
    val streamHealth: com.openandroidintelligence.conversation.model.StreamHealth =
        com.openandroidintelligence.conversation.model.StreamHealth.IDLE,
    /**
     * 当前连接的网关是否支持通过交互卡片答复审批请求。
     *
     * 为 false 时界面必须明确体现该事实：仍通过文本命令完成审批，而不绘制看似可点的无效卡片。
     * 该标记随每次 [WorkbenchController] 更新重新断言，确保不会与网关实际协商的能力发生漂移。
     *
     * 默认值为 false，反映的是网关真实能力而非界面偏好：在尚未握手协商出支持能力前，
     * 手机端只能诚实判定为不支持，避免在无真实路由的情况下渲染可点击按钮。
     */
    val approvalCardsSupported: Boolean = false,
    val isOnline: Boolean = true,
    /** Agent 在线与支持发送分别呈现，缺少发送能力不能冒充离线。 */
    val sendSupported: Boolean = true,
    val hasRecoverableSubmissions: Boolean = false,
    /** 未确认的发送结果单独保留，不阻止用户继续发送新消息。 */
    val hasUnknownSubmissionOutcome: Boolean = false,
    /** 本地发送身份装载完成之前不允许把旧消息误作一条新提交。 */
    val historyHydrated: Boolean = true,
    val organization: ConversationOrganization = ConversationOrganization(),
    /** 当前浏览器选中的目录，同时作为新建会话的默认归属目录。 */
    val selectedFolderId: String? = null,
    val pendingFolderAssignments: Map<String, String> = emptyMap(),
    val conversationManagementSupported: Boolean = false,
    val conversationDeleteSupported: Boolean = false,
    val conversationArchiveSupported: Boolean = false,
    val conversationUnarchiveSupported: Boolean = false,
    val conversationRenameSupported: Boolean = false,
    /** 原生 Agent 文件夹与排序偏好保存在手机端加密存储中。 */
    val organizationStoredLocally: Boolean = false,
    val managingThreads: Boolean = false,
    val busyThreadIds: Set<String> = emptySet(),
    /** 正在随网关发送请求上传中的附件 ID，不再作为新输入草稿的一部分。 */
    val inFlightAttachmentIds: Set<String> = emptySet(),
) {
    /** 在持久化目录分配请求尚未落盘前，保持已确认新建会话在对应文件夹下可见。 */
    val browserOrganization: ConversationOrganization get() =
        if (pendingFolderAssignments.isEmpty()) organization
        else organization.copy(assignments=organization.assignments +
            pendingFolderAssignments.filter { (id, folderId) -> id !in organization.assignments && organization.folders.any { it.folderId == folderId } })
    val canSend: Boolean get() = isOnline && sendSupported && historyHydrated &&
        (draft.isNotBlank() || attachments.any { it.id.value !in inFlightAttachmentIds }) &&
        !managingThreads && !activeThreadArchived &&
        composer != ComposerState.SUBMITTING && composer != ComposerState.WAITING_ATTACHMENTS
    val activeThreadArchived: Boolean get() = (threads as? Loadable.Ready)?.value?.any { it.id.value == activeThreadId && it.archived } == true
}
