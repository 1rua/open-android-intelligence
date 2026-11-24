package com.openandroidintelligence.mobile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.theme.LocalAppStateColors
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.ui.design.isReduced
import com.openandroidintelligence.ui.resources.R as UiR

private fun shouldSuppressWorkbenchBannerError(
    phase: AgentConnectionPhase?,
    offlineBannerVisible: Boolean,
    offlineConfirmed: Boolean,
    sessionReady: Boolean,
): Boolean =
    offlineBannerVisible ||
        phase == AgentConnectionPhase.CONNECTED ||
        phase == AgentConnectionPhase.CONNECTING ||
        (phase == AgentConnectionPhase.RECONNECTING && !offlineConfirmed) ||
        (sessionReady && phase != AgentConnectionPhase.OFFLINE && phase != AgentConnectionPhase.AUTH_REVOKED)

/**
 * 统一解析工作台顶部错误横幅：
 * 1. 已连接（[AgentConnectionPhase.CONNECTED]）、冷启动建链期（[AgentConnectionPhase.CONNECTING]）或
 *    未确认离线的短时重连宽限期（[AgentConnectionPhase.RECONNECTING] 且未确认离线）严格互斥，禁止展示错误卡片；
 * 2. 工作台会话已就绪（[sessionReady]）时仅在明确离线（[AgentConnectionPhase.OFFLINE]）或凭据吊销
 *    （[AgentConnectionPhase.AUTH_REVOKED]）状态下才保留失败提示，其余场景瞬时销毁错误卡片。
 */
internal fun resolveWorkbenchBannerError(
    agentConn: AgentConnectionState,
    offlineBannerVisible: Boolean,
    dismissedError: Boolean,
    sessionReady: Boolean = false,
): String? {
    if (dismissedError) return null
    if (shouldSuppressWorkbenchBannerError(agentConn.phase, offlineBannerVisible, agentConn.offlineConfirmed, sessionReady)) {
        return null
    }
    return agentConn.errorCode
}

/** 离线与操作结果仍由工作台内容区承载；点击离线重试时原位展示加载状态并防重复点击，严禁踢回登录页。 */
@Composable
internal fun RemoteWorkbenchNotices(
    offline: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    reconnecting: Boolean = false,
    phase: AgentConnectionPhase? = null,
    sessionReady: Boolean = false,
) {
    val reduceMotion = LocalMotionPolicy.current.isReduced
    val effectiveError = error?.takeUnless {
        shouldSuppressWorkbenchBannerError(
            phase = phase,
            offlineBannerVisible = offline,
            offlineConfirmed = offline,
            sessionReady = sessionReady,
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
        AnimatedVisibility(visible = offline,
            enter = expandVertically(MotionSpecs.emphasized(reduceMotion)) + fadeIn(MotionSpecs.fade(reduceMotion)),
            exit = shrinkVertically(MotionSpecs.emphasized(reduceMotion)) + fadeOut(MotionSpecs.fade(reduceMotion))) {
            val stateColors = LocalAppStateColors.current
            Surface(color = stateColors.warningContainer, contentColor = stateColors.onWarningContainer,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Dimensions.ScreenHorizontal, vertical = Dimensions.SpaceSmall)) {
                Row(Modifier.padding(start = Dimensions.SpaceMedium, end = Dimensions.SpaceSmall,
                    top = Dimensions.SpaceSmall, bottom = Dimensions.SpaceSmall), verticalAlignment = Alignment.CenterVertically) {
                    if (reconnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(Dimensions.SmallIcon),
                            strokeWidth = Dimensions.StrokeStitch,
                            color = stateColors.onWarningContainer,
                        )
                    } else {
                        Icon(Icons.Default.CloudOff, contentDescription = null, modifier = Modifier.size(Dimensions.SmallIcon))
                    }
                    Text(
                        text = stringResource(
                            if (reconnecting) UiR.string.conn_status_reconnecting_in_place
                            else UiR.string.conn_status_offline_mode_hint
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(horizontal = Dimensions.SpaceSmall),
                    )
                    TextButton(onClick = onRetry, enabled = !reconnecting) {
                        Text(stringResource(UiR.string.conn_action_reconnect))
                    }
                }
            }
        }
        effectiveError?.let {
            OperationNoticeBanner(remoteConnectionFailureText(it), onDismiss, onRetry = onRetry,
                modifier = Modifier.padding(horizontal = Dimensions.TimelineHorizontal))
        }
    }
}
