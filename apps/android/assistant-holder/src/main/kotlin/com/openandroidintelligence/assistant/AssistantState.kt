package com.openandroidintelligence.assistant

import androidx.compose.runtime.Immutable
import org.json.JSONArray
import org.json.JSONObject

/**
 * 助理进程可见的会话状态。
 *
 * 字段与主进程 `AssistantBridgeService` 发布的 JSON（version=1）一一对应；这里只读，
 * 不回写、不扩字段。跨进程协议是既有契约，界面换皮不得改动它。
 *
 * [bridgeAvailable] 与 [advisory] 是本进程内的真实事实，不来自主进程：
 * - [bridgeAvailable]：`bindService` 是否成功、连接是否仍在；
 * - [advisory]：系统是否提供了截图、附件编码是否失败等一次性提示。
 *
 * 没有任何字段是"演示数据"：未连接时 [messages] / [threads] 就是空列表，
 * 界面必须如实渲染未连接或未绑定引导，不能编造对话内容。
 */
@Immutable
internal data class AssistantState(
    val bridgeAvailable: Boolean = true,
    val connected: Boolean = false,
    val consent: Boolean = false,
    val title: String = "",
    val notice: String = "",
    val messages: List<AssistantMessage> = emptyList(),
    val threads: List<AssistantThread> = emptyList(),
    val draft: String = "",
    val generation: String = "",
    val advisory: String? = null,
    /** 主进程下发的已保存外观；旧版本主进程不带这个字段时为 null。 */
    val appearance: AssistantAppearance? = null,
) {
    /** 主进程只在「已连接且已绑定当前对话」时才允许读写对话内容。 */
    val canInteract: Boolean get() = bridgeAvailable && connected && consent

    val isGenerating: Boolean
        get() = generation == AssistantGeneration.QUEUED ||
            generation == AssistantGeneration.RUNNING ||
            generation == AssistantGeneration.CANCEL_REQUESTED

    val isStopping: Boolean get() = generation == AssistantGeneration.CANCEL_REQUESTED

    val hasUnknownOutcome: Boolean
        get() = generation == AssistantGeneration.OUTCOME_UNKNOWN
}

/** 主进程 JSON 里的生成状态名（与 `GenerationState` 枚举名一致）。 */
internal object AssistantGeneration {
    const val QUEUED = "QUEUED"
    const val RUNNING = "RUNNING"
    const val CANCEL_REQUESTED = "CANCEL_REQUESTED"
    const val OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN"
}

/** 一条时间线消息：只携带主进程已经发布过的字段。 */
@Immutable
internal data class AssistantMessage(
    val id: String,
    val fromUser: Boolean,
    val text: String,
    val attachments: List<String> = emptyList(),
    val streaming: Boolean = false,
)

/** 一个可切换的对话线程；标题可能为空（尚未命名的对话）。 */
@Immutable
internal data class AssistantThread(
    val id: String,
    val title: String,
)

/**
 * 解析主进程发布的状态 JSON。
 *
 * 解析失败返回 null，而不是用默认值冒充一次成功的状态更新：调用方据此保留上一帧，
 * 不把坏数据渲染成"未连接"或"空对话"。
 */
internal fun parseAssistantState(payload: String): AssistantState? = runCatching {
    val root = JSONObject(payload)
    val messages = root.optJSONArray("messages").objects().mapIndexed { index, row ->
        AssistantMessage(
            id = row.stringOrEmpty("id").ifBlank { "message-$index" },
            fromUser = row.stringOrEmpty("sender") == SENDER_USER,
            text = row.stringOrEmpty("text"),
            attachments = row.optJSONArray("attachments").strings(),
            streaming = row.optBoolean("streaming"),
        )
    }
    val threads = root.optJSONArray("threads").objects().mapNotNull { row ->
        val id = row.stringOrEmpty("id")
        if (id.isBlank()) null else AssistantThread(id = id, title = row.stringOrEmpty("title"))
    }
    val appearanceRow = root.optJSONObject("appearance")
    val appearance = appearanceRow?.let { row ->
        AssistantAppearance(
            theme = row.optString("theme", THEME_SYSTEM).takeIf { it.isNotBlank() } ?: THEME_SYSTEM,
            dynamicColor = row.optBoolean("dynamicColor"),
            palette = row.optString("palette", "").takeIf { it.isNotBlank() } ?: "INDIGO",
            reduceMotion = row.optBoolean("reduceMotion"),
        )
    }
    AssistantState(
        connected = root.optBoolean("connected"),
        consent = root.optBoolean("consent"),
        title = root.stringOrEmpty("title"),
        notice = root.stringOrEmpty("notice"),
        messages = messages,
        threads = threads,
        draft = root.stringOrEmpty("draft"),
        generation = root.stringOrEmpty("generation"),
        appearance = appearance,
    )
}.getOrNull()

/** 用户消息的 sender 字面量（协议既有取值，不做改名）。 */
private const val SENDER_USER = "user"

/**
 * `optString` 对 JSON `null` 会返回字符串 "null"；这里统一折成空串，
 * 避免界面出现字面量 "null"。
 */
private fun JSONObject.stringOrEmpty(key: String): String {
    if (!has(key) || isNull(key)) return ""
    return optString(key).takeUnless { it == "null" }.orEmpty()
}

private fun JSONArray?.objects(): List<JSONObject> {
    val array = this ?: return emptyList()
    return (0 until array.length()).mapNotNull { index -> array.optJSONObject(index) }
}

private fun JSONArray?.strings(): List<String> {
    val array = this ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        array.optString(index).takeUnless { it.isBlank() || it == "null" }
    }
}

/**
 * 副标题：Gateway 状态。
 *
 * 每个分支都有明确的文字结论，不靠颜色或图形暗示连接状态（规格 §7）。
 */
internal fun AssistantState.gatewayStatusLabel(): String = when {
    !bridgeAvailable -> "助理服务未连接"
    !connected -> "Gateway 未连接"
    !consent -> "Gateway 已连接 · 尚未绑定对话"
    isStopping -> "Gateway 已连接 · 正在停止生成"
    isGenerating -> "Gateway 已连接 · 正在生成"
    else -> "Gateway 已连接"
}

/** 输入坞禁用原因；null 表示当前可以发送。 */
internal fun AssistantState.composerBlockReason(): String? = when {
    !bridgeAvailable -> "助理服务无法连接，请打开 App 后重试"
    !connected -> "请先在 App 中连接 Gateway"
    !consent -> "绑定当前对话后才会显示内容"
    hasUnknownOutcome -> "上一条消息结果未知，请先在 App 中确认"
    else -> null
}
