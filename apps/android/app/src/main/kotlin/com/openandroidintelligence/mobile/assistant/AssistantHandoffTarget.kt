package com.openandroidintelligence.mobile.assistant

/**
 * 一次交接尝试的主进程侧结果。
 *
 * [Accepted] 才允许浮层打开主 App 并关闭自己；[Rejected] 必须把原因带回浮层，
 * 浮层保持可见、草稿与附件原样保留（严格交接）。
 */
internal sealed interface AssistantHandoffResult {
    data class Accepted(
        val target: AssistantHandoffTarget,
        /** 未能随草稿迁移的附件数（仍在上传/校验或超出上限）；浮层据此如实提示。 */
        val skippedAttachments: Int = 0,
    ) : AssistantHandoffResult

    data class Rejected(val reason: String) : AssistantHandoffResult
}

/** 一次成功交接的目标（主进程侧）。 */
internal data class AssistantHandoffTarget(
    val profileId: String,
    val accountId: String,
    val agentId: String?,
    /** 被接管的会话；尚未产生会话时为 null（只并入输入栏，不新建会话）。 */
    val conversationId: String?,
)

/** 交接被拒绝时给用户看的中文原因；每一条都对应一个真实的前置条件。 */
internal object AssistantHandoffReasons {
    const val NOT_CONNECTED = "主 App 尚未连接，草稿与附件仍保留在助手中"
    const val ACCOUNT_MISMATCH = "主 App 当前的账号与助手不一致，草稿与附件仍保留在助手中"
    const val WORKBENCH_NOT_READY = "主 App 的会话界面尚未就绪，草稿与附件仍保留在助手中"
    const val AGENT_NOT_READY = "主 App 正在切换到同一个 Agent，请稍后再滑一次"
}

/**
 * 助手交接深链里的会话身份。
 *
 * 只读 `EXTRA_SESSION_TOKEN`，并要求 action 与深链形状都符合既有回复通知约定：
 * 外部应用可以伪造任意 Intent，但主进程侧只认「刚刚接受过的那一次」token
 * （见 `GatewayRuntime.consumeAssistantHandoff`），所以这里不做额外信任。
 */
internal fun android.content.Intent.handoffSessionToken(): String? {
    if (action != HANDOFF_REPLY_ACTION) return null
    return getStringExtra(HANDOFF_EXTRA_SESSION_TOKEN)?.takeIf { it.isNotBlank() }
}

private const val HANDOFF_REPLY_ACTION = "com.openandroidintelligence.mobile.OPEN_AGENT_REPLY"
internal const val HANDOFF_EXTRA_SESSION_TOKEN =
    "com.openandroidintelligence.assistant.SESSION_TOKEN"
