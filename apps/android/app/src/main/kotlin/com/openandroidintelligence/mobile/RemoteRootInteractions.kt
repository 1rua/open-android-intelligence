package com.openandroidintelligence.mobile

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.conversation.state.WorkbenchUiState
import com.openandroidintelligence.gateway.remote.authproduction.RemoteActiveAccount
import com.openandroidintelligence.gateway.remote.runtime.RemoteAccountConnectionState
import com.openandroidintelligence.ui.resources.R as UiR

/** 顶栏表示账号 Client 控制面是否已认证；选择工作区的业务连接独立显示。 */
internal fun remoteWorkbenchConnected(
    workbench: WorkbenchUiState?,
    client: RemoteAccountConnectionState,
    busy: Boolean,
): Boolean = client == RemoteAccountConnectionState.CONNECTED

/** 权限拒绝后保留原提示动作，只有点击“前往设置”才打开本应用权限页。 */
internal suspend fun showRemoteAudioPermissionDenied(activity: ComponentActivity, snackbar: SnackbarHostState) {
    val result = snackbar.showSnackbar(activity.getString(UiR.string.perm_audio_required),
        actionLabel = activity.getString(UiR.string.common_action_go_to_settings), withDismissAction = true)
    if (result == SnackbarResult.ActionPerformed) {
        activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", activity.packageName, null)))
    }
}

/** 成功消费后才解除设置遮挡与待处理入口；失败保持原请求，等待真实会话就绪。 */
@Composable
internal fun RemotePendingConversationNavigation(
    account: RemoteActiveAccount?,
    controller: WorkbenchController?,
    pendingReply: MutableState<ReplyNotificationTarget?>,
    pendingHandoff: MutableState<String?>,
    openReply: suspend (ReplyNotificationTarget) -> Boolean,
    consumeHandoff: (String) -> Boolean,
    onOpened: () -> Unit,
) {
    LaunchedEffect(account, pendingReply.value) {
        pendingReply.value?.let { target ->
            if (openReply(target)) {
                onOpened()
                pendingReply.value = null
            }
        }
    }
    LaunchedEffect(controller, pendingHandoff.value) {
        pendingHandoff.value?.let { token ->
            if (consumeHandoff(token)) {
                onOpened()
                pendingHandoff.value = null
            }
        }
    }
}
