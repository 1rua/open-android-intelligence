package com.openandroidintelligence.mobile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.openandroidintelligence.conversation.components.CONNECTION_FAILURE_FALLBACK
import com.openandroidintelligence.conversation.components.readableFailure
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.resources.R as UiR

/** 复用原有中文错误指引，仅替换已退役产品名；异常原文和服务地址不直接展示。 */
@Composable
internal fun remoteConnectionFailureText(code: String): String =
    readableFailure(code, CONNECTION_FAILURE_FALLBACK).replace("Gateway", "Remote")

/** 登录阶段继续使用原来的淡化、错误色面与可重试入口。 */
@Composable
internal fun RemoteLoginPhase(busy: Boolean, error: String?, onRetry: (() -> Unit)?) {
    val reduceMotion = LocalMotionPolicy.current.reduceMotion
    AnimatedContent(targetState = busy to error, transitionSpec = {
        fadeIn(MotionSpecs.fade(reduceMotion)) togetherWith fadeOut(MotionSpecs.fade(reduceMotion))
    }, label = "remote-login-phase") { (running, failure) ->
        when {
            running -> Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                CircularProgressIndicator(Modifier.size(Dimensions.SmallIcon), strokeWidth = Dimensions.StrokeStitch)
                Text(stringResource(UiR.string.conn_status_connecting), style = MaterialTheme.typography.bodySmall)
            }
            failure != null -> Surface(color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer, shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Dimensions.SpaceCompact), verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceTiny)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, modifier = Modifier.size(Dimensions.SmallIcon))
                        Spacer(Modifier.width(Dimensions.SpaceSmall))
                        Text(remoteConnectionFailureText(failure), style = MaterialTheme.typography.bodySmall)
                    }
                    onRetry?.let { retry -> TextButton(onClick = retry) {
                        Text(stringResource(UiR.string.common_action_retry), color = MaterialTheme.colorScheme.onErrorContainer)
                    } }
                }
            }
        }
    }
}

/** 显式 HTTP 仍使用原持久可见的说明，不改变原文案或允许连接的条件。 */
@Composable
internal fun RemotePlaintextConnectionNotice() {
    Surface(color = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(Dimensions.SpaceCompact), verticalAlignment = Alignment.Top) {
            Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(Dimensions.SmallIcon))
            Spacer(Modifier.width(Dimensions.SpaceSmall))
            Text(stringResource(UiR.string.conn_security_http_warning_part1) + stringResource(UiR.string.conn_security_http_warning_part2),
                style = MaterialTheme.typography.labelSmall)
        }
    }
}
