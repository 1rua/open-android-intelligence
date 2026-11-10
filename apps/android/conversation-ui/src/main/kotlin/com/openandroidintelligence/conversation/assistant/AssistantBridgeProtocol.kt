package com.openandroidintelligence.conversation.assistant

import com.openandroidintelligence.conversation.model.ApprovalChoice
import com.openandroidintelligence.conversation.model.ApprovalId
import com.openandroidintelligence.conversation.model.ApprovalOption
import com.openandroidintelligence.conversation.model.ApprovalOptionStyle
import com.openandroidintelligence.conversation.model.ApprovalOutcome
import com.openandroidintelligence.conversation.model.ApprovalRequest
import com.openandroidintelligence.conversation.model.AttachmentLocalState
import com.openandroidintelligence.conversation.model.ClientMessageId
import com.openandroidintelligence.conversation.model.ComposerState
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.model.NativeExecutionStatus
import com.openandroidintelligence.conversation.model.NativeExecutionStep
import com.openandroidintelligence.conversation.model.TimelineAttachment
import com.openandroidintelligence.conversation.ports.AgentMessageStatus
import com.openandroidintelligence.conversation.state.ApprovalCardState
import com.openandroidintelligence.conversation.state.TimelineEntry
import org.json.JSONArray
import org.json.JSONObject

/**
 * 助理桥接协议 v2 的线上 DTO 与编解码。
 *
 * 与 v1 的 Messenger 状态相比，v2 的差异只有一处：状态里携带完整的
 * 时间线投影（执行步骤、审批卡、消息状态、附件元数据），而不是被裁成
 * `id/sender/text/streaming/attachments` 的摘要。协议仍然只传身份和投影，
 * 不传凭据、provider URI、文件路径或字节内容。
 *
 * 所有字段都是**可丢弃的投影**：解码失败或版本未知时返回 null，调用方保留
 * 上一帧，而不是把坏数据当成"未连接"或"空对话"。
 */
const val ASSISTANT_BRIDGE_VERSION = 2

/**
 * 助手桥接命令允许出现的 Bundle 键（协议字段封闭）。
 *
 * 两端（助理进程的 `AssistantBridgeClient`、主进程的 `AssistantBridgeService`）都必须
 * 引用这**同一份**集合：任何一侧新增字段而另一侧没同步，命令会被整条丢弃，表现为
 * 「点了没反应」，很难在真机上定位。测试也直接断言这份集合，避免手写白名单漂移。
 */
val ASSISTANT_BRIDGE_ALLOWED_KEYS: Set<String> = setOf(
    "version", "token", "command", "text", "content", "size",
    "durationMs", "mediaType", "draftId", "approvalId", "choice", "target", "visible",
    "uris", "count",
    // 交接请求代次：回执必须能对应到「这一次」请求，否则上一轮的终态会被当成这一轮的结果。
    "handoffId",
)

/**
 * 交接时等待未就绪附件（上传/校验中）完成的上限。
 *
 * 规格 §3.4：交接只接管已 `VERIFIED` 的附件；仍有附件在传时必须等它完成（有上限），
 * 超时按严格交接处理。3 秒足以覆盖正常大小的图片/文件，又不会让用户觉得上滑没反应。
 */
const val ASSISTANT_HANDOFF_ATTACHMENT_WAIT_MILLIS = 3_000L

/**
 * 浮层等待主进程交接回执的上限。
 *
 * 必须**大于**服务端的 [ASSISTANT_HANDOFF_ATTACHMENT_WAIT_MILLIS]，否则服务端还在等
 * 附件时浮层就先把结果判成超时，用户看到的就不是真实原因。
 */
const val ASSISTANT_HANDOFF_RESULT_TIMEOUT_MILLIS = 5_000L

/** 单条消息正文被预算截断时插入的固定标记；接收方据此显示"已截断"。 */
const val TEXT_TRUNCATION_MARKER = "\n\n…[内容已截断]…\n\n"

/** 用户消息的 sender 字面量（协议既有取值，不做改名）。 */
private const val SENDER_USER = "user"

private const val THEME_SYSTEM = "SYSTEM"
private const val DEFAULT_PALETTE = "INDIGO"
private const val APPROVAL_WAITING = "WAITING"
private const val APPROVAL_TEXT_ONLY = "TEXT_ONLY"
private const val APPROVAL_SUBMITTING_PREFIX = "SUBMITTING:"
private const val APPROVAL_RESOLVED_PREFIX = "RESOLVED:"

// ---------------------------------------------------------------------------
// DTO
// ---------------------------------------------------------------------------

/** 一条时间线的线上投影；只包含渲染所需的字段。 */
data class AssistantTimelineEntryDto(
    val id: String = "",
    val sender: String = "",
    val text: String = "",
    val streaming: Boolean = false,
    val textTruncated: Boolean = false,
    val attachments: List<AssistantAttachmentDto> = emptyList(),
    val executionSteps: List<AssistantExecutionStepDto> = emptyList(),
    val approval: AssistantApprovalDto? = null,
    /** `AgentMessageStatus.wireValue`；未知值解码为 null。 */
    val messageStatus: String? = null,
    val submissionFailureCode: String? = null,
    val clientMessageId: String? = null,
    val localPlaceholder: Boolean = false,
    /**
     * 该条消息所属的生成任务身份。
     *
     * 不能省略：渲染层的执行段入口以 `generationId` 非空为门槛
     * （`TimelineDisplay.build()`），缺它时工具卡与思考过程整体退化，
     * 最终只剩下一条空气泡。
     */
    val generationId: String? = null,
    /** 该行是「宿主工具调用事实」而不是一条消息；与 `conversation-domain` 的同名字段对应。 */
    val executionFact: Boolean = false,
    /** 该执行段所属任务的明确状态名；未知值解码为 null，不伪造终态。 */
    val executionGenerationState: String? = null,
)

/**
 * 附件元数据。
 *
 * [localKey] 是**本进程**定位本地字节的键（当前取附件草稿 ID）；线上不传
 * 缩略图、原图或文件路径。字节由各进程自己的媒体端口读取。
 */
data class AssistantAttachmentDto(
    val draftId: String = "",
    val filename: String = "",
    val mediaType: String = "",
    val isImage: Boolean = false,
    val localKey: String = "",
    /** `AttachmentLocalState` 名；未知值解码为 `PENDING`。 */
    val localState: String = AttachmentLocalState.PENDING.name,
)

/** 一次工具调用；[status] 是 `NativeExecutionStatus` 名。 */
data class AssistantExecutionStepDto(
    val stepId: String = "",
    val toolName: String = "",
    val status: String = NativeExecutionStatus.UNKNOWN.name,
    val arguments: String = "",
    val output: String? = null,
    /**
     * 步骤自身的修订号。
     *
     * 宿主会为同一步骤推送多次快照；接收方靠它判断「这一帧更新」，缺它就只能按到达
     * 顺序接受，迟到的 RUNNING 快照会覆盖已完成的结论。
     */
    val revision: Long = 0L,
    /** 步骤调用所在的源消息身份；用于把步骤放回真实位置，而不是凭时间猜。 */
    val sourceMessageId: String? = null,
    /** 原调用事件时间（毫秒）；结果更新不得改写调用位置。 */
    val occurredAt: Long? = null,
)

/** 一个审批选项；意图（[choice]）与样式分开，样式只是展示提示。 */
data class AssistantApprovalOptionDto(
    val choice: String = ApprovalChoice.UNKNOWN.wireValue,
    val label: String? = null,
    val style: String = ApprovalOptionStyle.NEUTRAL.wireValue,
)

/**
 * 一张审批卡。
 *
 * [state] 是封闭的生命周期 token：
 * - `WAITING`
 * - `SUBMITTING:<choice wire>`（保留用户按下的档位）
 * - `RESOLVED:<outcome wire>`（Gateway 已裁决）
 * - `TEXT_ONLY`
 *
 * 未知 token 按 `WAITING` 处理：卡片仍可显示，但不会伪造一个已裁决结果。
 */
data class AssistantApprovalDto(
    val approvalId: String = "",
    val state: String = APPROVAL_WAITING,
    val command: String = "",
    val reason: String = "",
    val expiresAt: Long = 0L,
    val options: List<AssistantApprovalOptionDto> = emptyList(),
)

/** 当前账号投影；不含凭据。 */
data class AssistantAccountDto(
    val profileId: String = "",
    val accountId: String = "",
    val agentName: String = "",
)

/** 一次性提示；[code] 是稳定诊断码，[message] 是已本地化文案。 */
data class AssistantNoticeDto(
    val code: String = "",
    val message: String = "",
)

/** 外观偏好；与主进程保存的设置一一对应。 */
data class AssistantAppearanceDto(
    val theme: String = THEME_SYSTEM,
    val dynamicColor: Boolean = false,
    val palette: String = DEFAULT_PALETTE,
    val reduceMotion: Boolean = false,
)

/** 交接请求的三种状态，与 [AssistantHandoffDto.state] 的线上字面量一一对应。 */
enum class AssistantHandoffState(val wireValue: String) {
    /** 主进程正在接管；客户端应继续等待回执。 */
    PENDING("PENDING"),

    /** 主进程已经接管草稿与附件，可以打开主 App 并关闭浮层。 */
    ACCEPTED("ACCEPTED"),

    /** 主进程拒绝（未连接、账号/Agent 不一致等）；[AssistantHandoffDto.reason] 是原因原文。 */
    REJECTED("REJECTED"),
    ;

    companion object {
        fun of(value: String?): AssistantHandoffState? = entries.firstOrNull { it.wireValue == value }
    }
}

/**
 * 一次交接尝试的结果。
 *
 * 属于**状态**投影而不是命令字段：交接是主进程发起的动作，客户端只读结果。
 * 没有这个字段的旧帧解码为 null（= 尚未请求过），不能用默认值伪造一次成功的交接。
 */
data class AssistantHandoffDto(
    /**
     * 本次回执对应的请求代次。
     *
     * 客户端每次上滑都会带一个新的 `handoffId`，回执原样带回。**没有代次就不能比较**：
     * 上一轮的 REJECTED 会留在状态帧里，下一轮请求的 `collect` 会立刻重放它并把本轮
     * 判成失败，随后真正的 ACCEPTED 被忽略——主进程已经接管草稿，浮层却停在失败状态。
     * 空字符串表示旧主进程不认识这个字段，此时调用方退回「只看终态」的宽容策略。
     */
    val handoffId: String = "",
    val state: String = AssistantHandoffState.PENDING.wireValue,
    /** 已本地化的原因；ACCEPTED/PENDING 时为空。 */
    val reason: String = "",
    /** 主进程接管的会话身份；ACCEPTED 时才有值（尚未产生会话时为 null）。 */
    val conversationId: String? = null,
    /** 交接目标身份：助手据此构造打开主 App 的深链，不需要额外的命令字段。 */
    val profileId: String = "",
    val accountId: String = "",
    val agentId: String? = null,
    /** 未能随草稿迁移的附件数（仍在上传/校验或超出上限）；0 表示全部带过去了。 */
    val skippedAttachments: Int = 0,
)

/**
 * 助理进程可见的完整状态。
 *
 * [revision] 单调递增，供接收方丢弃乱序帧；[timelineTruncated] 表示
 * **时间线条目被整个丢弃过**（单条正文截断由每条自己的 `textTruncated` 标记）。
 */
data class AssistantStateDto(
    val version: Int = ASSISTANT_BRIDGE_VERSION,
    val revision: Long = 0L,
    val connected: Boolean = false,
    val account: AssistantAccountDto? = null,
    val sessionId: String? = null,
    val generation: String = GenerationState.IDLE.name,
    val composer: String = ComposerState.EDITING.name,
    val creatingThread: Boolean = false,
    val draft: String = "",
    val activeThread: String? = null,
    val timeline: List<AssistantTimelineEntryDto> = emptyList(),
    val timelineTruncated: Boolean = false,
    val attachments: List<AssistantAttachmentDto> = emptyList(),
    val notice: AssistantNoticeDto? = null,
    val appearance: AssistantAppearanceDto = AssistantAppearanceDto(),
    /** 最近一次交接尝试的结果；从未请求过时为 null（旧帧同样为 null）。 */
    val handoff: AssistantHandoffDto? = null,
)

// ---------------------------------------------------------------------------
// 序列化
// ---------------------------------------------------------------------------

/** 把状态编码为 JSON；失败时抛出，由调用方决定是否重试。 */
fun encodeState(state: AssistantStateDto): String = JSONObject().apply {
    put("version", state.version)
    put("revision", state.revision)
    put("connected", state.connected)
    state.account?.let { put("account", it.toJson()) }
    state.sessionId?.let { put("sessionId", it) }
    put("generation", state.generation)
    put("composer", state.composer)
    put("creatingThread", state.creatingThread)
    put("draft", state.draft)
    state.activeThread?.let { put("activeThread", it) }
    put("timeline", state.timeline.toJsonArray { entry -> entry.toJson() })
    put("timelineTruncated", state.timelineTruncated)
    put("attachments", state.attachments.toJsonArray { attachment -> attachment.toJson() })
    state.notice?.let { put("notice", it.toJson()) }
    put("appearance", state.appearance.toJson())
    state.handoff?.let { put("handoff", it.toJson()) }
}.toString()

/**
 * 解析 JSON 状态。
 *
 * 根不是对象、版本不是 v2，或任何解析异常时返回 null；绝不返回一个"默认状态"
 * 来冒充成功解析。
 */
fun decodeState(payload: String): AssistantStateDto? = runCatching {
    val root = JSONObject(payload)
    // 未知版本整帧丢弃：调用方保留上一帧，而不是把新格式误读成旧字段。
    if (root.optInt("version", -1) != ASSISTANT_BRIDGE_VERSION) null else parseState(root)
}.getOrNull()

private fun parseState(root: JSONObject): AssistantStateDto = AssistantStateDto(
    version = ASSISTANT_BRIDGE_VERSION,
    revision = root.optLong("revision", 0L),
    connected = root.optBoolean("connected", false),
    account = root.optJSONObject("account")?.toAccountDto(),
    sessionId = root.stringOrNull("sessionId"),
    generation = root.stringOrEmpty("generation").ifBlank { GenerationState.IDLE.name },
    composer = root.stringOrEmpty("composer").ifBlank { ComposerState.EDITING.name },
    creatingThread = root.optBoolean("creatingThread", false),
    draft = root.stringOrEmpty("draft"),
    activeThread = root.stringOrNull("activeThread"),
    timeline = root.optJSONArray("timeline").objects().map { it.toTimelineEntryDto() },
    timelineTruncated = root.optBoolean("timelineTruncated", false),
    attachments = root.optJSONArray("attachments").objects().map { it.toAttachmentDto() },
    notice = root.optJSONObject("notice")?.toNoticeDto(),
    appearance = root.optJSONObject("appearance")?.toAppearanceDto() ?: AssistantAppearanceDto(),
    handoff = root.optJSONObject("handoff")?.toHandoffDto(),
)

/** 把状态里的时间线投影还原为界面使用的 [TimelineEntry]。 */
fun decodeTimeline(dto: AssistantStateDto): List<TimelineEntry> =
    dto.timeline.map { it.toTimelineEntry() }

// ---------------------------------------------------------------------------
// DTO ↔ 领域模型
// ---------------------------------------------------------------------------

/** 把一条界面条目投影为线上 DTO；正文截断标记决定 `textTruncated`。 */
fun TimelineEntry.toDto(): AssistantTimelineEntryDto = AssistantTimelineEntryDto(
    id = key,
    sender = sender,
    text = text,
    streaming = isStreaming,
    textTruncated = text.contains(TEXT_TRUNCATION_MARKER),
    attachments = attachments.map { it.toAssistantAttachmentDto() },
    executionSteps = executionSteps.map { it.toAssistantExecutionStepDto() },
    approval = approval?.toAssistantApprovalDto(),
    messageStatus = messageStatus?.wireValue,
    submissionFailureCode = submissionFailureCode,
    clientMessageId = clientMessageId?.value,
    localPlaceholder = localPlaceholder,
    generationId = generationId,
    executionFact = executionFact,
    executionGenerationState = executionGenerationState?.name,
)

/** 把一条线上投影还原为界面条目。 */
fun AssistantTimelineEntryDto.toTimelineEntry(): TimelineEntry = TimelineEntry(
    key = id,
    sender = sender,
    text = text,
    isUser = sender == SENDER_USER,
    timestamp = 0L,
    pendingAcceptance = false,
    batchGroupId = null,
    attachments = attachments.map { it.toTimelineAttachment() },
    isStreaming = streaming,
    messageStatus = AgentMessageStatus.entries.firstOrNull { it.wireValue == messageStatus },
    submissionFailureCode = submissionFailureCode,
    approval = approval?.toApprovalCardState(),
    clientMessageId = clientMessageId?.takeIf { it.isNotBlank() }?.let { runCatching { ClientMessageId(it) }.getOrNull() },
    executionSteps = executionSteps.map { it.toNativeExecutionStep() },
    localPlaceholder = localPlaceholder,
    generationId = generationId,
    executionFact = executionFact,
    // 未知状态名折成 null：宁可「不知道任务结论」，也不能猜成已完成或失败。
    executionGenerationState = GenerationState.entries.firstOrNull { it.name == executionGenerationState },
)

private fun TimelineAttachment.toAssistantAttachmentDto(): AssistantAttachmentDto = AssistantAttachmentDto(
    draftId = draftId,
    filename = filename,
    mediaType = mediaType,
    isImage = isImage,
    localKey = draftId,
    localState = localState.name,
)

private fun AssistantAttachmentDto.toTimelineAttachment(): TimelineAttachment {
    val parsedState = AttachmentLocalState.entries.firstOrNull { it.name == localState }
        ?: AttachmentLocalState.PENDING
    return TimelineAttachment(
        draftId = draftId,
        filename = filename,
        mediaType = mediaType,
        savedOffline = parsedState == AttachmentLocalState.SAVED,
        localState = parsedState,
    )
}

private fun NativeExecutionStep.toAssistantExecutionStepDto(): AssistantExecutionStepDto =
    AssistantExecutionStepDto(
        stepId = stepId,
        toolName = toolName,
        status = status.name,
        arguments = arguments,
        output = output,
        revision = revision,
        sourceMessageId = sourceMessageId,
        occurredAt = occurredAt,
    )

private fun AssistantExecutionStepDto.toNativeExecutionStep(): NativeExecutionStep = NativeExecutionStep(
    stepId = stepId,
    // 线上没有独立的 eventId；stepId 在宿主侧就是稳定身份，沿用它而不编造新值。
    eventId = stepId,
    toolName = toolName,
    arguments = arguments,
    output = output,
    status = NativeExecutionStatus.entries.firstOrNull { it.name == status }
        ?: NativeExecutionStatus.UNKNOWN,
    revision = revision,
    sourceMessageId = sourceMessageId,
    occurredAt = occurredAt,
)

private fun ApprovalCardState.toAssistantApprovalDto(): AssistantApprovalDto = AssistantApprovalDto(
    approvalId = request.approvalId.value,
    state = when (this) {
        is ApprovalCardState.Waiting -> APPROVAL_WAITING
        is ApprovalCardState.Submitting -> APPROVAL_SUBMITTING_PREFIX + choice.wireValue
        is ApprovalCardState.Resolved -> APPROVAL_RESOLVED_PREFIX + outcome.wireValue
        is ApprovalCardState.TextOnly -> APPROVAL_TEXT_ONLY
    },
    command = request.command,
    reason = request.reason,
    expiresAt = request.expiresAt,
    options = request.options.map { it.toAssistantApprovalOptionDto() },
)

private fun ApprovalOption.toAssistantApprovalOptionDto(): AssistantApprovalOptionDto =
    AssistantApprovalOptionDto(choice = choice.wireValue, label = label, style = style.wireValue)

private fun AssistantApprovalOptionDto.toApprovalOption(): ApprovalOption = ApprovalOption(
    choice = ApprovalChoice.of(choice),
    label = label,
    style = ApprovalOptionStyle.of(style),
)

private fun AssistantApprovalDto.toApprovalCardState(): ApprovalCardState? {
    // 无法构成合法身份时只能丢弃这张卡：伪造一个 approvalId 会让按键指向不存在的审批。
    val id = runCatching { ApprovalId(approvalId) }.getOrNull() ?: return null
    val request = ApprovalRequest(
        approvalId = id,
        conversationId = null,
        command = command,
        reason = reason,
        severity = null,
        options = options.map { it.toApprovalOption() },
        timeoutSeconds = 0L,
        // 线上只承诺 expiresAt；requestedAt 取同一个截止时刻，避免本地倒计时重算。
        requestedAt = expiresAt,
        expiresAt = expiresAt,
    )
    return when {
        state == APPROVAL_TEXT_ONLY -> ApprovalCardState.TextOnly(request, commands = listOf(command))
        state.startsWith(APPROVAL_SUBMITTING_PREFIX) ->
            ApprovalCardState.Submitting(request, ApprovalChoice.of(state.removePrefix(APPROVAL_SUBMITTING_PREFIX)))
        state.startsWith(APPROVAL_RESOLVED_PREFIX) ->
            ApprovalCardState.Resolved(request, ApprovalOutcome.of(state.removePrefix(APPROVAL_RESOLVED_PREFIX)))
        else -> ApprovalCardState.Waiting(request)
    }
}

// ---------------------------------------------------------------------------
// 预算与截断
// ---------------------------------------------------------------------------

/**
 * 时间线的线上预算。
 *
 * 规则顺序固定，便于两端得到同一结果：
 * 1. 流式尾条（最后一条且 `isStreaming=true`）永不截断正文、永不丢弃；
 * 2. 其余每条正文超过 [MAX_TEXT_CHARS] 时保留首尾并插入 [TEXT_TRUNCATION_MARKER]；
 * 3. 条目数超过 [MAX_ENTRIES] 时从最旧开始丢弃；
 * 4. 时间线 JSON 超过 [MAX_PAYLOAD_BYTES] 时继续从最旧开始丢弃，直到放得下；
 *    其余字段（草稿、外观等）很小，预算只约束时间线本身。
 *
 * 返回的布尔值表示**时间线条目是否被丢弃过**（即 `timelineTruncated` 的取值）；
 * 单条正文截断由该条自己的 `textTruncated` 标记，不会把整个时间线标成截断。
 */
object AssistantWireBudget {
    const val MAX_TEXT_CHARS = 32_000
    const val MAX_ENTRIES = 200
    const val MAX_PAYLOAD_BYTES = 512 * 1024

    /** 首尾保留比例：开头承载更多上下文，尾部保留最新结论。 */
    private const val HEAD_SHARE = 0.6

    fun applyBudget(entries: List<TimelineEntry>): Pair<List<TimelineEntry>, Boolean> {
        if (entries.isEmpty()) return emptyList<TimelineEntry>() to false
        val lastIndex = entries.lastIndex
        val adjusted = entries.mapIndexed { index, entry ->
            // 流式尾条是仍在增长的增量，截掉中段会隐藏最新内容；尾条正文必须完整送达。
            val streamingTail = index == lastIndex && entry.isStreaming
            if (streamingTail) entry else entry.copy(text = truncateText(entry.text))
        }

        // 先按条数封顶；保留最新条目，因此流式尾条必然留下。
        var start = (adjusted.size - MAX_ENTRIES).coerceAtLeast(0)
        var dropped = start > 0

        // 后缀字节和：每条的投影都是独立 JSON 对象，数组开销是分离符 + 方括号。
        val encoded = adjusted.map { entry ->
            encodeTimelineEntry(entry.toDto()).toByteArray(Charsets.UTF_8).size
        }
        val suffix = LongArray(adjusted.size + 1)
        for (index in adjusted.indices.reversed()) {
            suffix[index] = suffix[index + 1] + encoded[index]
        }
        fun timelineBytes(from: Int): Long {
            val count = adjusted.size - from
            if (count <= 0) return 2L // "[]"
            return 2L + (count - 1) + suffix[from]
        }

        // 至少留下最后一条：即使预算仍不够，也宁可超限也不返回空时间线。
        while (start < lastIndex && timelineBytes(start) > MAX_PAYLOAD_BYTES) {
            start += 1
            dropped = true
        }

        return adjusted.subList(start, adjusted.size).toList() to dropped
    }

    /** 测量一组条目编码成时间线 JSON 的实际字节数；与 [applyBudget] 共用同一编码。 */
    fun timelinePayloadBytes(entries: List<TimelineEntry>): Int =
        encodeTimelineArray(entries.map { it.toDto() }).toByteArray(Charsets.UTF_8).size

    /**
     * 首尾保留截断。
     *
     * 结果长度不超过 [MAX_TEXT_CHARS]；切点避开代理对，避免把 emoji 劈成半个字符。
     */
    private fun truncateText(text: String): String {
        if (text.length <= MAX_TEXT_CHARS) return text
        val keep = (MAX_TEXT_CHARS - TEXT_TRUNCATION_MARKER.length).coerceAtLeast(1)
        val headLength = (keep * HEAD_SHARE).toInt()
        val tailLength = keep - headLength

        var headEnd = headLength.coerceIn(0, text.length)
        if (headEnd in 1 until text.length &&
            text[headEnd - 1].isHighSurrogate() && text[headEnd].isLowSurrogate()
        ) {
            headEnd -= 1
        }
        var tailStart = (text.length - tailLength).coerceIn(0, text.length)
        if (tailStart in 1 until text.length &&
            text[tailStart - 1].isHighSurrogate() && text[tailStart].isLowSurrogate()
        ) {
            tailStart += 1
        }
        if (tailStart <= headEnd) return text.take(keep)

        return text.substring(0, headEnd) + TEXT_TRUNCATION_MARKER + text.substring(tailStart)
    }
}

// ---------------------------------------------------------------------------
// JSON 编解码辅助
// ---------------------------------------------------------------------------

private fun AssistantAccountDto.toJson(): JSONObject = JSONObject().apply {
    put("profileId", profileId)
    put("accountId", accountId)
    put("agentName", agentName)
}

private fun AssistantNoticeDto.toJson(): JSONObject = JSONObject().apply {
    put("code", code)
    put("message", message)
}

private fun AssistantHandoffDto.toJson(): JSONObject = JSONObject().apply {
    put("handoffId", handoffId)
    put("state", state)
    put("reason", reason)
    conversationId?.let { put("conversationId", it) }
    put("profileId", profileId)
    put("accountId", accountId)
    agentId?.let { put("agentId", it) }
    put("skippedAttachments", skippedAttachments)
}

private fun JSONObject.toHandoffDto(): AssistantHandoffDto {
    val parsed = AssistantHandoffState.of(stringOrNull("state")) ?: AssistantHandoffState.PENDING
    return AssistantHandoffDto(
        // 未知状态按 PENDING 处理：调用方会继续等待并在超时后失败关闭，
        // 绝不把没读懂的字面量升格成一次成功的交接。
        handoffId = stringOrEmpty("handoffId"),
        state = parsed.wireValue,
        reason = stringOrEmpty("reason"),
        conversationId = stringOrNull("conversationId"),
        profileId = stringOrEmpty("profileId"),
        accountId = stringOrEmpty("accountId"),
        agentId = stringOrNull("agentId"),
        skippedAttachments = optInt("skippedAttachments", 0).coerceAtLeast(0),
    )
}

private fun AssistantAppearanceDto.toJson(): JSONObject = JSONObject().apply {
    put("theme", theme)
    put("dynamicColor", dynamicColor)
    put("palette", palette)
    put("reduceMotion", reduceMotion)
}

private fun AssistantAttachmentDto.toJson(): JSONObject = JSONObject().apply {
    put("draftId", draftId)
    put("filename", filename)
    put("mediaType", mediaType)
    put("isImage", isImage)
    put("localKey", localKey)
    put("localState", localState)
}

private fun AssistantExecutionStepDto.toJson(): JSONObject = JSONObject().apply {
    put("stepId", stepId)
    put("toolName", toolName)
    put("status", status)
    put("arguments", arguments)
    output?.let { put("output", it) }
    put("revision", revision)
    sourceMessageId?.let { put("sourceMessageId", it) }
    occurredAt?.let { put("occurredAt", it) }
}

private fun AssistantApprovalOptionDto.toJson(): JSONObject = JSONObject().apply {
    put("choice", choice)
    label?.let { put("label", it) }
    put("style", style)
}

private fun AssistantApprovalDto.toJson(): JSONObject = JSONObject().apply {
    put("approvalId", approvalId)
    put("state", state)
    put("command", command)
    put("reason", reason)
    put("expiresAt", expiresAt)
    put("options", options.toJsonArray { option -> option.toJson() })
}

private fun AssistantTimelineEntryDto.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("sender", sender)
    put("text", text)
    put("streaming", streaming)
    put("textTruncated", textTruncated)
    put("attachments", attachments.toJsonArray { attachment -> attachment.toJson() })
    put("executionSteps", executionSteps.toJsonArray { step -> step.toJson() })
    approval?.let { put("approval", it.toJson()) }
    messageStatus?.let { put("messageStatus", it) }
    submissionFailureCode?.let { put("submissionFailureCode", it) }
    clientMessageId?.let { put("clientMessageId", it) }
    put("localPlaceholder", localPlaceholder)
    generationId?.let { put("generationId", it) }
    if (executionFact) put("executionFact", true)
    executionGenerationState?.let { put("executionGenerationState", it) }
}

/** 测量与编码共用同一份序列化，预算不会和实际 payload 走偏。 */
private fun encodeTimelineEntry(dto: AssistantTimelineEntryDto): String = dto.toJson().toString()

private fun encodeTimelineArray(entries: List<AssistantTimelineEntryDto>): String =
    entries.toJsonArray { entry -> entry.toJson() }.toString()

private fun <T> List<T>.toJsonArray(encode: (T) -> JSONObject): JSONArray = JSONArray().apply {
    forEach { put(encode(it)) }
}

private fun JSONObject.toAccountDto(): AssistantAccountDto = AssistantAccountDto(
    profileId = stringOrEmpty("profileId"),
    accountId = stringOrEmpty("accountId"),
    agentName = stringOrEmpty("agentName"),
)

private fun JSONObject.toNoticeDto(): AssistantNoticeDto = AssistantNoticeDto(
    code = stringOrEmpty("code"),
    message = stringOrEmpty("message"),
)

private fun JSONObject.toAppearanceDto(): AssistantAppearanceDto = AssistantAppearanceDto(
    theme = stringOrEmpty("theme").ifBlank { THEME_SYSTEM },
    dynamicColor = optBoolean("dynamicColor", false),
    palette = stringOrEmpty("palette").ifBlank { DEFAULT_PALETTE },
    reduceMotion = optBoolean("reduceMotion", false),
)

private fun JSONObject.toAttachmentDto(): AssistantAttachmentDto = AssistantAttachmentDto(
    draftId = stringOrEmpty("draftId"),
    filename = stringOrEmpty("filename"),
    mediaType = stringOrEmpty("mediaType"),
    isImage = optBoolean("isImage", false),
    localKey = stringOrEmpty("localKey"),
    localState = stringOrEmpty("localState").ifBlank { AttachmentLocalState.PENDING.name },
)

private fun JSONObject.toExecutionStepDto(): AssistantExecutionStepDto = AssistantExecutionStepDto(
    stepId = stringOrEmpty("stepId"),
    toolName = stringOrEmpty("toolName"),
    status = stringOrEmpty("status").ifBlank { NativeExecutionStatus.UNKNOWN.name },
    arguments = stringOrEmpty("arguments"),
    output = stringOrNull("output"),
    revision = optLong("revision", 0L),
    sourceMessageId = stringOrNull("sourceMessageId"),
    occurredAt = if (has("occurredAt") && !isNull("occurredAt")) optLong("occurredAt") else null,
)

private fun JSONObject.toApprovalOptionDto(): AssistantApprovalOptionDto = AssistantApprovalOptionDto(
    choice = stringOrEmpty("choice").ifBlank { ApprovalChoice.UNKNOWN.wireValue },
    label = stringOrNull("label"),
    style = stringOrEmpty("style").ifBlank { ApprovalOptionStyle.NEUTRAL.wireValue },
)

private fun JSONObject.toApprovalDto(): AssistantApprovalDto = AssistantApprovalDto(
    approvalId = stringOrEmpty("approvalId"),
    state = stringOrEmpty("state").ifBlank { APPROVAL_WAITING },
    command = stringOrEmpty("command"),
    reason = stringOrEmpty("reason"),
    expiresAt = optLong("expiresAt", 0L),
    options = optJSONArray("options").objects().map { it.toApprovalOptionDto() },
)

private fun JSONObject.toTimelineEntryDto(): AssistantTimelineEntryDto = AssistantTimelineEntryDto(
    id = stringOrEmpty("id"),
    sender = stringOrEmpty("sender"),
    text = stringOrEmpty("text"),
    streaming = optBoolean("streaming", false),
    textTruncated = optBoolean("textTruncated", false),
    attachments = optJSONArray("attachments").objects().map { it.toAttachmentDto() },
    executionSteps = optJSONArray("executionSteps").objects().map { it.toExecutionStepDto() },
    approval = optJSONObject("approval")?.toApprovalDto(),
    messageStatus = stringOrNull("messageStatus"),
    submissionFailureCode = stringOrNull("submissionFailureCode"),
    clientMessageId = stringOrNull("clientMessageId"),
    localPlaceholder = optBoolean("localPlaceholder", false),
    generationId = stringOrNull("generationId"),
    executionFact = optBoolean("executionFact", false),
    executionGenerationState = stringOrNull("executionGenerationState"),
)

/**
 * `optString` 对 JSON `null` 返回字符串 "null"；这里统一折成空串，
 * 避免界面出现字面量 "null"。
 */
private fun JSONObject.stringOrEmpty(key: String): String = stringOrNull(key).orEmpty()

private fun JSONObject.stringOrNull(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key).takeUnless { it == "null" }
}

private fun JSONArray?.objects(): List<JSONObject> {
    val array = this ?: return emptyList()
    return (0 until array.length()).mapNotNull { index -> array.optJSONObject(index) }
}
