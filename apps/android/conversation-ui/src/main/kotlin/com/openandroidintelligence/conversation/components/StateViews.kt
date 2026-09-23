package com.openandroidintelligence.conversation.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.openandroidintelligence.conversation.state.Loadable
import com.openandroidintelligence.conversation.theme.Dimensions

/** 无论处于哪一状态都保留调用方的尺寸约束，避免列表把编辑器挤出窗口。 */
@Composable
fun <T> LoadableRegion(state: Loadable<T>, emptyHint: String, onRetry: () -> Unit,
    modifier: Modifier = Modifier, ready: @Composable (T) -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        when (state) {
            Loadable.Idle -> Text("选择会话后显示内容", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Dimensions.SpaceMedium))
            Loadable.Loading -> Column(Modifier.padding(Dimensions.SpaceMedium),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                CircularProgressIndicator(Modifier.size(Dimensions.Progress), strokeWidth = Dimensions.StrokeStitch)
                Text("正在加载", style = MaterialTheme.typography.labelMedium)
            }
            Loadable.Empty -> StatusColumn("还没有内容", emptyHint, false, "刷新" to onRetry)
            is Loadable.Failed -> StatusColumn("暂时无法加载", readableFailure(state.code), true,
                if (state.retryable) "重试" to onRetry else null)
            is Loadable.Ready -> ready(state.value)
        }
    }
}

/**
 * 内容加载场景的兜底说明。
 *
 * 连接与登录场景必须传入自己的兜底文案：那里的失败可能来自地址、网络或两端版本，
 * 把它说成「无法取得内容」会把用户引向错误的排查方向（见 [readableFailure] 的 `fallback`）。
 */
const val CONTENT_FAILURE_FALLBACK: String =
    "暂时无法取得内容。请检查连接后重试；若持续失败，请检查 Gateway 服务。"

/**
 * 连接与登录阶段的兜底说明。
 *
 * 与 [CONTENT_FAILURE_FALLBACK] 分开是有意的：这里失败的原因可能是地址、网络、凭据或
 * 两端版本，说成「无法取得内容」会把用户引向检查内容。凡是展示 `ConnectionPhase.Failed.code`
 * 的界面都必须显式选一个兜底，不得直接打印错误码——那可能是含地址的异常原文。
 */
const val CONNECTION_FAILURE_FALLBACK: String =
    "无法连接 Gateway。请检查地址与网络，并确认 App 与插件版本一致后重试；若持续失败，请查看 Gateway 服务与日志。"

/**
 * 展示可采取的动作，不把可能含地址、服务端正文的原始异常当文案。
 *
 * 没有已知指引时退回 `fallback`（默认是内容加载场景的说明）；[specificFailureText]
 * 让调用方能区分「这条错误码有专门说明」与「只能给兜底」。
 */
fun readableFailure(code: String, fallback: String = CONTENT_FAILURE_FALLBACK): String =
    specificFailureText(code) ?: fallback

/**
 * 契约把 406 专属给 `PROTOCOL_INCOMPATIBLE`（见错误码与状态码的对应表）。
 *
 * 只认「独立的状态码」：`:406` 或整体就是 `406`。这样既能兜住客户端只能拿到状态码的
 * 路径，又不会把 `SEND_FAILED:406001` 这类恰好含这三个数字的标识误判成版本问题——
 * 那会给用户一条错误的「请升级」指引。
 */
private val STATUS_406 = Regex("(:|^)406\\b")

/** 已知错误码对应的说明；未知码返回 null，由调用方决定兜底文案。 */
fun specificFailureText(code: String): String? {
    val value = code.uppercase()
    return when {
        value.contains("DEVICE_KEY_REGISTRATION_UPGRADE_REQUIRED") -> "设备认证已修复，请重新登录一次以更新设备公钥。"
        value.contains("MASTER_KEY_UNAVAILABLE") -> "网关没有配置主密钥，无法保存附件或发送消息。请联系网关部署者执行 ./hermes-account.py init-key 并重启网关。"
        value.contains("APPROVAL_UNSUPPORTED") ->
            "该 Gateway 不支持审批卡片，App 不会伪造一张无法提交的卡片。请升级 Gateway，或用 /approve 文本命令回复。"
        value.contains("APPROVAL_EXPIRED") ->
            "审批已超时，命令没有执行。卡片恢复为不可点击，如需执行请让 Agent 重新发起。"
        value.contains("APPROVAL_FAILED") ->
            "审批决策没有送达 Gateway，卡片已恢复为可点击，请重试。"
        value.contains("CONVERSATION_CREATING") -> "正在等待 Agent 返回新会话，请稍候。"
        value.contains("CONVERSATION_CREATE_NO_RESULT") ->
            "Agent 已经回话了，但没有返回新建会话的结果，无法确认新会话的标识，因此仍停留在原会话。通常意味着当前 Gateway/宿主未实现该命令入口，请重启或升级后重试。"
        value.contains("CONVERSATION_CREATE_UNSUPPORTED") ->
            "该 Gateway 不支持由 App 新建对话。App 不会创建只有本机可见的会话，请升级 Gateway 或联系部署者。"
        value.contains("CONVERSATION_CREATE_UNAVAILABLE") -> "还没有可承接新对话的会话，请先发送一条消息。"
        value.contains("CONVERSATION_CREATE_TIMEOUT") ->
            "Agent 迟迟没有返回新建的会话，已停在原会话。请确认 Gateway 与 Agent 正在运行后重试。"
        value.contains("CONVERSATION_CREATE_CANCELLED") || value.contains("USER_CANCELLED") ->
            "已停止等待新建对话，仍停留在原会话。可以重新点击「新建对话」。"
        value.contains("CONVERSATION_CREATE_FAILED") ->
            "新建对话失败，已停留在原会话，本地没有留下与 Gateway 不一致的会话。请检查连接后重试。"
        value.contains("RENAME") || value.contains("TITLE_UPDATE") -> "网关没有保存这次重命名，已恢复原标题。请确认网关版本支持会话重命名。"
        value.contains("REQUEST_BODY_INVALID") -> "网关拒绝了这次请求的内容，请更新 App 或检查网关版本。"
        value.contains("CURSOR_EXPIRED") -> "会话进度已过期，请刷新以重新同步内容。"
        value.contains("URL-SCHEME") -> "网关地址不受支持，请使用 http:// 或 https:// 开头的地址。"
        value.contains("MISSING-TLS-IDENTITY") -> "Gateway 未提供可核验的 TLS 身份，已按安全要求拒绝连接。"
        // 核心 Schema 摘要不一致是唯一一种「两端都对却连不上」的失败：两端各自算出的
        // 契约版本不同，Gateway 按契约 §4 直接拒绝协商。文案必须指向升级，否则用户会
        // 去修网络。除了明文错误码，还要兜住只带状态码的形态：登录时协商已失效会拿到
        // `AUTHENTICATION_FAILED:406`（`GatewayAuthClient` 在非 2xx 时只能回落到状态码），
        // 而契约把 406 专属给 `PROTOCOL_INCOMPATIBLE`。
        value.contains("PROTOCOL_INCOMPATIBLE") || STATUS_406.containsMatchIn(value) ->
            "App 与 Gateway 的契约版本不一致（或本次协商已失效），连接被拒绝。请把 App 与插件升级到同一版本后重试；若刚升级过，重新登录一次即可。"
        value.contains("HOST_INCOMPATIBLE") ->
            "Gateway 与当前 Agent 宿主版本不兼容，请在 Agent 端升级插件或宿主后重试。"
        // 必须排在 `MISSING-TLS-IDENTITY` 之后：`NEGOTIATION_FAILED:missing-tls-identity`
        // 这种「前缀 + 更具体原因」的组合要命中更具体的那条说明。
        value.contains("NEGOTIATION_FAILED") ->
            "Gateway 没有完成协议协商。请确认地址指向的是 Gateway v2 服务，并检查 Gateway 版本与运行日志。"
        value.contains("OUTCOME_UNKNOWN") -> "操作结果尚未确认。请刷新核实，避免重复提交。"
        value.contains("AGENT_MESSAGE_FAILED") && value.contains("ATTACHMENT_READ_FAILED") ->
            "Agent 无法读取附件，请重新选择文件后重试。"
        value.contains("AGENT_MESSAGE_FAILED") && value.contains("AGENT_MEDIA_REJECTED") ->
            "Agent 不支持处理此附件格式。"
        value.contains("AGENT_MESSAGE_FAILED") && value.contains("MODEL_REQUEST_REJECTED") ->
            "模型拒绝了包含附件的请求格式，请检查 Agent 端模型配置。"
        value.contains("AGENT_MESSAGE_FAILED") && value.contains("AGENT_UNAVAILABLE") ->
            "Agent 当前不可用，附件已由 Gateway 接收但尚未处理。"
        value.contains("IDEMPOTENCY") || value.contains("409") -> "这次请求与已有操作冲突，请刷新会话核实结果。"
        value.contains("UNAUTHORIZED") || value.contains("401") || value.contains("CREDENTIAL") || value.contains("SESSION_EXPIRED") -> "登录凭据已失效，请前往账号与 Gateway 重新登录。"
        value.contains("403") || value.contains("FORBIDDEN") || value.contains("REVOKED") -> "当前账号没有访问权限，请检查账号或联系 Gateway 管理员。"
        value.contains("SSL") || value.contains("TLS") || value.contains("CERTIFICATE") -> "无法验证 Gateway 的安全连接，请检查地址与证书配置。"
        value.contains("ATTACHMENT_STORAGE_UNAVAILABLE") -> "Gateway 附件存储空间不可用，请检查 Agent 端磁盘空间后重试。"
        value.contains("413") -> "Gateway 或反向代理拒绝了上传请求，请检查服务端存储与代理配置。"
        value.contains("429") || value.contains("RATE_LIMIT") -> "请求过于频繁，请稍后重试。"
        value.contains("REPLY_TIMEOUT") || value.contains("NO_REPLY") ->
            "Gateway 一直没有返回回复。已尝试重新同步会话，若仍无内容请检查 Gateway 与 Agent 是否在运行。"
        value.contains("EVENTS_FAILED") || value.contains("EVENT_STREAM") ->
            "与 Gateway 的实时通道已断开，暂时收不到新回复。请检查网络或 Gateway 服务后重试。"
        value.contains("TIMEOUT") || value.contains("TIMED OUT") -> "连接超时，请检查网络后重试。"
        value.contains("CONNECT") || value.contains("NETWORK") || value.contains("IOEXCEPTION") || value.contains("UNKNOWNHOST") -> "暂时无法连接 Gateway，请检查网络和服务地址后重试。"
        else -> null
    }
}

/** 顶栏副标题用的通道状态短标签：始终有值，让「连着」和「断了」一眼可分。 */
fun connectionLabel(health: com.openandroidintelligence.conversation.model.StreamHealth): String =
    when (health) {
        com.openandroidintelligence.conversation.model.StreamHealth.IDLE -> "已连接"
        com.openandroidintelligence.conversation.model.StreamHealth.CONNECTING -> "正在连接…"
        com.openandroidintelligence.conversation.model.StreamHealth.LIVE -> "已连接"
        com.openandroidintelligence.conversation.model.StreamHealth.RECONNECTING -> "重连中…"
        com.openandroidintelligence.conversation.model.StreamHealth.FAILED -> "通道已断开"
    }

/** 实时通道状态的一句话说明；健康时不显示，避免噪音。 */
fun streamHealthText(health: com.openandroidintelligence.conversation.model.StreamHealth): String? =
    when (health) {
        com.openandroidintelligence.conversation.model.StreamHealth.IDLE -> null
        com.openandroidintelligence.conversation.model.StreamHealth.CONNECTING -> "正在连接 Gateway…"
        com.openandroidintelligence.conversation.model.StreamHealth.LIVE -> null
        com.openandroidintelligence.conversation.model.StreamHealth.RECONNECTING -> "连接中断，正在重连…"
        com.openandroidintelligence.conversation.model.StreamHealth.FAILED -> "实时通道已断开，暂时收不到回复"
    }

/**
 * Turns an internal failure code into something the user can act on, while
 * leaving already human-readable notices untouched.
 */
fun noticeText(notice: String): String {
    if (!notice.contains(':')) return notice
    // Both halves are candidates: "SEND_FAILED:MASTER_KEY_UNAVAILABLE" carries its
    // cause on the right, while "CONVERSATION_CREATE_TIMEOUT:NO_COMMAND_RESULT"
    // carries the actionable code on the left. Asking the halves in order is what
    // keeps the user from reading a generic sentence when a specific one exists.
    val candidates = listOf(notice.substringBefore(':'), notice.substringAfter(':'))
    for (candidate in candidates) {
        val code = candidate.trim()
        if (code.isEmpty() || !Regex("[A-Z0-9_]{3,}").containsMatchIn(code)) continue
        specificFailureText(code)?.let { return it }
    }
    return notice
}

@Composable
private fun StatusColumn(title: String, body: String, failed: Boolean, action: Pair<String, () -> Unit>?) {
    Column(Modifier.widthIn(max = Dimensions.FormWidth).padding(Dimensions.SpaceLarge).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
        Icon(if (failed) Icons.Default.ErrorOutline else Icons.Default.Forum, null,
            tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.let { (label, click) -> TextButton(onClick = click) { Text(label) } }
    }
}
