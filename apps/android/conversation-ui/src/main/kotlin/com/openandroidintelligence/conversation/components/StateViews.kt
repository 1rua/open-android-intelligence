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

/** 展示可采取的动作，不把可能含地址、服务端正文的原始异常当文案。 */
fun readableFailure(code: String): String {
    val value = code.uppercase()
    return when {
        value.contains("CURSOR_EXPIRED") -> "会话进度已过期，请刷新以重新同步内容。"
        value.contains("OUTCOME_UNKNOWN") -> "操作结果尚未确认。请刷新核实，避免重复提交。"
        value.contains("IDEMPOTENCY") || value.contains("409") -> "这次请求与已有操作冲突，请刷新会话核实结果。"
        value.contains("UNAUTHORIZED") || value.contains("401") || value.contains("CREDENTIAL") || value.contains("SESSION_EXPIRED") -> "登录凭据已失效，请前往账号与 Gateway 重新登录。"
        value.contains("403") || value.contains("FORBIDDEN") || value.contains("REVOKED") -> "当前账号没有访问权限，请检查账号或联系 Gateway 管理员。"
        value.contains("SSL") || value.contains("TLS") || value.contains("CERTIFICATE") -> "无法验证 Gateway 的安全连接，请检查地址与证书配置。"
        value.contains("413") || value.contains("TOO_LARGE") -> "内容超过了 Gateway 限制，请选择较小的文件。"
        value.contains("429") || value.contains("RATE_LIMIT") -> "请求过于频繁，请稍后重试。"
        value.contains("TIMEOUT") || value.contains("TIMED OUT") -> "连接超时，请检查网络后重试。"
        value.contains("CONNECT") || value.contains("NETWORK") || value.contains("IOEXCEPTION") || value.contains("UNKNOWNHOST") -> "暂时无法连接 Gateway，请检查网络和服务地址后重试。"
        else -> "暂时无法取得内容。请检查连接后重试；若持续失败，请检查 Gateway 服务。"
    }
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
