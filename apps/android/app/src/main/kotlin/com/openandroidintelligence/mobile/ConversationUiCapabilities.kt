package com.openandroidintelligence.mobile

/**
 * 对话界面协商能力位（契约 §4 的 `features.conversationUi`）在界面上的语义。
 *
 * 闭集与 [com.openandroidintelligence.gateway.negotiation.NegotiationClient]
 * 声明的客户端 offer 对齐：那 8 个能力键是这份契约里仅有的对话界面能力，
 * 不在这张表里的键不构成「第五个状态」，也不能在界面上凭空出现。
 */
object ConversationUiFeature {
    /** 契约定义的对话界面能力闭集，顺序即界面呈现顺序。 */
    val CLOSED_SET: List<String> = listOf(
        "agent-command-catalog-v1",
        "agent-command-new-v1",
        "agent-approval-cards-v1",
        "message-batches-v1",
        "newline-v1",
        "generation-cancel-v1",
        "conversation-mirror-v1",
        "attachment-status-v1",
    )
}

/**
 * 把一次协商折叠成每个能力键的三态：
 * `true` = 双方同意（协商交集内）；`false` = 客户端声明了但网关没同意；
 * `null` = 双方都没声明过。
 *
 * [agreed] 是**协商交集**——网关同意且客户端声明过并实现的键——所以凡是
 * 落进它的键都是 `true`，无论再拿它与 offer 求交得到什么；[requested] 是
 * 客户端 offer 的全集，落在里面却不交集里的键，就是这台网关明确没有同意的。
 *
 * 「未声明」与「声明了但被拒」必须保持可区分：前者是这台网关可能支持也可能
 * 不支持，后者是这台网关明确不支持。把 null 写成 false 会把一次可以升级的
 * 网关说成拒绝，把 false 写成 null 会把明确拒绝说成未知——两个方向都是撒谎。
 */
fun negotiatedConversationUi(
    agreed: Set<String>,
    requested: Set<String>,
): Map<String, Boolean?> = ConversationUiFeature.CLOSED_SET.associateWith { key ->
    when {
        key in agreed -> true
        key in requested -> false
        else -> null
    }
}

/** [negotiatedConversationUi] 的三态在界面上的固定措辞。 */
fun conversationUiStatusLabel(agreed: Boolean?): String = when (agreed) {
    true -> "已启用"
    false -> "本网关不支持"
    null -> "未声明"
}

/** 一个能力条目的呈现结论：能不能用，以及为什么。 */
data class CapabilityPresentation(
    val enabled: Boolean,
    val supporting: String,
)

/**
 * 「屏幕上下文分析与圈选」条目的如实降级。
 *
 * 圈选要成立需要两件事同时在场：宿主真的能拿到屏幕截图（当前没有接入任何
 * Assist 截图来源），以及网关同意回传圈选结果（`attachment-status-v1`）。
 * 缺任何一件都必须明说缺什么，而不是把开关画成可用的样子。
 *
 * @param screenshotSourceWired 宿主是否接入了真实截图来源（当前恒为 false）。
 * @param attachmentStatusAgreed `attachment-status-v1` 的协商三态。
 */
fun screenSelectionPresentation(
    screenshotSourceWired: Boolean,
    attachmentStatusAgreed: Boolean?,
): CapabilityPresentation = when {
    !screenshotSourceWired -> CapabilityPresentation(
        enabled = false,
        supporting = "截图来源未接入：本机无法获取屏幕快照，圈选不可用",
    )
    attachmentStatusAgreed == null -> CapabilityPresentation(
        enabled = false,
        supporting = "协商未声明 attachment-status-v1，圈选结果回传不可用",
    )
    !attachmentStatusAgreed -> CapabilityPresentation(
        enabled = false,
        supporting = "本网关不支持 attachment-status-v1，圈选结果回传不可用",
    )
    else -> CapabilityPresentation(
        enabled = true,
        supporting = "截图来源与圈选结果回传均已就绪",
    )
}

/**
 * 「系统通知推送」条目的如实降级。
 *
 * 推送要成立同样需要两件事：宿主装配了真实推送采集通道（当前没有），以及
 * 网关同意镜像会话（`conversation-mirror-v1`）。
 *
 * @param pushChannelWired 宿主是否接入了推送采集通道（当前恒为 false）。
 * @param mirrorAgreed `conversation-mirror-v1` 的协商三态。
 */
fun notificationPushPresentation(
    pushChannelWired: Boolean,
    mirrorAgreed: Boolean?,
): CapabilityPresentation = when {
    !pushChannelWired -> CapabilityPresentation(
        enabled = false,
        supporting = "推送通道未接入：本机没有装配推送采集，通知推送不可用",
    )
    mirrorAgreed == null -> CapabilityPresentation(
        enabled = false,
        supporting = "协商未声明 conversation-mirror-v1，通知推送不可用",
    )
    !mirrorAgreed -> CapabilityPresentation(
        enabled = false,
        supporting = "本网关不支持 conversation-mirror-v1，通知推送不可用",
    )
    else -> CapabilityPresentation(
        enabled = true,
        supporting = "推送通道与会话镜像均已就绪",
    )
}
