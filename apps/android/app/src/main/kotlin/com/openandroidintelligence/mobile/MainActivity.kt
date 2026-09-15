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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openandroidintelligence.conversation.ports.LocalAttachmentSelection
import com.openandroidintelligence.conversation.theme.OpenAndroidIntelligenceTheme
import com.openandroidintelligence.conversation.workbench.WorkbenchScreen
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

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    when (val currentPhase = phase) {
                        is ConnectionPhase.Connected -> {
                            val activeController = controller
                            if (activeController != null) {
                                WorkbenchScreen(
                                    controller = activeController,
                                    gatewayLabel = currentPhase.gatewayUrl,
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
                                if (showAssistant) {
                                    com.openandroidintelligence.conversation.workbench.FloatingConversationPanel(
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
                                    )
                                }
                            } else {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(strokeWidth = 2.dp)
                                }
                            }
                        }
                        else -> {
                            GatewayLoginScreen(
                                phase = currentPhase,
                                onLogin = { url, username, password ->
                                    runtime.login(url, username, password)
                                },
                                onOpenSettings = { showSettingsSheet = true },
                                onRetry = { runtime.resetFailure() },
                            )
                        }
                    }

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
