package com.openandroidintelligence.assistant

import androidx.compose.runtime.Immutable
import com.openandroidintelligence.conversation.assistant.AssistantAttachmentDto
import com.openandroidintelligence.conversation.assistant.decodeState
import com.openandroidintelligence.conversation.assistant.decodeTimeline
import com.openandroidintelligence.conversation.model.GenerationState
import com.openandroidintelligence.conversation.state.TimelineEntry

/**
 * 助理进程可见的会话状态（桥接协议 v2）。
 *
 * 字段与主进程 `AssistantBridgeService.publish` 发布的 v2 状态一一对应；这里只读，
 * 不回写、不扩字段。线上 JSON 的解码与时间线还原交给 conversation-ui 的
 * `AssistantBridgeProtocol`：版本不符或坏帧返回 null，调用方保留上一帧。
 *
 * [bridgeAvailable] 与 [advisory] 是本进程内的真实事实，不来自主进程：
 * - [bridgeAvailable]：`bindService` 是否成功、连接是否仍在；
 * - [advisory]：系统是否提供了截图、附件编码是否失败等一次性提示。
 *
 * 没有任何字段是"演示数据"：未连接时 [timeline] 就是空列表，界面必须如实渲染
 * 未连接引导，不能编造对话内容。
 */
@Immutable
internal data class AssistantState(
    val bridgeAvailable: Boolean = true,
    val connected: Boolean = false,
    /** 当前账号投影；未连接或账号不匹配时为 null。不含凭据。 */
    val account: AssistantAccount? = null,
    /** 当前会话身份；服务端在每次 `session.new` 时更换。 */
    val sessionId: String? = null,
    /** 状态帧序号：接收方据此丢弃乱序帧。 */
    val revision: Long = 0L,
    val generation: String = AssistantGeneration.IDLE,
    val draft: String = "",
    /** 当前活动对话的身份；时间线缓存与执行耗时按它隔离。 */
    val activeThread: String? = null,
    /** 当前活动会话的时间线投影（旧 → 新）。 */
    val timeline: List<TimelineEntry> = emptyList(),
    /** 时间线条目是否被线上预算整体丢弃过；单条正文截断见各条自身标记。 */
    val timelineTruncated: Boolean = false,
    /**
     * 当前草稿里已加入的附件元数据（旧 → 新）。
     *
     * 线上只有身份与元数据，没有字节；缩略图按 `localKey` 从本进程的
     * [AssistantLocalPreviews] 取回，取不到时界面按媒体类型显示图标。
     */
    val attachments: List<AssistantAttachmentDto> = emptyList(),
    val notice: String = "",
    val advisory: String? = null,
    /** 主进程下发的已保存外观；首帧到达前为 null，由调用方回落到本地偏好。 */
    val appearance: AssistantAppearance? = null,
) {
    /**
     * 当前生成状态的强类型投影。
     *
     * `generation` 是线上字面量；渲染层（执行段折叠、流式光标）需要枚举才能判断终态。
     * 未知字面量折成 null，宁可「不知道结论」也不猜成已完成或失败。
     */
    val generationState: GenerationState?
        get() = GenerationState.entries.firstOrNull { it.name == generation }

    /** 主进程只在「桥接可用且已连接当前账号」时才允许读写对话内容。 */
    val canInteract: Boolean get() = bridgeAvailable && connected

    val isGenerating: Boolean
        get() = generation == AssistantGeneration.QUEUED ||
            generation == AssistantGeneration.RUNNING ||
            generation == AssistantGeneration.CANCEL_REQUESTED

    val isStopping: Boolean get() = generation == AssistantGeneration.CANCEL_REQUESTED

    val hasUnknownOutcome: Boolean
        get() = generation == AssistantGeneration.OUTCOME_UNKNOWN
}

/** 当前账号投影；只含身份，不含凭据。 */
@Immutable
internal data class AssistantAccount(
    val profileId: String,
    val accountId: String,
    val agentName: String,
)

/** 主进程 JSON 里的生成状态名（与 `GenerationState` 枚举名一致）。 */
internal object AssistantGeneration {
    const val IDLE = "IDLE"
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val CANCEL_REQUESTED = "CANCEL_REQUESTED"
    const val OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN"
}

/**
 * 解析主进程发布的 v2 状态 JSON。
 *
 * 解析失败、根不是对象或版本不是 v2 时返回 null，而不是用默认值冒充一次成功的状态
 * 更新：调用方据此保留上一帧，不把坏数据渲染成"未连接"或"空对话"。
 */
internal fun parseAssistantState(payload: String): AssistantState? {
    // 解码只做一次：状态帧在流式输出期间很频繁，大帧重复解析会落在主线程上。
    val dto = decodeState(payload) ?: return null
    val account = dto.account
        ?.takeIf { it.profileId.isNotBlank() || it.accountId.isNotBlank() || it.agentName.isNotBlank() }
        ?.let {
            AssistantAccount(
                profileId = it.profileId,
                accountId = it.accountId,
                agentName = it.agentName,
            )
        }
    return AssistantState(
        connected = dto.connected,
        account = account,
        sessionId = dto.sessionId,
        revision = dto.revision,
        generation = dto.generation,
        draft = dto.draft,
        activeThread = dto.activeThread,
        timeline = decodeTimeline(dto),
        timelineTruncated = dto.timelineTruncated,
        attachments = dto.attachments,
        notice = dto.notice?.message.orEmpty(),
        // v2 服务端每帧都带 appearance；协议缺省值只在异常生产方下出现。
        appearance = AssistantAppearance(
            theme = dto.appearance.theme,
            dynamicColor = dto.appearance.dynamicColor,
            palette = dto.appearance.palette,
            reduceMotion = dto.appearance.reduceMotion,
        ),
    )
}

/**
 * 副标题：Gateway 状态。
 *
 * 每个分支都有明确的文字结论，不靠颜色或图形暗示连接状态（规格 §7）。
 */
internal fun AssistantState.gatewayStatusLabel(): String = when {
    !bridgeAvailable -> "助理服务未连接"
    !connected -> "Gateway 未连接"
    isStopping -> "Gateway 已连接 · 正在停止生成"
    isGenerating -> "Gateway 已连接 · 正在生成"
    else -> "Gateway 已连接"
}

/** 输入坞禁用原因；null 表示当前可以发送。 */
internal fun AssistantState.composerBlockReason(): String? = when {
    !bridgeAvailable -> "助理服务无法连接，请打开 App 后重试"
    !connected -> "请先在 App 中连接 Gateway"
    hasUnknownOutcome -> "上一条消息结果未知，请先在 App 中确认"
    else -> null
}
