package com.openandroidintelligence.mobile

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.capability.MediaProjectionCaptureService
import com.openandroidintelligence.capability.MediaProjectionRuntime
import com.openandroidintelligence.capability.MediaProjectionScreenCaptureSource
import com.openandroidintelligence.conversation.motion.AppTransitions
import com.openandroidintelligence.conversation.ports.LocalAttachmentSelection
import com.openandroidintelligence.conversation.theme.OpenAndroidIntelligenceTheme
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

    private val handoffGate: AssistantHandoffGate = DefaultAssistantHandoffGate()
    private var lastHandoffDecision: AssistantHandoffDecision =
        AssistantHandoffDecision.Denied(AssistantHandoffDenialReason.DEFAULT_DENY)

    fun evaluateAssistantHandoff(request: AssistantHandoffRequest): AssistantHandoffDecision =
        handoffGate.evaluate(request).also { lastHandoffDecision = it }

    fun currentAssistantHandoffDecision(): AssistantHandoffDecision = lastHandoffDecision

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                val phase by runtime.phase.collectAsState()
                val controller by runtime.controller.collectAsState()
                var showSettingsSheet by remember { mutableStateOf(false) }
                var showAssistant by remember { mutableStateOf(false) }

                val voiceInputLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult(),
                ) { result ->
                    if (result.resultCode == RESULT_OK) {
                        val spokenText = result.data
                            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                            ?.firstOrNull { it.isNotBlank() }
                        if (spokenText != null) {
                            controller?.let { active ->
                                val current = active.state.value.draft
                                active.editDraft(if (current.isBlank()) spokenText else "$current $spokenText")
                            }
                        }
                    }
                }

                val startVoiceInput: () -> Unit = {
                    launchVoiceInput(
                        launch = { voiceInputLauncher.launch(it) },
                        onUnavailable = {
                            Toast.makeText(this@MainActivity, "未安装可用的系统语音识别服务，请使用键盘语音输入", Toast.LENGTH_LONG).show()
                        },
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
                        try {
                            val stream = ByteArrayOutputStream()
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)
                            val bytes = stream.toByteArray()
                            val selection = LocalAttachmentSelection(
                                filename = "camera_${System.currentTimeMillis()}.jpg",
                                mediaType = "image/jpeg",
                                bytes = bytes,
                            )
                            controller?.addAttachment(selection)
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "相机照片处理失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                // 真实系统能力契约：图库选图
                val pickMediaLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.PickVisualMedia()
                ) { uri: Uri? ->
                    uri?.let {
                        try {
                            val selection = ContentResolverExtensions.resolveAttachment(contentResolver, it)
                            controller?.addAttachment(selection)
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "选择图片失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                // 真实系统能力契约：SAF 文档选择
                val openDocumentLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument()
                ) { uri: Uri? ->
                    uri?.let {
                        try {
                            val selection = ContentResolverExtensions.resolveAttachment(contentResolver, it)
                            controller?.addAttachment(selection)
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "选择文档失败: ${e.message}", Toast.LENGTH_SHORT).show()
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
                        MediaProjectionCaptureService.start(this@MainActivity)
                    }
                    val granted = screenCaptureSource.onAuthorizationResult(
                        result.resultCode, result.data,
                    )
                    Toast.makeText(
                        this@MainActivity,
                        if (granted) "已获得屏幕采集授权，请再次点击圈选开始截取"
                        else "未获得屏幕采集授权，圈选暂不可用",
                        Toast.LENGTH_LONG,
                    ).show()
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surfaceContainerLowest,
                ) {
                    val reduceMotion = LocalMotionPolicy.current.reduceMotion
                    // 登录页 ↔ 工作台：统一走标准淡入滑移转场，禁止生硬替换。
                    val enterWorkbench = phase is ConnectionPhase.Connected && controller != null
                    AnimatedContent(
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
                        WorkbenchScreen(
                            controller = activeController,
                            gatewayLabel = (phase as? ConnectionPhase.Connected)?.gatewayUrl ?: "",
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
                        )
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
