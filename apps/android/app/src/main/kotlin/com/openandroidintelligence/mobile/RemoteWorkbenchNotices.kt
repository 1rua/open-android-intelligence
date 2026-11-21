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
import com.openandroidintelligence.ui.resources.R as UiR

/** 离线与操作结果仍由工作台内容区承载；点击离线重试时原位展示加载状态并防重复点击，严禁踢回登录页。 */
@Composable
internal fun RemoteWorkbenchNotices(
    offline: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    reconnecting: Boolean = false,
) {
    val reduceMotion = LocalMotionPolicy.current.reduceMotion
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
        error?.let {
            OperationNoticeBanner(remoteConnectionFailureText(it), onDismiss, onRetry = onRetry,
                modifier = Modifier.padding(horizontal = Dimensions.TimelineHorizontal))
        }
    }
}
