package com.openandroidintelligence.mobile

import com.openandroidintelligence.ui.resources.R as UiR
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openandroidintelligence.conversation.motion.AppTransitions
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.conversation.model.StreamHealth
import com.openandroidintelligence.gateway.events.EventStreamStatus
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.theme.LocalAppStateColors
import com.openandroidintelligence.conversation.theme.OpenAndroidIntelligenceTheme
import com.openandroidintelligence.conversation.voice.ActiveVoiceRecording
import com.openandroidintelligence.conversation.voice.VoiceRecorder
import com.openandroidintelligence.conversation.voice.VoiceRecorderState
import com.openandroidintelligence.conversation.voice.VoiceRejection
import com.openandroidintelligence.conversation.voice.activeRecording
import com.openandroidintelligence.conversation.voice.voiceRejectionMessage
import com.openandroidintelligence.conversation.workbench.WorkbenchScreen
import com.openandroidintelligence.conversation.workbench.PastedImageContent
import com.openandroidintelligence.core.model.AssistantHandoffDecision
import com.openandroidintelligence.core.model.AssistantHandoffDenialReason
import com.openandroidintelligence.core.model.AssistantHandoffGate
import com.openandroidintelligence.core.model.AssistantHandoffRequest
import com.openandroidintelligence.core.model.DefaultAssistantHandoffGate
import com.openandroidintelligence.mobile.assistant.handoffSessionToken
import com.openandroidintelligence.mobile.onboarding.OnboardingFlow
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.withContext
import com.openandroidintelligence.mobile.screen.ScreenControlConfirmationDialog

/**
 * 宿主主入口 Activity。
 *
 * 遵循严格的平台架构准则：
 * 1. 彻底清除任何假数据与死界面占位；
 * 2. 状态全量交由 GatewayRuntime 与领域层驱动；
 * 3. 严格遵循 Material Design 3 规范与品牌设计令牌（外观偏好含深浅、动态取色、减少动态）；
 * 4. 接入原生相机快照、系统图片选择器与 SAF 文档选择器，走真实三步附件上传链路。
 *
 * 系统栏：图标明暗不再由 `themes.xml` 写死，而是在 [setContent] 里按应用内 `isDark`
 * 交给 [applySystemBarContrast]；这样「应用内深色 + 系统浅色」时状态栏图标仍然可读。
 */
/** 交接导航的重试次数与间隔：覆盖 Agent 切换后工作台发布的那一小段空窗。 */
private const val HANDOFF_NAVIGATION_ATTEMPTS = 6
private const val HANDOFF_NAVIGATION_RETRY_MILLIS = 500L

class MainActivity : ComponentActivity() {
    private val sharedAttachments: SharedConversationAttachments by viewModels()
    private val pendingInvitation = androidx.compose.runtime.mutableStateOf<String?>(null)
    private val pendingReply = androidx.compose.runtime.mutableStateOf<ReplyNotificationTarget?>(null)

    /** 助手浮层「上滑交接」带来的会话身份（只由主进程刚刚接受过的那一次产生）。 */
    private val pendingHandoff = androidx.compose.runtime.mutableStateOf<String?>(null)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveSharedConversationAttachments(this, sharedAttachments, intent)
        if (intent.data?.scheme=="oai" && intent.data?.host=="pair") pendingInvitation.value=intent.data.toString()
        // 助手交接的深链与回复通知同形（同一 action、同一条 oai-reply:// 路径），
        // 两者都会被解析出来。交接意图必须**只**进 pendingHandoff：否则回复通知那条
        // 路径会先跑、忽略 agentId 去 openThread，把用户带到旧 Agent 的同名会话或直接失败，
        // 而真正的交接导航又被它抢先消费掉。
        val handoffToken = intent.handoffSessionToken()
        if (handoffToken != null) {
            pendingHandoff.value = handoffToken
        } else {
            ReplyNotificationTarget.from(intent)?.let { pendingReply.value = it }
        }
    }

    private val handoffGate: AssistantHandoffGate = DefaultAssistantHandoffGate()
    private var lastHandoffDecision: AssistantHandoffDecision =
        AssistantHandoffDecision.Denied(AssistantHandoffDenialReason.DEFAULT_DENY)

    fun evaluateAssistantHandoff(request: AssistantHandoffRequest): AssistantHandoffDecision =
        handoffGate.evaluate(request).also { lastHandoffDecision = it }

    fun currentAssistantHandoffDecision(): AssistantHandoffDecision = lastHandoffDecision

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) receiveSharedConversationAttachments(this, sharedAttachments, intent)
        intent.handoffSessionToken()?.let { pendingHandoff.value = it }
            ?: run { ReplyNotificationTarget.from(intent)?.let { pendingReply.value = it } }
        val app = application as OpenAndroidIntelligenceApplication
        applySystemBarContrast(appearanceIsDark(app.appearancePreferences.settings.value))
        setContent { RemoteWorkbenchRoot(app, sharedAttachments, pendingReply, pendingHandoff) }
    }

}

/**
 * 离线镜像横幅：如实说明当前只能看本机缓存，并提供唯一的重连入口。
 * 用 warning 语义色 + 图标 + 文字三件套，不靠颜色单独传达状态。
 */
@Composable
private fun OfflineMirrorBanner(onReconnect: () -> Unit) {
    val stateColors = LocalAppStateColors.current
    Surface(
        color = stateColors.warningContainer,
        contentColor = stateColors.onWarningContainer,
        shape = RoundedCornerShape(AppRadius.Medium),
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = Dimensions.ScreenHorizontal,
                vertical = Dimensions.SpaceSmall,
            ),
    ) {
        Row(
            modifier = Modifier.padding(
                start = Dimensions.SpaceMedium,
                end = Dimensions.SpaceSmall,
                top = Dimensions.SpaceSmall,
                bottom = Dimensions.SpaceSmall,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                modifier = Modifier.size(Dimensions.SmallIcon),
            )
            Text(
                text = stringResource(UiR.string.conn_status_offline_mode_hint),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Dimensions.SpaceSmall),
            )
            TextButton(onClick = onReconnect) { Text(stringResource(UiR.string.conn_action_reconnect)) }
        }
    }
}

/** 生产工作台的账号操作装配；界面测试与 Activity 使用同一条真实会话路径。 */
@Composable
internal fun GatewayWorkbenchScreen(
    runtime: GatewayRuntime,
    controller: com.openandroidintelligence.conversation.state.WorkbenchController,
    gatewayLabel: String,
    onOpenSettings: () -> Unit,
    onPickCamera: () -> Unit,
    onPickGallery: () -> Unit,
    onPickDocument: () -> Unit,
    onVoiceInput: () -> Unit,
    modifier: Modifier = Modifier,
    recording: ActiveVoiceRecording? = null,
    onCancelRecording: () -> Unit = {},
    onFinishRecording: () -> Unit = {},
    pluginCards: @Composable () -> Unit = {},
    header: @Composable () -> Unit = {},
    offlineMirrorPhase: Boolean = false,
    onReconnectOfflineMirror: () -> Unit = {},
    reduceMotion: Boolean = false,
    onPasteImages: ((PastedImageContent) -> Unit)? = null,
    settingsOpen: Boolean = false,
    onSettingsOpenChange: (Boolean) -> Unit = {},
    settingsPanel: (@Composable (presentation: SettingsPresentation, onClose: () -> Unit) -> Unit)? = null,
) {
    val operationNotice by runtime.operationNotice.collectAsState()
    val phase by runtime.phase.collectAsState()
    val connectionStatus by runtime.connectionStatus.collectAsState()
    val localSession by runtime.localSessionState.collectAsState()
    val profiles by runtime.savedProfiles.collectAsState()
    val username = when (val current = phase) {
        is ConnectionPhase.Connected -> current.username
        is ConnectionPhase.OfflineMirror -> current.username
        else -> (localSession as? LocalSessionState.Workspace)?.account?.username.orEmpty()
    }
    val profile = profiles.firstOrNull { it.gatewayBaseUrl == gatewayLabel && it.username == username }
    val avatar = rememberAccountAvatarPicker(runtime, profile)
    // 必须读取工作台宿主宽度；进入设置插槽后，宽屏侧栏已经只有 320dp。
    BoxWithConstraints(modifier.fillMaxSize()) {
        val settingsPresentation = if (maxWidth >= Dimensions.ExpandedWindow) {
            SettingsPresentation.SIDEBAR
        } else {
            SettingsPresentation.SCREEN
        }
        Column(Modifier.fillMaxSize()) {
            WorkbenchScreen(
                accountName = username,
                agentName = (phase as? ConnectionPhase.Connected)?.agentName ?: profile?.agentName,
                accountAvatar = avatar.image,
                onChangeAvatar = avatar.pick,
                connectionHealth = workbenchConnectionHealth(phase, connectionStatus),
                onPasteImages = onPasteImages,
                controller = controller,
                gatewayLabel = gatewayLabel,
                onOpenSettings = onOpenSettings,
                settingsOpen = settingsOpen,
                onSettingsOpenChange = onSettingsOpenChange,
                settingsPanel = settingsPanel?.let { panel ->
                    { onClose -> panel(settingsPresentation, onClose) }
                },
                onPickCamera = onPickCamera,
                onPickGallery = onPickGallery,
                onPickDocument = onPickDocument,
                onVoiceInput = onVoiceInput,
                recording = recording,
                onCancelRecording = onCancelRecording,
                onFinishRecording = onFinishRecording,
                modifier = Modifier.weight(1f),
                onLogout = { runtime.logout(revokeRefresh = true) },
                pluginCards = pluginCards,
                header = {
                    // 通告区与时间线同宽，随内容滚动容器顶部分布；横幅本体带自己的圆角与内边距。
                    Column(verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall)) {
                        AnimatedVisibility(
                            visible = offlineMirrorPhase,
                            enter = expandVertically(MotionSpecs.emphasized(reduceMotion)) +
                                fadeIn(MotionSpecs.fade(reduceMotion)),
                            exit = shrinkVertically(MotionSpecs.emphasized(reduceMotion)) +
                                fadeOut(MotionSpecs.fade(reduceMotion)),
                        ) {
                            OfflineMirrorBanner(onReconnect = onReconnectOfflineMirror)
                        }
                        operationNotice?.let { notice ->
                            OperationNoticeBanner(
                                text = notice,
                                onDismiss = runtime::dismissOperationNotice,
                                onRetry = if (runtime.canRetryWorkbenchInitialization || phase is ConnectionPhase.Failed) runtime::retryConnection else null,
                                modifier = Modifier.padding(horizontal = Dimensions.TimelineHorizontal),
                            )
                        }
                    }
                    header()
                },
            )
        }
    }
}

internal fun workbenchConnectionHealth(
    phase: ConnectionPhase,
    status: EventStreamStatus,
): StreamHealth? {
    return when (phase) {
        ConnectionPhase.Initializing, ConnectionPhase.Negotiating, ConnectionPhase.Authenticating ->
            StreamHealth.CONNECTING
        ConnectionPhase.Reconnecting -> StreamHealth.RECONNECTING
        is ConnectionPhase.OfflineMirror -> StreamHealth.FAILED
        is ConnectionPhase.Connected -> when (status) {
            EventStreamStatus.CONNECTING ->
                StreamHealth.CONNECTING
            EventStreamStatus.RECONNECTING ->
                StreamHealth.RECONNECTING
            EventStreamStatus.FAILED ->
                StreamHealth.FAILED
            EventStreamStatus.LIVE ->
                StreamHealth.LIVE
            else -> null
        }
        else -> null
    }
}
