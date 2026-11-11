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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openandroidintelligence.capability.MediaProjectionCaptureService
import com.openandroidintelligence.capability.MediaProjectionRuntime
import com.openandroidintelligence.capability.MediaProjectionScreenCaptureSource
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
import com.openandroidintelligence.conversation.workbench.FloatingConversationPanel
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
        if (intent.data?.scheme=="oai" && intent.data?.host=="pair") pendingInvitation.value=intent.data.toString()
        // 与 onNewIntent 同一规则：交接意图只进 pendingHandoff，不重复当回复通知处理。
        val initialHandoffToken = intent.handoffSessionToken()
        if (initialHandoffToken != null) {
            pendingHandoff.value = initialHandoffToken
        } else {
            pendingReply.value = ReplyNotificationTarget.from(intent)
        }

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
                    ScreenControlConfirmationDialog(
                        requestId = request.requestId,
                        pluginId = request.pluginId,
                        capability = request.capability,
                        parameters = request.parameters,
                        agentName = request.agentName,
                        purpose = request.purpose,
                        operation = request.operation,
                        expiresAtMillis = request.expiresAtMillis,
                        onApprove = { durationMinutes -> runtime.decideDeviceRequest(request.requestId, true, durationMinutes, request.decisionToken) },
                        onDeny = { runtime.decideDeviceRequest(request.requestId, false, decisionToken = request.decisionToken) },
                    )
                }
                val phase by runtime.phase.collectAsState()
                val accountLoginStep by runtime.accountLoginStep.collectAsState()
                val controller by runtime.controller.collectAsState()
                val savedProfiles by runtime.savedProfiles.collectAsState()
                val isManagingProfiles by runtime.isManagingProfiles.collectAsState()
                val operationNotice by runtime.operationNotice.collectAsState()
                var showSettings by rememberSaveable { mutableStateOf(false) }
                val settingsEnvironment = remember(app) { app.platformSettingsEnvironment() }
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
                // 助手交接：复用 pendingReply 的形状 —— 控制器就绪且主进程确认才能清空。
                LaunchedEffect(phase, controller, pendingHandoff.value) {
                    pendingHandoff.value?.let { sessionToken ->
                        // Agent 切换后工作台要等旧分区的 IO 收尾才发布：短时间内重试几次，
                        // 而不是第一次失败就把用户推去手动操作。
                        var attempts = 0
                        while (attempts < HANDOFF_NAVIGATION_ATTEMPTS) {
                            if (runtime.consumeAssistantHandoff(sessionToken)) {
                                showSettings = false
                                showAssistant = false
                                pendingHandoff.value = null
                                intent.data = null
                                break
                            }
                            if (phase !is ConnectionPhase.Connected) break
                            attempts++
                            kotlinx.coroutines.delay(HANDOFF_NAVIGATION_RETRY_MILLIS)
                        }
                        if (pendingHandoff.value == sessionToken && phase is ConnectionPhase.Connected) {
                            notify("暂时无法进入助手中的会话，请稍后重试")
                        }
                    }
                }
                LaunchedEffect(phase, controller, pendingReply.value) {
                    pendingReply.value?.let { target ->
                        if (runtime.openReplyNotification(target)) {
                            showSettings = false
                            showAssistant = false
                            pendingReply.value = null
                            intent.data = null
                        } else if (phase is ConnectionPhase.Connected) {
                            notify("请切换到收到回复的账号后查看会话")
                        }
                    }
                }

                // 语音录制与默认数字助手共用同一个 VoiceRecorder：同一套状态机、同一套
                // 波形/计时/取消/发送界面。录音态由输入坞原地呈现，不再弹独立卡片遮挡会话。
                // 录音开始时所属的对话；交付开始后转移到 [sendingTarget]，让「正在交付的那段
                // 录音」与「用户可能立刻开始的新录音」各自持有自己的目标，不会串对话。
                // 用原子引用而不是 Compose 状态：交付回调在后台线程读取，界面从不直接渲染它们。
                val voiceTarget = remember { AtomicReference<Pair<WorkbenchController, String?>?>(null) }
                val sendingTarget = remember { AtomicReference<Pair<WorkbenchController, String?>?>(null) }
                var permissionTarget by remember { mutableStateOf<Pair<WorkbenchController, String?>?>(null) }
                // start() 的失败与交付阶段的失败是两种原因，提示沿用原来的两句话：
                // 「无法开始录音」发生在按下麦克风的同一帧，交付失败则是之后的后台写文件。
                val startFailure = remember { AtomicBoolean(false) }
                val recorder = remember(app) {
                    VoiceRecorder.create(
                        context = app,
                        onRecorded = { voice, sessionKey ->
                            // 交付在后台线程回调；真正的发送与提示回到主线程，
                            // 并且必须仍停留在录音所属的对话，绝不把语音发到别的会话。
                            // 组合已经销毁（作用域已取消）时任务体不会执行，必须在这里
                            // 自己关闭临时音频，否则缓存目录会留一份没人释放的录音。
                            if (uiScope.coroutineContext[Job]?.isActive != true) {
                                voice.close()
                                sendingTarget.set(null)
                                return@create
                            }
                            // 在回调线程就取走目标：交付收尾会清空 sendingTarget，晚一步就丢了。
                            val target = sendingTarget.get()
                            uiScope.launch {
                                try {
                                    if (target == null) {
                                        notify("录音已丢失，请重新录制")
                                    } else if (runtime.controller.value !== target.first) {
                                        notify("对话已切换，语音未发送")
                                    } else if (target.first.state.value.activeThreadId != sessionKey) {
                                        notify("对话已切换，语音未发送")
                                    } else if (!target.first.sendVoiceAttachment(voice.selection(), target.second)) {
                                        notify("语音未发送，请查看对话提示后重试")
                                    }
                                } finally {
                                    voice.close()
                                    sendingTarget.compareAndSet(target, null)
                                }
                            }
                        },
                        onRejected = { rejection ->
                            // ALREADY_SENDING 说明上一段仍在这条交付路径上，目标不能被清掉；
                            // 其余拒绝都意味着没有在途录音，顺手清掉目标，避免永久挡住下一次发送。
                            if (rejection != VoiceRejection.ALREADY_SENDING) sendingTarget.set(null)
                            val message = when {
                                rejection != VoiceRejection.RECORDING_FAILED ->
                                    voiceRejectionMessage(rejection)
                                startFailure.getAndSet(false) ->
                                    "无法开始录音，请检查麦克风权限及占用情况"
                                else -> "录音或发送失败，请重试"
                            }
                            uiScope.launch { notify(message) }
                        },
                    )
                }
                DisposableEffect(recorder) {
                    onDispose { recorder.close() }
                }
                val voiceState by recorder.state.collectAsState()
                val activeRecording = voiceState.activeRecording()
                // 只有真的取消了一段录音才清掉发送目标：正在写文件/发送时的 cancel() 返回
                // false，交付仍要落到原来那条对话上，不能被这里顺手清空成"录音已丢失"。
                val cancelRecording: () -> Unit = {
                    if (recorder.cancel()) voiceTarget.set(null)
                }
                val finishRecording: () -> Unit = {
                    // 上一段仍在交付时 send() 会拒绝这一次；此时不能动 sendingTarget，
                    // 否则会把正在交付的音频指向错误的对话。
                    if (sendingTarget.get() == null) {
                        val target = voiceTarget.getAndSet(null)
                        // 目标必须在 send() 之前登记：交付回调可能在后台线程立刻到达，
                        // 写在 send() 之后会留下"回调先到、目标还是空的"窗口。
                        sendingTarget.set(target)
                        val accepted = recorder.send {
                            runtime.controller.value?.state?.value?.activeThreadId
                        }
                        // 被拒绝（未在录音 / 会话已切换）时录音器已回到 Idle，目标一并清掉。
                        if (!accepted) sendingTarget.compareAndSet(target, null)
                    }
                }
                val beginRecording: (Pair<WorkbenchController, String?>) -> Unit = { target ->
                    if (runtime.controller.value !== target.first || target.first.state.value.activeThreadId != target.second) {
                        notify("对话已切换，请重新开始录音")
                    } else if (!target.first.canSendVoiceAttachment(target.second)) {
                        notify("当前对话暂不能发送语音，请等待处理完成后重试")
                    } else if (voiceState is VoiceRecorderState.Sending) {
                        notify(voiceRejectionMessage(VoiceRejection.ALREADY_SENDING))
                    } else if (voiceState is VoiceRecorderState.Idle) {
                        voiceTarget.set(target)
                        // 失败原因统一由 onRejected 给出，这里不重复提示；
                        // startFailure 让那条回调区分「起不来」与「交付失败」两种原因。
                        startFailure.set(true)
                        val started = recorder.start(target.second)
                        // start() 失败时 onRejected 已在调用内消费掉这个标记；成功后也要复位，
                        // 否则之后一次正常的录制失败会被误报成「无法开始录音」。
                        startFailure.set(false)
                        if (!started) voiceTarget.compareAndSet(target, null)
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
                    } else if (voiceState is VoiceRecorderState.Sending) {
                        notify(voiceRejectionMessage(VoiceRejection.ALREADY_SENDING))
                    } else if (voiceState is VoiceRecorderState.Idle && permissionTarget == null) {
                        val target = active to active.state.value.activeThreadId
                        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            beginRecording(target)
                        } else {
                            permissionTarget = target
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                }
                // 录音只属于录制它的对话：宿主切走会话时立即丢弃，而不是等发送时才失败。
                LaunchedEffect(controller) {
                    val active = controller ?: return@LaunchedEffect
                    snapshotFlow { active.state.value.activeThreadId }.collect { threadId ->
                        if (recorder.onSessionChanged(threadId)) voiceTarget.set(null)
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
                        permissionTarget = null
                        cancelRecording()
                    }
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
                    showSettings = false
                    showAssistant = false
                }

                // 真实系统能力契约：屏幕圈选的截图来源（MediaProjection）。
                // 授权必须由前台 Activity 发起系统对话框；授权结果先启动
                // mediaProjection 型前台服务（Android 14+ 硬性要求），再交给
                // 来源建立会话。拒绝/失败一律如实提示，圈选保持不可用降级。
                //
                // 刻意**不**复用 RemoteScreenCaptureRegistry 的实例：圈选与远程
                // 截图各自持有独立的系统票据与像素，一处授权不得扩大另一处的
                // 权限范围（见下方远程截图段的同一约束）。
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

                // ===== 远程屏幕截图：独立的系统投屏授权 =====
                // 与上面的圈选来源是**两个不同实例**：附件圈选持有一份系统票据和像素，
                // 远程截图不得借用，否则一处授权会悄悄扩大另一处的权限范围。
                //
                // System consent is installation-local and separate from the per-request global policy.
                // The returned ticket must still match before and after the foreground service starts.
                var remoteCaptureTicket by remember {
                    mutableStateOf<com.openandroidintelligence.mobile.screen.ScreenCaptureConsentTicket?>(null)
                }
                fun captureEnabled(): Boolean {
                    val plugin = "org.openandroidintelligence.screen-capture"
                    val entry = app.pluginHost.entries(plugin).firstOrNull { "kernel.screen.capture" in it.primitives }
                        ?: return false
                    return app.pluginHost.isEnabled(plugin) && app.pluginHost.capabilityPolicy(plugin, entry.key).enabled
                }
                fun captureConsentCurrent() = com.openandroidintelligence.mobile.screen.screenCaptureConsentCurrent(
                    remoteCaptureTicket, app.pairingGrants.state.value?.revision ?: -1L, app.pluginHost.revision.value,
                    captureEnabled(), app.kernel.isEmergencyStopped(),
                    com.openandroidintelligence.control.ScreenHostRegistry.current?.status()?.stopGeneration ?: -1L)
                val remoteScreenCaptureLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult()
                ) { result ->
                    val source = com.openandroidintelligence.capability.RemoteScreenCaptureRegistry.source
                    if (source == null) {
                        notify("未开启屏幕截图功能")
                        return@rememberLauncherForActivityResult
                    }
                    val accepted = result.resultCode == android.app.Activity.RESULT_OK
                    if (!accepted) {
                        remoteCaptureTicket = null
                        // 用户拒绝或结果数据缺失：能力保持不可用，绝不伪造结果。
                        source.release()
                        com.openandroidintelligence.capability.RemoteScreenCaptureRegistry
                            .stopForeground(this@MainActivity)
                        notify("未获得屏幕截图授权，截图暂时不可用")
                        return@rememberLauncherForActivityResult
                    }
                    val stillGranted = captureConsentCurrent()
                    if (!stillGranted) {
                        source.release()
                        com.openandroidintelligence.capability.RemoteScreenCaptureRegistry
                            .stopForeground(this@MainActivity)
                        remoteCaptureTicket = null
                        notify("截图能力或本机设置已变化，请重新发起系统授权")
                        return@rememberLauncherForActivityResult
                    }
                    // 先等前台服务完成 startForeground，再转交授权结果：
                    // Android 14+ 下顺序反了会确定性抛出 SecurityException。
                    com.openandroidintelligence.capability.RemoteScreenCaptureRegistry
                        .startForeground(this@MainActivity) {
                            if (!captureConsentCurrent()) {
                                source.release()
                                com.openandroidintelligence.capability.RemoteScreenCaptureRegistry.stopForeground(this@MainActivity)
                                remoteCaptureTicket = null
                                notify("截图能力已撤销，未使用系统授权结果")
                                return@startForeground
                            }
                            val granted = source.onAuthorizationResult(result.resultCode, result.data)
                            remoteCaptureTicket = null
                            if (!granted) {
                                com.openandroidintelligence.capability.RemoteScreenCaptureRegistry
                                    .stopForeground(this@MainActivity)
                            }
                            notify(
                                if (granted) "已获得屏幕截图授权"
                                else "屏幕截图会话建立失败，截图暂不可用"
                            )
                        }
                }
                val requestRemoteScreenCaptureAuth: () -> Unit = {
                    val source = com.openandroidintelligence.capability.RemoteScreenCaptureRegistry.source
                    val permitted = captureEnabled() && !app.kernel.isEmergencyStopped()
                    when {
                        source == null -> notify("未开启屏幕截图功能")
                        !permitted -> notify("请先启用截图插件并开启截图权限")
                        // 截图来源已可用（无障碍按需截图已就绪，或投屏此前已授权）
                        // 时不需要再走一次系统授权，如实告知即可。
                        source.isAvailable -> notify("截图已可用，不需要额外的系统授权")
                        else -> {
                            val intent = source.createAuthorizationIntent()
                            if (intent == null) {
                                notify("系统未提供屏幕录制服务，截图暂不可用")
                            } else {
                                remoteCaptureTicket = com.openandroidintelligence.mobile.screen.ScreenCaptureConsentTicket(
                                    app.pairingGrants.state.value?.revision ?: -1L, app.pluginHost.revision.value,
                                    com.openandroidintelligence.control.ScreenHostRegistry.current?.status()?.stopGeneration ?: -1L)
                                remoteScreenCaptureLauncher.launch(intent)
                            }
                        }
                    }
                }

                // ===== 首次启动引导（前置分支）=====
                // 引导是登录之前的一道独立流程，不参与「登录 ↔ 工作台」的状态判定，
                // 也不新建 Activity：这样既不改动既有登录/配对/会话逻辑（需求五.5、六.9），
                // 又能让系统返回键在引导内部逐级回退。
                val onboardingPreferences = remember { app.onboardingPreferences }
                val onboardingState by onboardingPreferences.state.collectAsState()
                val onboardingVisible = onboardingState.shouldShow
                // 已启用插件声明的 kernel primitive：决定「设备能力」权限卡片是否出现。
                // 不存在的能力不展示，满足需求「不请求不存在或尚不需要的权限」。
                //
                // 两点性能约束：
                // 1. listInstalled() 会遍历目录、读清单并解析 JSON —— 不能在组合（主线程）里做；
                // 2. 只有引导真正可见时才需要这份数据，引导隐藏时不必扫描。
                // 因此用 produceState 放进 IO 调度器，并受 onboardingVisible 门控。
                val onboardingPluginRevision by app.pluginHost.revision.collectAsState()
                val enabledPrimitives by produceState(
                    initialValue = emptySet<String>(),
                    onboardingVisible,
                    onboardingPluginRevision,
                ) {
                    value = if (!onboardingVisible) {
                        emptySet()
                    } else {
                        withContext(Dispatchers.IO) {
                            // 只统计「已安装且已启用」的插件：未启用的插件即使声明了能力，
                            // 也不会对 Agent 生效，把它算进来会误导用户授权一个用不上的权限。
                            app.pluginHost.installStore.listInstalled()
                                .mapNotNull { it.pluginId }
                                .filter { app.pluginHost.isEnabled(it) }
                                .flatMap { id -> app.pluginHost.entries(id).flatMap { it.primitives } }
                                .toSet()
                        }
                    }
                }

                if (onboardingVisible) {
                    OnboardingFlow(
                        state = onboardingState,
                        appearanceSettings = appearanceSettings,
                        appearancePreferences = app.appearancePreferences,
                        onStageChange = onboardingPreferences::setStage,
                        onGatewaySelected = onboardingPreferences::setLastGateway,
                        onFinished = {
                            // 完成引导只记录「用户走完了引导」，与 Gateway 是否连接无关：
                            // 登录成功与否仍由 ConnectionPhase 决定，绝不伪造连接状态。
                            onboardingPreferences.markCompleted()
                        },
                        onExit = {
                            // 在欢迎页再按返回：
                            // - 重看模式：退出重看，回到原来的主界面；
                            // - 首次启动：本次会话不再展示引导（不写 finished），
                            //   同时结束 Activity —— 用户明确表达了「现在不想用引导」，
                            //   停留在登录页反而会让他再面对一次同样的选择。
                            if (onboardingState.replay) {
                                onboardingPreferences.endReplay()
                            } else {
                                onboardingPreferences.dismissForCurrentSession()
                                finish()
                            }
                        },
                        pluginPrimitives = enabledPrimitives,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    // 根表面必须与各页面同色：登录 ↔ 工作台切换时不再出现色带。
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    // 登录页 ↔ 工作台：统一走标准淡入滑移转场，禁止生硬替换。
                    val localSessionState by runtime.localSessionState.collectAsState()
                    val enterWorkbench = localSessionState is LocalSessionState.LoadingLocal ||
                        localSessionState is LocalSessionState.Workspace ||
                        ((phase is ConnectionPhase.Connected || phase is ConnectionPhase.OfflineMirror) && controller != null)
                    val pluginRevision by app.pluginHost.revision.collectAsState()
                    val nativeUi = remember(pluginRevision) { app.pluginHost.nativeUiProvider() }
                    var displayedNativeUi by remember { mutableStateOf(nativeUi) }
                    LaunchedEffect(nativeUi, showSettings) {
                        if (!showSettings) displayedNativeUi = nativeUi
                    }
                    Box(modifier = Modifier.fillMaxSize()) {
                        // 原生插件界面 ↔ 宿主界面：交叉淡化切换，而不是硬 if 重建。
                        AnimatedContent(
                            targetState = displayedNativeUi,
                            transitionSpec = {
                                fadeIn(MotionSpecs.fade(reduceMotion)) togetherWith
                                    fadeOut(MotionSpecs.fade(reduceMotion))
                            },
                            label = "native-plugin-surface",
                        ) { activeNative ->
                            if (activeNative != null) {
                                PlatformSettingsDrawer(
                                    open = showSettings && displayedNativeUi == activeNative,
                                    onDismiss = { showSettings = false },
                                    environment = settingsEnvironment,
                                    runtime = runtime,
                                    onRequestScreenCaptureAuthorization = requestRemoteScreenCaptureAuth,
                                ) {
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
                                            { showSettings = true },
                                        ),
                                    )
                                }
                            } else AnimatedContent(
                                targetState = enterWorkbench,
                                transitionSpec = {
                                    AppTransitions.enter(reduceMotion, forward = targetState) togetherWith
                                        AppTransitions.exit(reduceMotion, forward = targetState)
                                },
                                label = "root-surface",
                            ) { onWorkbench ->
                                if (!onWorkbench) {
                                    PlatformSettingsDrawer(
                                        open = showSettings && !enterWorkbench && displayedNativeUi == null,
                                        onDismiss = { showSettings = false },
                                        environment = settingsEnvironment,
                                        runtime = runtime,
                                        onRequestScreenCaptureAuthorization = requestRemoteScreenCaptureAuth,
                                    ) {
                                        GatewayLoginScreen(
                                            phase = phase,
                                            onLogin = { url, username, password ->
                                                runtime.login(url, username, password)
                                            },
                                            onOpenSettings = { showSettings = true },
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
                                            accountLoginStep = accountLoginStep,
                                            onAccountProof = runtime::submitAccountLoginProof,
                                            onRecoveryCodesSaved = runtime::confirmAccountRecoveryCodesSaved,
                                            onCancelAccountLogin = runtime::cancelAccountLogin,
                                        )
                                    }
                                    return@AnimatedContent
                                }
                                val activeController = controller
                                if (activeController == null) {
                                    PlatformSettingsDrawer(
                                        open = showSettings && enterWorkbench && displayedNativeUi == null && controller == null,
                                        onDismiss = { showSettings = false },
                                        environment = settingsEnvironment,
                                        runtime = runtime,
                                        onRequestScreenCaptureAuthorization = requestRemoteScreenCaptureAuth,
                                    ) {
                                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                            if (operationNotice != null || phase is ConnectionPhase.Failed) {
                                                Column(
                                                    modifier = Modifier.padding(Dimensions.SpaceMedium),
                                                    horizontalAlignment = Alignment.CenterHorizontally,
                                                    verticalArrangement = Arrangement.spacedBy(Dimensions.SpaceSmall),
                                                ) {
                                                    Text(operationNotice ?: "本地会话暂时不可用，资料已保留。")
                                                    TextButton(onClick = runtime::retryConnection) { Text("重试连接") }
                                                    TextButton(onClick = { showSettings = true }) { Text("设置") }
                                                }
                                            } else CircularProgressIndicator(strokeWidth = 2.dp)
                                        }
                                    }
                                    return@AnimatedContent
                                }
                                // 通告（离线镜像 / Gateway 操作结果）由工作台在内容区顶部统一承载：
                                // 放在宿主外层 Column 里会让横幅压住状态栏，并把抽屉挤成半屏。
                                GatewayWorkbenchScreen(
                                    runtime = runtime,
                                    controller = activeController,
                                    gatewayLabel = (phase as? ConnectionPhase.Connected)?.gatewayUrl
                                        ?: (phase as? ConnectionPhase.OfflineMirror)?.gatewayUrl
                                        ?: (localSessionState as? LocalSessionState.Workspace)?.account?.gatewayBaseUrl.orEmpty(),
                                    onOpenSettings = { showSettings = true },
                                    settingsOpen = showSettings && enterWorkbench && displayedNativeUi == null,
                                    onSettingsOpenChange = { showSettings = it },
                                    settingsPanel = { presentation, onClose ->
                                        SettingsScreen(
                                            environment = settingsEnvironment,
                                            runtime = runtime,
                                            onBack = onClose,
                                            presentation = presentation,
                                            onRequestScreenCaptureAuthorization = requestRemoteScreenCaptureAuth,
                                        )
                                    },
                                    onOpenAssistant = { showAssistant = true },
                                    onPickCamera = attachmentPickers.camera,
                                    onPickGallery = attachmentPickers.gallery,
                                    onPickDocument = attachmentPickers.document,
                                    onPasteImages = attachmentPickers.paste,
                                    onVoiceInput = startVoiceInput,
                                    // 录音态只由当前可见的输入坞呈现：助理面板展开时由面板显示，
                                    // 面板收起后回到工作台，避免同一段录音在两处各画一份。
                                    recording = activeRecording.takeUnless { showAssistant },
                                    onCancelRecording = cancelRecording,
                                    onFinishRecording = finishRecording,
                                    pluginCards = {
                                        com.openandroidintelligence.mobile.plugins.ProtectedConversationCards(
                                            app.pluginHost,
                                            runtime.connectedAccountId,
                                        ) { showSettings = true }
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
                                        recording = activeRecording,
                                        onCancelRecording = cancelRecording,
                                        onFinishRecording = finishRecording,
                                        screenCaptureSource = screenCaptureSource,
                                        onNeedScreenCaptureAuthorization = {
                                            // 无障碍已可用时不需要任何系统授权：直接让用户重试圈选，
                                            // 不再把用户推进一次多余的投屏确认流程。
                                            if (screenCaptureSource.isAvailable) {
                                                notify("现在可以直接圈选，无需额外授权")
                                            } else {
                                                val intent = screenCaptureSource.createAuthorizationIntent()
                                                if (intent == null) {
                                                    notify("此设备不支持屏幕采集，圈选不可用")
                                                } else {
                                                    screenCaptureAuthLauncher.launch(intent)
                                                }
                                            }
                                        },
                                    )
                                }
                            }
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
                }   // 结束 else（引导未展示时走原有登录/工作台分支）
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
                text = "离线模式：仅显示本地缓存，连接后可继续收发",
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
    recording: ActiveVoiceRecording? = null,
    onCancelRecording: () -> Unit = {},
    onFinishRecording: () -> Unit = {},
    onOpenAssistant: (() -> Unit)? = null,
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
            else -> null
        }
        else -> null
    }
}
