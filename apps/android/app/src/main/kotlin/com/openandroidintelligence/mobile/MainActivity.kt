package com.openandroidintelligence.mobile

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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.openandroidintelligence.capability.MediaProjectionCaptureService
import com.openandroidintelligence.capability.MediaProjectionRuntime
import com.openandroidintelligence.capability.MediaProjectionScreenCaptureSource
import com.openandroidintelligence.conversation.motion.AppTransitions
import com.openandroidintelligence.conversation.motion.MotionSpecs
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.conversation.theme.AppRadius
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.theme.LocalAppStateColors
import com.openandroidintelligence.conversation.theme.OpenAndroidIntelligenceTheme
import com.openandroidintelligence.conversation.workbench.FloatingConversationPanel
import com.openandroidintelligence.conversation.workbench.WorkbenchScreen
import com.openandroidintelligence.conversation.workbench.PastedImageContent
import com.openandroidintelligence.core.model.AssistantHandoffDecision
import com.openandroidintelligence.core.model.AssistantHandoffDenialReason
import com.openandroidintelligence.core.model.AssistantHandoffGate
import com.openandroidintelligence.core.model.AssistantHandoffRequest
import com.openandroidintelligence.core.model.DefaultAssistantHandoffGate
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
class MainActivity : ComponentActivity() {
    private val sharedAttachments: SharedConversationAttachments by viewModels()
    private val pendingInvitation = androidx.compose.runtime.mutableStateOf<String?>(null)
    private val pendingReply = androidx.compose.runtime.mutableStateOf<ReplyNotificationTarget?>(null)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveSharedConversationAttachments(this, sharedAttachments, intent)
        if (intent.data?.scheme=="oai" && intent.data?.host=="pair") pendingInvitation.value=intent.data.toString()
        ReplyNotificationTarget.from(intent)?.let { pendingReply.value = it }
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
        if (intent.data?.scheme=="oai" && intent.data?.host=="pair") pendingInvitation.value=intent.data.toString()
        pendingReply.value = ReplyNotificationTarget.from(intent)

        val app = application as OpenAndroidIntelligenceApplication
        val runtime = app.gatewayRuntime

        // 首帧之前先按「应用内主题」设定系统栏图标，避免启动瞬间出现不可读的状态栏。
        applySystemBarContrast(appearanceIsDark(app.appearancePreferences.settings.value))

        setContent {
            val appearanceSettings by app.appearancePreferences.settings.collectAsState()
            val isDark = when (appearanceSettings.theme) {
                ThemePreference.LIGHT -> false
                ThemePreference.DARK -> true
                ThemePreference.SYSTEM -> isSystemInDarkTheme()
            }
            // 主题变化（含用户强制深浅）时同步系统栏图标明暗。
            LaunchedEffect(isDark) { applySystemBarContrast(isDark) }
            OpenAndroidIntelligenceTheme(
                darkTheme = isDark,
                dynamicColor = appearanceSettings.dynamicColor,
                palette = appearanceSettings.palette,
                reduceMotion = appearanceSettings.reduceMotion,
            ) {
                val reduceMotion = LocalMotionPolicy.current.reduceMotion
                // 全 App 唯一的 Snackbar 落点：离上下文远的操作结果统一在这里出现，
                // 不再各自弹 Toast（同一条信息不允许双重提示）。
                val snackbarHostState = remember { SnackbarHostState() }
                val uiScope = rememberCoroutineScope()
                fun notify(message: String) {
                    uiScope.launch {
                        snackbarHostState.showSnackbar(message, withDismissAction = true)
                    }
                }
                fun notifyWithSettingsAction(message: String) {
                    uiScope.launch {
                        val result = snackbarHostState.showSnackbar(
                            message = message,
                            actionLabel = "去设置",
                            withDismissAction = true,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            startActivity(
                                Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.fromParts("package", packageName, null),
                                ),
                            )
                        }
                    }
                }

                val deviceConfirmation by runtime.deviceConfirmation.collectAsState()
                deviceConfirmation?.let { request ->
                    AlertDialog(
                        onDismissRequest = { runtime.decideDeviceRequest(request.requestId,false) },
                        title = { Text("确认设备操作") },
                        text = { Text("${request.pluginId}\n${request.capability}\n${request.parameters}") },
                        confirmButton = { TextButton(onClick = { runtime.decideDeviceRequest(request.requestId,true) }) { Text("允许这次操作") } },
                        dismissButton = { TextButton(onClick = { runtime.decideDeviceRequest(request.requestId,false) }) { Text("拒绝") } },
                    )
                }
                val phase by runtime.phase.collectAsState()
                val controller by runtime.controller.collectAsState()
                val savedProfiles by runtime.savedProfiles.collectAsState()
                val isManagingProfiles by runtime.isManagingProfiles.collectAsState()
                val operationNotice by runtime.operationNotice.collectAsState()
                var showSettingsSheet by remember { mutableStateOf(false) }
                var showAssistant by remember { mutableStateOf(false) }
                val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                    runtime.replyNotifications.refreshPermission()
                    runtime.nativeConversationNotifications.refreshActive()
                }
                LaunchedEffect(phase) {
                    runtime.replyNotifications.refreshPermission()
                    runtime.nativeConversationNotifications.refreshActive()
                    if (phase is ConnectionPhase.Connected && runtime.replyNotifications.enabled.value &&
                        runtime.replyNotifications.shouldRequestPermission()) {
                        runtime.replyNotifications.markPermissionRequested()
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                LaunchedEffect(phase, controller, pendingReply.value) {
                    pendingReply.value?.let { target ->
                        if (runtime.openReplyNotification(target)) {
                            showSettingsSheet = false
                            showAssistant = false
                            pendingReply.value = null
                            intent.data = null
                        } else if (phase is ConnectionPhase.Connected) {
                            notify("请切换到收到回复的账号后查看会话")
                        }
                    }
                }

                var recording by remember { mutableStateOf<VoiceRecording?>(null) }
                var recordingTarget by remember { mutableStateOf<Pair<WorkbenchController, String?>?>(null) }
                var permissionTarget by remember { mutableStateOf<Pair<WorkbenchController, String?>?>(null) }
                var sendingVoice by remember { mutableStateOf(false) }
                var voiceSendJob by remember { mutableStateOf<Job?>(null) }
                var voiceSendController by remember { mutableStateOf<WorkbenchController?>(null) }
                val cancelRecording: () -> Unit = {
                    recording?.close()
                    recording = null
                    recordingTarget = null
                }
                val beginRecording: (Pair<WorkbenchController, String?>) -> Unit = { target ->
                    if (runtime.controller.value !== target.first || target.first.state.value.activeThreadId != target.second) {
                        notify("对话已切换，请重新开始录音")
                    } else if (!target.first.canSendVoiceAttachment(target.second)) {
                        notify("当前对话暂不能发送语音，请等待处理完成后重试")
                    } else if (recording == null && !sendingVoice) {
                        try {
                            val capture = VoiceRecording.create(this@MainActivity)
                            capture.start()
                            recordingTarget = target
                            recording = capture
                        } catch (_: Exception) {
                            notify("无法开始录音，请检查麦克风权限及占用情况")
                        }
                    }
                }
                val audioPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    val target = permissionTarget
                    permissionTarget = null
                    if (granted && target != null && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        beginRecording(target)
                    } else if (!granted) {
                        notifyWithSettingsAction("需要麦克风权限才能发送语音")
                    }
                }
                val startVoiceInput: () -> Unit = {
                    val active = controller
                    if (active == null) {
                        notify("对话尚未就绪")
                    } else if (!active.canSendVoiceAttachment(active.state.value.activeThreadId)) {
                        notify("当前对话暂不能发送语音，请等待处理完成后重试")
                    } else if (recording == null && !sendingVoice && permissionTarget == null) {
                        val target = active to active.state.value.activeThreadId
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            beginRecording(target)
                        } else {
                            permissionTarget = target
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                }
                DisposableEffect(Unit) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_STOP) {
                            permissionTarget = null
                            cancelRecording()
                        }
                    }
                    lifecycle.addObserver(observer)
                    onDispose {
                        lifecycle.removeObserver(observer)
                        voiceSendJob?.cancel()
                        permissionTarget = null
                        cancelRecording()
                    }
                }
                LaunchedEffect(controller) {
                    if (recordingTarget?.first?.let { it !== controller } == true) cancelRecording()
                    if (voiceSendController?.let { it !== controller } == true) voiceSendJob?.cancel()
                }
                if (recording != null) {
                    AlertDialog(
                        onDismissRequest = cancelRecording,
                        title = { Text("正在录音") },
                        text = { Text("录音结束后会将原始音频直接发送给 Agent。当前草稿和附件会保留。离开应用将取消录音。") },
                        dismissButton = { TextButton(onClick = cancelRecording) { Text("取消") } },
                        confirmButton = {
                            TextButton(onClick = {
                                val capture = recording
                                val target = recordingTarget
                                // Consume this recording before scheduling work: repeated clicks cannot resend it.
                                recording = null
                                recordingTarget = null
                                if (capture != null && target != null) {
                                    sendingVoice = true
                                    voiceSendController = target.first
                                    voiceSendJob = lifecycleScope.launch {
                                        var voice: RecordedVoice? = null
                                        try {
                                            val completed = withContext(Dispatchers.IO) { capture.finish() }
                                            voice = completed
                                            if (runtime.controller.value !== target.first) {
                                                notify("对话已切换，语音未发送")
                                            } else if (!target.first.sendVoiceAttachment(completed.selection(), target.second)) {
                                                notify("语音未发送，请查看对话提示后重试")
                                            }
                                        } catch (cause: CancellationException) {
                                            throw cause
                                        } catch (_: Exception) {
                                            notify("录音或发送失败，请重试")
                                        } finally {
                                            voice?.close()
                                            capture.close()
                                            sendingVoice = false
                                            voiceSendController = null
                                            voiceSendJob = null
                                        }
                                    }
                                }
                            }) { Text("结束并发送") }
                        },
                    )
                }
                if (sendingVoice) {
                    AlertDialog(
                        onDismissRequest = { voiceSendJob?.cancel() },
                        title = { Text("正在发送语音") },
                        text = { CircularProgressIndicator() },
                        dismissButton = { TextButton(onClick = { voiceSendJob?.cancel() }) { Text("停止等待") } },
                        confirmButton = {},
                    )
                }

                // 启动时自动尝试恢复已存储凭据
                LaunchedEffect(Unit) {
                    runtime.restoreSessionIfAvailable()
                }

                val attachmentPickers = rememberConversationAttachmentPickers(controller) {
                    runtime.controller.value === it
                }
                ReceivePendingConversationShares(sharedAttachments,
                    controller.takeIf { phase is ConnectionPhase.Connected }, attachmentPickers.receive) {
                    showSettingsSheet = false
                    showAssistant = false
                }

                // 真实系统能力契约：屏幕圈选的截图来源（MediaProjection）。
                // 授权必须由前台 Activity 发起系统对话框；授权结果先启动
                // mediaProjection 型前台服务（Android 14+ 硬性要求），再交给
                // 来源建立会话。拒绝/失败一律如实提示，圈选保持不可用降级。
                val screenCaptureSource = remember {
                    MediaProjectionScreenCaptureSource(MediaProjectionRuntime(applicationContext))
                }
                val screenCaptureAuthLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    if (result.resultCode == android.app.Activity.RESULT_OK) {
                        // getMediaProjection 要求 mediaProjection 型前台服务已经
                        // 完成 startForeground：必须等服务就绪回调后再转交授权
                        // 结果，否则 targetSdk 34+ 下确定性 SecurityException。
                        MediaProjectionCaptureService.start(this@MainActivity) {
                            val granted = screenCaptureSource.onAuthorizationResult(
                                result.resultCode, result.data,
                            )
                            if (!granted) {
                                // 会话建立失败也要撤掉前台状态，常驻通知不得悬挂。
                                MediaProjectionCaptureService.stop(this@MainActivity)
                            }
                            notify(
                                if (granted) "已获得屏幕采集授权，请再次点击圈选开始截取"
                                else "屏幕采集会话建立失败，圈选暂不可用",
                            )
                        }
                    } else {
                        val granted = screenCaptureSource.onAuthorizationResult(
                            result.resultCode, result.data,
                        )
                        MediaProjectionCaptureService.stop(this@MainActivity)
                        notify(
                            if (granted) "已获得屏幕采集授权，请再次点击圈选开始截取"
                            else "未获得屏幕采集授权，圈选暂不可用",
                        )
                    }
                }
                // 面板关闭即结束采集会话：常驻通知与投屏会话不得跨出使用场景存活。
                LaunchedEffect(showAssistant) {
                    if (!showAssistant) {
                        screenCaptureSource.release()
                        MediaProjectionCaptureService.stop(this@MainActivity)
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    // 根表面必须与各页面同色：登录 ↔ 工作台切换时不再出现色带。
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    // 登录页 ↔ 工作台：统一走标准淡入滑移转场，禁止生硬替换。
                    val enterWorkbench = (phase is ConnectionPhase.Connected || phase is ConnectionPhase.OfflineMirror) && controller != null
                    val pluginRevision by app.pluginHost.revision.collectAsState()
                    val nativeUi = remember(pluginRevision) { app.pluginHost.nativeUiProvider() }
                    Box(modifier = Modifier.fillMaxSize()) {
                        // 原生插件界面 ↔ 宿主界面：交叉淡化切换，而不是硬 if 重建。
                        AnimatedContent(
                            targetState = nativeUi,
                            transitionSpec = {
                                fadeIn(MotionSpecs.fade(reduceMotion)) togetherWith
                                    fadeOut(MotionSpecs.fade(reduceMotion))
                            },
                            label = "native-plugin-surface",
                        ) { activeNative ->
                            if (activeNative != null) {
                                val state = controller?.state?.collectAsState()?.value
                                com.openandroidintelligence.mobile.plugins.NativePluginSurface(
                                    app.pluginHost,
                                    activeNative.first,
                                    activeNative.second,
                                    com.openandroidintelligence.plugin.ui.NativeUiContext(
                                        (phase as? ConnectionPhase.Connected)?.gatewayUrl.orEmpty(),
                                        state?.activeThreadId,
                                        state?.activeThreadTitle.orEmpty(),
                                        state?.draft.orEmpty(),
                                        (state?.timeline as? com.openandroidintelligence.conversation.state.Loadable.Ready)?.value.orEmpty()
                                            .map { com.openandroidintelligence.plugin.ui.NativeUiMessage(it.key, it.sender, it.text, it.isStreaming) },
                                        { controller?.editDraft(it) },
                                        { controller?.sendDraft() },
                                        { controller?.stopGeneration() },
                                        { showSettingsSheet = true },
                                    ),
                                )
                            } else AnimatedContent(
                                targetState = enterWorkbench,
                                transitionSpec = {
                                    AppTransitions.enter(reduceMotion, forward = targetState) togetherWith
                                        AppTransitions.exit(reduceMotion, forward = targetState)
                                },
                                label = "root-surface",
                            ) { onWorkbench ->
                                if (!onWorkbench) {
                                    GatewayLoginScreen(
                                        phase = phase,
                                        onLogin = { url, username, password ->
                                            runtime.login(url, username, password)
                                        },
                                        onOpenSettings = { showSettingsSheet = true },
                                        onRetry = { runtime.resetFailure() },
                                        savedProfiles = savedProfiles,
                                        onSelectProfile = runtime::selectSavedAccount,
                                        onRemoveProfile = runtime::removeLocalAccount,
                                        onReconfirmIdentity = runtime::reconfirmGatewayIdentity,
                                        isManagingProfiles = isManagingProfiles,
                                        operationNotice = operationNotice,
                                        onDismissNotice = runtime::dismissOperationNotice,
                                        onInvite = { url,account,code,fingerprint -> runtime.login(url,account,code,invitation=true,expectedIdentityFingerprint=fingerprint) },
                                        onDeviceKey = { url,account -> runtime.login(url,account,charArrayOf(),deviceKey=true) },
                                        invitationPayload = pendingInvitation.value,
                                    )
                                    return@AnimatedContent
                                }
                                val activeController = controller
                                if (activeController == null) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(strokeWidth = 2.dp)
                                    }
                                    return@AnimatedContent
                                }
                                // 通告（离线镜像 / Gateway 操作结果）由工作台在内容区顶部统一承载：
                                // 放在宿主外层 Column 里会让横幅压住状态栏，并把抽屉挤成半屏。
                                GatewayWorkbenchScreen(
                                    runtime = runtime,
                                    controller = activeController,
                                    gatewayLabel = (phase as? ConnectionPhase.Connected)?.gatewayUrl
                                        ?: (phase as? ConnectionPhase.OfflineMirror)?.gatewayUrl.orEmpty(),
                                    onOpenSettings = { showSettingsSheet = true },
                                    onOpenAssistant = { showAssistant = true },
                                    onPickCamera = attachmentPickers.camera,
                                    onPickGallery = attachmentPickers.gallery,
                                    onPickDocument = attachmentPickers.document,
                                    onPasteImages = attachmentPickers.paste,
                                    onVoiceInput = startVoiceInput,
                                    pluginCards = {
                                        com.openandroidintelligence.mobile.plugins.ProtectedConversationCards(
                                            app.pluginHost,
                                            runtime.connectedAccountId,
                                        ) { showSettingsSheet = true }
                                    },
                                    offlineMirrorPhase = phase is ConnectionPhase.OfflineMirror,
                                    onReconnectOfflineMirror = runtime::reconnectOfflineMirror,
                                    reduceMotion = reduceMotion,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                AnimatedVisibility(
                                    visible = showAssistant,
                                    enter = AppTransitions.modalEnter(reduceMotion),
                                    exit = AppTransitions.modalExit(reduceMotion),
                                ) {
                                    FloatingConversationPanel(
                                        controller = activeController,
                                        onClose = { showAssistant = false },
                                        onPickCamera = attachmentPickers.camera,
                                        onPickGallery = attachmentPickers.gallery,
                                        onPickDocument = attachmentPickers.document,
                                        onPasteImages = attachmentPickers.paste,
                                        onVoiceInput = startVoiceInput,
                                        screenCaptureSource = screenCaptureSource,
                                        onNeedScreenCaptureAuthorization = {
                                            val intent = screenCaptureSource.createAuthorizationIntent()
                                            if (intent == null) {
                                                notify("此设备不支持屏幕采集，圈选不可用")
                                            } else {
                                                screenCaptureAuthLauncher.launch(intent)
                                            }
                                        },
                                    )
                                }
                            }
                        }

                        // 设置呈现形式：标准 M3 ModalBottomSheet（规格
                        // specs/2026-09-12-app-material-ui-and-motion.md），替代此前
                        // 的全屏 AnimatedVisibility。关闭走 onDismissRequest，
                        // 进出场动画由组件自身按 M3 规范承担。
                        if (showSettingsSheet) {
                            PlatformSettingsBottomSheet(
                                environment = app.platformSettingsEnvironment(),
                                runtime = runtime,
                                onDismissRequest = { showSettingsSheet = false },
                            )
                        }

                        // 底部 Snackbar：留出输入坞高度，避免压住撰写栏与手势条。
                        SnackbarHost(
                            hostState = snackbarHostState,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .navigationBarsPadding()
                                .padding(
                                    start = Dimensions.ScreenHorizontal,
                                    end = Dimensions.ScreenHorizontal,
                                    bottom = if (enterWorkbench) {
                                        Dimensions.ComposerPrimaryAction + Dimensions.SpaceMedium
                                    } else {
                                        Dimensions.SpaceMedium
                                    },
                                ),
                        )
                    }
                }
            }
        }
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
                text = "离线镜像：仅显示本机缓存，收发需重新连接 Gateway",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Dimensions.SpaceSmall),
            )
            TextButton(onClick = onReconnect) { Text("重新连接") }
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
    onOpenAssistant: (() -> Unit)? = null,
    pluginCards: @Composable () -> Unit = {},
    header: @Composable () -> Unit = {},
    offlineMirrorPhase: Boolean = false,
    onReconnectOfflineMirror: () -> Unit = {},
    reduceMotion: Boolean = false,
    onPasteImages: ((PastedImageContent) -> Unit)? = null,
) {
    val operationNotice by runtime.operationNotice.collectAsState()
    val phase by runtime.phase.collectAsState()
    val profiles by runtime.savedProfiles.collectAsState()
    val username = when (val current = phase) {
        is ConnectionPhase.Connected -> current.username
        is ConnectionPhase.OfflineMirror -> current.username
        else -> ""
    }
    val profile = profiles.firstOrNull { it.gatewayBaseUrl == gatewayLabel && it.username == username }
    val avatar = rememberAccountAvatarPicker(runtime, profile)
    Column(modifier.fillMaxSize()) {
        WorkbenchScreen(
            accountName = username,
            agentName = (phase as? ConnectionPhase.Connected)?.agentName ?: profile?.agentName,
            accountAvatar = avatar.image,
            onChangeAvatar = avatar.pick,
            onPasteImages = onPasteImages,
            controller = controller,
            gatewayLabel = gatewayLabel,
            onOpenSettings = onOpenSettings,
            onPickCamera = onPickCamera,
            onPickGallery = onPickGallery,
            onPickDocument = onPickDocument,
            onVoiceInput = onVoiceInput,
            modifier = Modifier.weight(1f),
            onOpenAssistant = onOpenAssistant,
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
                            modifier = Modifier.padding(horizontal = Dimensions.TimelineHorizontal),
                        )
                    }
                }
                header()
            },
        )
    }
}
