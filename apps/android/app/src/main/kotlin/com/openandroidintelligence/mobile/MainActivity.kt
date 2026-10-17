package com.openandroidintelligence.mobile

import android.graphics.Bitmap
import android.net.Uri
import android.content.Intent
import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.openandroidintelligence.conversation.state.WorkbenchController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.capability.MediaProjectionCaptureService
import com.openandroidintelligence.capability.MediaProjectionRuntime
import com.openandroidintelligence.capability.MediaProjectionScreenCaptureSource
import com.openandroidintelligence.conversation.motion.AppTransitions
import com.openandroidintelligence.conversation.ports.AttachmentContentSource
import com.openandroidintelligence.conversation.ports.LocalAttachmentSelection
import com.openandroidintelligence.conversation.theme.OpenAndroidIntelligenceTheme
import com.openandroidintelligence.conversation.theme.Dimensions
import com.openandroidintelligence.conversation.workbench.FloatingConversationPanel
import com.openandroidintelligence.conversation.workbench.WorkbenchScreen
import com.openandroidintelligence.ui.design.LocalMotionPolicy
import com.openandroidintelligence.core.model.AssistantHandoffDecision
import com.openandroidintelligence.core.model.AssistantHandoffDenialReason
import com.openandroidintelligence.core.model.AssistantHandoffGate
import com.openandroidintelligence.core.model.AssistantHandoffRequest
import com.openandroidintelligence.core.model.DefaultAssistantHandoffGate
import com.openandroidintelligence.mobile.util.ContentResolverExtensions
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 宿主主入口 Activity。
 *
 * 遵循严格的平台架构准则：
 * 1. 彻底清除任何假数据与死界面占位；
 * 2. 状态全量交由 GatewayRuntime 与领域层驱动；
 * 3. 严格遵循 Material Design 3 规范与系统动态取色（符合 Android 12+ Monet 标准，拒绝固定死颜色）；
 * 4. 接入原生相机快照、系统图片选择器与 SAF 文档选择器，走真实三步附件上传链路。
 */
class MainActivity : ComponentActivity() {
    private val pendingInvitation = androidx.compose.runtime.mutableStateOf<String?>(null)
    private val pendingReply = androidx.compose.runtime.mutableStateOf<ReplyNotificationTarget?>(null)
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.data?.scheme=="oai" && intent.data?.host=="pair") pendingInvitation.value=intent.data.toString()
        ReplyNotificationTarget.from(intent)?.let { pendingReply.value = it }
    }

    private val handoffGate: AssistantHandoffGate = DefaultAssistantHandoffGate()
    private var lastHandoffDecision: AssistantHandoffDecision =
        AssistantHandoffDecision.Denied(AssistantHandoffDenialReason.DEFAULT_DENY)

    fun evaluateAssistantHandoff(request: AssistantHandoffRequest): AssistantHandoffDecision =
        handoffGate.evaluate(request).also { lastHandoffDecision = it }

    fun currentAssistantHandoffDecision(): AssistantHandoffDecision = lastHandoffDecision

    private fun boundedImagePreview(source: Bitmap): ByteArray? {
        var bitmap = source
        var owned = false
        try {
            while (true) {
                val output = ByteArrayOutputStream()
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 72, output)) return null
                val bytes = output.toByteArray()
                if (bytes.size <= 256 * 1024) return bytes
                val width = (bitmap.width * 3 / 4).coerceAtLeast(64)
                val height = (bitmap.height * 3 / 4).coerceAtLeast(64)
                if (width == bitmap.width && height == bitmap.height) return null
                val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
                if (owned) bitmap.recycle()
                bitmap = scaled
                owned = bitmap !== source
            }
        } finally {
            if (owned) bitmap.recycle()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.data?.scheme=="oai" && intent.data?.host=="pair") pendingInvitation.value=intent.data.toString()
        pendingReply.value = ReplyNotificationTarget.from(intent)
        enableEdgeToEdge()

        val app = application as OpenAndroidIntelligenceApplication
        val runtime = app.gatewayRuntime

        setContent {
            val appearanceSettings by app.appearancePreferences.settings.collectAsState()
            val isDark = when (appearanceSettings.theme) {
                ThemePreference.LIGHT -> false
                ThemePreference.DARK -> true
                ThemePreference.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
            }
            OpenAndroidIntelligenceTheme(
                darkTheme = isDark,
                dynamicColor = appearanceSettings.dynamicColor,
                reduceMotion = appearanceSettings.reduceMotion,
            ) {
                val deviceConfirmation by runtime.deviceConfirmation.collectAsState()
                deviceConfirmation?.let { request ->
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { runtime.decideDeviceRequest(request.requestId,false) },
                        title = { androidx.compose.material3.Text("确认设备操作") },
                        text = { androidx.compose.material3.Text("${request.pluginId}\n${request.capability}\n${request.parameters}") },
                        confirmButton = { androidx.compose.material3.TextButton(onClick = { runtime.decideDeviceRequest(request.requestId,true) }) { androidx.compose.material3.Text("允许这次操作") } },
                        dismissButton = { androidx.compose.material3.TextButton(onClick = { runtime.decideDeviceRequest(request.requestId,false) }) { androidx.compose.material3.Text("拒绝") } },
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
                        !runtime.replyNotifications.permissionRequested &&
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
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
                            Toast.makeText(this@MainActivity, "请切换到收到回复的账号后查看会话", Toast.LENGTH_LONG).show()
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
                        Toast.makeText(this@MainActivity, "对话已切换，请重新开始录音", Toast.LENGTH_SHORT).show()
                    } else if (!target.first.canSendVoiceAttachment(target.second)) {
                        Toast.makeText(this@MainActivity, "当前对话暂不能发送语音，请等待处理完成后重试", Toast.LENGTH_SHORT).show()
                    } else if (recording == null && !sendingVoice) {
                        try {
                            val capture = VoiceRecording.create(this@MainActivity)
                            capture.start()
                            recordingTarget = target
                            recording = capture
                        } catch (_: Exception) {
                            Toast.makeText(this@MainActivity, "无法开始录音，请检查麦克风权限及占用情况", Toast.LENGTH_LONG).show()
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
                        Toast.makeText(this@MainActivity, "需要麦克风权限才能发送语音", Toast.LENGTH_SHORT).show()
                    }
                }
                val startVoiceInput: () -> Unit = {
                    val active = controller
                    if (active == null) {
                        Toast.makeText(this@MainActivity, "对话尚未就绪", Toast.LENGTH_SHORT).show()
                    } else if (!active.canSendVoiceAttachment(active.state.value.activeThreadId)) {
                        Toast.makeText(this@MainActivity, "当前对话暂不能发送语音，请等待处理完成后重试", Toast.LENGTH_SHORT).show()
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
                    androidx.compose.material3.AlertDialog(
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
                                                Toast.makeText(this@MainActivity, "对话已切换，语音未发送", Toast.LENGTH_SHORT).show()
                                            } else if (!target.first.sendVoiceAttachment(completed.selection(), target.second)) {
                                                Toast.makeText(this@MainActivity, "语音未发送，请查看对话提示后重试", Toast.LENGTH_LONG).show()
                                            }
                                        } catch (cause: CancellationException) {
                                            throw cause
                                        } catch (_: Exception) {
                                            Toast.makeText(this@MainActivity, "录音或发送失败，请重试", Toast.LENGTH_LONG).show()
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
                    androidx.compose.material3.AlertDialog(
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

                // 真实系统能力契约：相机拍照
                val takePictureLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.TakePicturePreview()
                ) { bitmap: Bitmap? ->
                    if (bitmap != null) {
                        val targetController = controller
                        if (targetController == null) {
                            Toast.makeText(this@MainActivity, "对话尚未就绪，照片未加入", Toast.LENGTH_SHORT).show()
                        } else {
                            lifecycleScope.launch(Dispatchers.IO) {
                                try {
                                    val selection = LocalAttachmentSelection(
                                        filename = "camera_${System.currentTimeMillis()}.jpg",
                                        mediaType = "image/jpeg",
                                        contentSource = AttachmentContentSource.fromWriter { output ->
                                            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)) {
                                                throw IOException("CAMERA_IMAGE_ENCODING_FAILED")
                                            }
                                        },
                                        previewBytes = boundedImagePreview(bitmap),
                                    )
                                    withContext(Dispatchers.Main) {
                                        if (runtime.controller.value === targetController) {
                                            targetController.addAttachment(selection)
                                        } else {
                                            Toast.makeText(this@MainActivity, "对话已切换，照片未加入", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(this@MainActivity, "相机照片处理失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }
                }

                // 真实系统能力契约：图库选图
                val pickMediaLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.PickVisualMedia()
                ) { uri: Uri? ->
                    uri?.let {
                        val targetController = controller
                        if (targetController == null) {
                            Toast.makeText(this@MainActivity, "对话尚未就绪，图片未加入", Toast.LENGTH_SHORT).show()
                        } else {
                            lifecycleScope.launch(Dispatchers.IO) {
                                try {
                                    val selection = ContentResolverExtensions.resolveAttachment(contentResolver, it)
                                    withContext(Dispatchers.Main) {
                                        if (runtime.controller.value === targetController) {
                                            targetController.addAttachment(selection)
                                        } else {
                                            Toast.makeText(this@MainActivity, "对话已切换，图片未加入", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(this@MainActivity, "选择图片失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }
                }

                // 真实系统能力契约：SAF 文档选择
                val openDocumentLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri: Uri? ->
                    uri?.let {
                        val targetController = controller
                        if (targetController == null) {
                            Toast.makeText(this@MainActivity, "对话尚未就绪，文件未加入", Toast.LENGTH_SHORT).show()
                        } else {
                            lifecycleScope.launch(Dispatchers.IO) {
                                try {
                                    val selection = ContentResolverExtensions.resolveAttachment(contentResolver, it)
                                    withContext(Dispatchers.Main) {
                                        if (runtime.controller.value === targetController) {
                                            targetController.addAttachment(selection)
                                        } else {
                                            Toast.makeText(this@MainActivity, "对话已切换，文件未加入", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(this@MainActivity, "选择文档失败: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    }
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
                            Toast.makeText(
                                this@MainActivity,
                                if (granted) "已获得屏幕采集授权，请再次点击圈选开始截取"
                                else "屏幕采集会话建立失败，圈选暂不可用",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    } else {
                        val granted = screenCaptureSource.onAuthorizationResult(
                            result.resultCode, result.data,
                        )
                        MediaProjectionCaptureService.stop(this@MainActivity)
                        Toast.makeText(
                            this@MainActivity,
                            if (granted) "已获得屏幕采集授权，请再次点击圈选开始截取"
                            else "未获得屏幕采集授权，圈选暂不可用",
                            Toast.LENGTH_LONG,
                        ).show()
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
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                ) {
                    val reduceMotion = LocalMotionPolicy.current.reduceMotion
                    // 登录页 ↔ 工作台：统一走标准淡入滑移转场，禁止生硬替换。
                    val enterWorkbench = (phase is ConnectionPhase.Connected || phase is ConnectionPhase.OfflineMirror) && controller != null
                    val pluginRevision by app.pluginHost.revision.collectAsState()
                    val nativeUi=remember(pluginRevision) { app.pluginHost.nativeUiProvider() }
                    if (nativeUi != null) {
                        val state=controller?.state?.collectAsState()?.value
                        com.openandroidintelligence.mobile.plugins.NativePluginSurface(app.pluginHost,nativeUi.first,nativeUi.second,
                            com.openandroidintelligence.plugin.ui.NativeUiContext((phase as? ConnectionPhase.Connected)?.gatewayUrl.orEmpty(),state?.activeThreadId,state?.activeThreadTitle.orEmpty(),state?.draft.orEmpty(),
                                (state?.timeline as? com.openandroidintelligence.conversation.state.Loadable.Ready)?.value.orEmpty().map { com.openandroidintelligence.plugin.ui.NativeUiMessage(it.key,it.sender,it.text,it.isStreaming) },
                                { controller?.editDraft(it) },{controller?.sendDraft()},{controller?.stopGeneration()},{showSettingsSheet=true}))
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
                        Column(Modifier.fillMaxSize()) {
                        if (phase is ConnectionPhase.OfflineMirror) {
                            TextButton(onClick=runtime::reconnectOfflineMirror) { Text("离线镜像 · 重新连接 Gateway") }
                        }
                        Box(Modifier.weight(1f)) { GatewayWorkbenchScreen(
                            runtime = runtime,
                            controller = activeController,
                            gatewayLabel = (phase as? ConnectionPhase.Connected)?.gatewayUrl ?: (phase as? ConnectionPhase.OfflineMirror)?.gatewayUrl.orEmpty(),
                            onOpenSettings = { showSettingsSheet = true },
                            onOpenAssistant = { showAssistant = true },
                            onPickCamera = { takePictureLauncher.launch(null) },
                            onPickGallery = {
                                pickMediaLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            onPickDocument = {
                                openDocumentLauncher.launch(arrayOf("*/*"))
                            },
                            onVoiceInput = startVoiceInput,
                            pluginCards={ com.openandroidintelligence.mobile.plugins.ProtectedConversationCards(app.pluginHost,runtime.connectedAccountId) {showSettingsSheet=true} },
                        )
                        }
                        }
                        AnimatedVisibility(
                            visible = showAssistant,
                            enter = AppTransitions.modalEnter(reduceMotion),
                            exit = AppTransitions.modalExit(reduceMotion),
                        ) {
                            FloatingConversationPanel(
                                controller = activeController,
                                onClose = { showAssistant = false },
                                onPickCamera = { takePictureLauncher.launch(null) },
                                onPickGallery = {
                                    pickMediaLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                onPickDocument = {
                                    openDocumentLauncher.launch(arrayOf("*/*"))
                                },
                                onVoiceInput = startVoiceInput,
                                screenCaptureSource = screenCaptureSource,
                                onNeedScreenCaptureAuthorization = {
                                    val intent = screenCaptureSource.createAuthorizationIntent()
                                    if (intent == null) {
                                        Toast.makeText(
                                            this@MainActivity,
                                            "此设备不支持屏幕采集，圈选不可用",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    } else {
                                        screenCaptureAuthLauncher.launch(intent)
                                    }
                                },
                            )
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
                }
            }
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
) {
    val operationNotice by runtime.operationNotice.collectAsState()
    Column(modifier.fillMaxSize()) {
        operationNotice?.let { notice ->
            OperationNoticeBanner(
                text = notice,
                onDismiss = runtime::dismissOperationNotice,
                modifier = Modifier.padding(
                    horizontal = Dimensions.ScreenHorizontal,
                    vertical = Dimensions.SpaceSmall,
                ),
            )
        }
        WorkbenchScreen(
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
        )
    }
}
